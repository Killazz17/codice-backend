package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.InvalidInputException
import edu.backend.codice.exception.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional

// IA-02: extracción del temario desde el PDF. Solo el profesor dueño; nada se publica; si la IA falla el curso no cambia.
class SyllabusExtractionServiceTest {

    /** Proveedor de prueba: cada llamada ejecuta [responder] (que puede lanzar) y se cuenta. */
    private class StubClient(private val responder: () -> String) : AiClient {
        override val provider = "stub"
        var calls = 0

        override fun generate(request: AiRequest): AiResponse {
            calls++
            return AiResponse(responder(), provider, "m-1")
        }
    }

    private val courseRepository = mock<CourseRepository>()
    private val userRepository = mock<UserRepository>()
    private val enrollmentRepository = mock<EnrollmentRepository>()

    private val owner = user(1L, "profe@una.ac.cr")
    private val other = user(2L, "otro@una.ac.cr")
    private val course = Course(id = 10L, code = "EIF411", name = "Móviles", teacher = owner)
    private val pdf = "%PDF-1.4 programa del curso".toByteArray()

    init {
        listOf(owner, other).forEach { whenever(userRepository.findByEmail(it.email)).thenReturn(Optional.of(it)) }
        whenever(courseRepository.findById(10L)).thenReturn(Optional.of(course))
        whenever(courseRepository.findById(99L)).thenReturn(Optional.empty())
        whenever(courseRepository.save(any<Course>())).thenAnswer { it.arguments[0] }
    }

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(id: Long, email: String) =
        User(id = id, firstName = "U$id", lastName = "Test", email = email, roleList = mutableSetOf(Role(name = "ROLE_TEACHER")))

    private fun serviceWith(client: AiClient) = AiSyllabusExtractionService(
        courseRepository,
        AccessGuard(userRepository, enrollmentRepository),
        AiGateway(listOf(client), client.provider),
    )

    private fun serviceReturning(json: String) = serviceWith(StubClient { json })

    @Test
    fun `el profesor dueño recibe el arbol y la salida cruda queda en el curso`() {
        LoggedUser.logIn(owner.email)
        val service = serviceWith(MockAiClient())

        val tree = service.extract(10L, pdf, "application/pdf")

        assertEquals(2, tree.modules.size)
        assertEquals(0.45, tree.modules[1].subtopics[1].confidence)
        assertEquals("mock", tree.provider)
        assertEquals(AiSyllabusExtractionService.PROMPT_VERSION, tree.promptVersion)

        val saved = course.extractedSyllabus!!
        assertEquals("mock", saved["provider"])
        assertEquals(AiSyllabusExtractionService.PROMPT_VERSION, saved["promptVersion"])
        assertEquals(2, ((saved["output"] as Map<*, *>)["modules"] as List<*>).size)
        assertNull(course.syllabusPublishedAt)
        verify(courseRepository).save(course)
    }

    @Test
    fun `quien no es dueño del curso recibe 403 y no se llama a la IA`() {
        LoggedUser.logIn(other.email)
        val client = StubClient { """{"modules":[]}""" }

        assertThrows<ForbiddenException> { serviceWith(client).extract(10L, pdf, "application/pdf") }

        assertEquals(0, client.calls)
        verify(courseRepository, never()).save(any<Course>())
    }

    @Test
    fun `un curso inexistente responde 404`() {
        LoggedUser.logIn(owner.email)

        assertThrows<NotFoundException> { serviceReturning("{}").extract(99L, pdf, "application/pdf") }
    }

    @Test
    fun `solo se acepta un PDF real y sin PDF no se llama a la IA`() {
        LoggedUser.logIn(owner.email)
        val client = StubClient { """{"modules":[]}""" }
        val service = serviceWith(client)

        assertThrows<InvalidInputException> { service.extract(10L, null, "application/pdf") }
        assertThrows<InvalidInputException> { service.extract(10L, ByteArray(0), "application/pdf") }
        assertThrows<InvalidInputException> { service.extract(10L, pdf, "image/png") }
        assertThrows<InvalidInputException> { service.extract(10L, pdf, null) }
        // Dice ser PDF pero no tiene la firma %PDF-.
        assertThrows<InvalidInputException> { service.extract(10L, "esto es texto".toByteArray(), "application/pdf") }

        assertEquals(0, client.calls)
    }

    @Test
    fun `si la IA falla el curso queda como estaba`() {
        LoggedUser.logIn(owner.email)
        course.extractedSyllabus = mapOf("previo" to true)
        val client = StubClient { throw AiUnavailableException("timeout") }

        assertThrows<AiUnavailableException> { serviceWith(client).extract(10L, pdf, "application/pdf") }

        assertEquals(AiGateway.MAX_ATTEMPTS, client.calls)
        assertEquals(mapOf<String, Any?>("previo" to true), course.extractedSyllabus)
        verify(courseRepository, never()).save(any<Course>())
    }

    @Test
    fun `numeros y codigos repetidos o ausentes se corrigen y lo ilegible se descarta`() {
        LoggedUser.logIn(owner.email)
        val service = serviceReturning(
            """{"modules":[
                {"number":1,"title":" Uno ","confidence":1.7,"subtopics":[
                    {"code":"1.1","title":"A","objectives":[" obj ","  "]},
                    {"code":"1.1","title":"B","confidence":0.5},
                    {"title":"C","confidence":0.5},
                    {"code":"1.9","title":"  "}
                ]},
                {"number":1,"title":"Dos","confidence":0.8,"subtopics":[]},
                {"number":3,"title":"   ","subtopics":[]}
            ]}"""
        )

        val modules = service.extract(10L, pdf, "application/pdf").modules

        assertEquals(listOf(1, 2), modules.map { it.number })
        assertEquals(listOf("Uno", "Dos"), modules.map { it.title })
        assertEquals(1.0, modules[0].confidence)
        val subtopics = modules[0].subtopics
        assertEquals(listOf("1.1", "1.2", "1.3"), subtopics.map { it.code })
        assertEquals(listOf("obj"), subtopics[0].objectives)
        assertEquals(0.0, subtopics[0].confidence)
        assertTrue(subtopics[1].objectives.isEmpty())
    }

    @Test
    fun `una respuesta sin modulos o que no es un objeto es un error de la IA`() {
        LoggedUser.logIn(owner.email)

        assertThrows<AiUnavailableException> { serviceReturning("""{"modules":[]}""").extract(10L, pdf, "application/pdf") }
        assertThrows<AiUnavailableException> { serviceReturning("[1,2]").extract(10L, pdf, "application/pdf") }
        assertThrows<AiUnavailableException> { serviceReturning("no es json").extract(10L, pdf, "application/pdf") }

        verify(courseRepository, times(0)).save(any<Course>())
    }
}

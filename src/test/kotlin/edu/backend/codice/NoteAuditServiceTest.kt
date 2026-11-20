package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional

// IA-04: auditoría del apunte con IA. Solo el dueño, solo texto confirmado, y si la IA falla no se toca la cobertura.
class NoteAuditServiceTest {

    /** Proveedor de prueba: ejecuta [responder] (que puede lanzar), cuenta las llamadas y guarda la última petición. */
    private class StubClient(private val responder: () -> String) : AiClient {
        override val provider = "stub"
        var calls = 0
        var lastRequest: AiRequest? = null

        override fun generate(request: AiRequest): AiResponse {
            calls++
            lastRequest = request
            return AiResponse(responder(), provider, "m-1")
        }
    }

    private val noteRepository = mock<NoteRepository>()
    private val subtopicRepository = mock<SubtopicRepository>()
    private val userRepository = mock<UserRepository>()
    private val enrollmentRepository = mock<EnrollmentRepository>()
    private val coverageService = mock<CoverageService>()

    private val student = user(1L, "ana@est.una.ac.cr", "ROLE_STUDENT")
    private val otherStudent = user(2L, "luis@est.una.ac.cr", "ROLE_STUDENT")
    private val teacher = user(3L, "profe@una.ac.cr", "ROLE_TEACHER")

    private val course = Course(id = 1L, code = "EIF411", name = "Móviles")
    private val group = StudentGroup(id = 5L, course = course, number = "01")
    private val enrollment = Enrollment(id = 10L, group = group, student = student, status = EnrollmentStatus.ACTIVE)
    private val module = CourseModule(id = 7L, course = course, number = 1, title = "Árboles")
    private val avl = Subtopic(id = 50L, module = module, code = "1.1", title = "AVL")
    private val heaps = Subtopic(id = 51L, module = module, code = "1.2", title = "Heaps")
    private val note = Note(id = 100L, enrollment = enrollment, confirmedText = "Una rotación simple rebalancea un AVL.")

    init {
        listOf(student, otherStudent, teacher).forEach { whenever(userRepository.findByEmail(it.email)).thenReturn(Optional.of(it)) }
        whenever(noteRepository.findById(100L)).thenReturn(Optional.of(note))
        whenever(noteRepository.findById(999L)).thenReturn(Optional.empty())
        whenever(subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(1L)).thenReturn(listOf(avl, heaps))
        whenever(coverageService.applyAudit(any(), any(), any(), any())).thenReturn(emptyList())
    }

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(id: Long, email: String, role: String) =
        User(id = id, firstName = "U$id", lastName = "Test", email = email, roleList = mutableSetOf(Role(name = role)))

    private fun serviceWith(client: AiClient) = AiNoteAuditService(
        noteRepository, subtopicRepository, AccessGuard(userRepository, enrollmentRepository), coverageService,
        AiGateway(listOf(client), client.provider),
    )

    private fun serviceReturning(json: String) = serviceWith(StubClient { json })

    private fun appliedVerdicts(): List<SubtopicVerdictDTO> {
        val captor = argumentCaptor<List<SubtopicVerdictDTO>>()
        verify(coverageService).applyAudit(eq(100L), captor.capture(), any(), any())
        return captor.firstValue
    }

    @Test
    fun `el dueño audita con IA y los veredictos pasan por applyAudit con el modelo y la version`() {
        LoggedUser.logIn(student.email)

        serviceWith(MockAiClient()).audit(100L)

        verify(coverageService).applyAudit(
            eq(100L),
            eq(listOf(SubtopicVerdictDTO(50L, "COVERED", "Evidencia simulada para el subtema 50"), SubtopicVerdictDTO(51L, "PARTIAL", "Evidencia simulada para el subtema 51"))),
            eq("mock:mock"),
            eq(AiNoteAuditService.PROMPT_VERSION),
        )
    }

    @Test
    fun `el prompt lleva el texto confirmado y los subtemas del curso`() {
        LoggedUser.logIn(student.email)
        val client = StubClient { """{"verdicts":[]}""" }

        serviceWith(client).audit(100L)

        val request = client.lastRequest!!
        assertEquals(AiPurpose.NOTE_AUDIT, request.purpose)
        assertEquals(AiNoteAuditService.PROMPT_VERSION, request.promptVersion)
        assertEquals(listOf(50L, 51L), request.metadata["subtopicIds"])
        assertTrue(request.prompt.contains("subtopicId=50 | 1.1 AVL | objetivos: (sin objetivos)"))
        assertTrue(request.prompt.contains("Una rotación simple rebalancea un AVL."))
    }

    @Test
    fun `se descartan subtemas ajenos y estados inventados, y un subtema repetido conserva su mejor veredicto`() {
        LoggedUser.logIn(student.email)
        val service = serviceReturning(
            """{"verdicts":[
                {"subtopicId":50,"status":"partial","evidence":"a"},
                {"subtopicId":50,"status":"COVERED","evidence":" b "},
                {"subtopicId":999,"status":"COVERED"},
                {"subtopicId":51,"status":"REINFORCED"},
                {"subtopicId":51,"status":"CUBIERTO"}
            ]}"""
        )

        service.audit(100L)

        assertEquals(listOf(SubtopicVerdictDTO(50L, "COVERED", "b")), appliedVerdicts())
    }

    @Test
    fun `la evidencia se recorta a 500 caracteres y una evidencia en blanco queda en null`() {
        LoggedUser.logIn(student.email)
        val long = "x".repeat(900)
        val service = serviceReturning(
            """{"verdicts":[{"subtopicId":50,"status":"COVERED","evidence":"$long"},{"subtopicId":51,"status":"PARTIAL","evidence":"  "}]}"""
        )

        service.audit(100L)

        val verdicts = appliedVerdicts()
        assertEquals(500, verdicts[0].evidence!!.length)
        assertNull(verdicts[1].evidence)
    }

    @Test
    fun `una lista vacia de veredictos es valida y se aplica vacia`() {
        LoggedUser.logIn(student.email)

        serviceReturning("""{"verdicts":[]}""").audit(100L)

        assertTrue(appliedVerdicts().isEmpty())
    }

    @Test
    fun `quien no es dueño, un profesor o un apunte inexistente no llegan a la IA`() {
        val client = StubClient { """{"verdicts":[]}""" }
        val service = serviceWith(client)

        LoggedUser.logIn(otherStudent.email)
        assertThrows<ForbiddenException> { service.audit(100L) }
        LoggedUser.logIn(teacher.email)
        assertThrows<ForbiddenException> { service.audit(100L) }
        LoggedUser.logIn(student.email)
        assertThrows<NotFoundException> { service.audit(999L) }

        assertEquals(0, client.calls)
        verify(coverageService, never()).applyAudit(any(), any(), any(), any())
    }

    @Test
    fun `sin texto confirmado no se audita y no se llama a la IA`() {
        LoggedUser.logIn(student.email)
        val client = StubClient { """{"verdicts":[]}""" }
        val service = serviceWith(client)

        note.confirmedText = null
        assertThrows<BusinessRuleException> { service.audit(100L) }
        note.confirmedText = "   "
        assertThrows<BusinessRuleException> { service.audit(100L) }

        assertEquals(0, client.calls)
    }

    @Test
    fun `un curso sin temario no se audita`() {
        LoggedUser.logIn(student.email)
        whenever(subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(1L)).thenReturn(emptyList())
        val client = StubClient { """{"verdicts":[]}""" }

        assertThrows<BusinessRuleException> { serviceWith(client).audit(100L) }

        assertEquals(0, client.calls)
    }

    @Test
    fun `si la IA falla la cobertura no se toca y el apunte se puede reintentar`() {
        LoggedUser.logIn(student.email)
        val client = StubClient { throw AiUnavailableException("timeout") }

        assertThrows<AiUnavailableException> { serviceWith(client).audit(100L) }

        assertEquals(AiGateway.MAX_ATTEMPTS, client.calls)
        verify(coverageService, never()).applyAudit(any(), any(), any(), any())
    }

    @Test
    fun `una respuesta sin la lista de veredictos es un error de la IA`() {
        LoggedUser.logIn(student.email)

        assertThrows<AiUnavailableException> { serviceReturning("""{"otra":1}""").audit(100L) }
        assertThrows<AiUnavailableException> { serviceReturning("no es json").audit(100L) }

        verify(coverageService, never()).applyAudit(any(), any(), any(), any())
    }
}

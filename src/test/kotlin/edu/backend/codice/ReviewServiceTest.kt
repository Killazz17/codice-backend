package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.InvalidInputException
import edu.backend.codice.exception.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional

// IA-05: quiz de repaso con repetición espaciada (1, 3, 7, 14 y 30 días).
class ReviewServiceTest {

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

    private val reviewRepository = mock<ReviewRepository>()
    private val coverageRepository = mock<CoverageRepository>()
    private val userRepository = mock<UserRepository>()
    private val enrollmentRepository = mock<EnrollmentRepository>()
    private val subtopicRepository = mock<SubtopicRepository>()

    private val student = user(1L, "ana@est.una.ac.cr", "ROLE_STUDENT")
    private val otherStudent = user(2L, "luis@est.una.ac.cr", "ROLE_STUDENT")
    private val teacher = user(3L, "profe@una.ac.cr", "ROLE_TEACHER")

    private val course = Course(id = 1L, code = "EIF411", name = "Móviles")
    private val module = CourseModule(id = 7L, course = course, number = 1, title = "Árboles")
    private val note = Note(id = 100L)
    private val s50 = subtopic(50L, "1.1", "AVL")
    private val s51 = subtopic(51L, "1.2", "Heaps")
    private val s52 = subtopic(52L, "1.3", "Grafos")
    private val s53 = subtopic(53L, "1.4", "Tries")

    private var nextId = 200L
    private val today = AiReviewService.today()

    init {
        listOf(student, otherStudent, teacher).forEach { whenever(userRepository.findByEmail(it.email)).thenReturn(Optional.of(it)) }
        whenever(reviewRepository.findByStudentId(1L)).thenReturn(emptyList())
        whenever(reviewRepository.save(any<Review>())).thenAnswer { (it.arguments[0] as Review).apply { if (id == null) id = nextId++ } }
        whenever(coverageRepository.save(any<Coverage>())).thenAnswer { it.arguments[0] }
    }

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(id: Long, email: String, role: String) =
        User(id = id, firstName = "U$id", lastName = "Test", email = email, roleList = mutableSetOf(Role(name = role)))

    private fun subtopic(id: Long, code: String, title: String) = Subtopic(id = id, module = module, code = code, title = title)

    private fun coverage(subtopic: Subtopic, status: CoverageStatus, justification: String? = null) =
        Coverage(student = student, subtopic = subtopic, status = status, justification = justification, noteOrigin = note)

    private fun serviceWith(client: AiClient) = AiReviewService(
        reviewRepository, coverageRepository, enrollmentRepository, subtopicRepository,
        AccessGuard(userRepository, enrollmentRepository), AiGateway(listOf(client), client.provider),
    )

    // El estudiante está activo en el curso y el temario (s50..s53) tiene [published] o no.
    private fun enrolledInCourse(published: Boolean = true) {
        course.syllabusPublishedAt = if (published) java.time.Instant.now() else null
        val group = StudentGroup(id = 5L, course = course, number = "01")
        whenever(enrollmentRepository.findByStudentIdAndStatus(1L, EnrollmentStatus.ACTIVE))
            .thenReturn(listOf(Enrollment(id = 9L, group = group, student = student, status = EnrollmentStatus.ACTIVE)))
        whenever(subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(1L)).thenReturn(listOf(s50, s51, s52, s53))
    }

    private fun validQuestion(subtopicId: Long, correcta: Int = 0, enunciado: String = "Pregunta $subtopicId") =
        """{"subtemaId":$subtopicId,"enunciado":"$enunciado","opciones":["a","b","c","d"],"correcta":$correcta,"explicacion":"porque sí"}"""

    private fun savedReviews(times: Int): List<Review> {
        val captor = argumentCaptor<Review>()
        verify(reviewRepository, times(times)).save(captor.capture())
        return captor.allValues
    }

    private fun dueReview(subtopic: Subtopic, intervalDay: Int, nextDate: java.time.LocalDate = today) =
        Review(id = 5L, student = student, subtopic = subtopic, intervalDay = intervalDay, nextDate = nextDate, result = ReviewResult.PENDING)

    // ---------- Generar ----------

    @Test
    fun `genera un repaso por cada vacio sin repaso, programado a un dia`() {
        LoggedUser.logIn(student.email)
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(
            listOf(
                coverage(s50, CoverageStatus.NO_NOTES, "No menciona las rotaciones"),
                coverage(s51, CoverageStatus.PARTIAL),
                coverage(s52, CoverageStatus.COVERED),
                coverage(s53, CoverageStatus.NO_NOTES),
            )
        )
        // El subtema 53 ya tiene su repaso: no se genera otro.
        whenever(reviewRepository.findByStudentId(1L)).thenReturn(listOf(Review(id = 1L, student = student, subtopic = s53)))

        val created = serviceWith(MockAiClient()).generate()

        assertEquals(listOf(50L, 51L), created.map { it.subtopicId })
        assertTrue(created.all { it.intervalDay == 1 && it.nextDate == today.plusDays(1) && it.result == "PENDING" })
        assertEquals(4, created[0].questions.single().opciones.size)
        assertEquals(100L, created[0].origin!!.noteId)
        assertEquals("No menciona las rotaciones", created[0].origin!!.justification)

        val stored = savedReviews(2).first().questions!!
        assertEquals("mock:mock", stored["modelo"])
        assertEquals(AiReviewService.PROMPT_VERSION, stored["promptVersion"])
        assertEquals(1, (stored["preguntas"] as List<*>).size)
    }

    @Test
    fun `el prompt lista los subtemas con vacio y lo que detecto la auditoria`() {
        LoggedUser.logIn(student.email)
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(
            listOf(coverage(s50, CoverageStatus.NO_NOTES, "No menciona las rotaciones"), coverage(s52, CoverageStatus.COVERED))
        )
        val client = StubClient { """{"preguntas":[${validQuestion(50L)}]}""" }

        serviceWith(client).generate()

        val request = client.lastRequest!!
        assertEquals(AiPurpose.QUIZ_GENERATION, request.purpose)
        assertEquals(AiReviewService.PROMPT_VERSION, request.promptVersion)
        assertEquals(listOf(50L), request.metadata["subtopicIds"])
        assertTrue(request.prompt.contains("subtemaId=50 | 1.1 AVL | estado: NO_NOTES"))
        assertTrue(request.prompt.contains("vacío detectado: No menciona las rotaciones"))
        assertTrue(!request.prompt.contains("1.3 Grafos"))
    }

    @Test
    fun `sin vacios no se llama a la IA`() {
        LoggedUser.logIn(student.email)
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(listOf(coverage(s52, CoverageStatus.COVERED)))
        val client = StubClient { """{"preguntas":[]}""" }

        assertTrue(serviceWith(client).generate().isEmpty())

        assertEquals(0, client.calls)
    }

    @Test
    fun `un subtema sin ninguna fila de cobertura tambien es un vacio y se le crea su fila`() {
        LoggedUser.logIn(student.email)
        enrolledInCourse()
        // Solo tiene apuntes de AVL: heaps, grafos y tries no tienen fila (la auditoría los omitió).
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(listOf(coverage(s50, CoverageStatus.COVERED)))
        val client = StubClient { """{"preguntas":[${listOf(51L, 52L, 53L).joinToString(",") { validQuestion(it) }}]}""" }

        val created = serviceWith(client).generate()

        assertEquals(listOf(51L, 52L, 53L), created.map { it.subtopicId })
        assertEquals(listOf(51L, 52L, 53L), client.lastRequest!!.metadata["subtopicIds"])
        assertTrue(client.lastRequest!!.prompt.contains("subtemaId=51 | 1.2 Heaps | estado: NO_NOTES"))
        val rows = argumentCaptor<Coverage>()
        verify(coverageRepository, times(3)).save(rows.capture())
        assertTrue(rows.allValues.all { it.student == student && it.status == CoverageStatus.NO_NOTES && it.noteOrigin == null })
    }

    @Test
    fun `los vacios con fila van primero y los subtemas sin fila ocupan el cupo que queda`() {
        LoggedUser.logIn(student.email)
        enrolledInCourse()
        // Un parcial con fila y tres sin fila (s51, s52, s53) que caben todos: 1 + 3 = 4 <= 5.
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(listOf(coverage(s50, CoverageStatus.PARTIAL)))
        val client = StubClient { """{"preguntas":[${listOf(50L, 51L, 52L, 53L).joinToString(",") { validQuestion(it) }}]}""" }

        serviceWith(client).generate()

        assertEquals(listOf(50L, 51L, 52L, 53L), client.lastRequest!!.metadata["subtopicIds"])
    }

    @Test
    fun `un subtema sin fila que ya tiene repaso programado no se repite`() {
        LoggedUser.logIn(student.email)
        enrolledInCourse()
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(listOf(coverage(s50, CoverageStatus.COVERED)))
        whenever(reviewRepository.findByStudentId(1L)).thenReturn(listOf(Review(id = 1L, student = student, subtopic = s51)))
        val client = StubClient { """{"preguntas":[${listOf(52L, 53L).joinToString(",") { validQuestion(it) }}]}""" }

        serviceWith(client).generate()

        assertEquals(listOf(52L, 53L), client.lastRequest!!.metadata["subtopicIds"])
    }

    @Test
    fun `un curso con el temario sin publicar no genera repasos por subtemas sin fila`() {
        LoggedUser.logIn(student.email)
        enrolledInCourse(published = false)
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(emptyList())
        val client = StubClient { """{"preguntas":[]}""" }

        assertTrue(serviceWith(client).generate().isEmpty())

        assertEquals(0, client.calls)
        verify(coverageRepository, never()).save(any<Coverage>())
    }

    @Test
    fun `una llamada genera como maximo 5 repasos`() {
        LoggedUser.logIn(student.email)
        val subtopics = (60L..66L).map { subtopic(it, "2.${it - 59}", "Tema $it") }
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(subtopics.map { coverage(it, CoverageStatus.NO_NOTES) })
        val client = StubClient { """{"preguntas":[${(60L..64L).joinToString(",") { validQuestion(it) }}]}""" }

        val created = serviceWith(client).generate()

        assertEquals(listOf(60L, 61L, 62L, 63L, 64L), client.lastRequest!!.metadata["subtopicIds"])
        assertEquals(5, created.size)
    }

    @Test
    fun `se descartan las preguntas invalidas y queda una por subtema`() {
        LoggedUser.logIn(student.email)
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(
            listOf(coverage(s50, CoverageStatus.NO_NOTES), coverage(s51, CoverageStatus.PARTIAL), coverage(s53, CoverageStatus.NO_NOTES))
        )
        val client = StubClient {
            """{"preguntas":[
                ${validQuestion(50L, correcta = 2, enunciado = "Primera")},
                ${validQuestion(50L, correcta = 0, enunciado = "Repetida")},
                ${validQuestion(51L, correcta = 9)},
                {"subtemaId":53,"enunciado":"Opciones repetidas","opciones":["a","A","c"],"correcta":0,"explicacion":"x"},
                ${validQuestion(999L)}
            ]}"""
        }

        val created = serviceWith(client).generate()

        assertEquals(listOf(50L), created.map { it.subtopicId })
        assertEquals("Primera", created.single().questions.single().enunciado)
        assertEquals(2, created.single().questions.single().correcta)
    }

    @Test
    fun `si ninguna pregunta es valida es un error de la IA y no se guarda nada`() {
        LoggedUser.logIn(student.email)
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(listOf(coverage(s50, CoverageStatus.NO_NOTES)))
        val client = StubClient { """{"preguntas":[${validQuestion(50L, correcta = 7)}]}""" }

        assertThrows<AiUnavailableException> { serviceWith(client).generate() }

        assertEquals(AiGateway.MAX_ATTEMPTS, client.calls)
        verify(reviewRepository, never()).save(any<Review>())
    }

    @Test
    fun `si la IA falla no se guarda nada y se puede reintentar`() {
        LoggedUser.logIn(student.email)
        whenever(coverageRepository.findByStudentId(1L)).thenReturn(listOf(coverage(s50, CoverageStatus.NO_NOTES)))
        val client = StubClient { throw AiUnavailableException("timeout") }

        assertThrows<AiUnavailableException> { serviceWith(client).generate() }

        verify(reviewRepository, never()).save(any<Review>())
    }

    @Test
    fun `un profesor no genera ni ve repasos`() {
        LoggedUser.logIn(teacher.email)
        val client = StubClient { "{}" }
        val service = serviceWith(client)

        assertThrows<ForbiddenException> { service.generate() }
        assertThrows<ForbiddenException> { service.pending() }
        assertThrows<ForbiddenException> { service.recordResult(5L, "PASSED") }

        assertEquals(0, client.calls)
    }

    // ---------- Pendientes ----------

    @Test
    fun `pendientes devuelve lo que vence, ordenado, con las preguntas y el origen`() {
        LoggedUser.logIn(student.email)
        val older = dueReview(s51, 1, today.minusDays(2)).apply { id = 6L }
        val recent = dueReview(s50, 3).apply {
            questions = mapOf(
                "modelo" to "modelo-de-ejemplo",
                "preguntas" to listOf(
                    mapOf("enunciado" to "¿Qué es un AVL?", "opciones" to listOf("Un árbol", "Una lista", "Un mapa"), "correcta" to 0, "subtemaId" to 50, "explicacion" to "Es un árbol balanceado"),
                    "no es una pregunta",
                ),
            )
        }
        whenever(reviewRepository.findByStudentIdAndNextDateLessThanEqual(eq(1L), eq(today))).thenReturn(listOf(recent, older))
        whenever(coverageRepository.findByStudentIdAndSubtopicId(1L, 50L)).thenReturn(Optional.of(coverage(s50, CoverageStatus.NO_NOTES, "No menciona rotaciones")))
        whenever(coverageRepository.findByStudentIdAndSubtopicId(1L, 51L)).thenReturn(Optional.empty())

        val pending = serviceWith(MockAiClient()).pending()

        assertEquals(listOf(6L, 5L), pending.map { it.id })
        assertTrue(pending[0].questions.isEmpty())
        assertEquals(null, pending[0].origin)
        assertEquals("¿Qué es un AVL?", pending[1].questions.single().enunciado)
        assertEquals(3, pending[1].questions.single().opciones.size)
        assertEquals("No menciona rotaciones", pending[1].origin!!.justification)
    }

    // ---------- Programados ----------

    @Test
    fun `programados devuelve los repasos que aun no vencen, del mas proximo al mas lejano, sin tocar el calendario`() {
        LoggedUser.logIn(student.email)
        val far = dueReview(s51, 7, today.plusDays(7)).apply { id = 6L }
        val near = dueReview(s50, 3, today.plusDays(1))
        whenever(reviewRepository.findByStudentIdAndNextDateGreaterThan(eq(1L), eq(today))).thenReturn(listOf(far, near))
        whenever(coverageRepository.findByStudentIdAndSubtopicId(any(), any())).thenReturn(Optional.empty())

        val scheduled = serviceWith(MockAiClient()).scheduled()

        assertEquals(listOf(5L, 6L), scheduled.map { it.id })
        assertEquals(listOf(today.plusDays(1), today.plusDays(7)), scheduled.map { it.nextDate })
        verify(reviewRepository, never()).save(any<Review>())
    }

    @Test
    fun `un profesor no ve los repasos programados`() {
        LoggedUser.logIn(teacher.email)

        assertThrows<ForbiddenException> { serviceWith(MockAiClient()).scheduled() }
    }

    // ---------- Resultado ----------

    @Test
    fun `aprobar sube al siguiente intervalo y cierra el vacio de cobertura`() {
        LoggedUser.logIn(student.email)
        val review = dueReview(s50, 1)
        val gap = coverage(s50, CoverageStatus.NO_NOTES)
        whenever(reviewRepository.findById(5L)).thenReturn(Optional.of(review))
        whenever(coverageRepository.findByStudentIdAndSubtopicId(1L, 50L)).thenReturn(Optional.of(gap))

        val dto = serviceWith(MockAiClient()).recordResult(5L, "passed")

        assertEquals(3, dto.intervalDay)
        assertEquals(today.plusDays(3), dto.nextDate)
        assertEquals("PASSED", dto.result)
        assertEquals(CoverageStatus.COVERED, gap.status)
        assertEquals("Aprobó el repaso de 1 días.", gap.justification)
        verify(coverageRepository).save(gap)
    }

    @Test
    fun `aprobar un subtema ya cubierto lo refuerza`() {
        LoggedUser.logIn(student.email)
        val covered = coverage(s50, CoverageStatus.COVERED)
        whenever(reviewRepository.findById(5L)).thenReturn(Optional.of(dueReview(s50, 3)))
        whenever(coverageRepository.findByStudentIdAndSubtopicId(1L, 50L)).thenReturn(Optional.of(covered))

        val dto = serviceWith(MockAiClient()).recordResult(5L, "PASSED")

        assertEquals(7, dto.intervalDay)
        assertEquals(CoverageStatus.REINFORCED, covered.status)
    }

    @Test
    fun `fallar vuelve a un dia y no toca la cobertura`() {
        LoggedUser.logIn(student.email)
        whenever(reviewRepository.findById(5L)).thenReturn(Optional.of(dueReview(s50, 7)))

        val dto = serviceWith(MockAiClient()).recordResult(5L, "FAILED")

        assertEquals(1, dto.intervalDay)
        assertEquals(today.plusDays(1), dto.nextDate)
        assertEquals("FAILED", dto.result)
        verify(coverageRepository, never()).save(any<Coverage>())
    }

    @Test
    fun `el calculo de intervalos sube, se queda en 30 y vuelve a 1 al fallar`() {
        assertEquals(listOf(3, 7, 14, 30, 30), listOf(1, 3, 7, 14, 30).map { AiReviewService.nextInterval(it, passed = true) })
        assertEquals(7, AiReviewService.nextInterval(5, passed = true))
        assertEquals(listOf(1, 1, 1), listOf(3, 14, 30).map { AiReviewService.nextInterval(it, passed = false) })
    }

    @Test
    fun `solo el dueño registra el resultado y un repaso inexistente es 404`() {
        whenever(reviewRepository.findById(5L)).thenReturn(Optional.of(dueReview(s50, 1)))
        whenever(reviewRepository.findById(999L)).thenReturn(Optional.empty())
        val service = serviceWith(MockAiClient())

        LoggedUser.logIn(otherStudent.email)
        assertThrows<ForbiddenException> { service.recordResult(5L, "PASSED") }
        LoggedUser.logIn(student.email)
        assertThrows<NotFoundException> { service.recordResult(999L, "PASSED") }

        verify(reviewRepository, never()).save(any<Review>())
    }

    @Test
    fun `un repaso que todavia no vence no se puede aprobar`() {
        LoggedUser.logIn(student.email)
        whenever(reviewRepository.findById(5L)).thenReturn(Optional.of(dueReview(s50, 3, today.plusDays(2))))

        assertThrows<BusinessRuleException> { serviceWith(MockAiClient()).recordResult(5L, "PASSED") }

        verify(reviewRepository, never()).save(any<Review>())
    }

    @Test
    fun `un resultado que no es PASSED ni FAILED es una solicitud invalida`() {
        LoggedUser.logIn(student.email)
        whenever(reviewRepository.findById(5L)).thenReturn(Optional.of(dueReview(s50, 1)))
        val service = serviceWith(MockAiClient())

        assertThrows<InvalidInputException> { service.recordResult(5L, "PENDING") }
        assertThrows<InvalidInputException> { service.recordResult(5L, "tal vez") }

        verify(reviewRepository, never()).save(any<Review>())
    }
}

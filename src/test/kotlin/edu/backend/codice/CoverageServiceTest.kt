package edu.backend.codice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ForbiddenException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mapstruct.factory.Mappers
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional

// SVC-06: auditoría simulada, cobertura personal y cobertura agregada y anónima del grupo.
class CoverageServiceTest {

    private val noteRepository = mock<NoteRepository>()
    private val subtopicRepository = mock<SubtopicRepository>()
    private val coverageRepository = mock<CoverageRepository>()
    private val enrollmentRepository = mock<EnrollmentRepository>()
    private val studentGroupRepository = mock<StudentGroupRepository>()
    private val userRepository = mock<UserRepository>()
    private val reviewRepository = mock<ReviewRepository>()

    private val service = AbstractCoverageService(
        noteRepository, subtopicRepository, coverageRepository, enrollmentRepository, studentGroupRepository,
        userRepository, Mappers.getMapper(CoverageMapper::class.java), reviewRepository,
    )

    private val teacher = user(1L, "profe@una.ac.cr", "ROLE_TEACHER")
    private val otherTeacher = user(2L, "otro@una.ac.cr", "ROLE_TEACHER")
    private val studentA = user(3L, "a@est.una.ac.cr", "ROLE_STUDENT")
    private val studentB = user(4L, "b@est.una.ac.cr", "ROLE_STUDENT")

    private val course = Course(id = 1L, code = "EIF411", name = "Móviles")
    private val module = CourseModule(id = 1L, course = course, number = 4, title = "Árboles")
    private val avl = Subtopic(id = 10L, module = module, code = "4.1", title = "AVL", position = 1)
    private val heaps = Subtopic(id = 11L, module = module, code = "4.2", title = "Heaps", position = 2)
    private val tries = Subtopic(id = 12L, module = module, code = "4.3", title = "Tries", position = 3)
    private val foreign = Subtopic(
        id = 99L, code = "1.1", title = "Otro curso",
        module = CourseModule(id = 9L, course = Course(id = 9L, code = "EIF400", name = "Paradigmas"), number = 1, title = "M"),
    )
    private val group = StudentGroup(id = 5L, course = course, teacher = teacher, number = "01")

    private val noteA = Note(id = 100L, enrollment = Enrollment(id = 20L, group = group, student = studentA, status = EnrollmentStatus.ACTIVE))

    init {
        listOf(teacher, otherTeacher, studentA, studentB).forEach { whenever(userRepository.findByEmail(it.email)).thenReturn(Optional.of(it)) }
        listOf(avl, heaps, tries, foreign).forEach { whenever(subtopicRepository.findById(it.id!!)).thenReturn(Optional.of(it)) }
        whenever(noteRepository.findById(100L)).thenReturn(Optional.of(noteA))
        whenever(studentGroupRepository.findById(5L)).thenReturn(Optional.of(group))
        whenever(subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(1L)).thenReturn(listOf(avl, heaps, tries))
        whenever(coverageRepository.findByStudentIdAndSubtopicId(any(), any())).thenReturn(Optional.empty())
        whenever(coverageRepository.save(any<Coverage>())).thenAnswer { it.arguments[0] }
        whenever(enrollmentRepository.existsByGroupIdAndStudentIdAndStatus(5L, studentA.id!!, EnrollmentStatus.ACTIVE)).thenReturn(true)
    }

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(id: Long, email: String, role: String) =
        User(id = id, firstName = "U$id", lastName = "Test", email = email, roleList = mutableSetOf(Role(name = role)))

    private fun loginAs(user: User) = LoggedUser.logIn(user.email)

    private fun audit(vararg verdicts: Pair<Long, String>) =
        NoteAuditRequestDTO(verdicts.map { (id, status) -> SubtopicVerdictDTO(subtopicId = id, status = status, evidence = "evidencia $id") })

    private fun count(subtopicId: Long, status: CoverageStatus, total: Long) = object : SubtopicCoverageCount {
        override val subtopicId = subtopicId
        override val status = status
        override val total = total
    }

    // ---------- Auditoría ----------

    @Test
    fun `una auditoria valida guarda los veredictos en el apunte y crea la cobertura`() {
        loginAs(studentA)

        val result = service.registerAudit(100L, audit(10L to "COVERED", 11L to "PARTIAL"))

        assertEquals(listOf("COVERED", "PARTIAL"), result.map { it.status })
        assertEquals("stub", noteA.audit?.get("model"))
        assertEquals(AbstractCoverageService.AUDIT_PROMPT_VERSION, noteA.audit?.get("promptVersion"))
        assertEquals(2, (noteA.audit?.get("verdicts") as List<*>).size)
        val saved = argumentCaptor<Coverage>()
        verify(coverageRepository, times(2)).save(saved.capture())
        assertEquals(studentA, saved.firstValue.student)
        assertEquals("evidencia 10", saved.firstValue.justification)
        assertEquals(noteA, saved.firstValue.noteOrigin)
    }

    // ---------- Borrar un apunte ----------

    private fun audited(note: Note, vararg verdicts: Pair<Long, String>) = note.apply {
        audit = mapOf("verdicts" to verdicts.map { (id, status) -> mapOf("subtopicId" to id, "status" to status, "evidence" to "evidencia $id") })
    }

    private fun rowOf(subtopic: Subtopic, status: CoverageStatus, origin: Note?) =
        Coverage(id = subtopic.id, student = studentA, subtopic = subtopic, status = status, noteOrigin = origin).also {
            whenever(coverageRepository.findByStudentIdAndSubtopicId(studentA.id!!, subtopic.id!!)).thenReturn(Optional.of(it))
        }

    private fun remainingNotes(vararg notes: Note) =
        whenever(noteRepository.findByEnrollmentStudentIdOrderByCreatedAtDesc(studentA.id!!)).thenReturn(notes.toList())

    @Test
    fun `borrar el unico apunte que respaldaba un subtema borra su cobertura`() {
        audited(noteA, 10L to "COVERED")
        val row = rowOf(avl, CoverageStatus.COVERED, noteA)
        remainingNotes(noteA)

        service.refreshAfterNoteRemoval(noteA)

        verify(coverageRepository).delete(row)
    }

    @Test
    fun `borrar un apunte conserva lo que otro apunte todavia respalda`() {
        val noteB = Note(id = 101L, enrollment = noteA.enrollment, createdAt = java.time.Instant.parse("2026-11-02T10:00:00Z"))
        audited(noteA, 10L to "COVERED")
        audited(noteB, 10L to "COVERED")
        val row = rowOf(avl, CoverageStatus.REINFORCED, noteA)
        remainingNotes(noteA, noteB)

        service.refreshAfterNoteRemoval(noteA)

        // Con dos apuntes cubría de sobra (REINFORCED); con uno solo queda COVERED y la evidencia pasa al apunte que queda.
        assertEquals(CoverageStatus.COVERED, row.status)
        assertEquals(noteB, row.noteOrigin)
        verify(coverageRepository, never()).delete(any<Coverage>())
    }

    @Test
    fun `borrar un apunte baja a PARTIAL si lo que queda solo lo menciona`() {
        val noteB = Note(id = 101L, enrollment = noteA.enrollment, createdAt = java.time.Instant.parse("2026-11-02T10:00:00Z"))
        audited(noteA, 11L to "COVERED")
        audited(noteB, 11L to "PARTIAL")
        val row = rowOf(heaps, CoverageStatus.COVERED, noteA)
        remainingNotes(noteA, noteB)

        service.refreshAfterNoteRemoval(noteA)

        assertEquals(CoverageStatus.PARTIAL, row.status)
        assertEquals("evidencia 11", row.justification)
    }

    @Test
    fun `un repaso aprobado conserva COVERED aunque ya no quede el apunte`() {
        audited(noteA, 10L to "COVERED")
        val row = rowOf(avl, CoverageStatus.COVERED, noteA)
        remainingNotes(noteA)
        whenever(reviewRepository.findByStudentId(studentA.id!!)).thenReturn(
            listOf(Review(student = studentA, subtopic = avl, result = ReviewResult.PASSED)),
        )

        service.refreshAfterNoteRemoval(noteA)

        assertEquals(CoverageStatus.COVERED, row.status)
        assertEquals(null, row.noteOrigin)
        verify(coverageRepository, never()).delete(any<Coverage>())
    }

    @Test
    fun `borrar un apunte sin auditar no toca la cobertura`() {
        remainingNotes(noteA)

        service.refreshAfterNoteRemoval(noteA)

        verify(coverageRepository, never()).delete(any<Coverage>())
        verify(coverageRepository, never()).save(any<Coverage>())
    }

    @Test
    fun `la consolidacion conserva el mejor estado y refuerza lo que ya estaba cubierto`() {
        assertEquals(CoverageStatus.PARTIAL, AbstractCoverageService.consolidate(null, CoverageStatus.PARTIAL))
        assertEquals(CoverageStatus.COVERED, AbstractCoverageService.consolidate(CoverageStatus.PARTIAL, CoverageStatus.COVERED))
        assertEquals(CoverageStatus.PARTIAL, AbstractCoverageService.consolidate(CoverageStatus.PARTIAL, CoverageStatus.NO_NOTES))
        assertEquals(CoverageStatus.COVERED, AbstractCoverageService.consolidate(CoverageStatus.COVERED, CoverageStatus.PARTIAL))
        assertEquals(CoverageStatus.REINFORCED, AbstractCoverageService.consolidate(CoverageStatus.COVERED, CoverageStatus.COVERED))
        assertEquals(CoverageStatus.REINFORCED, AbstractCoverageService.consolidate(CoverageStatus.REINFORCED, CoverageStatus.COVERED))
    }

    @Test
    fun `un veredicto peor no baja la cobertura que ya existe`() {
        loginAs(studentA)
        val existing = Coverage(id = 7L, student = studentA, subtopic = avl, status = CoverageStatus.COVERED, justification = "antes")
        whenever(coverageRepository.findByStudentIdAndSubtopicId(studentA.id!!, 10L)).thenReturn(Optional.of(existing))

        val result = service.registerAudit(100L, audit(10L to "PARTIAL"))

        assertEquals("COVERED", result.single().status)
        assertEquals("antes", existing.justification)
    }

    @Test
    fun `un subtema de otro curso rechaza toda la auditoria sin guardar nada`() {
        loginAs(studentA)

        assertThrows<BusinessRuleException> { service.registerAudit(100L, audit(10L to "COVERED", 99L to "COVERED")) }

        verify(noteRepository, never()).save(any<Note>())
        verify(coverageRepository, never()).save(any<Coverage>())
    }

    @Test
    fun `solo el dueno del apunte puede auditarlo`() {
        loginAs(studentB)
        assertThrows<ForbiddenException> { service.registerAudit(100L, audit(10L to "COVERED")) }

        loginAs(teacher)
        assertThrows<ForbiddenException> { service.registerAudit(100L, audit(10L to "COVERED")) }
    }

    // ---------- Cobertura personal ----------

    @Test
    fun `la cobertura personal sin registros pone todos los subtemas en NO_NOTES`() {
        loginAs(studentA)
        whenever(coverageRepository.findByStudentIdAndSubtopicModuleCourseId(studentA.id!!, 1L)).thenReturn(emptyList())

        val coverage = service.personalCoverage(5L)

        assertEquals(listOf("NO_NOTES", "NO_NOTES", "NO_NOTES"), coverage.subtopics.map { it.status })
        assertEquals(0.0, coverage.coveredPercentage)
    }

    @Test
    fun `la cobertura personal con registros cuenta cubierto y reforzado`() {
        loginAs(studentA)
        whenever(coverageRepository.findByStudentIdAndSubtopicModuleCourseId(studentA.id!!, 1L)).thenReturn(
            listOf(
                Coverage(student = studentA, subtopic = avl, status = CoverageStatus.REINFORCED),
                Coverage(student = studentA, subtopic = heaps, status = CoverageStatus.PARTIAL),
            ),
        )

        val coverage = service.personalCoverage(5L)

        assertEquals(listOf("4.1", "4.2", "4.3"), coverage.subtopics.map { it.subtopicCode })
        assertEquals(listOf("REINFORCED", "PARTIAL", "NO_NOTES"), coverage.subtopics.map { it.status })
        assertEquals(100.0 / 3, coverage.coveredPercentage, 1e-9)
    }

    @Test
    fun `sin matricula aprobada no hay cobertura personal y un profesor no puede pedirla`() {
        loginAs(studentB)
        assertThrows<ForbiddenException> { service.personalCoverage(5L) }

        loginAs(teacher)
        assertThrows<ForbiddenException> { service.personalCoverage(5L) }
    }

    // ---------- Cobertura del grupo ----------

    @Test
    fun `con 3 estudiantes, 1 cubierto, 1 parcial y 1 sin apuntes da un tercio en cada estado`() {
        loginAs(teacher)
        whenever(enrollmentRepository.countByGroupIdAndStatus(5L, EnrollmentStatus.ACTIVE)).thenReturn(3L)
        whenever(coverageRepository.countByStatusForGroup(5L)).thenReturn(
            listOf(count(10L, CoverageStatus.COVERED, 1), count(10L, CoverageStatus.PARTIAL, 1)),
        )

        val coverage = service.groupCoverage(5L)

        assertEquals(3, coverage.totalStudents)
        val avlRow = coverage.subtopics.first { it.subtopicId == 10L }
        assertEquals(100.0 / 3, avlRow.coveredPercentage, 1e-9)
        assertEquals(100.0 / 3, avlRow.partialPercentage, 1e-9)
        assertEquals(100.0 / 3, avlRow.noNotesPercentage, 1e-9)
        // Un subtema sin filas: todo el grupo sin apuntes.
        val triesRow = coverage.subtopics.first { it.subtopicId == 12L }
        assertEquals(100.0, triesRow.noNotesPercentage)
    }

    @Test
    fun `reforzado cuenta como cubierto y los no_notes guardados como sin apuntes`() {
        loginAs(teacher)
        whenever(enrollmentRepository.countByGroupIdAndStatus(5L, EnrollmentStatus.ACTIVE)).thenReturn(4L)
        whenever(coverageRepository.countByStatusForGroup(5L)).thenReturn(
            listOf(
                count(10L, CoverageStatus.COVERED, 1), count(10L, CoverageStatus.REINFORCED, 2),
                count(10L, CoverageStatus.NO_NOTES, 1),
            ),
        )

        val avlRow = service.groupCoverage(5L).subtopics.first { it.subtopicId == 10L }

        assertEquals(75.0, avlRow.coveredPercentage)
        assertEquals(0.0, avlRow.partialPercentage)
        assertEquals(25.0, avlRow.noNotesPercentage)
    }

    @Test
    fun `un grupo sin estudiantes da todo en cero`() {
        loginAs(teacher)
        whenever(enrollmentRepository.countByGroupIdAndStatus(5L, EnrollmentStatus.ACTIVE)).thenReturn(0L)
        whenever(coverageRepository.countByStatusForGroup(5L)).thenReturn(emptyList())

        val coverage = service.groupCoverage(5L)

        assertEquals(0, coverage.totalStudents)
        coverage.subtopics.forEach {
            assertEquals(0.0, it.coveredPercentage)
            assertEquals(0.0, it.partialPercentage)
            assertEquals(0.0, it.noNotesPercentage)
        }
    }

    @Test
    fun `la cobertura del grupo no contiene ni carga datos de ningun estudiante`() {
        loginAs(teacher)
        whenever(enrollmentRepository.countByGroupIdAndStatus(5L, EnrollmentStatus.ACTIVE)).thenReturn(2L)
        whenever(coverageRepository.countByStatusForGroup(5L)).thenReturn(listOf(count(10L, CoverageStatus.COVERED, 2)))

        val json = jacksonObjectMapper().valueToTree<JsonNode>(service.groupCoverage(5L))

        fun keys(node: JsonNode): Set<String> = when {
            node.isObject -> node.fields().asSequence().flatMap { (k, v) -> sequenceOf(k) + keys(v) }.toSet()
            node.isArray -> node.flatMap { keys(it) }.toSet()
            else -> emptySet()
        }
        val forbidden = setOf("studentId", "studentName", "studentEmail", "userId", "name", "email", "firstName", "lastName")
        assertTrue(keys(json).intersect(forbidden).isEmpty())
        assertTrue(json.toString().let { s -> listOf("a@est.una.ac.cr", "b@est.una.ac.cr").none { it in s } })
        // Solo conteos agregados: el servicio nunca pide filas de cobertura de un estudiante.
        verify(coverageRepository, never()).findByStudentIdAndSubtopicModuleCourseId(any(), any())
        verify(coverageRepository, never()).findByStudentId(any())
        verify(enrollmentRepository, never()).findByGroupIdAndStatus(any(), any())
    }

    @Test
    fun `un estudiante y un profesor ajeno no pueden pedir la cobertura del grupo`() {
        loginAs(studentA)
        assertThrows<ForbiddenException> { service.groupCoverage(5L) }

        loginAs(otherTeacher)
        assertThrows<ForbiddenException> { service.groupCoverage(5L) }
    }
}

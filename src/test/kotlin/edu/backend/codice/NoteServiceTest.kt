package edu.backend.codice

import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ConflictException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mapstruct.factory.Mappers
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.util.Optional

// SVC-05: cada estudiante solo accede a sus propios apuntes y un profesor nunca ve apuntes individuales.
class NoteServiceTest {

    private val noteRepository = mock<NoteRepository>()
    private val notePageRepository = mock<NotePageRepository>()
    private val enrollmentRepository = mock<EnrollmentRepository>()
    private val subtopicRepository = mock<SubtopicRepository>()
    private val userRepository = mock<UserRepository>()
    private val coverageService = mock<CoverageService>()

    private val transcriptionService = TranscriptionService(AiGateway(listOf(MockAiClient()), "mock"), 0.70)

    // Los primeros bytes reales de un JPEG: el servicio no se fía del tipo que declara el cliente.
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    private val service = AbstractNoteService(
        noteRepository, notePageRepository, enrollmentRepository, subtopicRepository, userRepository,
        Mappers.getMapper(NoteMapper::class.java),
        transcriptionService,
        coverageService
    )

    // Dos estudiantes del mismo grupo y un profesor.
    private val studentA = user(1L, "a@est.una.ac.cr", "ROLE_STUDENT")
    private val studentB = user(2L, "b@est.una.ac.cr", "ROLE_STUDENT")
    private val teacher = user(3L, "profe@una.ac.cr", "ROLE_TEACHER")

    private val course = Course(id = 1L, code = "EIF411", name = "Móviles")
    private val otherCourse = Course(id = 2L, code = "EIF400", name = "Paradigmas")
    private val subtopic = subtopicOf(course, 50L)
    private val foreignSubtopic = subtopicOf(otherCourse, 60L)
    private val group = StudentGroup(id = 5L, course = course, number = "01")

    private val enrollmentA = Enrollment(id = 10L, group = group, student = studentA, status = EnrollmentStatus.ACTIVE)
    private val pendingA = Enrollment(id = 11L, group = group, student = studentA, status = EnrollmentStatus.PENDING)
    private val enrollmentB = Enrollment(id = 12L, group = group, student = studentB, status = EnrollmentStatus.ACTIVE)

    private val noteA = Note(id = 100L, enrollment = enrollmentA, subtopic = subtopic, confirmedText = "Apunte de A")
    private val noteB = Note(id = 200L, enrollment = enrollmentB, subtopic = subtopic, confirmedText = "Apunte de B")

    init {
        listOf(studentA, studentB, teacher).forEach { whenever(userRepository.findByEmail(it.email)).thenReturn(Optional.of(it)) }
        listOf(enrollmentA, pendingA, enrollmentB).forEach { whenever(enrollmentRepository.findById(it.id!!)).thenReturn(Optional.of(it)) }
        listOf(subtopic, foreignSubtopic).forEach { whenever(subtopicRepository.findById(it.id!!)).thenReturn(Optional.of(it)) }
        listOf(noteA, noteB).forEach { whenever(noteRepository.findById(it.id!!)).thenReturn(Optional.of(it)) }
        whenever(noteRepository.findById(999L)).thenReturn(Optional.empty())
        whenever(noteRepository.findByClientRequestId(any())).thenReturn(Optional.empty())
        whenever(noteRepository.save(any<Note>())).thenAnswer { (it.arguments[0] as Note).apply { if (id == null) id = 300L } }
    }

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(id: Long, email: String, role: String) =
        User(id = id, firstName = "U$id", lastName = "Test", email = email, roleList = mutableSetOf(Role(name = role)))

    private fun subtopicOf(course: Course, id: Long) =
        Subtopic(id = id, module = CourseModule(id = id, course = course, number = 1, title = "Módulo"), code = "1.1", title = "Tema")

    private fun loginAs(user: User) = LoggedUser.logIn(user.email)

    // ---------- Transcripción con IA (IA-03) ----------

    @Test
    fun `transcribir guarda la salida cruda en el apunte y no confirma el texto`() {
        loginAs(studentA)

        val dto = service.transcribeAndAttach(100L, jpeg, "image/jpeg")

        assertEquals(100L, dto.id)
        assertEquals("Apunte de A", noteA.confirmedText)
        val transcription = noteA.transcription!!
        assertEquals("mock", transcription["proveedor"])
        assertEquals(TranscriptionService.PROMPT_VERSION, transcription["promptVersion"])
        assertEquals(1, transcription["bloquesPorRevisar"])
    }

    @Test
    fun `un estudiante no puede transcribir sobre el apunte de otro`() {
        loginAs(studentA)

        assertThrows<ForbiddenException> { service.transcribeAndAttach(200L, jpeg, "image/jpeg") }
        verify(noteRepository, never()).save(any<Note>())
    }

    // ---------- Crear ----------

    @Test
    fun `un estudiante con matricula aprobada crea un apunte en su grupo`() {
        loginAs(studentA)

        val dto = service.createNote(NoteCreateDTO(enrollmentId = 10L, subtopicId = 50L, confirmedText = "AVL"))

        assertEquals(300L, dto.id)
        assertEquals(10L, dto.enrollmentId)
        assertEquals(50L, dto.subtopicId)
        assertEquals("AVL", dto.confirmedText)
        assertEquals("SYNCED", dto.syncStatus)
    }

    @Test
    fun `sin matricula aprobada no se puede crear un apunte`() {
        loginAs(studentA)

        assertThrows<ForbiddenException> { service.createNote(NoteCreateDTO(enrollmentId = 11L, confirmedText = "AVL")) }
        verify(noteRepository, never()).save(any<Note>())
    }

    @Test
    fun `no se puede crear un apunte en la matricula de otro estudiante`() {
        loginAs(studentA)

        assertThrows<ForbiddenException> { service.createNote(NoteCreateDTO(enrollmentId = 12L, confirmedText = "AVL")) }
        verify(noteRepository, never()).save(any<Note>())
    }

    @Test
    fun `el subtema tiene que ser del curso del grupo`() {
        loginAs(studentA)

        assertThrows<BusinessRuleException> { service.createNote(NoteCreateDTO(enrollmentId = 10L, subtopicId = 60L)) }
        verify(noteRepository, never()).save(any<Note>())
    }

    @Test
    fun `reintentar una subida con el mismo clientRequestId devuelve el mismo apunte`() {
        loginAs(studentA)
        whenever(noteRepository.findByClientRequestId("req-1")).thenReturn(Optional.of(noteA))

        val dto = service.createNote(NoteCreateDTO(enrollmentId = 10L, clientRequestId = "req-1"))

        assertEquals(100L, dto.id)
        verify(noteRepository, never()).save(any<Note>())
    }

    @Test
    fun `un clientRequestId de otro estudiante no devuelve su apunte`() {
        loginAs(studentA)
        whenever(noteRepository.findByClientRequestId("req-b")).thenReturn(Optional.of(noteB))

        assertThrows<ConflictException> { service.createNote(NoteCreateDTO(enrollmentId = 10L, clientRequestId = "req-b")) }
    }

    // ---------- Listar y buscar ----------

    private fun stubSearch() = whenever(
        noteRepository.searchByStudent(any(), anyOrNull(), anyOrNull(), any(), any()),
    ).thenAnswer { PageImpl(listOf(noteA), it.arguments[4] as Pageable, 1) }

    @Test
    fun `listar sin filtros pide solo los apuntes del estudiante, del mas reciente al mas antiguo`() {
        loginAs(studentA)
        stubSearch()

        val page = service.search(null, null, null, 0, 20)

        val pageable = argumentCaptor<Pageable>()
        verify(noteRepository).searchByStudent(eq(1L), eq(null), eq(null), eq(""), pageable.capture())
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt"), pageable.firstValue.sort)
        assertEquals(listOf(100L), page.content.map { it.id })
        assertEquals(1L, page.totalElements)
    }

    @Test
    fun `cada filtro llega a la consulta`() {
        loginAs(studentA)
        stubSearch()

        service.search(5L, null, null, 0, 20)
        service.search(null, 50L, null, 0, 20)
        service.search(null, null, "  avl  ", 0, 20)

        verify(noteRepository).searchByStudent(eq(1L), eq(5L), eq(null), eq(""), any())
        verify(noteRepository).searchByStudent(eq(1L), eq(null), eq(50L), eq(""), any())
        verify(noteRepository).searchByStudent(eq(1L), eq(null), eq(null), eq("avl"), any())
    }

    @Test
    fun `la paginacion se acota a valores validos`() {
        loginAs(studentA)
        stubSearch()

        service.search(null, null, null, -3, 500)

        val pageable = argumentCaptor<Pageable>()
        verify(noteRepository).searchByStudent(any(), anyOrNull(), anyOrNull(), any(), pageable.capture())
        assertEquals(0, pageable.firstValue.pageNumber)
        assertEquals(AbstractNoteService.MAX_PAGE_SIZE, pageable.firstValue.pageSize)
    }

    @Test
    fun `manipular los filtros no cambia de quien son los apuntes que se buscan`() {
        loginAs(studentA)
        stubSearch()

        // El grupo y el subtema existen y tienen apuntes de B, pero la consulta sigue atada a A.
        service.search(group.id, subtopic.id, "B", 0, 20)

        verify(noteRepository).searchByStudent(eq(studentA.id!!), eq(5L), eq(50L), eq("B"), any())
    }

    // ---------- Obtener, actualizar y eliminar ----------

    @Test
    fun `el dueno obtiene su apunte`() {
        loginAs(studentA)

        assertEquals("Apunte de A", service.findById(100L).confirmedText)
    }

    @Test
    fun `un apunte inexistente responde NotFound`() {
        loginAs(studentA)

        assertThrows<NotFoundException> { service.findById(999L) }
    }

    @Test
    fun `el estudiante A no puede obtener, actualizar ni eliminar el apunte de B`() {
        loginAs(studentA)

        assertThrows<ForbiddenException> { service.findById(200L) }
        assertThrows<ForbiddenException> { service.update(200L, NoteUpdateDTO(confirmedText = "hackeado")) }
        assertThrows<ForbiddenException> { service.delete(200L) }

        assertEquals("Apunte de B", noteB.confirmedText)
        verify(noteRepository, never()).delete(any<Note>())
    }

    @Test
    fun `un profesor no puede obtener ni listar apuntes`() {
        loginAs(teacher)

        assertThrows<ForbiddenException> { service.findById(100L) }
        assertThrows<ForbiddenException> { service.search(null, null, null, 0, 20) }
        assertThrows<ForbiddenException> { service.createNote(NoteCreateDTO(enrollmentId = 10L)) }
    }

    @Test
    fun `el dueno actualiza el texto y el subtema de su apunte`() {
        loginAs(studentA)
        val note = Note(id = 101L, enrollment = enrollmentA, confirmedText = "Antes")
        whenever(noteRepository.findById(101L)).thenReturn(Optional.of(note))

        val dto = service.update(101L, NoteUpdateDTO(subtopicId = 50L, confirmedText = "Después"))

        assertEquals("Después", dto.confirmedText)
        assertEquals(50L, dto.subtopicId)
        assertSame(subtopic, note.subtopic)
    }

    @Test
    fun `actualizar a un subtema de otro curso se rechaza`() {
        loginAs(studentA)

        assertThrows<BusinessRuleException> { service.update(100L, NoteUpdateDTO(subtopicId = 60L)) }
        assertFalse(noteA.subtopic === foreignSubtopic)
    }

    @Test
    fun `el dueno elimina su apunte`() {
        loginAs(studentA)

        service.delete(100L)

        verify(noteRepository).delete(noteA)
    }

    @Test
    fun `al borrar un apunte se recalcula la cobertura que respaldaba`() {
        loginAs(studentA)

        service.delete(100L)

        verify(coverageService).refreshAfterNoteRemoval(noteA)
    }

    @Test
    fun `si no se puede borrar el apunte tampoco se toca la cobertura`() {
        loginAs(studentA)

        assertThrows<ForbiddenException> { service.delete(200L) }

        verify(coverageService, never()).refreshAfterNoteRemoval(any())
    }
}

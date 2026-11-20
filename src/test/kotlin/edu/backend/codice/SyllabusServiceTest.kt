package edu.backend.codice

import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ConflictException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mapstruct.factory.Mappers
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional

// SVC-04: el profesor dueño edita y publica el temario del curso; el estudiante aprobado lo lee publicado.
class SyllabusServiceTest {

    private val courseModuleRepository = mock<CourseModuleRepository>()
    private val subtopicRepository = mock<SubtopicRepository>()
    private val subtopicObjectiveRepository = mock<SubtopicObjectiveRepository>()
    private val courseRepository = mock<CourseRepository>()
    private val noteRepository = mock<NoteRepository>()
    private val enrollmentRepository = mock<EnrollmentRepository>()
    private val userRepository = mock<UserRepository>()

    private val service = AbstractSyllabusService(
        courseModuleRepository, subtopicRepository, subtopicObjectiveRepository, courseRepository, noteRepository,
        Mappers.getMapper(SyllabusMapper::class.java), AccessGuard(userRepository, enrollmentRepository),
    )

    private val owner = user(1L, "profe@una.ac.cr", "ROLE_TEACHER")
    private val otherTeacher = user(2L, "otro@una.ac.cr", "ROLE_TEACHER")
    private val approved = user(3L, "a@est.una.ac.cr", "ROLE_STUDENT")
    private val notApproved = user(4L, "b@est.una.ac.cr", "ROLE_STUDENT")

    private val course = Course(id = 10L, code = "EIF411", name = "Móviles", teacher = owner)
    private val module = CourseModule(id = 20L, course = course, number = 4, title = "Árboles")
    private val avl = Subtopic(id = 30L, code = "4.1", title = "AVL", position = 1).also { module.addSubtopic(it) }

    init {
        listOf(owner, otherTeacher, approved, notApproved).forEach { whenever(userRepository.findByEmail(it.email)).thenReturn(Optional.of(it)) }
        whenever(courseRepository.findById(10L)).thenReturn(Optional.of(course))
        whenever(courseModuleRepository.findById(20L)).thenReturn(Optional.of(module))
        whenever(courseModuleRepository.findByCourseIdOrderByNumber(10L)).thenReturn(listOf(module))
        whenever(subtopicRepository.findById(30L)).thenReturn(Optional.of(avl))
        whenever(subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(10L)).thenAnswer { module.subtopics.toList() }
        whenever(subtopicRepository.save(any<Subtopic>())).thenAnswer { (it.arguments[0] as Subtopic).apply { if (id == null) id = 31L } }
        whenever(subtopicObjectiveRepository.save(any<SubtopicObjective>())).thenAnswer { (it.arguments[0] as SubtopicObjective).apply { if (id == null) id = 40L } }
        whenever(courseModuleRepository.save(any<CourseModule>())).thenAnswer { (it.arguments[0] as CourseModule).apply { if (id == null) id = 21L } }
        // Solo `approved` tiene matrícula ACTIVE; `notApproved` está PENDING, REJECTED o sin matrícula.
        whenever(enrollmentRepository.existsByGroupCourseIdAndStudentIdAndStatus(10L, approved.id!!, EnrollmentStatus.ACTIVE)).thenReturn(true)
        whenever(enrollmentRepository.existsByGroupCourseIdAndStudentIdAndStatus(10L, notApproved.id!!, EnrollmentStatus.ACTIVE)).thenReturn(false)
    }

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(id: Long, email: String, role: String) =
        User(id = id, firstName = "U$id", lastName = "Test", email = email, roleList = mutableSetOf(Role(name = role)))

    private fun loginAs(user: User) = LoggedUser.logIn(user.email)

    // ---------- Subtemas ----------

    @Test
    fun `el dueno crea un subtema dentro de un modulo`() {
        loginAs(owner)

        val dto = service.createSubtopic(20L, SubtopicCreateDTO(code = "4.2", title = "Heaps", position = 2))

        assertEquals(31L, dto.id)
        assertEquals(20L, dto.moduleId)
        assertEquals("4.2", dto.code)
        assertEquals(2, module.subtopics.size)
    }

    @Test
    fun `un codigo de subtema repetido en el modulo se rechaza`() {
        loginAs(owner)
        whenever(subtopicRepository.existsByModuleIdAndCode(20L, "4.1")).thenReturn(true)

        assertThrows<ConflictException> { service.createSubtopic(20L, SubtopicCreateDTO(code = "4.1", title = "Otra vez")) }
    }

    @Test
    fun `el dueno actualiza un subtema sin tocar lo que no envia`() {
        loginAs(owner)

        val dto = service.updateSubtopic(30L, SubtopicUpdateDTO(title = "Árboles AVL"))

        assertEquals("Árboles AVL", dto.title)
        assertEquals("4.1", dto.code)
        assertEquals(1, dto.position)
    }

    @Test
    fun `el dueno elimina un subtema sin apuntes`() {
        loginAs(owner)
        whenever(noteRepository.existsBySubtopicId(30L)).thenReturn(false)

        service.deleteSubtopic(30L)

        verify(subtopicRepository).delete(avl)
        assertTrue(module.subtopics.isEmpty())
    }

    @Test
    fun `un subtema con apuntes no se puede eliminar`() {
        loginAs(owner)
        whenever(noteRepository.existsBySubtopicId(30L)).thenReturn(true)

        assertThrows<BusinessRuleException> { service.deleteSubtopic(30L) }
        verify(subtopicRepository, never()).delete(any<Subtopic>())
    }

    // ---------- Módulos: editar, reordenar y eliminar ----------

    private val graphs = CourseModule(id = 21L, course = course, number = 5, title = "Grafos")

    @Test
    fun `el dueno renombra un modulo y le cambia el numero`() {
        loginAs(owner)

        val dto = service.updateModule(20L, ModuleUpdateDTO(title = "Árboles y heaps", number = 6))

        assertEquals("Árboles y heaps", dto.title)
        assertEquals(6, dto.number)
    }

    @Test
    fun `actualizar un modulo sin enviar campos no cambia nada`() {
        loginAs(owner)

        val dto = service.updateModule(20L, ModuleUpdateDTO())

        assertEquals("Árboles", dto.title)
        assertEquals(4, dto.number)
        verify(courseModuleRepository, never()).existsByCourseIdAndNumber(any(), any())
    }

    @Test
    fun `un numero de modulo ocupado se rechaza`() {
        loginAs(owner)
        whenever(courseModuleRepository.existsByCourseIdAndNumber(10L, 5)).thenReturn(true)

        assertThrows<ConflictException> { service.updateModule(20L, ModuleUpdateDTO(number = 5)) }
        assertEquals(4, module.number)
    }

    @Test
    fun `solo el dueno edita, reordena o elimina modulos`() {
        loginAs(otherTeacher)

        assertThrows<ForbiddenException> { service.updateModule(20L, ModuleUpdateDTO(title = "Otro")) }
        assertThrows<ForbiddenException> { service.reorderModules(10L, ModuleOrderDTO(listOf(20L))) }
        assertThrows<ForbiddenException> { service.deleteModule(20L) }
        verify(courseModuleRepository, never()).delete(any<CourseModule>())
    }

    @Test
    fun `reordenar intercambia los numeros pasando por negativos para no chocar con la unicidad`() {
        loginAs(owner)
        whenever(courseModuleRepository.findByCourseIdOrderByNumber(10L)).thenReturn(listOf(module, graphs))
        val numbersAtEachFlush = mutableListOf<List<Int>>()
        whenever(courseModuleRepository.saveAllAndFlush(any<List<CourseModule>>())).thenAnswer {
            numbersAtEachFlush += listOf(module.number, graphs.number)
            it.arguments[0]
        }

        val result = service.reorderModules(10L, ModuleOrderDTO(listOf(21L, 20L)))

        assertEquals(listOf(21L, 20L), result.map { it.id })
        assertEquals(listOf(1, 2), result.map { it.number })
        // Primero se apartan en negativos (sin chocar con 4 y 5) y después reciben su número definitivo.
        assertEquals(listOf(listOf(-2, -1), listOf(2, 1)), numbersAtEachFlush)
    }

    @Test
    fun `reordenar exige cada modulo del curso exactamente una vez`() {
        loginAs(owner)
        whenever(courseModuleRepository.findByCourseIdOrderByNumber(10L)).thenReturn(listOf(module, graphs))

        assertThrows<BusinessRuleException> { service.reorderModules(10L, ModuleOrderDTO(listOf(20L))) }
        assertThrows<BusinessRuleException> { service.reorderModules(10L, ModuleOrderDTO(listOf(20L, 20L))) }
        assertThrows<BusinessRuleException> { service.reorderModules(10L, ModuleOrderDTO(listOf(20L, 21L, 99L))) }
        verify(courseModuleRepository, never()).saveAllAndFlush(any<List<CourseModule>>())
        assertEquals(4, module.number)
    }

    @Test
    fun `el dueno elimina un modulo sin apuntes`() {
        loginAs(owner)
        whenever(noteRepository.existsBySubtopicModuleId(20L)).thenReturn(false)

        service.deleteModule(20L)

        verify(courseModuleRepository).delete(module)
    }

    @Test
    fun `un modulo con subtemas que tienen apuntes no se puede eliminar`() {
        loginAs(owner)
        whenever(noteRepository.existsBySubtopicModuleId(20L)).thenReturn(true)

        assertThrows<BusinessRuleException> { service.deleteModule(20L) }
        verify(courseModuleRepository, never()).delete(any<CourseModule>())
    }

    @Test
    fun `un modulo que no existe responde 404`() {
        loginAs(owner)
        whenever(courseModuleRepository.findById(99L)).thenReturn(Optional.empty())

        assertThrows<NotFoundException> { service.updateModule(99L, ModuleUpdateDTO(title = "X")) }
        assertThrows<NotFoundException> { service.deleteModule(99L) }
    }

    // ---------- Objetivos ----------

    @Test
    fun `el dueno agrega y elimina objetivos de un subtema`() {
        loginAs(owner)

        val dto = service.addObjective(30L, ObjectiveCreateDTO("Explicar la rotación simple"))

        assertEquals(40L, dto.id)
        assertEquals("Explicar la rotación simple", dto.description)
        val objective = avl.objectives.single()
        whenever(subtopicObjectiveRepository.findById(40L)).thenReturn(Optional.of(objective))

        assertEquals("Explicar la rotación doble", service.updateObjective(40L, ObjectiveCreateDTO("Explicar la rotación doble")).description)

        service.deleteObjective(40L)

        verify(subtopicObjectiveRepository).delete(objective)
        assertTrue(avl.objectives.isEmpty())
    }

    // ---------- Publicar ----------

    @Test
    fun `publicar sin subtemas se rechaza`() {
        loginAs(owner)
        module.subtopics.clear()

        assertThrows<BusinessRuleException> { service.publish(10L) }
        assertNull(course.syllabusPublishedAt)
    }

    @Test
    fun `publicar avisa de los subtemas sin objetivos y no los bloquea`() {
        loginAs(owner)
        avl.addObjective(SubtopicObjective(id = 41L, description = "Rotar"))
        module.addSubtopic(Subtopic(id = 32L, code = "4.2", title = "Heaps", position = 2))

        val result = service.publish(10L)

        assertTrue(result.published)
        assertNotNull(course.syllabusPublishedAt)
        assertEquals(listOf("4.2"), result.warnings.map { it.subtopicCode })
        assertEquals(32L, result.warnings.single().subtopicId)
    }

    @Test
    fun `publicar con todos los subtemas completos no deja advertencias`() {
        loginAs(owner)
        avl.addObjective(SubtopicObjective(id = 41L, description = "Rotar"))

        assertTrue(service.publish(10L).warnings.isEmpty())
    }

    // ---------- Lectura y permisos ----------

    @Test
    fun `el profesor dueno lee el temario aunque no este publicado`() {
        loginAs(owner)

        val syllabus = service.getSyllabus(10L)

        assertFalse(syllabus.published)
        assertEquals(listOf("4.1"), syllabus.modules.single().subtopics.map { it.code })
    }

    @Test
    fun `un estudiante aprobado lee el temario publicado`() {
        course.syllabusPublishedAt = Instant.now()
        loginAs(approved)

        val syllabus = service.getSyllabus(10L)

        assertTrue(syllabus.published)
        assertEquals(10L, syllabus.courseId)
    }

    @Test
    fun `un estudiante no ve el temario antes de que se publique`() {
        loginAs(approved)

        assertThrows<NotFoundException> { service.getSyllabus(10L) }
    }

    @Test
    fun `un estudiante sin matricula aprobada no ve el temario aunque este publicado`() {
        course.syllabusPublishedAt = Instant.now()
        loginAs(notApproved)

        assertThrows<ForbiddenException> { service.getSyllabus(10L) }
    }

    @Test
    fun `un profesor que no es dueno no puede leer ni modificar el temario`() {
        loginAs(otherTeacher)

        assertThrows<ForbiddenException> { service.getSyllabus(10L) }
        assertThrows<ForbiddenException> { service.createModule(10L, ModuleCreateDTO(number = 5, title = "Grafos")) }
        assertThrows<ForbiddenException> { service.createSubtopic(20L, SubtopicCreateDTO(code = "4.9", title = "X")) }
        assertThrows<ForbiddenException> { service.updateSubtopic(30L, SubtopicUpdateDTO(title = "Hackeado")) }
        assertThrows<ForbiddenException> { service.deleteSubtopic(30L) }
        assertThrows<ForbiddenException> { service.addObjective(30L, ObjectiveCreateDTO("X")) }
        assertThrows<ForbiddenException> { service.publish(10L) }
        assertEquals("AVL", avl.title)
    }

    @Test
    fun `un estudiante no puede modificar el temario`() {
        loginAs(approved)

        assertThrows<ForbiddenException> { service.createSubtopic(20L, SubtopicCreateDTO(code = "4.9", title = "X")) }
        assertThrows<ForbiddenException> { service.publish(10L) }
    }

    @Test
    fun `el dueno crea modulos con numero unico en el curso`() {
        loginAs(owner)

        assertEquals(5, service.createModule(10L, ModuleCreateDTO(number = 5, title = "Grafos")).number)

        whenever(courseModuleRepository.existsByCourseIdAndNumber(10L, 4)).thenReturn(true)
        assertThrows<ConflictException> { service.createModule(10L, ModuleCreateDTO(number = 4, title = "Repetido")) }
    }
}

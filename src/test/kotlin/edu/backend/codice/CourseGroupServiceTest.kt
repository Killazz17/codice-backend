package edu.backend.codice

import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ConflictException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mapstruct.factory.Mappers
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.util.Optional

// SVC-02: cursos y grupos, con las reglas de propiedad del profesor y el código de invitación.
class CourseGroupServiceTest {

    private val courseRepository = mock<CourseRepository>()
    private val studentGroupRepository = mock<StudentGroupRepository>()
    private val enrollmentRepository = mock<EnrollmentRepository>()
    private val userRepository = mock<UserRepository>()
    private val access = AccessGuard(userRepository, enrollmentRepository)

    private val courseService = AbstractCourseService(courseRepository, enrollmentRepository, Mappers.getMapper(CourseMapper::class.java), access)
    private val groupService = AbstractStudentGroupService(studentGroupRepository, courseRepository, Mappers.getMapper(StudentGroupMapper::class.java), access)

    private val owner = user(1L, "profe@una.ac.cr", "ROLE_TEACHER")
    private val otherTeacher = user(2L, "otro@una.ac.cr", "ROLE_TEACHER")
    private val student = user(3L, "a@est.una.ac.cr", "ROLE_STUDENT")
    private val course = Course(id = 10L, code = "EIF411", name = "Móviles", teacher = owner)

    // Grupos "guardados": findByInviteCode los busca aquí, así un código regenerado deja de existir.
    private val savedGroups = mutableListOf<StudentGroup>()

    init {
        listOf(owner, otherTeacher, student).forEach { whenever(userRepository.findByEmail(it.email)).thenReturn(Optional.of(it)) }
        whenever(courseRepository.findById(10L)).thenReturn(Optional.of(course))
        whenever(courseRepository.findById(999L)).thenReturn(Optional.empty())
        whenever(courseRepository.findByCode(any())).thenReturn(Optional.empty())
        whenever(courseRepository.save(any<Course>())).thenAnswer { (it.arguments[0] as Course).apply { if (id == null) id = 11L } }
        whenever(studentGroupRepository.save(any<StudentGroup>())).thenAnswer { inv ->
            (inv.arguments[0] as StudentGroup).also { g -> if (g.id == null) g.id = 50L + savedGroups.size; if (g !in savedGroups) savedGroups.add(g) }
        }
        whenever(studentGroupRepository.findByInviteCode(any())).thenAnswer { inv ->
            Optional.ofNullable(savedGroups.firstOrNull { it.inviteCode == inv.arguments[0] })
        }
        whenever(studentGroupRepository.findById(any())).thenAnswer { inv -> Optional.ofNullable(savedGroups.firstOrNull { it.id == inv.arguments[0] }) }
    }

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(id: Long, email: String, role: String) =
        User(id = id, firstName = "U$id", lastName = "Test", email = email, roleList = mutableSetOf(Role(name = role)))

    private fun loginAs(user: User) = LoggedUser.logIn(user.email)

    private fun createGroup(number: String = "01", requiresApproval: Boolean = true) =
        groupService.createGroup(StudentGroupCreateDTO(courseId = 10L, number = number, period = "2026-II", requiresApproval = requiresApproval))

    // ---------- Cursos ----------

    @Test
    fun `un profesor crea un curso y queda como su dueno`() {
        loginAs(owner)

        val dto = courseService.create(CourseCreateDTO(code = "EIF500", name = "Nuevo"))

        val saved = argumentCaptor<Course>()
        verify(courseRepository).save(saved.capture())
        assertEquals(owner, saved.firstValue.teacher)
        assertEquals("EIF500", dto.code)
    }

    @Test
    fun `un estudiante no crea cursos y un codigo repetido se rechaza`() {
        loginAs(student)
        assertThrows<ForbiddenException> { courseService.create(CourseCreateDTO(code = "EIF500", name = "Nuevo")) }

        loginAs(owner)
        whenever(courseRepository.findByCode("EIF411")).thenReturn(Optional.of(course))
        assertThrows<ConflictException> { courseService.create(CourseCreateDTO(code = "EIF411", name = "Otro")) }
    }

    @Test
    fun `listar depende del rol`() {
        whenever(courseRepository.findByTeacherIdOrderByCode(owner.id!!)).thenReturn(listOf(course))
        whenever(courseRepository.findByStudentEnrollment(student.id!!, EnrollmentStatus.ACTIVE)).thenReturn(listOf(course))

        loginAs(owner)
        assertEquals(listOf("EIF411"), courseService.findAll().map { it.code })
        verify(courseRepository).findByTeacherIdOrderByCode(owner.id!!)

        loginAs(student)
        assertEquals(listOf("EIF411"), courseService.findAll().map { it.code })
        verify(courseRepository).findByStudentEnrollment(student.id!!, EnrollmentStatus.ACTIVE)
    }

    @Test
    fun `obtener un curso con y sin permiso`() {
        loginAs(owner)
        assertEquals("EIF411", courseService.findById(10L).code)

        loginAs(student)
        whenever(enrollmentRepository.existsByGroupCourseIdAndStudentIdAndStatus(10L, student.id!!, EnrollmentStatus.ACTIVE)).thenReturn(true)
        assertEquals("EIF411", courseService.findById(10L).code)

        whenever(enrollmentRepository.existsByGroupCourseIdAndStudentIdAndStatus(10L, student.id!!, EnrollmentStatus.ACTIVE)).thenReturn(false)
        assertThrows<ForbiddenException> { courseService.findById(10L) }

        assertThrows<NotFoundException> { courseService.findById(999L) }
    }

    @Test
    fun `un profesor no puede leer ni modificar los cursos de otro profesor`() {
        loginAs(otherTeacher)

        assertThrows<ForbiddenException> { courseService.findById(10L) }
        assertThrows<ForbiddenException> { courseService.update(10L, CourseCreateDTO(code = "EIF411", name = "Hackeado")) }
        assertThrows<ForbiddenException> { courseService.delete(10L) }
        assertThrows<ForbiddenException> { createGroup() }
        assertEquals("Móviles", course.name)
    }

    @Test
    fun `el dueno actualiza su curso`() {
        loginAs(owner)

        val dto = courseService.update(10L, CourseCreateDTO(code = "EIF411", name = "Móviles II", color = "#000000"))

        assertEquals("Móviles II", dto.name)
        assertEquals("#000000", course.color)
    }

    @Test
    fun `eliminar se permite sin estudiantes aprobados y se bloquea con ellos`() {
        loginAs(owner)

        whenever(enrollmentRepository.existsByGroupCourseIdAndStatus(10L, EnrollmentStatus.ACTIVE)).thenReturn(true)
        assertThrows<BusinessRuleException> { courseService.delete(10L) }
        verify(courseRepository, never()).delete(any<Course>())

        whenever(enrollmentRepository.existsByGroupCourseIdAndStatus(10L, EnrollmentStatus.ACTIVE)).thenReturn(false)
        courseService.delete(10L)
        verify(courseRepository).delete(course)
    }

    // ---------- Grupos ----------

    @Test
    fun `el codigo de invitacion tiene el formato CURSO-NN-XXXX y vence a los 30 dias`() {
        loginAs(owner)
        val before = Instant.now()

        val group = createGroup(number = "1")

        assertTrue(Regex("^EIF411-01-[A-Z0-9]{4}$").matches(group.inviteCode), group.inviteCode)
        val validity = Duration.between(before, group.inviteExpiresAt)
        assertTrue(validity >= Duration.ofDays(30) && validity < Duration.ofDays(30).plusMinutes(1), "Vence en $validity")
        assertEquals(owner.id, group.teacherId)
        assertTrue(group.requiresApproval)
    }

    @Test
    fun `el numero de grupo es unico por curso`() {
        loginAs(owner)
        whenever(studentGroupRepository.existsByCourseIdAndNumber(10L, "01")).thenReturn(true)

        assertThrows<ConflictException> { createGroup("01") }
    }

    @Test
    fun `regenerar el codigo invalida el anterior`() {
        loginAs(owner)
        val group = createGroup()
        val oldCode = group.inviteCode

        val regenerated = groupService.regenerateInviteCode(group.id)

        assertNotEquals(oldCode, regenerated.inviteCode)
        assertThrows<NotFoundException> { groupService.findByInviteCode(oldCode) }
        assertEquals(group.id, groupService.findByInviteCode(regenerated.inviteCode).id)
    }

    @Test
    fun `el profesor cambia si el grupo requiere aprobacion y otro profesor no puede`() {
        loginAs(owner)
        val group = createGroup()

        assertEquals(false, groupService.updateRequiresApproval(group.id, false).requiresApproval)

        loginAs(otherTeacher)
        assertThrows<ForbiddenException> { groupService.updateRequiresApproval(group.id, true) }
        assertThrows<ForbiddenException> { groupService.regenerateInviteCode(group.id) }
    }

    @Test
    fun `listar los grupos de un curso`() {
        loginAs(owner)
        createGroup()
        whenever(studentGroupRepository.findByCourseId(10L)).thenReturn(savedGroups.toList())

        assertEquals(listOf("01"), groupService.findByCourseId(10L).map { it.number })
    }
}

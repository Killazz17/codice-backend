package edu.backend.codice

import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ConflictException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.NotFoundException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mapstruct.factory.Mappers
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant
import java.util.Optional

// SVC-03: el estudiante se une con el código y el profesor aprueba o rechaza.
class EnrollmentServiceTest {

    private val enrollmentRepository = mock<EnrollmentRepository>()
    private val studentGroupRepository = mock<StudentGroupRepository>()
    private val userRepository = mock<UserRepository>()

    private val service = AbstractEnrollmentService(
        enrollmentRepository, studentGroupRepository, Mappers.getMapper(EnrollmentMapper::class.java),
        AccessGuard(userRepository, enrollmentRepository),
    )

    private val owner = user(1L, "profe@una.ac.cr", "ROLE_TEACHER")
    private val otherTeacher = user(2L, "otro@una.ac.cr", "ROLE_TEACHER")
    private val studentA = user(3L, "a@est.una.ac.cr", "ROLE_STUDENT")
    private val studentB = user(4L, "b@est.una.ac.cr", "ROLE_STUDENT")

    private val course = Course(id = 10L, code = "EIF411", name = "Móviles", teacher = owner)
    private val inAMonth = Instant.now().plus(Duration.ofDays(30))
    private val withApproval = group(5L, "01", "EIF411-01-7K2A", requiresApproval = true, expires = inAMonth)
    private val open = group(6L, "02", "EIF411-02-ABCD", requiresApproval = false, expires = inAMonth)
    private val expired = group(7L, "03", "EIF411-03-OLD1", requiresApproval = true, expires = Instant.now().minusSeconds(60))

    init {
        listOf(owner, otherTeacher, studentA, studentB).forEach { whenever(userRepository.findByEmail(it.email)).thenReturn(Optional.of(it)) }
        whenever(studentGroupRepository.findByInviteCode(any())).thenReturn(Optional.empty())
        listOf(withApproval, open, expired).forEach {
            whenever(studentGroupRepository.findByInviteCode(it.inviteCode)).thenReturn(Optional.of(it))
            whenever(studentGroupRepository.findById(it.id!!)).thenReturn(Optional.of(it))
        }
        whenever(enrollmentRepository.findByGroupIdAndStudentId(any(), any())).thenReturn(Optional.empty())
        whenever(enrollmentRepository.save(any<Enrollment>())).thenAnswer { (it.arguments[0] as Enrollment).apply { if (id == null) id = 100L } }
    }

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(id: Long, email: String, role: String) =
        User(id = id, firstName = "U$id", lastName = "Test", email = email, roleList = mutableSetOf(Role(name = role)))

    private fun group(id: Long, number: String, code: String, requiresApproval: Boolean, expires: Instant) = StudentGroup(
        id = id, course = course, teacher = owner, number = number, period = "2026-II",
        inviteCode = code, requiresApproval = requiresApproval, inviteExpiresAt = expires,
    )

    private fun pending(id: Long, student: User = studentA) =
        Enrollment(id = id, group = withApproval, student = student, status = EnrollmentStatus.PENDING).also {
            whenever(enrollmentRepository.findById(id)).thenReturn(Optional.of(it))
        }

    private fun loginAs(user: User) = LoggedUser.logIn(user.email)

    // ---------- Unirse ----------

    @Test
    fun `unirse a un grupo con aprobacion deja la matricula pendiente`() {
        loginAs(studentA)

        val dto = service.joinGroup(JoinGroupDTO("eif411-01-7k2a")) // se acepta en minúsculas

        assertEquals("PENDING", dto.status)
        assertEquals(5L, dto.groupId)
        assertEquals("Móviles", dto.courseName)
        assertNull(dto.resolvedAt)
    }

    @Test
    fun `unirse a un grupo sin aprobacion queda aprobada de una vez`() {
        loginAs(studentA)

        val dto = service.joinGroup(JoinGroupDTO("EIF411-02-ABCD"))

        assertEquals("ACTIVE", dto.status)
        assertNotNull(dto.resolvedAt)
    }

    @Test
    fun `un codigo inexistente da NotFound y uno vencido da BusinessRule`() {
        loginAs(studentA)

        assertThrows<NotFoundException> { service.joinGroup(JoinGroupDTO("EIF411-09-XXXX")) }
        assertThrows<BusinessRuleException> { service.joinGroup(JoinGroupDTO("EIF411-03-OLD1")) }
        verify(enrollmentRepository, never()).save(any<Enrollment>())
    }

    @Test
    fun `no se puede pedir dos veces el mismo grupo`() {
        loginAs(studentA)
        val existing = pending(20L)
        whenever(enrollmentRepository.findByGroupIdAndStudentId(5L, studentA.id!!)).thenReturn(Optional.of(existing))

        assertThrows<ConflictException> { service.joinGroup(JoinGroupDTO("EIF411-01-7K2A")) }
    }

    @Test
    fun `una solicitud rechazada se puede volver a pedir y reutiliza la misma fila`() {
        loginAs(studentA)
        val rejected = Enrollment(id = 21L, group = withApproval, student = studentA, status = EnrollmentStatus.REJECTED, resolvedAt = Instant.now())
        whenever(enrollmentRepository.findByGroupIdAndStudentId(5L, studentA.id!!)).thenReturn(Optional.of(rejected))

        val dto = service.joinGroup(JoinGroupDTO("EIF411-01-7K2A"))

        assertEquals(21L, dto.id)
        assertEquals("PENDING", dto.status)
        assertNull(rejected.resolvedAt)
    }

    @Test
    fun `un estudiante aprobado en otro grupo del curso no queda aprobado dos veces`() {
        loginAs(studentA)
        whenever(enrollmentRepository.existsByGroupCourseIdAndStudentIdAndStatus(10L, studentA.id!!, EnrollmentStatus.ACTIVE)).thenReturn(true)

        assertThrows<ConflictException> { service.joinGroup(JoinGroupDTO("EIF411-02-ABCD")) }
    }

    @Test
    fun `un profesor no puede unirse como estudiante`() {
        loginAs(otherTeacher)

        assertThrows<ForbiddenException> { service.joinGroup(JoinGroupDTO("EIF411-01-7K2A")) }
    }

    // ---------- Decidir ----------

    @Test
    fun `el profesor aprueba una solicitud`() {
        loginAs(owner)
        val enrollment = pending(30L)

        val dto = service.decide(30L, EnrollmentDecisionDTO("APPROVE"))

        assertEquals("ACTIVE", dto.status)
        assertNotNull(enrollment.resolvedAt)
    }

    @Test
    fun `el profesor rechaza una solicitud`() {
        loginAs(owner)
        pending(31L)

        assertEquals("REJECTED", service.decide(31L, EnrollmentDecisionDTO("REJECT")).status)
    }

    @Test
    fun `una solicitud ya decidida no se decide otra vez`() {
        loginAs(owner)
        pending(32L)
        service.decide(32L, EnrollmentDecisionDTO("APPROVE"))

        assertThrows<ConflictException> { service.decide(32L, EnrollmentDecisionDTO("REJECT")) }
    }

    @Test
    fun `nadie decide ni lista las solicitudes de un grupo ajeno`() {
        pending(33L, studentB)

        loginAs(otherTeacher)
        assertThrows<ForbiddenException> { service.decide(33L, EnrollmentDecisionDTO("APPROVE")) }
        assertThrows<ForbiddenException> { service.listRequests(5L, null) }

        // Solicitud ajena: un estudiante tampoco puede aprobar la suya ni la de otro.
        loginAs(studentA)
        assertThrows<ForbiddenException> { service.decide(33L, EnrollmentDecisionDTO("APPROVE")) }
    }

    // ---------- Listar ----------

    @Test
    fun `el profesor lista las solicitudes de su grupo con y sin filtro de estado`() {
        loginAs(owner)
        val request = pending(40L)
        whenever(enrollmentRepository.findByGroupIdAndStatus(5L, EnrollmentStatus.PENDING)).thenReturn(listOf(request))
        whenever(enrollmentRepository.findByGroupIdOrderByRequestedAtDesc(5L)).thenReturn(listOf(request))

        val filtered = service.listRequests(5L, "pending")
        val all = service.listRequests(5L, null)

        assertEquals(listOf("U3 Test"), filtered.map { it.studentName })
        assertEquals(listOf("a@est.una.ac.cr"), all.map { it.studentEmail })
        assertThrows<BusinessRuleException> { service.listRequests(5L, "MAYBE") }
    }

    @Test
    fun `mis matriculas trae las del estudiante autenticado`() {
        loginAs(studentA)
        val mine = pending(50L)
        whenever(enrollmentRepository.findByStudentIdOrderByRequestedAtDesc(studentA.id!!)).thenReturn(listOf(mine))

        val result = service.getMyEnrollments()

        assertEquals(listOf(50L), result.map { it.id })
        assertSame(studentA, mine.student)
    }
}

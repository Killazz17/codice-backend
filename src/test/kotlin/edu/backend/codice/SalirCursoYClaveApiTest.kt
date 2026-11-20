package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.post

/*
 * Salir de un grupo, quitar a un estudiante y cambiar la contraseña. La matrícula nunca se borra (los apuntes cuelgan de
 * ella con ON DELETE CASCADE): pasa a LEFT o REMOVED y deja de listarse.
 */
class SalirCursoYClaveApiTest : ApiIntegrationTest() {

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    private lateinit var teacher: Actor
    private lateinit var otherTeacher: Actor
    private lateinit var student: Actor
    private lateinit var otherStudent: Actor

    @BeforeEach
    fun setUp() {
        teacher = actor("profe@una.ac.cr", "ROLE_TEACHER")
        otherTeacher = actor("otro.profe@una.ac.cr", "ROLE_TEACHER")
        student = actor("ana@est.una.ac.cr", "ROLE_STUDENT")
        otherStudent = actor("luis@est.una.ac.cr", "ROLE_STUDENT")
    }

    private fun statusOf(enrollmentId: Long): EnrollmentStatus {
        reload()
        return enrollmentRepository.findById(enrollmentId).get().status
    }

    // ==================== DELETE /api/v1/enrollments/{id} (salir) ====================

    @Test
    fun `un estudiante sale de su grupo y la matricula pasa a LEFT sin borrarse`() {
        val enrollment = enroll(group(course(teacher.user)), student.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/enrollments/${enrollment.id}", student).andExpect { status { isNoContent() } }

        assertEquals(EnrollmentStatus.LEFT, statusOf(enrollment.id!!))
    }

    @Test
    fun `al salir, la matricula ya no aparece en Mis matriculas ni en las del grupo`() {
        val group = group(course(teacher.user))
        val enrollment = enroll(group, student.user, EnrollmentStatus.ACTIVE)
        enroll(group, otherStudent.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/enrollments/${enrollment.id}", student).andExpect { status { isNoContent() } }
        reload()

        val mine = getAs("/api/v1/enrollments/my", student).andExpect { status { isOk() } }.body()
        assertEquals(0, mine.size())

        val inGroup = getAs("/api/v1/groups/${group.id}/enrollments", teacher).andExpect { status { isOk() } }.body()
        assertEquals(1, inGroup.size())
        assertEquals("luis@est.una.ac.cr", inGroup[0]["studentEmail"].asText())
    }

    @Test
    fun `un estudiante puede cancelar una solicitud que todavia esta pendiente`() {
        val enrollment = enroll(group(course(teacher.user)), student.user, EnrollmentStatus.PENDING)
        reload()

        deleteAs("/api/v1/enrollments/${enrollment.id}", student).andExpect { status { isNoContent() } }

        assertEquals(EnrollmentStatus.LEFT, statusOf(enrollment.id!!))
    }

    @Test
    fun `no se puede salir de la matricula de otro estudiante`() {
        val enrollment = enroll(group(course(teacher.user)), student.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/enrollments/${enrollment.id}", otherStudent).expectError(403, "Forbidden")

        assertEquals(EnrollmentStatus.ACTIVE, statusOf(enrollment.id!!))
    }

    @Test
    fun `salir dos veces o de una matricula inexistente responde 409 y 404`() {
        val enrollment = enroll(group(course(teacher.user)), student.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/enrollments/${enrollment.id}", student).andExpect { status { isNoContent() } }
        deleteAs("/api/v1/enrollments/${enrollment.id}", student).expectError(409, "Conflict")
        deleteAs("/api/v1/enrollments/999999", student).expectError(404, "Not Found")
    }

    @Test
    fun `salir exige ser estudiante y tener sesion`() {
        val enrollment = enroll(group(course(teacher.user)), student.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/enrollments/${enrollment.id}", null).expectError(401, "Unauthorized")
        deleteAs("/api/v1/enrollments/${enrollment.id}", teacher).expectError(403, "Forbidden")
    }

    // ==================== DELETE /api/v1/groups/{groupId}/enrollments/{enrollmentId} (quitar) ====================

    @Test
    fun `el profesor del grupo quita a un estudiante y la matricula pasa a REMOVED`() {
        val group = group(course(teacher.user))
        val enrollment = enroll(group, student.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/groups/${group.id}/enrollments/${enrollment.id}", teacher).andExpect { status { isNoContent() } }

        assertEquals(EnrollmentStatus.REMOVED, statusOf(enrollment.id!!))
        // Ya no ve el curso: sin matrícula activa no hay acceso.
        assertEquals(0, getAs("/api/v1/enrollments/my", student).body().size())
    }

    @Test
    fun `otro profesor no puede quitar estudiantes de un grupo ajeno`() {
        val group = group(course(teacher.user))
        val enrollment = enroll(group, student.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/groups/${group.id}/enrollments/${enrollment.id}", otherTeacher).expectError(403, "Forbidden")

        assertEquals(EnrollmentStatus.ACTIVE, statusOf(enrollment.id!!))
    }

    @Test
    fun `un estudiante no puede quitar a nadie`() {
        val group = group(course(teacher.user))
        val enrollment = enroll(group, otherStudent.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/groups/${group.id}/enrollments/${enrollment.id}", student).expectError(403, "Forbidden")
        deleteAs("/api/v1/groups/${group.id}/enrollments/${enrollment.id}", null).expectError(401, "Unauthorized")
    }

    @Test
    fun `no se quita una matricula que es de otro grupo aunque se cambie el id`() {
        val course = course(teacher.user)
        val g1 = group(course, "01")
        val g2 = group(course, "02")
        val inG2 = enroll(g2, student.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/groups/${g1.id}/enrollments/${inG2.id}", teacher).expectError(404, "Not Found")

        assertEquals(EnrollmentStatus.ACTIVE, statusOf(inG2.id!!))
    }

    @Test
    fun `solo se quita a un estudiante inscrito, no a una solicitud pendiente`() {
        val group = group(course(teacher.user))
        val pending = enroll(group, student.user, EnrollmentStatus.PENDING)
        reload()

        deleteAs("/api/v1/groups/${group.id}/enrollments/${pending.id}", teacher).expectError(409, "Conflict")
    }

    // ==================== Volver a unirse ====================

    @Test
    fun `quien salio de un grupo abierto vuelve a unirse directo en la misma matricula`() {
        val group = group(course(teacher.user), requiresApproval = false)
        val enrollment = enroll(group, student.user, EnrollmentStatus.LEFT)
        reload()

        val body = postAs("/api/v1/enrollments", student, """{"groupId":${group.id}}""").andExpect { status { isCreated() } }.body()

        assertEquals("ACTIVE", body["status"].asText())
        // Se reabrió la misma fila: así recupera los apuntes que colgaban de ella.
        assertEquals(enrollment.id, body["id"].asLong())
    }

    @Test
    fun `a quien el profesor quito no se le abre la puerta sola aunque el grupo sea abierto`() {
        val group = group(course(teacher.user), requiresApproval = false)
        enroll(group, student.user, EnrollmentStatus.REMOVED)
        reload()

        val body = postAs("/api/v1/enrollments", student, """{"groupId":${group.id}}""").andExpect { status { isCreated() } }.body()

        assertEquals("PENDING", body["status"].asText())
    }

    @Test
    fun `quien salio puede matricularse en otro grupo del mismo curso`() {
        val course = course(teacher.user)
        enroll(group(course, "01"), student.user, EnrollmentStatus.LEFT)
        val g2 = group(course, "02", requiresApproval = false)
        reload()

        val body = postAs("/api/v1/enrollments", student, """{"groupId":${g2.id}}""").andExpect { status { isCreated() } }.body()

        assertEquals("ACTIVE", body["status"].asText())
    }

    // ==================== POST /api/v1/auth/change-password ====================

    private fun userWithPassword(email: String, password: String): Actor {
        val actor = actor(email, "ROLE_STUDENT")
        actor.user.password = passwordEncoder.encode(password)
        userRepository.save(actor.user)
        return actor
    }

    private fun changePassword(who: Actor?, current: String, new: String) =
        postAs("/api/v1/auth/change-password", who, """{"currentPassword":"$current","newPassword":"$new"}""")

    private fun login(email: String, password: String) = mockMvc.post("/api/v1/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"username":"$email","password":"$password"}"""
    }

    @Test
    fun `cambiar la contrasena responde 204 y desde entonces solo vale la nueva`() {
        val ana = userWithPassword("clave@est.una.ac.cr", "Vieja1234")
        reload()

        changePassword(ana, "Vieja1234", "Nueva5678").andExpect { status { isNoContent() } }

        login("clave@est.una.ac.cr", "Nueva5678").andExpect { status { isOk() } }
        login("clave@est.una.ac.cr", "Vieja1234").expectError(401, "Unauthorized")
    }

    @Test
    fun `con la contrasena actual incorrecta responde 422 y no cambia nada`() {
        val ana = userWithPassword("clave@est.una.ac.cr", "Vieja1234")
        reload()

        // 422 y no 401: un 401 haría que la app creyera que la sesión venció.
        changePassword(ana, "Equivocada1", "Nueva5678").expectError(422, "Unprocessable Entity")

        login("clave@est.una.ac.cr", "Vieja1234").andExpect { status { isOk() } }
    }

    @Test
    fun `la contrasena nueva igual a la actual responde 422`() {
        val ana = userWithPassword("clave@est.una.ac.cr", "Vieja1234")
        reload()

        changePassword(ana, "Vieja1234", "Vieja1234").expectError(422, "Unprocessable Entity")
    }

    @Test
    fun `una contrasena nueva debil o faltante responde 400`() {
        val ana = userWithPassword("clave@est.una.ac.cr", "Vieja1234")
        reload()

        val corta = changePassword(ana, "Vieja1234", "abc1").expectError(400, "Bad Request").body()
        assertTrue(corta.toString().contains("newPassword"), corta.toString())

        changePassword(ana, "Vieja1234", "soloLetras").expectError(400, "Bad Request")
        postAs("/api/v1/auth/change-password", ana, """{"currentPassword":"Vieja1234"}""").expectError(400, "Bad Request")
        postAs("/api/v1/auth/change-password", ana, """{}""").expectError(400, "Bad Request")
    }

    @Test
    fun `cambiar la contrasena exige sesion`() {
        changePassword(null, "Vieja1234", "Nueva5678").expectError(401, "Unauthorized")
    }

    @Test
    fun `la respuesta de cambiar la contrasena no devuelve ninguna contrasena`() {
        val ana = userWithPassword("clave@est.una.ac.cr", "Vieja1234")
        reload()

        val raw = changePassword(ana, "Vieja1234", "Nueva5678").andExpect { status { isNoContent() } }
            .andReturn().response.contentAsString

        assertFalse(raw.contains("Nueva5678") || raw.contains("Vieja1234"), raw)
    }
}

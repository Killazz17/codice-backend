package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/*
 * API-04: el flujo de incorporación a un grupo por REST (unirse con el código, ver las solicitudes, aprobar o rechazar),
 * con la cadena de seguridad real y los servicios reales sobre H2. Un caso por cada status de la tabla del issue.
 */
class MatriculaApiTest : ApiIntegrationTest() {

    private lateinit var teacher: Actor
    private lateinit var otherTeacher: Actor
    private lateinit var ana: Actor
    private lateinit var beto: Actor
    private lateinit var course: Course

    private fun join(code: String) = """{"inviteCode":"$code"}"""

    @BeforeEach
    fun setUp() {
        teacher = actor("profe@una.ac.cr", "ROLE_TEACHER")
        otherTeacher = actor("otro.profe@una.ac.cr", "ROLE_TEACHER")
        ana = actor("ana@est.una.ac.cr", "ROLE_STUDENT")
        beto = actor("beto@est.una.ac.cr", "ROLE_STUDENT")
        course = course(teacher.user)
    }

    // ==================== POST /api/v1/matriculas ====================

    @Test
    fun `unirse a un grupo que pide aprobacion responde 201 con Location y estado PENDING`() {
        val group = group(course, requiresApproval = true)
        reload()

        val created = postAs("/api/v1/matriculas", ana, join(group.inviteCode)).andExpect { status { isCreated() } }
        val body = created.body()

        assertEquals("/api/v1/matriculas/${body["id"].asLong()}", created.location())
        assertEquals("PENDING", body["status"].asText())
        assertEquals(group.id, body["groupId"].asLong())
        assertEquals(ana.user.id, body["studentId"].asLong())
        assertNull(body["resolvedAt"].takeUnless { it.isNull })
    }

    @Test
    fun `unirse a un grupo sin aprobacion responde 201 y estado ACTIVE`() {
        val group = group(course, requiresApproval = false)
        reload()

        val body = postAs("/api/v1/matriculas", ana, join(group.inviteCode)).andExpect { status { isCreated() } }.body()

        assertEquals("ACTIVE", body["status"].asText())
        assertNotNull(body["resolvedAt"].takeUnless { it.isNull })
    }

    @Test
    fun `el codigo se acepta en minusculas`() {
        val group = group(course, requiresApproval = false)
        reload()

        postAs("/api/v1/matriculas", ana, join(group.inviteCode.lowercase())).andExpect { status { isCreated() } }
    }

    @Test
    fun `unirse con un codigo mal formado responde 400`() {
        val body = postAs("/api/v1/matriculas", ana, join("no es un codigo")).expectError(400, "Bad Request").body()
        assertEquals("inviteCode", body["errors"][0]["field"].asText())

        postAs("/api/v1/matriculas", ana, "{}").expectError(400, "Bad Request")
    }

    @Test
    fun `unirse sin token responde 401 y siendo profesor 403`() {
        val group = group(course)
        reload()

        postAs("/api/v1/matriculas", null, join(group.inviteCode)).expectError(401, "Unauthorized")
        postAs("/api/v1/matriculas", teacher, join(group.inviteCode)).expectError(403, "Forbidden")
        postAs("/api/v1/matriculas", otherTeacher, join(group.inviteCode)).expectError(403, "Forbidden")
    }

    @Test
    fun `unirse con un codigo que no existe responde 404`() {
        postAs("/api/v1/matriculas", ana, join("ZZZ999-01-AAAA")).expectError(404, "Not Found")
    }

    @Test
    fun `unirse dos veces al mismo grupo responde 409`() {
        val group = group(course, requiresApproval = true)
        reload()

        postAs("/api/v1/matriculas", ana, join(group.inviteCode)).andExpect { status { isCreated() } }
        postAs("/api/v1/matriculas", ana, join(group.inviteCode)).expectError(409, "Conflict")
    }

    @Test
    fun `unirse con un codigo vencido responde 422`() {
        val group = group(course, expiresAt = Instant.now().minusSeconds(60))
        reload()

        postAs("/api/v1/matriculas", ana, join(group.inviteCode)).expectError(422, "Unprocessable Entity")
    }

    // ==================== GET /api/v1/matriculas/mias ====================

    @Test
    fun `un estudiante ve sus matriculas, y solo las suyas`() {
        val second = course(teacher.user, code = "EIF500", name = "Otro curso")
        enroll(group(course), ana.user, EnrollmentStatus.ACTIVE)
        enroll(group(second), ana.user, EnrollmentStatus.PENDING)
        enroll(group(second, "02"), beto.user, EnrollmentStatus.PENDING)
        reload()

        val list = getAs("/api/v1/matriculas/mias", ana).andExpect { status { isOk() } }.body()

        assertEquals(2, list.size())
        assertEquals(setOf(ana.user.id), list.map { it["studentId"].asLong() }.toSet())
        assertEquals(setOf("ACTIVE", "PENDING"), list.map { it["status"].asText() }.toSet())
    }

    @Test
    fun `mis matriculas sin token responde 401 y siendo profesor 403`() {
        getAs("/api/v1/matriculas/mias", null).expectError(401, "Unauthorized")
        getAs("/api/v1/matriculas/mias", teacher).expectError(403, "Forbidden")
    }

    // ==================== GET /api/v1/grupos/{grupoId}/matriculas ====================

    @Test
    fun `el profesor dueno ve las solicitudes de su grupo con solo nombre y correo`() {
        val group = group(course)
        enroll(group, ana.user, EnrollmentStatus.PENDING)
        enroll(group, beto.user, EnrollmentStatus.ACTIVE)
        reload()

        val list = getAs("/api/v1/grupos/${group.id}/matriculas", teacher).andExpect { status { isOk() } }.body()

        assertEquals(2, list.size())
        assertEquals(setOf("id", "studentName", "studentEmail", "status", "requestedAt"), list[0].names())
        assertEquals(setOf("ana@est.una.ac.cr", "beto@est.una.ac.cr"), list.map { it["studentEmail"].asText() }.toSet())
    }

    @Test
    fun `el filtro estado acepta los nombres de la base y los del enunciado`() {
        val group = group(course)
        enroll(group, ana.user, EnrollmentStatus.PENDING)
        enroll(group, beto.user, EnrollmentStatus.ACTIVE)
        val carla = actor("carla@est.una.ac.cr", "ROLE_STUDENT")
        enroll(group, carla.user, EnrollmentStatus.REJECTED)
        reload()

        fun emailsFor(estado: String) = getAs("/api/v1/grupos/${group.id}/matriculas?estado=$estado", teacher)
            .andExpect { status { isOk() } }.body().map { it["studentEmail"].asText() }

        assertEquals(listOf("ana@est.una.ac.cr"), emailsFor("PENDING"))
        assertEquals(listOf("ana@est.una.ac.cr"), emailsFor("PENDIENTE"))
        assertEquals(listOf("ana@est.una.ac.cr"), emailsFor("pendiente"))
        assertEquals(listOf("beto@est.una.ac.cr"), emailsFor("ACTIVE"))
        assertEquals(listOf("beto@est.una.ac.cr"), emailsFor("APROBADA"))
        assertEquals(listOf("carla@est.una.ac.cr"), emailsFor("REJECTED"))
        assertEquals(listOf("carla@est.una.ac.cr"), emailsFor("RECHAZADA"))
    }

    @Test
    fun `un estado invalido responde 400 con ErrorResponse`() {
        val group = group(course)
        reload()

        val body = getAs("/api/v1/grupos/${group.id}/matriculas?estado=OTRO", teacher).expectError(400, "Bad Request").body()

        assertTrue(body["errors"].size() > 0)
        assertTrue(body["errors"][0]["message"].asText().contains("PENDING"), body.toString())
    }

    @Test
    fun `ver las solicitudes de un grupo ajeno o siendo estudiante responde 403, de uno inexistente 404 y sin token 401`() {
        val group = group(course)
        reload()

        getAs("/api/v1/grupos/${group.id}/matriculas", otherTeacher).expectError(403, "Forbidden")
        getAs("/api/v1/grupos/${group.id}/matriculas", ana).expectError(403, "Forbidden")
        getAs("/api/v1/grupos/999999/matriculas", teacher).expectError(404, "Not Found")
        getAs("/api/v1/grupos/${group.id}/matriculas", null).expectError(401, "Unauthorized")
    }

    // ==================== PATCH /api/v1/matriculas/{id} ====================

    @Test
    fun `el profesor dueno aprueba una solicitud y la matricula queda ACTIVE`() {
        val enrollment = enroll(group(course), ana.user, EnrollmentStatus.PENDING)
        reload()

        patchAs("/api/v1/matriculas/${enrollment.id}", teacher, """{"decision":"APPROVE"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.resolvedAt") { isNotEmpty() }
        }
        // Ya aprobada, el curso aparece entre los del estudiante.
        reload()
        getAs("/api/v1/cursos", ana).andExpect { jsonPath("$[0].code") { value("EIF411") } }
    }

    @Test
    fun `el profesor dueno rechaza una solicitud y la matricula queda REJECTED`() {
        val enrollment = enroll(group(course), ana.user, EnrollmentStatus.PENDING)
        reload()

        patchAs("/api/v1/matriculas/${enrollment.id}", teacher, """{"decision":"REJECT"}""").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REJECTED") }
        }
    }

    @Test
    fun `una decision invalida responde 400`() {
        val enrollment = enroll(group(course), ana.user, EnrollmentStatus.PENDING)
        reload()

        patchAs("/api/v1/matriculas/${enrollment.id}", teacher, """{"decision":"TAL VEZ"}""").expectError(400, "Bad Request")
        patchAs("/api/v1/matriculas/${enrollment.id}", teacher, "{}").expectError(400, "Bad Request")
    }

    @Test
    fun `decidir sobre una matricula de otro profesor o siendo estudiante responde 403`() {
        val enrollment = enroll(group(course), ana.user, EnrollmentStatus.PENDING)
        reload()

        patchAs("/api/v1/matriculas/${enrollment.id}", otherTeacher, """{"decision":"APPROVE"}""").expectError(403, "Forbidden")
        patchAs("/api/v1/matriculas/${enrollment.id}", ana, """{"decision":"APPROVE"}""").expectError(403, "Forbidden")
        patchAs("/api/v1/matriculas/${enrollment.id}", null, """{"decision":"APPROVE"}""").expectError(401, "Unauthorized")

        reload()
        assertEquals(EnrollmentStatus.PENDING, enrollmentRepository.findById(enrollment.id!!).get().status)
    }

    @Test
    fun `decidir sobre una matricula inexistente responde 404`() {
        patchAs("/api/v1/matriculas/999999", teacher, """{"decision":"APPROVE"}""").expectError(404, "Not Found")
    }

    @Test
    fun `decidir dos veces la misma solicitud responde 409`() {
        val enrollment = enroll(group(course), ana.user, EnrollmentStatus.PENDING)
        reload()

        patchAs("/api/v1/matriculas/${enrollment.id}", teacher, """{"decision":"REJECT"}""").andExpect { status { isOk() } }
        patchAs("/api/v1/matriculas/${enrollment.id}", teacher, """{"decision":"APPROVE"}""").expectError(409, "Conflict")
    }

    // ==================== FLUJO COMPLETO ====================

    @Test
    fun `el recorrido completo, de crear el curso a que el estudiante lo vea sin el codigo del grupo`() {
        // El profesor crea el curso y el grupo por la API; el estudiante se une con el código y el profesor lo aprueba.
        val courseId = postAs("/api/v1/cursos", teacher, """{"code":"FLOW411","name":"Flujo"}""").andExpect { status { isCreated() } }.body()["id"].asLong()
        val groupBody = postAs("/api/v1/cursos/$courseId/grupos", teacher, """{"number":"01","period":"2026-II"}""").andExpect { status { isCreated() } }.body()
        reload()

        val code = groupBody["inviteCode"].asText()
        val enrollmentId = postAs("/api/v1/matriculas", ana, join(code)).andExpect { status { isCreated() } }.body()["id"].asLong()
        reload()

        val pending = getAs("/api/v1/grupos/${groupBody["id"].asLong()}/matriculas?estado=PENDIENTE", teacher).andExpect { status { isOk() } }.body()
        assertEquals(listOf(enrollmentId), pending.map { it["id"].asLong() })

        patchAs("/api/v1/matriculas/$enrollmentId", teacher, """{"decision":"APPROVE"}""").andExpect { status { isOk() } }
        reload()

        // Ya matriculada, Ana ve el curso, y el grupo sin el código de invitación.
        getAs("/api/v1/cursos/$courseId", ana).andExpect { status { isOk() } }
        val groups = getAs("/api/v1/cursos/$courseId/grupos", ana).andExpect { status { isOk() } }
        assertFalse(groups.andReturn().response.contentAsString.contains(code))
    }

    // ==================== SWAGGER ====================

    @Test
    fun `los endpoints de matriculas aparecen en OpenAPI con ejemplos`() {
        val doc = getAs("/api-docs", null).andExpect { status { isOk() } }.body()
        val paths = doc["paths"]

        assertTrue(paths.path("/api/v1/matriculas").has("post"), "falta POST /matriculas")
        assertTrue(paths.path("/api/v1/matriculas/mias").has("get"), "falta GET /matriculas/mias")
        assertTrue(paths.path("/api/v1/grupos/{grupoId}/matriculas").has("get"), "falta GET /grupos/{grupoId}/matriculas")
        assertTrue(paths.path("/api/v1/matriculas/{id}").has("patch"), "falta PATCH /matriculas/{id}")

        val schemas = doc["components"]["schemas"]
        for (name in listOf("JoinGroupDTO", "EnrollmentDTO", "EnrollmentDecisionDTO", "EnrollmentRequestSummaryDTO")) {
            assertFalse(schemas.path(name).path("example").isMissingNode, "$name no tiene example")
        }
    }
}

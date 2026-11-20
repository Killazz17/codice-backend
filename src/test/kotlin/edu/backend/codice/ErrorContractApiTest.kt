package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post

/*
 * El contrato de errores de punta a punta, con rutas y filtros reales: cada status (400, 401, 403, 404, 405, 406, 409, 415,
 * 422) llega como el ErrorResponse común en JSON, aunque el cliente pida otro formato, y ninguno deja ver trazas ni clases
 * internas. También los headers que el cliente necesita: Content-Type, Location y CORS. Es lo que revisa PM-05 en Postman.
 */
class ErrorContractApiTest : ApiIntegrationTest() {

    private lateinit var teacher: Actor
    private lateinit var student: Actor

    @BeforeEach
    fun setUp() {
        teacher = actor("profe@una.ac.cr", "ROLE_TEACHER")
        student = actor("ana@est.una.ac.cr", "ROLE_STUDENT")
    }

    /** Nada de lo que solo ve quien programa: nombres de clases, trazas de pila o el nombre de una excepción. */
    private fun assertNoInternals(raw: String) {
        for (leak in listOf("Exception", "stackTrace", "at edu.backend", "org.springframework", "java.lang", "jakarta.", "com.fasterxml")) {
            assertFalse(raw.contains(leak), "la respuesta de error expone «$leak»: $raw")
        }
    }

    // ==================== STATUS DE LA TABLA DEL ISSUE ====================

    @Test
    fun `400 un JSON mal formado, un body invalido, un size fuera de rango y una pagina negativa`() {
        val malformed = mockMvc.post("/api/v1/cursos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${teacher.token}")
            contentType = MediaType.APPLICATION_JSON
            content = "{ esto no es json"
        }.expectError(400, "Bad Request")
        assertNoInternals(malformed.andReturn().response.contentAsString)

        postAs("/api/v1/cursos", teacher, """{"code":"x","name":"","color":"azul"}""").expectError(400, "Bad Request")

        getAs("/api/v1/apuntes?size=1000", student).expectError(400, "Bad Request")
        getAs("/api/v1/apuntes?page=-1", student).expectError(400, "Bad Request")
    }

    @Test
    fun `404 un id que no existe en curso, grupo, matricula, subtema, objetivo y apunte`() {
        getAs("/api/v1/cursos/999999", teacher).expectError(404, "Not Found")
        patchAs("/api/v1/grupos/999999", teacher, """{"requiresApproval":false}""").expectError(404, "Not Found")
        patchAs("/api/v1/matriculas/999999", teacher, """{"decision":"APPROVE"}""").expectError(404, "Not Found")
        patchAs("/api/v1/subtemas/999999", teacher, """{"title":"Nuevo título"}""").expectError(404, "Not Found")
        patchAs("/api/v1/objetivos/999999", teacher, """{"description":"Nuevo objetivo"}""").expectError(404, "Not Found")
        getAs("/api/v1/apuntes/999999", student).expectError(404, "Not Found")
    }

    @Test
    fun `405 un verbo no soportado, aunque la ruta sea publica y no haya token`() {
        val response = mockMvc.get("/api/v1/auth/register").expectError(405, "Method Not Allowed")
        assertNoInternals(response.andReturn().response.contentAsString)

        mockMvc.post("/api/v1/auth/me") { header(HttpHeaders.AUTHORIZATION, "Bearer ${student.token}") }.expectError(405, "Method Not Allowed")
    }

    @Test
    fun `406 si el cliente solo acepta XML, el error igual llega en JSON`() {
        val response = mockMvc.get("/api/v1/cursos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${teacher.token}")
            header(HttpHeaders.ACCEPT, "application/xml")
        }.expectError(406, "Not Acceptable")

        assertNoInternals(response.andReturn().response.contentAsString)
    }

    @Test
    fun `415 un POST con Content-Type text plain`() {
        val response = mockMvc.post("/api/v1/cursos") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${teacher.token}")
            contentType = MediaType.TEXT_PLAIN
            content = "code=EIF411"
        }.expectError(415, "Unsupported Media Type")

        assertNoInternals(response.andReturn().response.contentAsString)
    }

    @Test
    fun `401 y 403 llegan tambien como ErrorResponse y sin datos internos`() {
        val unauthorized = getAs("/api/v1/cursos", null).expectError(401, "Unauthorized").andReturn().response.contentAsString
        val forbidden = postAs("/api/v1/cursos", student, """{"code":"EIF411","name":"Móviles"}""").expectError(403, "Forbidden").andReturn().response.contentAsString

        assertNoInternals(unauthorized)
        assertNoInternals(forbidden)
    }

    @Test
    fun `409 y 422 tambien son ErrorResponse`() {
        val course = course(teacher.user)
        val group = group(course, expiresAt = java.time.Instant.now().minusSeconds(60))
        enroll(group(course, "02"), student.user, EnrollmentStatus.ACTIVE)
        reload()

        val conflict = postAs("/api/v1/cursos", teacher, """{"code":"EIF411","name":"Repetido"}""").expectError(409, "Conflict")
        assertNoInternals(conflict.andReturn().response.contentAsString)

        val expired = postAs("/api/v1/matriculas", actor("beto@est.una.ac.cr", "ROLE_STUDENT"), """{"inviteCode":"${group.inviteCode}"}""")
            .expectError(422, "Unprocessable Entity")
        assertNoInternals(expired.andReturn().response.contentAsString)
    }

    // ==================== FORMA DEL ERROR ====================

    @Test
    fun `todo error trae timestamp, status, error, message y path, y el status coincide con el HTTP`() {
        val responses = listOf(
            getAs("/api/v1/cursos", null).andReturn().response,                                            // 401
            postAs("/api/v1/cursos", student, """{"code":"EIF411","name":"X"}""").andReturn().response,    // 403
            getAs("/api/v1/cursos/999999", teacher).andReturn().response,                                 // 404
            mockMvc.get("/api/v1/auth/register").andReturn().response,                                    // 405
        )

        for (response in responses) {
            val body = mapper.readTree(response.contentAsString)
            assertEquals(setOf("timestamp", "status", "error", "message", "path", "errors"), body.names(), response.contentAsString)
            assertEquals(response.status, body["status"].asInt(), "el status del cuerpo debe ser el HTTP real")
            assertTrue(response.contentType!!.startsWith("application/json"), "Content-Type ${response.contentType}")
        }
    }

    // ==================== HEADERS ====================

    @Test
    fun `una peticion CORS con Origin recibe los headers Access-Control-Allow`() {
        mockMvc.options("/api/v1/cursos") {
            header(HttpHeaders.ORIGIN, "http://localhost:3000")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
        }.andExpect {
            status { isOk() }
            header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000") }
            header { exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS) }
        }
    }

    @Test
    fun `un origen que no esta permitido no recibe Access-Control-Allow-Origin`() {
        mockMvc.options("/api/v1/cursos") {
            header(HttpHeaders.ORIGIN, "http://sitio-ajeno.example")
            header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
        }.andExpect {
            header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
        }
    }

    @Test
    fun `los 201 de las rutas anteriores de la app tambien traen Location`() {
        val course = postAs("/api/v1/courses", teacher, """{"code":"EIF411","name":"Móviles"}""").andExpect { status { isCreated() } }
        assertTrue(course.location()!!.matches(Regex("/api/v1/courses/\\d+")), course.location())

        val group = postAs("/api/v1/groups", teacher, """{"courseId":${course.body()["id"].asLong()},"number":"01","period":"2026-II"}""")
            .andExpect { status { isCreated() } }
        assertTrue(group.location()!!.matches(Regex("/api/v1/groups/\\d+")), group.location())

        val enrollment = postAs("/api/v1/enrollments", student, """{"groupId":${group.body()["id"].asLong()}}""").andExpect { status { isCreated() } }
        assertTrue(enrollment.location()!!.matches(Regex("/api/v1/enrollments/\\d+")), enrollment.location())
    }

    @Test
    fun `los 201 traen Location y es una URL que existe`() {
        val created = postAs("/api/v1/cursos", teacher, """{"code":"EIF411","name":"Móviles"}""").andExpect { status { isCreated() } }
        val location = created.location()!!

        assertTrue(location.startsWith("/api/v1/cursos/"), location)
        getAs(location, teacher).andExpect { status { isOk() } }
    }
}

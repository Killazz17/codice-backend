package edu.backend.codice

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional

/*
 * API-02: AuthController y seguridad JWT de punta a punta, con la cadena de filtros real, los servicios reales y H2.
 * Cubre cada status de la tabla del issue (register 201/400/409, login 200/400/401, me 200/401), el 401 en JSON de las
 * rutas protegidas sin token, el 403 por rol incorrecto y los tokens vencidos, manipulados o de otro secreto.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerSecurityTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val mapper: ObjectMapper,
    @Autowired val userRepository: UserRepository,
    @Autowired val passwordEncoder: PasswordEncoder,
) {

    private companion object {
        const val PASSWORD = "Segura123"
        const val COURSE_JSON = """{"code":"SEC411","name":"Seguridad"}"""
    }

    @AfterEach
    fun cleanThread() = LoggedUser.logOut()

    // ==================== AYUDAS ====================

    private fun register(email: String, role: String = "ROLE_STUDENT", password: String = PASSWORD, path: String = "/api/v1/auth/register") =
        mockMvc.post(path) {
            contentType = MediaType.APPLICATION_JSON
            content = """{"firstName":"Ana","lastName":"Mora","email":"$email","password":"$password","roleName":"$role"}"""
        }

    private fun login(email: String, password: String = PASSWORD) =
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"$email","password":"$password"}"""
        }

    /** Registra al usuario, inicia sesión y devuelve el token. */
    private fun tokenOf(email: String, role: String): String {
        register(email, role).andExpect { status { isCreated() } }
        val json = login(email).andExpect { status { isOk() } }.andReturn().response.contentAsString
        return mapper.readTree(json)["token"].asText()
    }

    private fun me(token: String?) = mockMvc.get("/api/v1/auth/me") {
        if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    private fun createCourse(token: String?) = mockMvc.post("/api/v1/courses") {
        contentType = MediaType.APPLICATION_JSON
        content = COURSE_JSON
        if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
    }

    /** El cuerpo de error común (ErrorResponse) en JSON, con el status y el nombre del error esperados. */
    private fun ResultActionsDsl.expectError(code: Int, error: String) = andExpect {
        status { isEqualTo(code) }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        jsonPath("$.status") { value(code) }
        jsonPath("$.error") { value(error) }
        jsonPath("$.path") { exists() }
        jsonPath("$.timestamp") { exists() }
    }

    private fun tokenFor(email: String, jwt: JwtService): String = jwt.generateToken(userRepository.findByEmail(email).get())

    // ==================== POST /api/v1/auth/register ====================

    @Test
    fun `register responde 201 con el usuario y sin contrasena`() {
        register("nuevo@una.cr", "ROLE_TEACHER").andExpect {
            status { isCreated() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.id") { exists() }
            jsonPath("$.email") { value("nuevo@una.cr") }
            jsonPath("$.roles[0]") { value("ROLE_TEACHER") }
            jsonPath("$.createdAt") { exists() }
            jsonPath("$.password") { doesNotExist() }
            jsonPath("$.passwordHash") { doesNotExist() }
        }
    }

    @Test
    fun `signup, el nombre que usa la app movil, sigue registrando`() {
        register("alias@una.cr", path = "/api/v1/auth/signup").andExpect {
            status { isCreated() }
            jsonPath("$.email") { value("alias@una.cr") }
        }
    }

    @Test
    fun `register con datos invalidos responde 400 con un error por campo`() {
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"firstName":"A","lastName":"","email":"no-es-correo","password":"corta","roleName":"ROLE_ADMIN"}"""
        }.expectError(400, "Bad Request").andExpect {
            jsonPath("$.errors") { isArray() }
        }.andReturn().response.contentAsString.let { body ->
            val fields = mapper.readTree(body)["errors"].map { it["field"].asText() }.toSet()
            assertEquals(setOf("firstName", "lastName", "email", "password", "roleName"), fields)
        }
    }

    @Test
    fun `register sin rol responde 400`() {
        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"firstName":"Ana","lastName":"Mora","email":"sinrol@una.cr","password":"$PASSWORD"}"""
        }.expectError(400, "Bad Request").andExpect {
            jsonPath("$.errors[0].field") { value("roleName") }
        }
    }

    @Test
    fun `register con un correo repetido responde 409`() {
        register("repetido@una.cr").andExpect { status { isCreated() } }
        register("repetido@una.cr").expectError(409, "Conflict")
    }

    // ==================== POST /api/v1/auth/login ====================

    @Test
    fun `login responde 200 con el token, su vencimiento y el usuario`() {
        register("login@una.cr", "ROLE_STUDENT").andExpect { status { isCreated() } }

        login("login@una.cr").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.token") { isNotEmpty() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresAt") { exists() }
            jsonPath("$.user.email") { value("login@una.cr") }
            jsonPath("$.user.roles[0]") { value("ROLE_STUDENT") }
            jsonPath("$.user.password") { doesNotExist() }
            jsonPath("$.password") { doesNotExist() }
        }
    }

    @Test
    fun `login con contrasena incorrecta o con un correo que no existe responde 401`() {
        register("clave@una.cr").andExpect { status { isCreated() } }

        val wrongPassword = login("clave@una.cr", "Incorrecta1").expectError(401, "Unauthorized")
            .andReturn().response.contentAsString
        val unknownUser = login("nadie@una.cr").expectError(401, "Unauthorized")
            .andReturn().response.contentAsString

        // El mensaje es el mismo: no se revela si el correo existe.
        assertEquals(mapper.readTree(wrongPassword)["message"], mapper.readTree(unknownUser)["message"])
    }

    @Test
    fun `login con el cuerpo invalido responde 400`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"","password":""}"""
        }.expectError(400, "Bad Request")

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"no-es-un-correo","password":"$PASSWORD"}"""
        }.expectError(400, "Bad Request")
    }

    @Test
    fun `login de una cuenta deshabilitada responde 401`() {
        userRepository.save(
            User(firstName = "Baja", lastName = "Test", email = "baja@una.cr", password = passwordEncoder.encode(PASSWORD), enabled = false)
        )

        login("baja@una.cr").expectError(401, "Unauthorized")
    }

    // ==================== GET /api/v1/auth/me ====================

    @Test
    fun `me con el token del login responde 200 con el perfil`() {
        val token = tokenOf("perfil@una.cr", "ROLE_TEACHER")

        me(token).andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.email") { value("perfil@una.cr") }
            jsonPath("$.roles[0]") { value("ROLE_TEACHER") }
            jsonPath("$.password") { doesNotExist() }
        }
    }

    @Test
    fun `me acepta tambien el token del login clasico que usa la app movil`() {
        register("clasico@una.cr", "ROLE_STUDENT").andExpect { status { isCreated() } }

        val result = mockMvc.post("/v1/users/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"clasico@una.cr","password":"$PASSWORD"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.username") { value("clasico@una.cr") }
            jsonPath("$.authorities[0]") { value("ROLE_STUDENT") }
            jsonPath("$.password") { doesNotExist() }
        }.andReturn()

        val header = result.response.getHeader(HttpHeaders.AUTHORIZATION)
        assertNotNull(header, "el login clásico devuelve el token en el header Authorization")
        assertTrue(header!!.startsWith("Bearer "))
        // La parte pública del token (lo que muestra jwt.io) trae el correo y los permisos.
        val payload = String(Base64.getUrlDecoder().decode(header.removePrefix("Bearer ").split(".")[1]))
        assertTrue(payload.contains("\"sub\":\"clasico@una.cr\""), payload)
        assertTrue(payload.contains("ROLE_STUDENT"), payload)

        me(header.removePrefix("Bearer ")).andExpect {
            status { isOk() }
            jsonPath("$.email") { value("clasico@una.cr") }
        }
    }

    @Test
    fun `me sin token responde 401 en JSON y no 404`() {
        me(null).expectError(401, "Unauthorized")
    }

    @Test
    fun `me con un token inventado responde 401`() {
        me("esto.no.es-un-jwt").expectError(401, "Unauthorized")
    }

    @Test
    fun `me con un token vencido responde 401`() {
        register("vencido@una.cr").andExpect { status { isCreated() } }
        val expired = tokenFor("vencido@una.cr", JwtServiceImpl(SecurityConstants.TOKEN_SECRET, -60_000L))

        me(expired).expectError(401, "Unauthorized")
    }

    @Test
    fun `me con un token manipulado responde 401`() {
        val token = tokenOf("manipulado@una.cr", "ROLE_STUDENT")
        // Se cambian los últimos caracteres de la firma: el contenido ya no coincide con ella.
        val tampered = token.dropLast(10) + "AAAAAAAAAA"

        me(tampered).expectError(401, "Unauthorized")
    }

    @Test
    fun `me con un token firmado con otro secreto responde 401`() {
        register("forjado@una.cr").andExpect { status { isCreated() } }
        val forged = tokenFor("forjado@una.cr", JwtServiceImpl("otro-secreto-".repeat(10), 3_600_000L))

        me(forged).expectError(401, "Unauthorized")
    }

    @Test
    fun `me con el token de un usuario que ya no existe responde 401`() {
        val token = tokenOf("borrado@una.cr", "ROLE_STUDENT")
        userRepository.delete(userRepository.findByEmail("borrado@una.cr").get())

        me(token).expectError(401, "Unauthorized")
    }

    // ==================== CONTROL DE ACCESO ====================

    @Test
    fun `una ruta protegida sin token responde 401 en JSON`() {
        mockMvc.get("/api/v1/courses").expectError(401, "Unauthorized")
        createCourse(null).expectError(401, "Unauthorized")
    }

    @Test
    fun `un estudiante en una ruta de profesor responde 403 en JSON`() {
        val student = tokenOf("estudiante@una.cr", "ROLE_STUDENT")

        createCourse(student).expectError(403, "Forbidden")
    }

    @Test
    fun `un profesor en una ruta de estudiante responde 403 en JSON`() {
        val teacher = tokenOf("profesor@una.cr", "ROLE_TEACHER")

        mockMvc.get("/api/v1/enrollments/my") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $teacher")
        }.expectError(403, "Forbidden")
    }

    @Test
    fun `cada rol entra a las rutas de su rol`() {
        val teacher = tokenOf("dueno@una.cr", "ROLE_TEACHER")
        val student = tokenOf("alumno@una.cr", "ROLE_STUDENT")

        createCourse(teacher).andExpect {
            status { isCreated() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.code") { value("SEC411") }
        }
        mockMvc.get("/api/v1/enrollments/my") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $student")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `la identidad del usuario no sobrevive a la peticion`() {
        val token = tokenOf("hilo@una.cr", "ROLE_STUDENT")

        me(token).andExpect { status { isOk() } }

        // MockMvc corre en este mismo hilo: si el filtro no limpiara LoggedUser, aquí seguiría el correo.
        assertEquals("", LoggedUser.get())
    }

    // ==================== RUTAS PÚBLICAS Y DOCUMENTACIÓN ====================

    @Test
    fun `un verbo no soportado en las rutas publicas responde 405 aunque no haya token`() {
        mockMvc.delete("/api/v1/auth/login").expectError(405, "Method Not Allowed")
        mockMvc.get("/api/v1/auth/register").expectError(405, "Method Not Allowed")
        // /me sí exige token: sin él, un verbo cualquiera responde 401 y no revela qué verbos admite.
        mockMvc.post("/api/v1/auth/me").expectError(401, "Unauthorized")
    }

    @Test
    fun `el health check es publico`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }

    @Test
    fun `la documentacion OpenAPI es publica y lista los tres endpoints de auth`() {
        val body = mockMvc.get("/api-docs").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val paths = mapper.readTree(body)["paths"]

        assertTrue(paths.has("/api/v1/auth/register"), "falta register en Swagger")
        assertTrue(paths.has("/api/v1/auth/login"), "falta login en Swagger")
        assertTrue(paths.has("/api/v1/auth/me"), "falta me en Swagger")
        assertFalse(paths.has("/api/v1/auth/signup"), "signup es un alias oculto")

        // register y login no piden token (security vacío anula el bearerAuth global); me sí.
        for (path in listOf("/api/v1/auth/login", "/api/v1/auth/register")) {
            val security = paths[path]["post"].path("security")
            assertTrue(security.isArray && security.isEmpty, "$path debe verse público en Swagger: $security")
        }
        assertTrue(paths["/api/v1/auth/me"]["get"].path("security").path(0).has("bearerAuth"))
    }

    @Test
    fun `la interfaz de Swagger es publica`() {
        val status = mockMvc.get("/swagger-ui.html").andReturn().response.status
        assertTrue(status != 401 && status != 403, "Swagger respondió $status")
    }
}

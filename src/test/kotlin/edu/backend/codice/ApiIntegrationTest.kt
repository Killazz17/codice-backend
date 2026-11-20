package edu.backend.codice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/*
 * Base de las pruebas de los endpoints: la cadena de seguridad real, los servicios reales y H2. Los usuarios se guardan
 * directo en la base con un token válido (sin pasar por BCrypt); el registro y el login ya se prueban en
 * AuthControllerSecurityTest. Cada prueba corre en una transacción que se deshace al terminar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
abstract class ApiIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var roleRepository: RoleRepository
    @Autowired lateinit var courseRepository: CourseRepository
    @Autowired lateinit var studentGroupRepository: StudentGroupRepository
    @Autowired lateinit var enrollmentRepository: EnrollmentRepository
    @Autowired lateinit var jwtService: JwtService
    @PersistenceContext lateinit var entityManager: EntityManager

    @AfterEach
    fun cleanThread() = LoggedUser.logOut()

    /** Un usuario ya guardado y el token con el que hace sus peticiones. */
    class Actor(val user: User, val token: String)

    protected fun actor(email: String, roleName: String): Actor {
        val role = roleRepository.findByName(roleName).orElseGet { roleRepository.save(Role(name = roleName)) }
        val user = userRepository.save(
            User(firstName = email.substringBefore("@"), lastName = "Test", email = email, password = "x", roleList = mutableSetOf(role))
        )
        return Actor(user, jwtService.generateToken(user))
    }

    protected fun course(owner: User, code: String = "EIF411", name: String = "Plataformas Móviles") =
        courseRepository.save(Course(code = code, name = name, teacher = owner))

    protected fun group(
        course: Course,
        number: String = "01",
        requiresApproval: Boolean = true,
        expiresAt: Instant? = Instant.now().plusSeconds(3600),
    ): StudentGroup = studentGroupRepository.save(
        StudentGroup(
            course = course, teacher = course.teacher, number = number, period = "2026-II", schedule = "L/J 18:00-19:30",
            inviteCode = "${course.code}-$number-TEST", requiresApproval = requiresApproval, inviteExpiresAt = expiresAt,
        )
    )

    protected fun enroll(group: StudentGroup, student: User, status: EnrollmentStatus) = enrollmentRepository.save(
        // course lo llena en PostgreSQL el trigger de la base; en H2 no existe, así que se copia a mano.
        Enrollment(group = group, course = group.course, student = student, status = status)
    )

    /**
     * Guarda lo pendiente y vacía el contexto de persistencia. En producción cada petición tiene el suyo; aquí toda la
     * prueba comparte uno y un curso ya cargado conservaría la lista de grupos que tenía antes de la petición.
     */
    protected fun reload() {
        entityManager.flush()
        entityManager.clear()
    }

    // ---------- Peticiones (who = null: sin token) ----------

    protected fun getAs(url: String, who: Actor?) = mockMvc.get(url) { auth(who) }

    protected fun deleteAs(url: String, who: Actor?) = mockMvc.delete(url) { auth(who) }

    protected fun postAs(url: String, who: Actor?, body: String? = null) = mockMvc.post(url) {
        auth(who)
        if (body != null) {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
    }

    protected fun patchAs(url: String, who: Actor?, body: String) = mockMvc.patch(url) {
        auth(who)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun org.springframework.test.web.servlet.MockHttpServletRequestDsl.auth(who: Actor?) {
        if (who != null) header(HttpHeaders.AUTHORIZATION, "Bearer ${who.token}")
    }

    // ---------- Comprobaciones ----------

    /** El cuerpo de error común (ErrorResponse) en JSON, con el status y el nombre del error esperados. */
    protected fun ResultActionsDsl.expectError(code: Int, error: String) = andExpect {
        status { isEqualTo(code) }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        jsonPath("$.status") { value(code) }
        jsonPath("$.error") { value(error) }
        jsonPath("$.path") { exists() }
        jsonPath("$.timestamp") { exists() }
    }

    protected fun ResultActionsDsl.body(): JsonNode = mapper.readTree(andReturn().response.contentAsString)

    protected fun ResultActionsDsl.location(): String? = andReturn().response.getHeader(HttpHeaders.LOCATION)

    protected fun JsonNode.names(): Set<String> = fieldNames().asSequence().toSet()
}

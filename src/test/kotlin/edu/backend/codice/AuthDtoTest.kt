package edu.backend.codice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.persistence.Entity
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mapstruct.factory.Mappers
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/*
 * DTO-02: reglas de validación y serialización de los DTOs de Auth y Usuario. Cada regla tiene su caso válido y su
 * caso inválido. Los nombres del enunciado (RegistroRequest, LoginRequest, UsuarioResponse) son en este proyecto
 * UserSignUpInput, UserLoginInput y UserResult; la equivalencia está documentada en DTOs.kt.
 */
class AuthDtoTest {

    private lateinit var validator: Validator
    private lateinit var mapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        validator = Validation.buildDefaultValidatorFactory().validator
        // Igual que el ObjectMapper de Spring Boot: fechas en ISO-8601 y no como números.
        mapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    private fun signUp(
        firstName: String? = "Carlos",
        lastName: String? = "Mora",
        email: String? = "carlos@example.com",
        password: String? = "Segura123",
        roleName: String? = "ROLE_STUDENT",
    ) = UserSignUpInput(firstName, lastName, email, password, roleName)

    /** Mensajes de las violaciones que cayeron sobre [field]; vacío si el campo es válido. */
    private fun errorsOn(input: Any, field: String): List<String> =
        validator.validate(input).filter { it.propertyPath.toString() == field }.map { it.message }

    private fun user(createdAt: Instant = Instant.parse("2026-10-13T08:30:00Z")) = UserResult(
        id = 7L, firstName = "Ana", lastName = "Mora", email = "ana@una.cr",
        enabled = true, roles = listOf("ROLE_STUDENT"), createdAt = createdAt,
    )

    // ==================== REGISTRO ====================

    @Test
    fun `un registro correcto de estudiante o de profesor no tiene violaciones`() {
        assertTrue(validator.validate(signUp(roleName = "ROLE_STUDENT")).isEmpty())
        assertTrue(validator.validate(signUp(roleName = "ROLE_TEACHER")).isEmpty())
    }

    @Test
    fun `el nombre y el apellido van de 2 a 100 caracteres`() {
        for (field in listOf("firstName", "lastName")) {
            fun build(value: String?) = if (field == "firstName") signUp(firstName = value) else signUp(lastName = value)

            // Válidos: justo en los dos bordes.
            assertTrue(errorsOn(build("Al"), field).isEmpty(), "$field con 2 caracteres")
            assertTrue(errorsOn(build("A".repeat(100)), field).isEmpty(), "$field con 100 caracteres")

            // Inválidos: uno menos, uno más, vacío, solo espacios y ausente.
            assertEquals(1, errorsOn(build("A"), field).size, "$field con 1 carácter")
            assertEquals(1, errorsOn(build("A".repeat(101)), field).size, "$field con 101 caracteres")
            assertFalse(errorsOn(build(""), field).isEmpty(), "$field vacío")
            assertFalse(errorsOn(build("   "), field).isEmpty(), "$field en blanco")
            assertFalse(errorsOn(build(null), field).isEmpty(), "$field ausente")
        }
        assertTrue(errorsOn(signUp(firstName = "A"), "firstName").single().contains("entre 2 y 100"))
    }

    @Test
    fun `el correo debe tener formato de email`() {
        assertTrue(errorsOn(signUp(email = "ana.mora@est.una.ac.cr"), "email").isEmpty())

        for (invalid in listOf("sin-arroba", "dos@@arrobas.com", "@sin-usuario.com", "con espacios@una.cr", "", "   ", null)) {
            assertFalse(errorsOn(signUp(email = invalid), "email").isEmpty(), "el correo '$invalid' debió ser inválido")
        }
        // La columna es VARCHAR(255): más largo que eso sería un error de la base y no un 400.
        assertFalse(errorsOn(signUp(email = "a".repeat(250) + "@una.cr"), "email").isEmpty())
    }

    @Test
    fun `la contrasena lleva al menos 8 caracteres, una letra y un numero`() {
        // Válidas: justo 8, con tilde y con símbolos.
        for (valid in listOf("Abcdefg1", "contraseña1", "clave#2026!", "12345678a")) {
            assertTrue(errorsOn(signUp(password = valid), "password").isEmpty(), "'$valid' debió ser válida")
        }

        // Muy corta: falla el largo, no la composición.
        val corta = errorsOn(signUp(password = "Abc123"), "password")
        assertEquals(1, corta.size)
        assertTrue(corta.single().contains("al menos 8 caracteres"))

        // Sin número o sin letra: falla solo la composición.
        for (invalid in listOf("abcdefgh", "ABCDEFGH", "12345678", "########")) {
            val errors = errorsOn(signUp(password = invalid), "password")
            assertEquals(1, errors.size, "'$invalid'")
            assertTrue(errors.single().contains("una letra y un número"), "'$invalid'")
        }

        // Obligatoria.
        assertFalse(errorsOn(signUp(password = null), "password").isEmpty())
        assertFalse(errorsOn(signUp(password = "        "), "password").isEmpty())
    }

    @Test
    fun `el rol es obligatorio y solo puede ser estudiante o profesor`() {
        assertTrue(errorsOn(signUp(roleName = "ROLE_STUDENT"), "roleName").isEmpty())
        assertTrue(errorsOn(signUp(roleName = "ROLE_TEACHER"), "roleName").isEmpty())

        for (invalid in listOf("ROLE_ADMIN", "STUDENT", "role_student", "ROLE_STUDENT,ROLE_TEACHER", "")) {
            assertFalse(errorsOn(signUp(roleName = invalid), "roleName").isEmpty(), "el rol '$invalid' debió ser inválido")
        }
        assertFalse(errorsOn(signUp(roleName = null), "roleName").isEmpty(), "el rol es obligatorio")
        assertTrue(errorsOn(signUp(roleName = "ROLE_ADMIN"), "roleName").single().contains("ROLE_STUDENT o ROLE_TEACHER"))
    }

    @Test
    fun `un registro con todo mal reporta cada campo`() {
        val violated = validator.validate(
            signUp(firstName = "", lastName = "x", email = "malo", password = "123", roleName = "ROLE_ADMIN")
        ).map { it.propertyPath.toString() }.toSet()

        assertEquals(setOf("firstName", "lastName", "email", "password", "roleName"), violated)
    }

    // ==================== LOGIN ====================

    @Test
    fun `el login pide un correo valido y una contrasena`() {
        assertTrue(validator.validate(UserLoginInput(username = "ana@una.cr", password = "x")).isEmpty())

        assertFalse(errorsOn(UserLoginInput(username = "", password = "x"), "username").isEmpty())
        assertFalse(errorsOn(UserLoginInput(username = "no-es-un-correo", password = "x"), "username").isEmpty())
        assertFalse(errorsOn(UserLoginInput(username = "ana@una.cr", password = ""), "password").isEmpty())
        assertFalse(errorsOn(UserLoginInput(username = "ana@una.cr", password = "   "), "password").isEmpty())
    }

    // ==================== CONTRATO CON LA APP MÓVIL ====================

    @Test
    fun `el JSON que manda la app movil sigue deserializandose`() {
        val signUpJson = """{"firstName":"Ana","lastName":"Mora","email":"ana@una.cr","password":"Segura123","roleName":"ROLE_TEACHER"}"""
        val parsed = mapper.readValue(signUpJson, UserSignUpInput::class.java)
        assertEquals(signUp(firstName = "Ana", lastName = "Mora", email = "ana@una.cr", roleName = "ROLE_TEACHER"), parsed)

        // El filtro del login clásico lee este DTO con un ObjectMapper sin el módulo de Kotlin.
        val loginJson = """{"username":"ana@una.cr","password":"Segura123"}"""
        val login = ObjectMapper().readValue(loginJson, UserLoginInput::class.java)
        assertEquals("ana@una.cr", login.username)
        assertEquals("Segura123", login.password)
    }

    // ==================== SERIALIZACIÓN DE LAS RESPUESTAS ====================

    @Test
    fun `UsuarioResponse expone sus campos en camelCase y la fecha en ISO-8601 UTC`() {
        val tree = mapper.readTree(mapper.writeValueAsString(user()))

        assertEquals(setOf("id", "firstName", "lastName", "email", "enabled", "roles", "createdAt"), tree.names())
        assertEquals("2026-10-13T08:30:00Z", tree["createdAt"].asText())
        assertEquals(listOf("ROLE_STUDENT"), tree["roles"].map { it.asText() })
    }

    @Test
    fun `AuthResponse lleva token, tipo, vencimiento y usuario`() {
        val response = AuthResponse(token = "aaa.bbb.ccc", expiresAt = Instant.parse("2026-10-14T12:00:00Z"), user = user())
        val tree = mapper.readTree(mapper.writeValueAsString(response))

        assertEquals(setOf("token", "tokenType", "expiresAt", "user"), tree.names())
        assertEquals("aaa.bbb.ccc", tree["token"].asText())
        assertEquals("Bearer", tree["tokenType"].asText())
        assertEquals("2026-10-14T12:00:00Z", tree["expiresAt"].asText())
        assertEquals(7L, tree["user"]["id"].asLong())
    }

    @Test
    fun `ninguna respuesta incluye la contrasena ni su hash`() {
        val hash = "\$2a\$10\$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"
        val response = AuthResponse(token = "aaa.bbb.ccc", expiresAt = Instant.now(), user = user())

        for (json in listOf(mapper.writeValueAsString(user()), mapper.writeValueAsString(response))) {
            assertFalse(json.contains("password", ignoreCase = true), json)
            assertFalse(json.contains("passwordHash", ignoreCase = true), json)
            assertFalse(json.contains(hash), json)
        }

        // Lo mismo pero por estructura: ninguna propiedad se llama como una contraseña.
        for (type in listOf(UserResult::class, AuthResponse::class)) {
            val names = type.memberProperties.map { it.name.lowercase() }
            assertTrue(names.none { it.contains("password") || it.contains("hash") }, "$type: $names")
        }
    }

    @Test
    fun `el mapper de usuario copia la fecha de registro`() {
        val entity = User(id = 3L, firstName = "Ana", lastName = "Mora", email = "ana@una.cr", password = "hash")
        val response = Mappers.getMapper(UserMapper::class.java).toUserResponse(entity)

        assertNotNull(response.createdAt)
        assertEquals(entity.createDate, response.createdAt)
    }

    // ==================== AISLAMIENTO DE LAS ENTIDADES ====================

    @Test
    fun `ningun DTO de auth expone una entidad JPA`() {
        // Comprobación de cordura: el detector sí reconoce una entidad.
        assertNotNull(User::class.findAnnotation<Entity>())

        for (dto in listOf(UserSignUpInput::class, UserLoginInput::class, UserResult::class, AuthResponse::class)) {
            for (property in dto.memberProperties) {
                val type = property.returnType.classifier as? KClass<*>
                assertTrue(type?.findAnnotation<Entity>() == null, "${dto.simpleName}.${property.name} es una entidad")
            }
        }
    }

    private fun JsonNode.names(): Set<String> = fieldNames().asSequence().toSet()
}

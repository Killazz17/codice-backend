package edu.backend.codice

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class DtoValidationAndSerializationTest {

    private lateinit var validator: Validator
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        validator = Validation.buildDefaultValidatorFactory().validator
        objectMapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
    }

    @Test
    fun `test ErrorResponse Jackson JSON serialization field names`() {
        val errorResponse = ErrorResponse(
            timestamp = Instant.parse("2026-10-10T12:00:00Z"),
            status = 400,
            error = "Bad Request",
            message = "Violación de validación",
            path = "/api/v1/courses",
            errors = listOf(FieldErrorDto(field = "code", message = "El código es obligatorio"))
        )

        val json = objectMapper.writeValueAsString(errorResponse)

        assertTrue(json.contains("\"timestamp\":"))
        assertTrue(json.contains("\"status\":400"))
        assertTrue(json.contains("\"error\":\"Bad Request\""))
        assertTrue(json.contains("\"message\":\"Violación de validación\""))
        assertTrue(json.contains("\"path\":\"/api/v1/courses\""))
        assertTrue(json.contains("\"errors\":["))
        assertTrue(json.contains("\"field\":\"code\""))
        assertTrue(json.contains("\"message\":\"El código es obligatorio\""))
    }

    @Test
    fun `test PageResponse Jackson JSON serialization field names`() {
        val pageResponse = PageResponse(
            content = listOf("item1", "item2"),
            page = 0,
            size = 10,
            totalElements = 2L,
            totalPages = 1
        )

        val json = objectMapper.writeValueAsString(pageResponse)

        assertTrue(json.contains("\"content\":[\"item1\",\"item2\"]"))
        assertTrue(json.contains("\"page\":0"))
        assertTrue(json.contains("\"size\":10"))
        assertTrue(json.contains("\"totalElements\":2"))
        assertTrue(json.contains("\"totalPages\":1"))
    }

    @Test
    fun `test Bean Validation on invalid UserSignUpInput`() {
        val invalidSignUp = UserSignUpInput(
            firstName = "",
            lastName = "   ",
            email = "invalid-email-format",
            password = "123"
        )

        val violations = validator.validate(invalidSignUp)
        assertFalse(violations.isEmpty())

        val violatedFields = violations.map { it.propertyPath.toString() }.toSet()
        assertTrue(violatedFields.contains("firstName"))
        assertTrue(violatedFields.contains("lastName"))
        assertTrue(violatedFields.contains("email"))
        assertTrue(violatedFields.contains("password"))
    }

    @Test
    fun `test Bean Validation on valid UserSignUpInput`() {
        val validSignUp = UserSignUpInput(
            firstName = "Carlos",
            lastName = "Mora",
            email = "carlos@example.com",
            password = "securePassword123",
            roleName = "ROLE_STUDENT" // DTO-02: el rol es obligatorio, ya no hay uno por defecto
        )

        val violations = validator.validate(validSignUp)
        assertTrue(violations.isEmpty())
    }
}

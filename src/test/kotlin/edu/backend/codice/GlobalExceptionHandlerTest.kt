package edu.backend.codice

import edu.backend.codice.exception.*
import jakarta.validation.ConstraintViolationException
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.*
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException

@RestController
@RequestMapping("/test-exceptions")
class TestExceptionController {

    @PostMapping("/method-argument-not-valid")
    fun testValid(@RequestBody @jakarta.validation.Valid input: UserSignUpInput) {}

    @GetMapping("/constraint-violation")
    fun testConstraint() {
        throw ConstraintViolationException("Violación", emptySet<jakarta.validation.ConstraintViolation<*>>())
    }

    @GetMapping("/bad-request-json")
    fun testJson() {
        throw org.springframework.http.converter.HttpMessageNotReadableException("Malformed JSON")
    }

    @GetMapping("/unauthorized")
    fun testUnauthorized() {
        throw UnauthorizedException("Token inválido")
    }

    @GetMapping("/bad-credentials")
    fun testBadCredentials() {
        throw BadCredentialsException("Credenciales malas")
    }

    @GetMapping("/forbidden")
    fun testForbidden() {
        throw ForbiddenException("Prohibido")
    }

    @GetMapping("/access-denied")
    fun testAccessDenied() {
        throw AccessDeniedException("Acceso denegado")
    }

    @GetMapping("/not-found")
    fun testNotFound() {
        throw NotFoundException("No encontrado")
    }

    @GetMapping("/no-such-element")
    fun testNoSuchElement() {
        throw NoSuchElementException("Elemento no existe")
    }

    @GetMapping("/method-not-supported")
    fun testMethodNotSupported() {
        throw HttpRequestMethodNotSupportedException("POST", listOf("GET"))
    }

    @GetMapping("/media-type-not-acceptable")
    fun testNotAcceptable() {
        throw HttpMediaTypeNotAcceptableException(emptyList())
    }

    @GetMapping("/conflict")
    fun testConflict() {
        throw ConflictException("Conflicto")
    }

    @GetMapping("/data-integrity")
    fun testDataIntegrity() {
        throw org.springframework.dao.DataIntegrityViolationException("Duplicado")
    }

    @GetMapping("/media-type-not-supported")
    fun testNotSupported() {
        throw HttpMediaTypeNotSupportedException("No soportado")
    }

    @GetMapping("/business-rule")
    fun testBusinessRule() {
        throw BusinessRuleException("Regla de negocio violada")
    }

    @GetMapping("/internal-error")
    fun testInternalError() {
        throw RuntimeException("Error interno confidencial con stack trace secreto traceId=98765")
    }
}

@WebMvcTest(TestExceptionController::class)
@WithMockUser
class GlobalExceptionHandlerTest(
    @Autowired val mockMvc: MockMvc
) {

    @Test
    fun `test MethodArgumentNotValid returns 400 and ErrorResponse shape`() {
        mockMvc.post("/test-exceptions/method-argument-not-valid") {
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
            with(csrf())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.status") { value(400) }
            jsonPath("$.error") { value("Bad Request") }
            jsonPath("$.message") { exists() }
            jsonPath("$.path") { value("/test-exceptions/method-argument-not-valid") }
            jsonPath("$.timestamp") { exists() }
            jsonPath("$.errors") { isArray() }
        }
    }

    @Test
    fun `test ConstraintViolation returns 400 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/constraint-violation").andExpect {
            status { isBadRequest() }
            jsonPath("$.status") { value(400) }
            jsonPath("$.error") { value("Bad Request") }
            jsonPath("$.path") { value("/test-exceptions/constraint-violation") }
        }
    }

    @Test
    fun `test HttpMessageNotReadable returns 400 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/bad-request-json").andExpect {
            status { isBadRequest() }
            jsonPath("$.status") { value(400) }
            jsonPath("$.error") { value("Bad Request") }
            jsonPath("$.path") { value("/test-exceptions/bad-request-json") }
        }
    }

    @Test
    fun `test UnauthorizedException returns 401 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/unauthorized").andExpect {
            status { isUnauthorized() }
            jsonPath("$.status") { value(401) }
            jsonPath("$.error") { value("Unauthorized") }
            jsonPath("$.path") { value("/test-exceptions/unauthorized") }
        }
    }

    @Test
    fun `test BadCredentialsException returns 401 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/bad-credentials").andExpect {
            status { isUnauthorized() }
            jsonPath("$.status") { value(401) }
            jsonPath("$.error") { value("Unauthorized") }
            jsonPath("$.path") { value("/test-exceptions/bad-credentials") }
        }
    }

    @Test
    fun `test ForbiddenException returns 403 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/forbidden").andExpect {
            status { isForbidden() }
            jsonPath("$.status") { value(403) }
            jsonPath("$.error") { value("Forbidden") }
            jsonPath("$.path") { value("/test-exceptions/forbidden") }
        }
    }

    @Test
    fun `test AccessDeniedException returns 403 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/access-denied").andExpect {
            status { isForbidden() }
            jsonPath("$.status") { value(403) }
            jsonPath("$.error") { value("Forbidden") }
            jsonPath("$.path") { value("/test-exceptions/access-denied") }
        }
    }

    @Test
    fun `test NotFoundException returns 404 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/not-found").andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
            jsonPath("$.error") { value("Not Found") }
            jsonPath("$.path") { value("/test-exceptions/not-found") }
        }
    }

    @Test
    fun `test NoSuchElementException returns 404 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/no-such-element").andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
            jsonPath("$.error") { value("Not Found") }
            jsonPath("$.path") { value("/test-exceptions/no-such-element") }
        }
    }

    @Test
    fun `test HttpRequestMethodNotSupportedException returns 405 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/method-not-supported").andExpect {
            status { isMethodNotAllowed() }
            jsonPath("$.status") { value(405) }
            jsonPath("$.error") { value("Method Not Allowed") }
            jsonPath("$.path") { value("/test-exceptions/method-not-supported") }
        }
    }

    @Test
    fun `test HttpMediaTypeNotAcceptableException returns 406 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/media-type-not-acceptable").andExpect {
            status { isNotAcceptable() }
            jsonPath("$.status") { value(406) }
            jsonPath("$.error") { value("Not Acceptable") }
            jsonPath("$.path") { value("/test-exceptions/media-type-not-acceptable") }
        }
    }

    @Test
    fun `test ConflictException returns 409 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/conflict").andExpect {
            status { isConflict() }
            jsonPath("$.status") { value(409) }
            jsonPath("$.error") { value("Conflict") }
            jsonPath("$.path") { value("/test-exceptions/conflict") }
        }
    }

    @Test
    fun `test DataIntegrityViolationException returns 409 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/data-integrity").andExpect {
            status { isConflict() }
            jsonPath("$.status") { value(409) }
            jsonPath("$.error") { value("Conflict") }
            jsonPath("$.path") { value("/test-exceptions/data-integrity") }
        }
    }

    @Test
    fun `test HttpMediaTypeNotSupportedException returns 415 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/media-type-not-supported").andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.status") { value(415) }
            jsonPath("$.error") { value("Unsupported Media Type") }
            jsonPath("$.path") { value("/test-exceptions/media-type-not-supported") }
        }
    }

    @Test
    fun `test BusinessRuleException returns 422 and ErrorResponse shape`() {
        mockMvc.get("/test-exceptions/business-rule").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.status") { value(422) }
            jsonPath("$.error") { value("Unprocessable Entity") }
            jsonPath("$.path") { value("/test-exceptions/business-rule") }
        }
    }

    @Test
    fun `test Internal Server Error returns 500 without stack trace or internal details`() {
        mockMvc.get("/test-exceptions/internal-error").andExpect {
            status { isInternalServerError() }
            jsonPath("$.status") { value(500) }
            jsonPath("$.error") { value("Internal Server Error") }
            jsonPath("$.message") { value("Ocurrió un error interno en el servidor") }
            jsonPath("$.message", not(containsString("RuntimeException")))
            jsonPath("$.message", not(containsString("confidencial")))
            jsonPath("$.message", not(containsString("traceId")))
            jsonPath("$.path") { value("/test-exceptions/internal-error") }
        }
    }
}

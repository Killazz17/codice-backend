package edu.backend.codice

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

// Frena la fuerza bruta del login y el abuso de la IA sin tocar el resto de la API.
class RateLimitFilterTest {

    private val filter = RateLimitFilter(authPerMinute = 3, aiPerHour = 2, objectMapper = ObjectMapper().findAndRegisterModules())

    private fun call(method: String, path: String, ip: String = "1.1.1.1", token: String? = null, forwarded: String? = null): MockHttpServletResponse {
        val request = MockHttpServletRequest(method, path).apply {
            remoteAddr = ip
            token?.let { addHeader("Authorization", it) }
            forwarded?.let { addHeader("X-Forwarded-For", it) }
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    @Test
    fun `el login pasa hasta el limite y despues responde 429 con Retry-After`() {
        repeat(3) { assertEquals(200, call("POST", "/api/v1/auth/login").status) }

        val blocked = call("POST", "/api/v1/auth/login")

        assertEquals(429, blocked.status)
        assertNotNull(blocked.getHeader("Retry-After"))
        assertTrue(blocked.contentAsString.contains("Demasiados intentos"))
        assertTrue(blocked.contentType!!.contains("application/json"))
    }

    @Test
    fun `cada direccion IP tiene su propio contador`() {
        repeat(4) { call("POST", "/v1/users/login", ip = "2.2.2.2") }

        assertEquals(200, call("POST", "/v1/users/login", ip = "3.3.3.3").status)
    }

    @Test
    fun `se usa la ultima IP de X-Forwarded-For y no la que escribe el cliente`() {
        repeat(3) { call("POST", "/api/v1/auth/signup", forwarded = "9.9.9.$it, 4.4.4.4") }

        // El cliente cambia la primera entrada para esquivar el límite, pero la IP real (la última) es la misma.
        assertEquals(429, call("POST", "/api/v1/auth/signup", forwarded = "8.8.8.8, 4.4.4.4").status)
    }

    @Test
    fun `las llamadas a la IA se cuentan por usuario y no por IP`() {
        repeat(2) { assertEquals(200, call("POST", "/api/v1/apuntes/5/auditar", token = "Bearer a").status) }

        assertEquals(429, call("POST", "/api/v1/apuntes/5/auditar", token = "Bearer a").status)
        assertEquals(200, call("POST", "/api/v1/apuntes/5/auditar", token = "Bearer b").status)
    }

    @Test
    fun `buscar un codigo de invitacion tambien tiene limite`() {
        repeat(3) { call("GET", "/api/v1/groups/invite/ABC", ip = "5.5.5.5") }

        assertEquals(429, call("GET", "/api/v1/groups/invite/XYZ", ip = "5.5.5.5").status)
    }

    @Test
    fun `el resto de la API no se limita`() {
        repeat(20) { assertEquals(200, call("GET", "/api/v1/courses", ip = "6.6.6.6").status) }
    }
}

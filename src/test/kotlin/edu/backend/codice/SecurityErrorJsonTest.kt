package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Los handlers de 401/403 de Spring Security escriben el JSON a mano, fuera de Spring MVC.
 * Con un ObjectMapper sin el módulo de fechas, el Instant de ErrorResponse hacía fallar la respuesta
 * y el cliente recibía la página HTML de Tomcat en vez del JSON.
 */
class SecurityErrorJsonTest {

    @Test
    fun `el error de seguridad se serializa con la fecha en ISO-8601`() {
        val error = ErrorResponse(
            timestamp = Instant.parse("2026-10-13T20:00:00Z"),
            status = 401,
            error = "Unauthorized",
            message = "Autenticación requerida",
            path = "/api/v1/apuntes",
        )

        val json = securityErrorMapper.writeValueAsString(error)

        assertTrue(json.contains("\"timestamp\":\"2026-10-13T20:00:00Z\""), json)
        assertTrue(json.contains("\"status\":401"), json)
        assertTrue(json.contains("\"path\":\"/api/v1/apuntes\""), json)
    }
}

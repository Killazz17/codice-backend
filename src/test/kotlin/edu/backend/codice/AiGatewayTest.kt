package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest

// IA-01: AiGateway es el contexto del patrón Strategy: elige el proveedor por nombre y reintenta una vez.
class AiGatewayTest {

    /** Estrategia de prueba: cada llamada consume el siguiente resultado de la lista. */
    private class ScriptedClient(override val provider: String, vararg steps: () -> AiResponse) : AiClient {
        private val remaining = steps.toMutableList()
        var calls = 0

        override fun generate(request: AiRequest): AiResponse {
            calls++
            return remaining.removeAt(0)()
        }
    }

    private val request = AiRequest(AiPurpose.TRANSCRIPTION, "v1", "prompt")
    private fun ok(json: String = "{}") = AiResponse(json, "fake", "m")
    private fun transient() = AiUnavailableException("timeout")

    @Test
    fun `elige el proveedor por nombre sin importar mayusculas ni espacios`() {
        val alfa = ScriptedClient("alfa", { ok() })
        val beta = ScriptedClient("beta", { ok() })

        val gateway = AiGateway(listOf(alfa, beta), " BETA ")
        gateway.generate(request) { it }

        assertEquals("beta", gateway.providerInUse)
        assertEquals(0, alfa.calls)
        assertEquals(1, beta.calls)
    }

    @Test
    fun `un proveedor desconocido impide arrancar y lista los disponibles`() {
        val error = assertThrows<IllegalStateException> {
            AiGateway(listOf(ScriptedClient("alfa"), ScriptedClient("beta")), "claude")
        }

        assertTrue(error.message!!.contains("claude"))
        assertTrue(error.message!!.contains("alfa") && error.message!!.contains("beta"))
    }

    @Test
    fun `reintenta una vez si el proveedor falla de forma pasajera`() {
        val client = ScriptedClient("alfa", { throw transient() }, { ok("{\"bloques\":[]}") })

        val json = AiGateway(listOf(client), "alfa").generate(request) { it.json }

        assertEquals("{\"bloques\":[]}", json)
        assertEquals(2, client.calls)
    }

    @Test
    fun `no reintenta si el error no es pasajero`() {
        val client = ScriptedClient("alfa", { throw AiUnavailableException("sin llave", retryable = false) })

        assertThrows<AiUnavailableException> { AiGateway(listOf(client), "alfa").generate(request) { it } }

        assertEquals(1, client.calls)
    }

    @Test
    fun `si falla en todos los intentos sale un AiUnavailableException`() {
        val client = ScriptedClient("alfa", { throw transient() }, { throw transient() }, { throw transient() })

        assertThrows<AiUnavailableException> { AiGateway(listOf(client), "alfa").generate(request) { it } }

        assertEquals(AiGateway.MAX_ATTEMPTS, client.calls)
    }

    @Test
    fun `se recupera si el proveedor responde en el tercer intento`() {
        val client = ScriptedClient("alfa", { throw transient() }, { throw transient() }, { ok("{\"bloques\":[]}") })

        val json = AiGateway(listOf(client), "alfa").generate(request) { it.json }

        assertEquals("{\"bloques\":[]}", json)
        assertEquals(3, client.calls)
    }

    @Test
    fun `espera entre un intento y otro, cada vez más`() {
        val client = ScriptedClient("alfa", { throw transient() }, { throw transient() }, { throw transient() })
        val started = System.nanoTime()

        assertThrows<AiUnavailableException> { AiGateway(listOf(client), "alfa", retryDelayMs = 40).generate(request) { it } }

        // Esperas de 40 ms y 80 ms entre los tres intentos; después del último no se espera.
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs >= 110, "esperó solo $elapsedMs ms")
    }

    @Test
    fun `una respuesta que el parser rechaza tambien se reintenta`() {
        val client = ScriptedClient("alfa", { ok("basura") }, { ok("{\"valido\":true}") })

        val json = AiGateway(listOf(client), "alfa").generate(request) {
            if (!it.json.startsWith("{")) throw AiUnavailableException("JSON ilegible")
            it.json
        }

        assertEquals("{\"valido\":true}", json)
        assertEquals(2, client.calls)
    }

    @Test
    fun `el cliente simulado devuelve bloques con un bloque bajo el umbral`() {
        val response = MockAiClient().generate(request)

        assertEquals("mock", response.provider)
        assertTrue(response.json.contains("0.41"))
    }
}

// Un fallo de la IA llega al cliente como 503 con un mensaje que no expone detalles internos.
class AiUnavailableHandlerTest {

    @Test
    fun `AiUnavailableException se responde como 503 sin detalles internos`() {
        val response = GlobalExceptionHandler().handleAiUnavailable(
            AiUnavailableException("Gemini respondió HTTP 500"),
            MockHttpServletRequest("POST", "/api/v1/apuntes/extraer"),
        )

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
        assertEquals(503, response.body!!.status)
        assertFalse(response.body!!.message!!.contains("Gemini"))
        assertEquals("/api/v1/apuntes/extraer", response.body!!.path)
    }
}

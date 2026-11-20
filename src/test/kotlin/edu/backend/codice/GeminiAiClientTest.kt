package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

// IA-01: la estrategia de Gemini contra un servidor simulado; ninguna prueba llama al proveedor real.
class GeminiAiClientTest {

    private val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent"
    private val request = AiRequest(
        purpose = AiPurpose.TRANSCRIPTION,
        promptVersion = "v1",
        prompt = "Transcribe",
        attachments = listOf(AiAttachment("image/jpeg", byteArrayOf(1, 2, 3))),
    )

    private val backupUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-respaldo:generateContent"

    private fun clientWith(apiKey: String = "llave-secreta", fallback: String = ""): Pair<GeminiAiClient, MockRestServiceServer> {
        val client = GeminiAiClient(apiKey, "gemini-test", 5, fallback)
        return client to MockRestServiceServer.bindTo(client.restTemplate).build()
    }

    private fun okBody(text: String) = """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""

    @Test
    fun `envia la llave en el header y no en la URL, y devuelve el texto de la IA`() {
        val (client, server) = clientWith()
        server.expect(requestTo(url))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-goog-api-key", "llave-secreta"))
            .andExpect(jsonPath("$.contents[0].parts[0].text").value("Transcribe"))
            .andExpect(jsonPath("$.contents[0].parts[1].inline_data.mime_type").value("image/jpeg"))
            .andExpect(jsonPath("$.contents[0].parts[1].inline_data.data").value("AQID"))
            .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
            .andRespond(
                withSuccess(
                    """{"candidates":[{"content":{"parts":[{"text":"{\"bloques\":[]}"}]}}]}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val response = client.generate(request)

        assertEquals("{\"bloques\":[]}", response.json)
        assertEquals("gemini", response.provider)
        assertEquals("gemini-test", response.model)
        server.verify()
    }

    @Test
    fun `sin llave falla de inmediato, sin reintento y sin llamar a Google`() {
        val (client, server) = clientWith(apiKey = "")

        val error = assertThrows<AiUnavailableException> { client.generate(request) }

        assertFalse(error.retryable)
        assertTrue(error.message!!.contains("AI_API_KEY"))
        server.verify()
    }

    @Test
    fun `un error 5xx o 429 de Google es pasajero y se puede reintentar`() {
        listOf(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS).forEach { status ->
            val (client, server) = clientWith()
            server.expect(requestTo(url)).andRespond(withStatus(status))

            val error = assertThrows<AiUnavailableException> { client.generate(request) }

            assertTrue(error.retryable, "HTTP ${status.value()} debería poder reintentarse")
        }
    }

    @Test
    fun `una llave rechazada o un modelo retirado no se reintentan`() {
        listOf(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND).forEach { status ->
            val (client, server) = clientWith()
            server.expect(requestTo(url)).andRespond(withStatus(status))

            val error = assertThrows<AiUnavailableException> { client.generate(request) }

            assertFalse(error.retryable, "HTTP ${status.value()} no debería reintentarse")
        }
    }

    @Test
    fun `una respuesta sin candidatos es un error y no texto inventado`() {
        val (client, server) = clientWith()
        server.expect(requestTo(url)).andRespond(withSuccess("""{"promptFeedback":{"blockReason":"SAFETY"}}""", MediaType.APPLICATION_JSON))

        assertThrows<AiUnavailableException> { client.generate(request) }
    }

    @Test
    fun `el motivo que da Google queda en el mensaje para diagnosticar, y un cuerpo que no es JSON se ignora`() {
        val (client, server) = clientWith()
        server.expect(requestTo(url)).andRespond(
            withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":{"code":404,"message":"models/gemini-test is not found for API version v1beta","status":"NOT_FOUND"}}""")
        )
        val withReason = assertThrows<AiUnavailableException> { client.generate(request) }
        assertTrue(withReason.message!!.contains("HTTP 404"))
        assertTrue(withReason.message!!.contains("models/gemini-test is not found"))

        val (client2, server2) = clientWith()
        server2.expect(requestTo(url)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>Bad gateway</html>"))
        val withoutReason = assertThrows<AiUnavailableException> { client2.generate(request) }
        assertEquals("Gemini respondió HTTP 502", withoutReason.message)
    }

    @Test
    fun `si el modelo principal agota su cuota se usa el de respaldo y la respuesta dice cual respondio`() {
        val (client, server) = clientWith(fallback = "gemini-respaldo")
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(requestTo(backupUrl)).andRespond(withSuccess(okBody("respaldo"), MediaType.APPLICATION_JSON))

        val response = client.generate(request)

        assertEquals("respaldo", response.json)
        assertEquals("gemini-respaldo", response.model)
        server.verify()
    }

    @Test
    fun `el respaldo tambien cubre un modelo retirado (404) y uno saturado (503)`() {
        listOf(HttpStatus.NOT_FOUND, HttpStatus.SERVICE_UNAVAILABLE).forEach { status ->
            val (client, server) = clientWith(fallback = "gemini-respaldo")
            server.expect(requestTo(url)).andRespond(withStatus(status))
            server.expect(requestTo(backupUrl)).andRespond(withSuccess(okBody("ok"), MediaType.APPLICATION_JSON))

            assertEquals("gemini-respaldo", client.generate(request).model, "HTTP ${status.value()} debería usar el respaldo")
        }
    }

    @Test
    fun `una llave o una peticion mala no prueba el respaldo, fallaria igual`() {
        listOf(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN).forEach { status ->
            val (client, server) = clientWith(fallback = "gemini-respaldo")
            server.expect(requestTo(url)).andRespond(withStatus(status))

            assertThrows<AiUnavailableException> { client.generate(request) }

            server.verify()
        }
    }

    @Test
    fun `sin respaldo configurado, o igual al principal, no se llama a otro modelo`() {
        listOf("", "gemini-test").forEach { fallback ->
            val (client, server) = clientWith(fallback = fallback)
            server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))

            val error = assertThrows<AiUnavailableException> { client.generate(request) }

            assertEquals(429, error.httpStatus)
            server.verify()
        }
    }

    @Test
    fun `si el respaldo tambien falla sale el error del respaldo`() {
        val (client, server) = clientWith(fallback = "gemini-respaldo")
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS))
        server.expect(requestTo(backupUrl)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        val error = assertThrows<AiUnavailableException> { client.generate(request) }

        assertEquals(503, error.httpStatus)
        assertTrue(error.retryable)
    }

    @Test
    fun `el mensaje de error no incluye la llave`() {
        val (client, server) = clientWith()
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.FORBIDDEN))

        val error = assertThrows<AiUnavailableException> { client.generate(request) }

        assertFalse(error.message!!.contains("llave-secreta"))
    }
}

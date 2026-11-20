package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import edu.backend.codice.exception.InvalidInputException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

// IA-03: transcripción de apuntes con bloques, confianza y revisión obligatoria bajo el umbral.
class TranscriptionServiceTest {

    /** Proveedor de prueba: devuelve siempre el mismo JSON y guarda las peticiones que recibe. */
    private class FixedClient(private val json: String) : AiClient {
        override val provider = "fijo"
        val requests = mutableListOf<AiRequest>()

        override fun generate(request: AiRequest): AiResponse {
            requests += request
            return AiResponse(json, provider, "modelo-x")
        }
    }

    // Los primeros bytes reales de cada formato: el servicio no se fía del tipo que declara el cliente.
    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray()

    private fun serviceWith(json: String, threshold: Double = 0.70): Pair<TranscriptionService, FixedClient> {
        val client = FixedClient(json)
        return TranscriptionService(AiGateway(listOf(client), "fijo"), threshold) to client
    }

    @Test
    fun `los bloques bajo el umbral quedan marcados para revision`() {
        val service = TranscriptionService(AiGateway(listOf(MockAiClient()), "mock"), 0.70)

        val result = service.transcribe(photo, "image/jpeg")

        val blocks = result.paginas.single().bloques
        assertEquals(3, blocks.size)
        assertEquals(listOf(false, true, false), blocks.map { it.requiereRevision })
        assertEquals(1, result.bloquesPorRevisar)
        assertEquals("mock", result.proveedor)
        assertEquals(TranscriptionService.PROMPT_VERSION, result.promptVersion)
        assertEquals(blocks.joinToString("\n\n") { it.texto }, result.textoCompleto)
    }

    @Test
    fun `el umbral es configurable`() {
        val service = TranscriptionService(AiGateway(listOf(MockAiClient()), "mock"), 0.95)

        assertEquals(3, service.transcribe(photo, "image/jpeg").bloquesPorRevisar)
    }

    @Test
    fun `una confianza ausente cuenta como cero y una fuera de rango se acota`() {
        val (service, _) = serviceWith(
            """{"bloques":[{"texto":"sin confianza"},{"texto":"de mas","confianza":1.7,"tipo":"titulo"},{"texto":"negativa","confianza":-2}]}"""
        )

        val blocks = service.transcribe(png, "image/png").paginas.single().bloques

        assertEquals(listOf(0.0, 1.0, 0.0), blocks.map { it.confianza })
        assertEquals(listOf(true, false, true), blocks.map { it.requiereRevision })
        assertEquals(listOf("parrafo", "titulo", "parrafo"), blocks.map { it.tipo })
    }

    @Test
    fun `los bloques en blanco se descartan`() {
        val (service, _) = serviceWith("""{"bloques":[{"texto":"   ","confianza":0.9},{"texto":"util","confianza":0.9}]}""")

        assertEquals(listOf("util"), service.transcribe(photo, "image/jpeg").paginas.single().bloques.map { it.texto })
    }

    @Test
    fun `una respuesta sin texto es un error de la IA y se reintenta`() {
        val (service, client) = serviceWith("""{"bloques":[]}""")

        assertThrows<AiUnavailableException> { service.transcribe(photo, "image/jpeg") }

        assertEquals(AiGateway.MAX_ATTEMPTS, client.requests.size)
    }

    @Test
    fun `un JSON ilegible de la IA es un error y no texto inventado`() {
        val (service, _) = serviceWith("esto no es json")

        assertThrows<AiUnavailableException> { service.transcribe(photo, "image/jpeg") }
    }

    @Test
    fun `sin archivo no se llama a la IA`() {
        val (service, client) = serviceWith("""{"bloques":[]}""")

        assertThrows<InvalidInputException> { service.transcribe(null, "image/jpeg") }
        assertThrows<InvalidInputException> { service.transcribe(ByteArray(0), "image/jpeg") }

        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `solo se aceptan fotos JPEG, PNG o WebP`() {
        val (service, client) = serviceWith("""{"bloques":[]}""")

        assertThrows<InvalidInputException> { service.transcribe(photo, "application/pdf") }
        assertThrows<InvalidInputException> { service.transcribe(photo, null) }

        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `un archivo cuyo contenido no coincide con el tipo declarado se rechaza sin llamar a la IA`() {
        val (service, client) = serviceWith("""{"bloques":[]}""")
        val texto = "esto no es una imagen".toByteArray()

        // Un PNG declarado como JPEG, un texto declarado como PNG y un WebP cortado.
        assertThrows<InvalidInputException> { service.transcribe(png, "image/jpeg") }
        assertThrows<InvalidInputException> { service.transcribe(texto, "image/png") }
        assertThrows<InvalidInputException> { service.transcribe("RIFF".toByteArray(), "image/webp") }

        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `un WebP de verdad se acepta`() {
        val (service, client) = serviceWith("""{"bloques":[{"texto":"hola","confianza":0.9}]}""")

        service.transcribe(webp, "image/webp")

        assertEquals("image/webp", client.requests.single().attachments.single().mimeType)
    }

    @Test
    fun `envia la foto y el prompt versionado al proveedor`() {
        val (service, client) = serviceWith("""{"bloques":[{"texto":"hola","confianza":0.9}]}""")

        service.transcribe(png, "image/PNG; charset=binary")

        val sent = client.requests.single()
        assertEquals(AiPurpose.TRANSCRIPTION, sent.purpose)
        assertEquals(TranscriptionService.PROMPT_VERSION, sent.promptVersion)
        assertEquals("image/png", sent.attachments.single().mimeType)
        assertTrue(sent.prompt.contains("bloques"))
        assertFalse(sent.prompt.isBlank())
    }

    @Test
    fun `el prompt pide reconstruir el texto cortado y marcarlo para revision`() {
        val (service, client) = serviceWith("""{"bloques":[{"texto":"hola","confianza":0.9}]}""")

        service.transcribe(png, "image/png")

        val prompt = client.requests.single().prompt
        // Una foto recortada no debe dejar palabras truncadas: se reconstruyen con el contexto...
        assertTrue(prompt.contains("cortada por el borde"))
        // ...pero como es una deducción, el bloque queda bajo el umbral de revisión (0.70) para que el estudiante lo confirme.
        assertTrue(prompt.contains("0.6 como máximo"))
        assertEquals("transcripcion-v2", TranscriptionService.PROMPT_VERSION)
    }
}

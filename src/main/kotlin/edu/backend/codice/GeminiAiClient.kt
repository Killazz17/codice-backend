package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import java.util.Base64

/**
 * Estrategia de IA para Gemini (nivel gratuito de Google AI Studio). Sirve para fotos y PDF por igual: ambos
 * viajan como archivo en línea junto al prompt, y se pide la respuesta como JSON.
 *
 * La llave va en el header `x-goog-api-key`, no en la URL: así no queda en los logs de acceso.
 * El modelo se cambia con AI_MODEL sin tocar el código, por si Google retira el actual.
 */
@Component
class GeminiAiClient(
    @Value("\${ai.api-key:}") private val apiKey: String,
    @Value("\${ai.model:gemini-3.5-flash}") private val model: String,
    @Value("\${ai.timeout-seconds:60}") timeoutSeconds: Long,
    // Modelo de respaldo (vacío = sin respaldo). Se prueba si el principal falla por cuota, retiro o saturación.
    @Value("\${ai.fallback-model:}") private val fallbackModel: String = "",
) : AiClient {

    override val provider = "gemini"

    private val log = LoggerFactory.getLogger(GeminiAiClient::class.java)

    // internal var: las pruebas lo cambian por un RestTemplate conectado a MockRestServiceServer.
    internal var restTemplate: RestTemplate = RestTemplate(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(CONNECT_TIMEOUT_MS)
            setReadTimeout((timeoutSeconds * 1000).toInt())
        }
    )

    override fun generate(request: AiRequest): AiResponse {
        if (apiKey.isBlank()) {
            // Sin llave no tiene sentido reintentar. Se avisa con un error controlado, nunca con texto inventado.
            throw AiUnavailableException("La IA no está configurada: falta AI_API_KEY", retryable = false)
        }
        try {
            return call(model, request)
        } catch (e: AiUnavailableException) {
            val backup = fallbackModel.trim()
            if (backup.isEmpty() || backup == model || !isModelProblem(e)) throw e
            // Cada modelo tiene su propia cuota: si el principal se agotó (429), se retiró (404) o está saturado (5xx),
            // el de respaldo suele responder. Un 400/401/403 (llave o petición mala) fallaría igual, así que no se prueba.
            log.warn("Gemini: el modelo {} falló ({}); se prueba el de respaldo {}", model, e.message, backup)
            return call(backup, request)
        }
    }

    private fun isModelProblem(e: AiUnavailableException): Boolean =
        e.httpStatus.let { it == null || it == 404 || it == 429 || it >= 500 }

    private fun call(model: String, request: AiRequest): AiResponse {
        val parts = mutableListOf<Map<String, Any>>(mapOf("text" to request.prompt))
        request.attachments.forEach {
            parts += mapOf(
                "inline_data" to mapOf("mime_type" to it.mimeType, "data" to Base64.getEncoder().encodeToString(it.data))
            )
        }
        val body = mapOf(
            "contents" to listOf(mapOf("parts" to parts)),
            "generationConfig" to mapOf("responseMimeType" to "application/json", "temperature" to 0.1),
        )
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("x-goog-api-key", apiKey)
        }

        val response = try {
            restTemplate.postForObject("$BASE_URL/$model:generateContent", HttpEntity(body, headers), Map::class.java)
        } catch (e: HttpStatusCodeException) {
            val status = e.statusCode.value()
            // 429 y 5xx suelen ser pasajeros; un 400/401/403/404 (llave mala, modelo retirado) no se arregla reintentando.
            // El motivo de Google va al log para diagnosticar (p. ej. "model not found"); al cliente de la API no le llega.
            val reason = providerReason(e)
            throw AiUnavailableException(
                "Gemini respondió HTTP $status" + if (reason.isEmpty()) "" else ": $reason", e,
                retryable = status == 429 || status >= 500, httpStatus = status,
            )
        } catch (e: RestClientException) {
            throw AiUnavailableException("No se pudo contactar a Gemini (${e.javaClass.simpleName})", e)
        }

        val text = extractText(response) ?: throw AiUnavailableException("Gemini no devolvió texto")
        return AiResponse(json = text, provider = provider, model = model)
    }

    // Google responde los errores como {"error":{"code":404,"message":"...","status":"NOT_FOUND"}}. Es texto del proveedor,
    // nunca incluye la llave; se recorta por si es largo y se ignora si el cuerpo no es ese JSON.
    private fun providerReason(e: HttpStatusCodeException): String = try {
        ObjectMapper().readTree(e.responseBodyAsString).path("error").path("message").asText("").trim().take(MAX_REASON)
    } catch (ex: Exception) {
        ""
    }

    private fun extractText(response: Map<*, *>?): String? {
        val candidates = response?.get("candidates") as? List<*> ?: return null
        val content = (candidates.firstOrNull() as? Map<*, *>)?.get("content") as? Map<*, *> ?: return null
        val parts = content["parts"] as? List<*> ?: return null
        return parts.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            .joinToString("")
            .takeIf { it.isNotBlank() }
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MAX_REASON = 200
    }
}

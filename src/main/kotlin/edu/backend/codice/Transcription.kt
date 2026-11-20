package edu.backend.codice

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import edu.backend.codice.exception.AiUnavailableException
import edu.backend.codice.exception.InvalidInputException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/** Un bloque de texto transcrito. [requiereRevision] es true si su confianza queda bajo el umbral (0.70 por defecto). */
data class TranscriptionBlockDTO(
    val texto: String,
    val confianza: Double,
    val tipo: String,
    val requiereRevision: Boolean,
)

data class TranscriptionPageDTO(val indice: Int, val bloques: List<TranscriptionBlockDTO>)

data class TranscriptionResultDTO(
    val proveedor: String,
    val modelo: String,
    val promptVersion: String,
    val textoCompleto: String,
    val paginas: List<TranscriptionPageDTO>,
    val bloquesPorRevisar: Int,
)

/**
 * Transcribe la foto de un apunte (IA-03, llamada 2). No sabe qué proveedor de IA hay detrás: habla con AiGateway.
 * Si la IA falla no devuelve texto de relleno: lanza AiUnavailableException y el estudiante transcribe a mano.
 */
@Service
class TranscriptionService(
    private val ai: AiGateway,
    @Value("\${ai.review-threshold:0.70}") private val reviewThreshold: Double,
) {
    private val mapper = ObjectMapper()

    fun transcribe(fileBytes: ByteArray?, mimeType: String?): TranscriptionResultDTO {
        if (fileBytes == null || fileBytes.isEmpty()) {
            throw InvalidInputException("Debe enviar la foto del apunte en el campo 'file'")
        }
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime == null || mime !in ALLOWED_TYPES) {
            throw InvalidInputException("Formato no soportado: la foto debe ser JPEG, PNG o WebP")
        }
        // El tipo lo declara el cliente: los primeros bytes confirman que de verdad es una imagen de ese formato.
        if (!hasImageSignature(fileBytes, mime)) {
            throw InvalidInputException("El archivo no es una imagen $mime válida")
        }

        val request = AiRequest(
            purpose = AiPurpose.TRANSCRIPTION,
            promptVersion = PROMPT_VERSION,
            prompt = PROMPT,
            attachments = listOf(AiAttachment(mime, fileBytes)),
        )
        return ai.generate(request) { parse(it) }
    }

    private fun parse(response: AiResponse): TranscriptionResultDTO {
        val rawBlocks = try {
            mapper.readTree(response.json).path("bloques")
        } catch (e: JsonProcessingException) {
            throw AiUnavailableException("La IA devolvió un JSON ilegible", e)
        }
        if (!rawBlocks.isArray) throw AiUnavailableException("La IA no devolvió la lista de bloques")

        val blocks = rawBlocks.mapNotNull { node ->
            val text = node.path("texto").asText("").trim()
            if (text.isEmpty()) return@mapNotNull null
            // Sin confianza válida se asume 0: un bloque dudoso va a revisión en vez de pasar como seguro.
            val confidence = node.path("confianza").asDouble(0.0).coerceIn(0.0, 1.0)
            TranscriptionBlockDTO(
                texto = text,
                confianza = confidence,
                tipo = node.path("tipo").asText("").ifBlank { "parrafo" },
                requiereRevision = confidence < reviewThreshold,
            )
        }
        if (blocks.isEmpty()) throw AiUnavailableException("La IA no pudo leer texto en la imagen")

        return TranscriptionResultDTO(
            proveedor = response.provider,
            modelo = response.model,
            promptVersion = PROMPT_VERSION,
            textoCompleto = blocks.joinToString("\n\n") { it.texto },
            paginas = listOf(TranscriptionPageDTO(indice = 0, bloques = blocks)),
            bloquesPorRevisar = blocks.count { it.requiereRevision },
        )
    }

    private fun hasImageSignature(bytes: ByteArray, mime: String): Boolean = when (mime) {
        "image/jpeg" -> bytes.startsWith(0xFF, 0xD8, 0xFF)
        "image/png" -> bytes.startsWith(0x89, 0x50, 0x4E, 0x47)
        // WebP: "RIFF" + 4 bytes de tamaño + "WEBP".
        "image/webp" -> bytes.size >= 12 && bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
            String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
        else -> false
    }

    private fun ByteArray.startsWith(vararg signature: Int): Boolean =
        size >= signature.size && signature.indices.all { this[it] == signature[it].toByte() }

    companion object {
        // v2: las fotos mal encuadradas ya no dejan texto truncado: la IA reconstruye lo cortado y lo marca para revisión.
        const val PROMPT_VERSION = "transcripcion-v2"
        private val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        // El tope de 0.6 para el texto reconstruido queda bajo el umbral de revisión (0.70): esos bloques los confirma el estudiante.
        private const val PROMPT = """Eres un transcriptor de apuntes de estudiantes universitarios.
Transcribe el texto de la imagen (manuscrito o impreso), en español, y no añadas contenido que no esté en la foto.
Divide el texto en bloques (párrafo, título, lista, código o fórmula).
La foto puede estar mal encuadrada: si una palabra o una línea queda cortada por el borde de la imagen, no la dejes truncada ni la omitas. Léela con ayuda del contexto (la frase, el tema del apunte y el vocabulario de la materia) y escribe la palabra o la frase completa y coherente.
Como eso es una deducción y no una lectura, el bloque que contenga texto reconstruido debe llevar "confianza" de 0.6 como máximo, para que el estudiante lo revise.
Reconstruye solo cuando el contexto lo permita con razonable seguridad. Si no hay manera de saber qué decía, escribe "?" en su lugar: nunca inventes ideas nuevas.
Para el resto de bloques estima "confianza" entre 0 y 1: más baja si la letra es dudosa o ilegible. Si no se lee una palabra, escribe "?" en su lugar.
Responde SOLO con JSON, sin texto adicional, con esta forma exacta:
{"bloques":[{"texto":"...","confianza":0.0,"tipo":"parrafo"}]}"""
    }
}

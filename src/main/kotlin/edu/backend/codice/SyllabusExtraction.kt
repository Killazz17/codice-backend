package edu.backend.codice

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import edu.backend.codice.exception.AiUnavailableException
import edu.backend.codice.exception.InvalidInputException
import edu.backend.codice.exception.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/*
 * IA-02 (llamada 1): el profesor sube el PDF del programa y la IA propone el árbol módulo → subtema → objetivos.
 * Las claves coinciden con las del temario de la API (modules, subtopics, objectives) y con lo que ya lee la app.
 * Cada nodo trae su confianza (0 a 1) para que la pantalla "Revisar el temario" marque lo dudoso.
 */

data class ExtractedSubtopicDTO(
    val code: String,
    val title: String,
    val confidence: Double,
    val objectives: List<String>,
)

data class ExtractedModuleDTO(
    val number: Int,
    val title: String,
    val confidence: Double,
    val subtopics: List<ExtractedSubtopicDTO>,
)

/** El árbol propuesto. Nada se publica: el profesor lo revisa y lo guarda con los endpoints del temario (API-05). */
data class SyllabusExtractionDTO(
    val courseId: Long,
    val provider: String,
    val model: String,
    val promptVersion: String,
    val modules: List<ExtractedModuleDTO>,
)

interface SyllabusExtractionService {
    fun extract(courseId: Long, pdfBytes: ByteArray?, mimeType: String?): SyllabusExtractionDTO
}

@Service
@Transactional
class AiSyllabusExtractionService(
    private val courseRepository: CourseRepository,
    private val access: AccessGuard,
    private val ai: AiGateway,
) : SyllabusExtractionService {

    private val mapper = ObjectMapper()

    override fun extract(courseId: Long, pdfBytes: ByteArray?, mimeType: String?): SyllabusExtractionDTO {
        val course = courseRepository.findById(courseId).orElseThrow {
            NotFoundException("Curso no encontrado con ID: $courseId")
        }
        // Primero el dueño y después el archivo: a quien no es dueño no se le dice nada del contenido.
        access.requireOwner(course, access.currentUser())
        val pdf = requirePdf(pdfBytes, mimeType)

        val request = AiRequest(
            purpose = AiPurpose.SYLLABUS_EXTRACTION,
            promptVersion = PROMPT_VERSION,
            prompt = PROMPT,
            attachments = listOf(AiAttachment("application/pdf", pdf)),
        )
        val (tree, rawOutput) = ai.generate(request) { parse(courseId, it) }

        // Salida cruda en el jsonb del curso, con quién y cómo se generó. Si la IA falla no se llega aquí: el curso
        // queda como estaba, sin temario a medias.
        course.extractedSyllabus = mapOf(
            "provider" to tree.provider,
            "model" to tree.model,
            "promptVersion" to tree.promptVersion,
            "extractedAt" to Instant.now().toString(),
            "output" to rawOutput,
        )
        courseRepository.save(course)
        return tree
    }

    private fun requirePdf(bytes: ByteArray?, mimeType: String?): ByteArray {
        if (bytes == null || bytes.isEmpty()) throw InvalidInputException("Debe enviar el PDF del programa en el campo 'file'")
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        // El tipo lo declara el cliente; la firma "%PDF-" (en los primeros 1 KB) confirma que de verdad lo es.
        val header = String(bytes, 0, minOf(bytes.size, PDF_SIGNATURE_WINDOW), Charsets.ISO_8859_1)
        if (mime != "application/pdf" || !header.contains("%PDF-")) {
            throw InvalidInputException("El archivo debe ser un PDF válido")
        }
        return bytes
    }

    private fun parse(courseId: Long, response: AiResponse): Pair<SyllabusExtractionDTO, Map<String, Any?>> {
        val root = try {
            mapper.readTree(response.json)
        } catch (e: JsonProcessingException) {
            throw AiUnavailableException("La IA devolvió un JSON ilegible", e)
        }
        if (!root.isObject) throw AiUnavailableException("La IA devolvió una respuesta que no es un objeto JSON")

        val usedNumbers = mutableSetOf<Int>()
        val modules = root.path("modules").mapNotNull { node -> parseModule(node, usedNumbers) }
        if (modules.isEmpty()) throw AiUnavailableException("La IA no encontró módulos en el PDF")

        val raw = mapper.convertValue(root, object : TypeReference<Map<String, Any?>>() {})
        val tree = SyllabusExtractionDTO(courseId, response.provider, response.model, PROMPT_VERSION, modules)
        return tree to raw
    }

    // Los números de módulo y los códigos de subtema deben ser únicos (la base los exige): si la IA repite o
    // omite uno se asigna el siguiente libre, y el profesor lo ajusta en la revisión.
    private fun parseModule(node: JsonNode, usedNumbers: MutableSet<Int>): ExtractedModuleDTO? {
        val title = node.path("title").asText("").trim().take(MAX_TITLE)
        if (title.isEmpty()) return null
        val number = node.path("number").asInt(0).takeIf { it > 0 && it !in usedNumbers }
            ?: ((usedNumbers.maxOrNull() ?: 0) + 1)
        usedNumbers += number

        val usedCodes = mutableSetOf<String>()
        val subtopics = node.path("subtopics").mapIndexedNotNull { index, sub ->
            val subTitle = sub.path("title").asText("").trim().take(MAX_TITLE)
            if (subTitle.isEmpty()) return@mapIndexedNotNull null
            val code = sub.path("code").asText("").trim().take(MAX_CODE).takeIf { it.isNotEmpty() && it !in usedCodes }
                ?: generateSequence(index + 1) { it + 1 }.map { "$number.$it" }.first { it !in usedCodes }
            usedCodes += code
            ExtractedSubtopicDTO(
                code = code,
                title = subTitle,
                confidence = confidenceOf(sub),
                objectives = sub.path("objectives").mapNotNull { it.asText("").trim().take(MAX_OBJECTIVE).ifEmpty { null } },
            )
        }
        return ExtractedModuleDTO(number, title, confidenceOf(node), subtopics)
    }

    // Sin confianza válida se asume 0: lo dudoso se muestra para revisar en vez de pasar como seguro.
    private fun confidenceOf(node: JsonNode) = node.path("confidence").asDouble(0.0).coerceIn(0.0, 1.0)

    companion object {
        const val PROMPT_VERSION = "temario-v1"
        private const val PDF_SIGNATURE_WINDOW = 1024
        // Los mismos máximos que validan los DTOs del temario (API-05).
        private const val MAX_TITLE = 255
        private const val MAX_CODE = 20
        private const val MAX_OBJECTIVE = 500

        private const val PROMPT = """Eres un asistente que lee el programa (temario) de un curso universitario en PDF.
Extrae su estructura en módulos, subtemas y objetivos de aprendizaje, en español y en el mismo orden del documento.
No inventes contenido que no esté en el PDF.
Cada subtema lleva un "code" con el formato "módulo.posición", por ejemplo "1.1".
Para cada módulo y cada subtema estima "confidence" entre 0 y 1: más baja si el texto del PDF es ambiguo o está incompleto.
Responde SOLO con JSON, sin texto adicional, con esta forma exacta:
{"modules":[{"number":1,"title":"...","confidence":0.0,"subtopics":[{"code":"1.1","title":"...","confidence":0.0,"objectives":["..."]}]}]}"""
    }
}

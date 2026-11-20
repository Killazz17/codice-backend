package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/*
 * Patrón Strategy para los proveedores de IA (IA-01).
 *
 * Cada proveedor (Gemini, Claude, ...) es una estrategia: una clase que implementa AiClient. El resto del backend
 * no sabe cuál se usa; solo habla con AiGateway. Cambiar de proveedor es cambiar la variable AI_PROVIDER, y agregar
 * uno nuevo es crear una clase @Component que implemente AiClient: ningún servicio de negocio se toca.
 */

/** Para qué se llama a la IA. El cliente simulado lo usa para devolver un JSON con la forma de cada llamada. */
enum class AiPurpose { TRANSCRIPTION, SYLLABUS_EXTRACTION, NOTE_AUDIT, QUIZ_GENERATION }

/** Un archivo (foto o PDF) que acompaña al prompt. */
class AiAttachment(val mimeType: String, val data: ByteArray)

data class AiRequest(
    val purpose: AiPurpose,
    /** Versión del prompt: se guarda junto al resultado para saber con qué instrucciones se generó. */
    val promptVersion: String,
    val prompt: String,
    val attachments: List<AiAttachment> = emptyList(),
    /** Datos estructurados que acompañan al prompt. El cliente simulado los usa para armar una respuesta coherente. */
    val metadata: Map<String, Any?> = emptyMap(),
)

/** Respuesta del proveedor: siempre un JSON en texto, más quién y con qué modelo lo produjo. */
data class AiResponse(val json: String, val provider: String, val model: String)

/** Contrato de una estrategia de IA. Un fallo del proveedor se informa con AiUnavailableException, nunca con texto inventado. */
interface AiClient {
    /** Nombre con el que se elige en AI_PROVIDER (en minúsculas). */
    val provider: String

    fun generate(request: AiRequest): AiResponse
}

/**
 * Contexto del patrón Strategy: elige el proveedor configurado y es la única puerta hacia la IA.
 * Reintenta, con una espera creciente entre intentos, si el proveedor falla de forma pasajera (timeout, 429, 503) o
 * devuelve algo que no se puede leer: un modelo saturado suele recuperarse en unos segundos, pero no en el mismo instante.
 */
@Service
class AiGateway(
    clients: List<AiClient>,
    @Value("\${ai.provider:mock}") providerName: String,
    // Espera base entre intentos; se multiplica por el número de intento. Las pruebas construyen el gateway sin espera.
    @Value("\${ai.retry-delay-ms:1500}") private val retryDelayMs: Long = 0,
) {
    private val log = LoggerFactory.getLogger(AiGateway::class.java)

    private val client: AiClient = clients.firstOrNull { it.provider.equals(providerName.trim(), ignoreCase = true) }
        ?: throw IllegalStateException(
            "AI_PROVIDER '$providerName' no existe. Proveedores disponibles: ${clients.joinToString { it.provider }}"
        )

    val providerInUse: String get() = client.provider

    /**
     * Llama al proveedor y convierte su respuesta con [parse]. Si el proveedor falla o [parse] rechaza la respuesta,
     * se reintenta (hasta [MAX_ATTEMPTS] intentos en total); si todos fallan sale un AiUnavailableException (503).
     */
    fun <T> generate(request: AiRequest, parse: (AiResponse) -> T): T {
        var lastError: AiUnavailableException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return parse(client.generate(request))
            } catch (e: AiUnavailableException) {
                log.warn("IA {} falló en el intento {} de {}: {}", client.provider, attempt + 1, MAX_ATTEMPTS, e.message)
                if (!e.retryable) throw e
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1 && retryDelayMs > 0) Thread.sleep(retryDelayMs * (attempt + 1))
            }
        }
        throw lastError ?: AiUnavailableException("El servicio de IA no está disponible")
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * Estrategia simulada para desarrollo y pruebas sin llave. Solo se usa si AI_PROVIDER=mock se pide a propósito:
 * en producción el valor por defecto es gemini, así que nunca devuelve datos falsos como si fueran reales.
 */
@Component
class MockAiClient : AiClient {
    override val provider = "mock"

    override fun generate(request: AiRequest): AiResponse {
        val json = when (request.purpose) {
            AiPurpose.TRANSCRIPTION -> MOCK_TRANSCRIPTION
            AiPurpose.SYLLABUS_EXTRACTION -> MOCK_SYLLABUS
            AiPurpose.NOTE_AUDIT -> mockAudit(request)
            AiPurpose.QUIZ_GENERATION -> mockQuiz(request)
        }
        return AiResponse(json = json, provider = provider, model = "mock")
    }

    // Primer subtema cubierto y segundo parcial, con los ids reales que mandó el servicio en metadata.
    private fun mockAudit(request: AiRequest): String {
        val ids = (request.metadata["subtopicIds"] as? List<*>).orEmpty().filterIsInstance<Long>()
        val verdicts = ids.take(2).mapIndexed { index, id ->
            val status = if (index == 0) "COVERED" else "PARTIAL"
            """{"subtopicId":$id,"status":"$status","evidence":"Evidencia simulada para el subtema $id"}"""
        }
        return """{"verdicts":[${verdicts.joinToString(",")}]}"""
    }

    // Una pregunta de 4 opciones por subtema, con la correcta en una posición distinta cada vez.
    private fun mockQuiz(request: AiRequest): String {
        val ids = (request.metadata["subtopicIds"] as? List<*>).orEmpty().filterIsInstance<Long>()
        val questions = ids.mapIndexed { index, id ->
            """{"subtemaId":$id,"enunciado":"Pregunta simulada $index sobre el subtema $id","opciones":["Opción A","Opción B","Opción C","Opción D"],"correcta":${index % 4},"explicacion":"Explicación simulada del subtema $id"}"""
        }
        return """{"preguntas":[${questions.joinToString(",")}]}"""
    }

    private companion object {
        // Un bloque bajo el umbral (0.41), como en la pantalla "Revisá la transcripción" del prototipo.
        const val MOCK_TRANSCRIPTION = """{"bloques":[
            {"texto":"Las corrutinas permiten escribir código asíncrono de forma secuencial.","confianza":0.94,"tipo":"parrafo"},
            {"texto":"El dispatcher IO se usa para llamadas de red y lectura de archivos en segundo plano.","confianza":0.41,"tipo":"parrafo"},
            {"texto":"Retrofit convierte la interfaz anotada en una implementación que ejecuta las peticiones HTTP.","confianza":0.78,"tipo":"parrafo"}
        ]}"""

        // Un subtema con confianza baja (0.45) para ver cómo la pantalla de revisión marca lo dudoso.
        const val MOCK_SYLLABUS = """{"modules":[
            {"number":1,"title":"Fundamentos de Android","confidence":0.95,"subtopics":[
                {"code":"1.1","title":"Ciclo de vida de una Activity","confidence":0.92,"objectives":["Explicar los estados de una Activity"]},
                {"code":"1.2","title":"Jetpack Compose","confidence":0.88,"objectives":["Construir pantallas declarativas","Manejar estado con remember"]}
            ]},
            {"number":2,"title":"Persistencia y red","confidence":0.9,"subtopics":[
                {"code":"2.1","title":"Room","confidence":0.9,"objectives":["Definir entidades y DAOs"]},
                {"code":"2.2","title":"Consumo de APIs REST","confidence":0.45,"objectives":["Usar Retrofit con corrutinas"]}
            ]}
        ]}"""
    }
}

package edu.backend.codice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant

// DTO-05: DTOs de apuntes y cobertura. La cobertura del grupo es agregada y anónima por diseño del DTO.
class NoteCoverageDtoTest {

    private lateinit var validator: Validator
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        validator = Validation.buildDefaultValidatorFactory().validator
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    }

    private fun violatedFields(dto: Any) = validator.validate(dto).map { it.propertyPath.toString() }.toSet()

    private fun allKeys(node: JsonNode): Set<String> = when {
        node.isObject -> node.fields().asSequence().flatMap { (key, value) -> sequenceOf(key) + allKeys(value) }.toSet()
        node.isArray -> node.flatMap { allKeys(it) }.toSet()
        else -> emptySet()
    }

    // ---------- Apunte ----------

    @Test
    fun `un apunte nuevo puede llegar sin texto confirmado pero no con texto en blanco ni demasiado largo`() {
        assertTrue(violatedFields(NoteCreateDTO(enrollmentId = 1)).isEmpty())
        assertTrue(violatedFields(NoteCreateDTO(enrollmentId = 1, confirmedText = "Rotación simple en AVL")).isEmpty())
        assertEquals(setOf("confirmedText"), violatedFields(NoteCreateDTO(enrollmentId = 1, confirmedText = "   ")))
        assertEquals(setOf("confirmedText"), violatedFields(NoteCreateDTO(enrollmentId = 1, confirmedText = "x".repeat(20_001))))
        assertEquals(setOf("clientRequestId"), violatedFields(NoteCreateDTO(enrollmentId = 1, clientRequestId = "x".repeat(101))))
    }

    @Test
    fun `actualizar un apunte permite omitir campos pero no dejar el texto en blanco`() {
        assertTrue(violatedFields(NoteUpdateDTO()).isEmpty())
        assertTrue(violatedFields(NoteUpdateDTO(subtopicId = 5, confirmedText = "Texto corregido")).isEmpty())
        assertEquals(setOf("confirmedText"), violatedFields(NoteUpdateDTO(confirmedText = "")))
        assertEquals(setOf("confirmedText"), violatedFields(NoteUpdateDTO(confirmedText = "x".repeat(20_001))))
    }

    // ---------- Auditoría simulada ----------

    @ParameterizedTest
    @ValueSource(strings = ["COVERED", "PARTIAL", "NO_NOTES"])
    fun `un veredicto de auditoria acepta los tres estados`(status: String) {
        val request = NoteAuditRequestDTO(listOf(SubtopicVerdictDTO(subtopicId = 5, status = status, evidence = "Explica la rotación")))

        assertTrue(violatedFields(request).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "REINFORCED", "covered", "CUBIERTO"])
    fun `un veredicto de auditoria rechaza cualquier otro estado`(status: String) {
        val request = NoteAuditRequestDTO(listOf(SubtopicVerdictDTO(subtopicId = 5, status = status)))

        assertEquals(setOf("verdicts[0].status"), violatedFields(request))
    }

    @Test
    fun `la auditoria exige al menos un veredicto y una evidencia de hasta 500 caracteres`() {
        assertEquals(setOf("verdicts"), violatedFields(NoteAuditRequestDTO(emptyList())))

        val longEvidence = NoteAuditRequestDTO(listOf(SubtopicVerdictDTO(subtopicId = 5, status = "PARTIAL", evidence = "x".repeat(501))))
        assertEquals(setOf("verdicts[0].evidence"), violatedFields(longEvidence))
    }

    // ---------- Cobertura ----------

    @Test
    fun `la cobertura personal lleva el porcentaje cubierto y el estado de cada subtema`() {
        val coverage = PersonalCoverageDTO(
            groupId = 20,
            coveredPercentage = 50.0,
            subtopics = listOf(
                SubtopicCoverageDTO(subtopicId = 100, subtopicCode = "4.1", subtopicTitle = "AVL", status = "COVERED"),
                SubtopicCoverageDTO(subtopicId = 101, subtopicCode = "4.2", subtopicTitle = "Rojo-negro", status = "NO_NOTES"),
            ),
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(coverage))

        assertEquals(50.0, json["coveredPercentage"].asDouble())
        assertEquals("COVERED", json["subtopics"][0]["status"].asText())
    }

    @Test
    fun `la cobertura del grupo es agregada y no tiene ningun campo que identifique a un estudiante`() {
        val coverage = GroupCoverageDTO(
            groupId = 20,
            totalStudents = 4,
            subtopics = listOf(
                AggregatedSubtopicCoverageDTO(
                    subtopicId = 100, subtopicCode = "4.1", subtopicTitle = "AVL",
                    coveredPercentage = 50.0, partialPercentage = 25.0, noNotesPercentage = 25.0,
                ),
            ),
        )

        val keys = allKeys(objectMapper.readTree(objectMapper.writeValueAsString(coverage)))

        val forbidden = setOf("studentId", "studentName", "studentEmail", "userId", "name", "email", "firstName", "lastName")
        assertTrue(keys.intersect(forbidden).isEmpty(), "Claves que identifican a un estudiante: ${keys.intersect(forbidden)}")
        assertEquals(
            setOf(
                "groupId", "totalStudents", "subtopics", "subtopicId", "subtopicCode", "subtopicTitle",
                "coveredPercentage", "partialPercentage", "noNotesPercentage",
                // El módulo es del temario, no del estudiante.
                "moduleNumber", "moduleTitle",
            ),
            keys,
        )
    }

    @Test
    fun `el resumen de un apunte para listados indica si ya fue auditado`() {
        val summary = NoteSummaryDTO(
            id = 1, subtopicId = 100, syncStatus = "SYNCED", audited = true,
            createdAt = Instant.parse("2026-10-12T15:00:00Z"),
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(summary))

        assertTrue(json["audited"].asBoolean())
        assertEquals(
            setOf(
                "id", "subtopicId", "syncStatus", "audited", "createdAt", "confirmedText", "pageCount",
                "subtopicCode", "subtopicTitle", "moduleId", "moduleLabel",
            ),
            json.fieldNames().asSequence().toSet(),
        )
    }
}

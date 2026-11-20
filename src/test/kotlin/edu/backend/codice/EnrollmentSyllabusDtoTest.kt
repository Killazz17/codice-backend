package edu.backend.codice

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

// DTO-04: DTOs de matrícula y temario. Validan la entrada (código de invitación, decisión del profesor,
// subtemas y objetivos) y fijan la forma del JSON de salida (árbol del temario y solicitudes).
class EnrollmentSyllabusDtoTest {

    private lateinit var validator: Validator
    private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        validator = Validation.buildDefaultValidatorFactory().validator
        objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    }

    private fun violatedFields(dto: Any) = validator.validate(dto).map { it.propertyPath.toString() }.toSet()

    // ---------- Matrícula ----------

    @ParameterizedTest
    @ValueSource(strings = ["EIF411-01-7K2A", "EIF411-02-9M4C", "eif411-01-7k2a", "MAT101-100-AB12CD"])
    fun `un codigo de invitacion con el formato CURSO-GRUPO-SUFIJO es valido`(code: String) {
        assertTrue(violatedFields(JoinGroupDTO(code)).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "EIF411017K2A", "EIF411-01", "EIF411-01-7K2A-X", "EIF 411-01-7K2A", "EIF411--7K2A"])
    fun `un codigo de invitacion con otro formato se rechaza`(code: String) {
        assertEquals(setOf("inviteCode"), violatedFields(JoinGroupDTO(code)))
    }

    @Test
    fun `un codigo de invitacion de mas de 30 caracteres se rechaza`() {
        assertEquals(setOf("inviteCode"), violatedFields(JoinGroupDTO("EIF411-01-" + "A".repeat(21))))
    }

    @ParameterizedTest
    @ValueSource(strings = ["APPROVE", "REJECT"])
    fun `la decision del profesor acepta APPROVE y REJECT`(decision: String) {
        assertTrue(violatedFields(EnrollmentDecisionDTO(decision)).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "ACTIVE", "approve", "MAYBE"])
    fun `la decision del profesor rechaza cualquier otro valor`(decision: String) {
        assertEquals(setOf("decision"), violatedFields(EnrollmentDecisionDTO(decision)))
    }

    @Test
    fun `la solicitud de matricula que ve el profesor solo expone nombre y correo del estudiante`() {
        val request = EnrollmentRequestSummaryDTO(
            id = 7,
            studentName = "Ana Mora",
            studentEmail = "ana@est.una.ac.cr",
            status = "PENDING",
            requestedAt = Instant.parse("2026-10-12T15:00:00Z"),
        )

        val fields = objectMapper.readTree(objectMapper.writeValueAsString(request)).fieldNames().asSequence().toSet()

        assertEquals(setOf("id", "studentName", "studentEmail", "status", "requestedAt"), fields)
    }

    // ---------- Temario ----------

    @Test
    fun `crear un subtema exige codigo y titulo`() {
        assertEquals(setOf("code", "title"), violatedFields(SubtopicCreateDTO(code = " ", title = "")))
    }

    @Test
    fun `actualizar un subtema permite omitir campos pero no dejarlos en blanco`() {
        assertTrue(violatedFields(SubtopicUpdateDTO()).isEmpty())
        assertTrue(violatedFields(SubtopicUpdateDTO(title = "Árboles AVL", position = 2)).isEmpty())
        assertEquals(setOf("code", "title"), violatedFields(SubtopicUpdateDTO(code = "  ", title = "")))
        assertEquals(setOf("position"), violatedFields(SubtopicUpdateDTO(position = -1)))
        assertEquals(setOf("code", "title"), violatedFields(SubtopicUpdateDTO(code = "4".repeat(21), title = "t".repeat(256))))
    }

    @Test
    fun `un objetivo exige descripcion de hasta 500 caracteres`() {
        assertTrue(violatedFields(ObjectiveCreateDTO("Explicar la rotación simple")).isEmpty())
        assertEquals(setOf("description"), violatedFields(ObjectiveCreateDTO(" ")))
        assertEquals(setOf("description"), violatedFields(ObjectiveCreateDTO("x".repeat(501))))
    }

    @Test
    fun `el temario se serializa como arbol modulo, subtema y objetivos`() {
        val syllabus = SyllabusDTO(
            courseId = 1,
            published = true,
            publishedAt = Instant.parse("2026-10-12T15:00:00Z"),
            modules = listOf(
                ModuleDTO(
                    id = 10, courseId = 1, number = 4, title = "Árboles",
                    subtopics = listOf(
                        SubtopicDTO(
                            id = 100, moduleId = 10, code = "4.1", title = "AVL", position = 1,
                            objectives = listOf(SubtopicObjectiveDTO(1000, "Explicar la rotación simple")),
                        ),
                        SubtopicDTO(id = 101, moduleId = 10, code = "4.2", title = "Rojo-negro", position = 2),
                    ),
                ),
            ),
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(syllabus))

        assertEquals(1, json["courseId"].asInt())
        assertTrue(json["published"].asBoolean())
        val module = json["modules"][0]
        assertEquals("Árboles", module["title"].asText())
        assertEquals("4.1", module["subtopics"][0]["code"].asText())
        assertEquals("Explicar la rotación simple", module["subtopics"][0]["objectives"][0]["description"].asText())
        // Un subtema sin objetivos se serializa con lista vacía, no con null.
        assertTrue(module["subtopics"][1]["objectives"].isArray)
        assertEquals(0, module["subtopics"][1]["objectives"].size())
    }

    @Test
    fun `publicar devuelve las advertencias de subtemas sin objetivos`() {
        val response = SyllabusPublishDTO(
            published = true,
            publishedAt = Instant.parse("2026-10-12T15:00:00Z"),
            warnings = listOf(SyllabusWarningDTO(subtopicId = 101, subtopicCode = "4.2", message = "El subtema no tiene objetivos")),
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(response))

        assertTrue(json["published"].asBoolean())
        assertEquals(101, json["warnings"][0]["subtopicId"].asInt())
        assertEquals("4.2", json["warnings"][0]["subtopicCode"].asText())
    }
}

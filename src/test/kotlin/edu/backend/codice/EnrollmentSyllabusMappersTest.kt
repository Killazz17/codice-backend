package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mapstruct.factory.Mappers
import java.time.Instant

// MAP-02: mappers de matrícula y temario, incluido el armado del árbol módulo → subtema → objetivos.
class EnrollmentSyllabusMappersTest {

    private val enrollmentMapper = Mappers.getMapper(EnrollmentMapper::class.java)
    private val syllabusMapper = Mappers.getMapper(SyllabusMapper::class.java)

    private val course = Course(id = 1L, code = "EIF411", name = "Diseño Móvil")
    private val requestedAt = Instant.parse("2026-10-12T15:00:00Z")

    private fun enrollment() = Enrollment(
        id = 7L,
        group = StudentGroup(id = 20L, number = "01", course = course),
        student = User(id = 30L, firstName = "Ana", lastName = "Mora", email = "ana@est.una.ac.cr"),
        status = EnrollmentStatus.PENDING,
        requestedAt = requestedAt,
    )

    private fun subtopic(id: Long, code: String, position: Int, vararg objectiveIds: Long) =
        Subtopic(id = id, code = code, title = "Subtema $code", position = position).apply {
            objectiveIds.forEach { addObjective(SubtopicObjective(id = it, description = "Objetivo $it")) }
        }

    private fun module(id: Long, number: Int, vararg subtopics: Subtopic) =
        CourseModule(id = id, course = course, number = number, title = "Módulo $number").apply {
            subtopics.forEach { addSubtopic(it) }
        }

    // ---------- Matrícula ----------

    @Test
    fun `la matricula toma el curso desde el grupo`() {
        // enrollment.course lo llena un trigger de la base; recién creada todavía es null.
        val response = enrollmentMapper.toEnrollmentResponse(enrollment())

        assertEquals(7L, response.id)
        assertEquals(20L, response.groupId)
        assertEquals("01", response.groupNumber)
        assertEquals(1L, response.courseId)
        assertEquals("EIF411", response.courseCode)
        assertEquals("Diseño Móvil", response.courseName)
        assertEquals(30L, response.studentId)
        assertEquals("Ana Mora", response.studentName)
        assertEquals("PENDING", response.status)
        assertEquals(requestedAt, response.requestedAt)
        assertNull(response.resolvedAt)
    }

    @Test
    fun `la solicitud que ve el profesor solo trae nombre y correo del estudiante`() {
        val summary = enrollmentMapper.toEnrollmentRequestSummary(enrollment())

        assertEquals(7L, summary.id)
        assertEquals("Ana Mora", summary.studentName)
        assertEquals("ana@est.una.ac.cr", summary.studentEmail)
        assertEquals("PENDING", summary.status)
        assertEquals(requestedAt, summary.requestedAt)
        assertEquals(
            setOf("id", "studentName", "studentEmail", "status", "requestedAt"),
            EnrollmentRequestSummaryDTO::class.java.declaredFields.map { it.name }.toSet(),
        )
    }

    @Test
    fun `la decision del profesor se traduce de forma explicita al estado de la matricula`() {
        assertEquals(EnrollmentStatus.ACTIVE, enrollmentMapper.toEnrollmentStatus(EnrollmentDecisionDTO("APPROVE")))
        assertEquals(EnrollmentStatus.REJECTED, enrollmentMapper.toEnrollmentStatus(EnrollmentDecisionDTO("REJECT")))
    }

    @Test
    fun `una decision distinta de APPROVE o REJECT no se traduce a ningun estado`() {
        assertThrows<IllegalArgumentException> { enrollmentMapper.toEnrollmentStatus(EnrollmentDecisionDTO("ACTIVE")) }
    }

    // ---------- Temario: entrada ----------

    @Test
    fun `crear un subtema copia codigo, titulo y posicion sin modulo ni id`() {
        val subtopic = syllabusMapper.toSubtopic(SubtopicCreateDTO(code = "4.1", title = "AVL", position = 3))

        assertNull(subtopic.id)
        assertNull(subtopic.module)
        assertEquals("4.1", subtopic.code)
        assertEquals("AVL", subtopic.title)
        assertEquals(3, subtopic.position)
        assertTrue(subtopic.objectives.isEmpty())
    }

    @Test
    fun `actualizar un subtema solo cambia los campos que vienen`() {
        val existing = module(10L, 4, subtopic(100L, "4.1", 1, 1000L)).subtopics.first()

        syllabusMapper.updateSubtopicFromRequest(SubtopicUpdateDTO(title = "Árboles AVL"), existing)

        assertEquals(100L, existing.id)
        assertEquals("4.1", existing.code)
        assertEquals("Árboles AVL", existing.title)
        assertEquals(1, existing.position)
        assertEquals(10L, existing.module?.id)
        assertEquals(1, existing.objectives.size)
    }

    @Test
    fun `crear un objetivo copia la descripcion sin subtema ni id`() {
        val objective = syllabusMapper.toObjective(ObjectiveCreateDTO("Explicar la rotación simple"))

        assertNull(objective.id)
        assertNull(objective.subtopic)
        assertEquals("Explicar la rotación simple", objective.description)
    }

    // ---------- Temario: salida ----------

    @Test
    fun `un subtema se mapea con su modulo y sus objetivos`() {
        val dto = syllabusMapper.subtopicToSubtopicDTO(module(10L, 4, subtopic(100L, "4.1", 1, 1000L)).subtopics.first())

        assertEquals(100L, dto.id)
        assertEquals(10L, dto.moduleId)
        assertEquals("4.1", dto.code)
        assertEquals("Subtema 4.1", dto.title)
        assertEquals(1, dto.position)
        assertEquals(listOf(SubtopicObjectiveDTO(1000L, "Objetivo 1000")), dto.objectives)
    }

    @Test
    fun `el arbol del temario respeta el orden de modulos, subtemas y objetivos`() {
        course.syllabusPublishedAt = requestedAt
        // Todo llega desordenado a propósito: el mapper es quien ordena.
        val modules = listOf(
            module(11L, 5, subtopic(200L, "5.1", 1)),
            module(
                10L, 4,
                subtopic(102L, "4.10", 3, 1004L),
                subtopic(100L, "4.1", 1, 1002L, 1001L),
                subtopic(101L, "4.2", 2),
            ),
        )

        val syllabus = syllabusMapper.toSyllabusDTO(course, modules)

        assertEquals(1L, syllabus.courseId)
        assertTrue(syllabus.published)
        assertEquals(requestedAt, syllabus.publishedAt)
        assertEquals(listOf(4, 5), syllabus.modules.map { it.number })
        val first = syllabus.modules.first()
        assertEquals(1L, first.courseId)
        assertEquals("Módulo 4", first.title)
        // Por position, no por código: como texto '4.10' quedaría antes que '4.2'.
        assertEquals(listOf("4.1", "4.2", "4.10"), first.subtopics.map { it.code })
        assertEquals(listOf(1001L, 1002L), first.subtopics[0].objectives.map { it.id })
        // Un subtema sin objetivos trae lista vacía, no null.
        assertTrue(first.subtopics[1].objectives.isEmpty())
    }

    @Test
    fun `un temario vacio y sin publicar se mapea sin modulos`() {
        val draftCourse = Course(id = 2L, code = "EIF400", name = "Paradigmas")

        val syllabus = syllabusMapper.toSyllabusDTO(draftCourse, emptyList())

        assertEquals(2L, syllabus.courseId)
        assertFalse(syllabus.published)
        assertNull(syllabus.publishedAt)
        assertTrue(syllabus.modules.isEmpty())
    }
}

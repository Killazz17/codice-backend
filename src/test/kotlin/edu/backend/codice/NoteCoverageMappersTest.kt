package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mapstruct.factory.Mappers
import java.time.Instant

// MAP-03: mappers de apuntes y cobertura. La vista agregada del profesor no puede recibir ni producir
// datos de identidad de un estudiante.
class NoteCoverageMappersTest {

    private val noteMapper = Mappers.getMapper(NoteMapper::class.java)
    private val coverageMapper = Mappers.getMapper(CoverageMapper::class.java)

    private val createdAt = Instant.parse("2026-10-12T15:00:00Z")
    private val subtopic = Subtopic(id = 100L, code = "4.1", title = "AVL", position = 1)
    private val student = User(id = 30L, firstName = "Ana", lastName = "Mora", email = "ana@est.una.ac.cr")

    private fun note(audit: Map<String, Any?>? = null) = Note(
        id = 1L,
        enrollment = Enrollment(id = 7L),
        subtopic = subtopic,
        clientRequestId = "req-1",
        confirmedText = "Rotación simple",
        syncStatus = SyncStatus.SYNCED,
        createdAt = createdAt,
        audit = audit,
    ).apply { pages.add(NotePage(id = 50L, note = this, pageIndex = 0, filePath = "notes/1/0.jpg")) }

    // ---------- Apunte ----------

    @Test
    fun `crear un apunte copia los datos y deja las relaciones al servicio`() {
        val note = noteMapper.toNote(NoteCreateDTO(enrollmentId = 7, subtopicId = 100, clientRequestId = "req-1", confirmedText = "Texto"))

        assertNull(note.id)
        assertNull(note.enrollment)
        assertNull(note.subtopic)
        assertEquals("req-1", note.clientRequestId)
        assertEquals("Texto", note.confirmedText)
        assertEquals(SyncStatus.QUEUED, note.syncStatus)
        assertNull(note.audit)
        assertTrue(note.pages.isEmpty())
    }

    @Test
    fun `actualizar un apunte no sobrescribe con null los campos que no vienen`() {
        val note = note()

        noteMapper.updateNoteFromRequest(NoteUpdateDTO(subtopicId = 999), note)

        assertEquals("Rotación simple", note.confirmedText)
        assertEquals(100L, note.subtopic?.id) // el subtema lo cambia el servicio, no el mapper
        assertEquals(7L, note.enrollment?.id)

        noteMapper.updateNoteFromRequest(NoteUpdateDTO(confirmedText = "Texto corregido"), note)

        assertEquals("Texto corregido", note.confirmedText)
        assertEquals(1L, note.id)
    }

    @Test
    fun `un apunte sin auditoria sale como no auditado y con sus paginas`() {
        val dto = noteMapper.noteToNoteDTO(note())

        assertEquals(1L, dto.id)
        assertEquals(7L, dto.enrollmentId)
        assertEquals(100L, dto.subtopicId)
        assertEquals("req-1", dto.clientRequestId)
        assertEquals("Rotación simple", dto.confirmedText)
        assertEquals("SYNCED", dto.syncStatus)
        assertFalse(dto.audited)
        assertEquals(createdAt, dto.createdAt)
        assertEquals(listOf(NotePageDTO(50L, 0, "notes/1/0.jpg")), dto.pages)
    }

    @Test
    fun `un apunte con auditoria sale como auditado`() {
        assertTrue(noteMapper.noteToNoteDTO(note(audit = mapOf("verdicts" to emptyList<Any>()))).audited)
    }

    @Test
    fun `el resumen de un apunte deriva si fue auditado en los dos casos`() {
        val pending = noteMapper.toNoteSummary(note())
        val audited = noteMapper.toNoteSummary(note(audit = mapOf("verdicts" to emptyList<Any>())))

        assertEquals(1L, pending.id)
        assertEquals(100L, pending.subtopicId)
        assertEquals("SYNCED", pending.syncStatus)
        assertEquals(createdAt, pending.createdAt)
        assertFalse(pending.audited)
        assertTrue(audited.audited)
        // Lo que la biblioteca de la app muestra en cada tarjeta.
        assertEquals("Rotación simple", pending.confirmedText)
        assertEquals(1, pending.pageCount)
        assertEquals("4.1", pending.subtopicCode)
        assertEquals("AVL", pending.subtopicTitle)
        assertNull(pending.moduleLabel) // el subtema de este test no tiene módulo
        val conModulo = note().apply { subtopic = Subtopic(id = 100L, code = "4.1", title = "AVL", module = CourseModule(id = 9L, number = 4, title = "Árboles")) }
        assertEquals("Módulo 4 · Árboles", noteMapper.toNoteSummary(conModulo).moduleLabel)
        assertEquals(9L, noteMapper.toNoteSummary(conModulo).moduleId)
    }

    // ---------- Cobertura personal ----------

    @Test
    fun `la cobertura de un subtema toma el estado guardado`() {
        val coverage = Coverage(id = 1L, student = student, subtopic = subtopic, status = CoverageStatus.REINFORCED)

        val dto = coverageMapper.toSubtopicCoverage(subtopic, coverage)

        assertEquals(SubtopicCoverageDTO(100L, "4.1", "AVL", "REINFORCED"), dto)
    }

    @Test
    fun `un subtema sin fila de cobertura sale como NO_NOTES`() {
        assertEquals("NO_NOTES", coverageMapper.toSubtopicCoverage(subtopic, null).status)
    }

    @Test
    fun `la cobertura agregada de un subtema trae su modulo`() {
        val counts = SubtopicCoverageCounts(100L, "4.1", "AVL", covered = 1, partial = 0, noNotes = 1, moduleNumber = 4, moduleTitle = "Árboles")

        val dto = coverageMapper.toAggregatedCoverage(counts, totalStudents = 2)

        assertEquals(4, dto.moduleNumber)
        assertEquals("Árboles", dto.moduleTitle)
    }

    @Test
    fun `la cobertura de un subtema trae su modulo para que la app agrupe el mapa`() {
        assertNull(coverageMapper.toSubtopicCoverage(subtopic, null).moduleNumber) // el subtema de este test no tiene módulo
        val conModulo = Subtopic(id = 100L, code = "4.1", title = "AVL", module = CourseModule(id = 9L, number = 4, title = "Árboles"))

        val dto = coverageMapper.toSubtopicCoverage(conModulo, null)

        assertEquals(4, dto.moduleNumber)
        assertEquals("Árboles", dto.moduleTitle)
    }

    @Test
    fun `la cobertura personal se arma con el porcentaje que calcula el servicio`() {
        val items = listOf(coverageMapper.toSubtopicCoverage(subtopic, null))

        val dto = coverageMapper.toPersonalCoverage(groupId = 20L, coveredPercentage = 0.0, subtopics = items)

        assertEquals(PersonalCoverageDTO(20L, 0.0, items), dto)
    }

    // ---------- Cobertura del grupo ----------

    @Test
    fun `los conteos agregados se convierten a porcentajes de 0 a 100`() {
        val counts = SubtopicCoverageCounts(subtopicId = 100L, subtopicCode = "4.1", subtopicTitle = "AVL", covered = 2, partial = 1, noNotes = 1)

        val dto = coverageMapper.toAggregatedCoverage(counts, totalStudents = 4)

        assertEquals(AggregatedSubtopicCoverageDTO(100L, "4.1", "AVL", 50.0, 25.0, 25.0), dto)
    }

    @Test
    fun `un grupo sin estudiantes da porcentajes en cero y no divide entre cero`() {
        val counts = SubtopicCoverageCounts(subtopicId = 100L, subtopicCode = "4.1", subtopicTitle = "AVL", covered = 0, partial = 0, noNotes = 0)

        val dto = coverageMapper.toAggregatedCoverage(counts, totalStudents = 0)

        assertEquals(0.0, dto.coveredPercentage)
        assertEquals(0.0, dto.partialPercentage)
        assertEquals(0.0, dto.noNotesPercentage)
    }

    @Test
    fun `la cobertura del grupo se arma solo con conteos`() {
        val counts = listOf(SubtopicCoverageCounts(100L, "4.1", "AVL", covered = 1, partial = 0, noNotes = 1))

        val dto = coverageMapper.toGroupCoverage(groupId = 20L, totalStudents = 2, counts = counts)

        assertEquals(20L, dto.groupId)
        assertEquals(2, dto.totalStudents)
        assertEquals(listOf(AggregatedSubtopicCoverageDTO(100L, "4.1", "AVL", 50.0, 0.0, 50.0)), dto.subtopics)
    }

    @Test
    fun `ningun metodo de la cobertura del grupo recibe ni produce datos de identidad de un estudiante`() {
        val identityTypes = setOf(User::class.java, Coverage::class.java, Enrollment::class.java, Note::class.java, CoverageDTO::class.java)
        val groupMethods = CoverageMapper::class.java.methods.filter { it.name.contains("Group") || it.name.contains("Aggregated") }

        assertTrue(groupMethods.isNotEmpty())
        groupMethods.forEach { method ->
            val types = method.parameterTypes.toSet() + method.returnType
            assertTrue(types.intersect(identityTypes).isEmpty(), "${method.name} usa ${types.intersect(identityTypes)}")
        }
        val countFields = SubtopicCoverageCounts::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("subtopicId", "subtopicCode", "subtopicTitle", "covered", "partial", "noNotes", "moduleNumber", "moduleTitle"), countFields)
    }
}

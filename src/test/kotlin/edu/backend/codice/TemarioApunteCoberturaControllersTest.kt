package edu.backend.codice

import com.fasterxml.jackson.databind.ObjectMapper
import edu.backend.codice.exception.AiUnavailableException
import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ConflictException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.InvalidInputException
import edu.backend.codice.exception.NotFoundException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant

/*
 * API-05, API-06 y API-07 con MockMvc. Los servicios están simulados: aquí se prueba la traducción
 * HTTP (rutas, validación, status codes, Location) y que las excepciones del servicio lleguen como
 * 403/404/422 por el GlobalExceptionHandler.
 */

private val json = ObjectMapper().findAndRegisterModules()

private fun ResultActionsDsl.status(code: Int) = andExpect { status { isEqualTo(code) } }

// ==================== API-05 Temario ====================

@WebMvcTest(TemarioController::class)
@WithMockUser
class TemarioControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockBean
    lateinit var syllabusService: SyllabusService

    @MockBean
    lateinit var syllabusExtractionService: SyllabusExtractionService

    private val subtopic = SubtopicDTO(30L, 20L, "4.1", "AVL", 1, listOf(SubtopicObjectiveDTO(40L, "Rotar")))
    private val syllabus = SyllabusDTO(10L, true, Instant.parse("2026-10-12T15:00:00Z"), listOf(ModuleDTO(20L, 10L, 4, "Árboles", listOf(subtopic))))

    @Test
    fun `GET temario devuelve el arbol anidado`() {
        whenever(syllabusService.getSyllabus(10L)).thenReturn(syllabus)

        mockMvc.get("/api/v1/cursos/10/temario").andExpect {
            status { isOk() }
            jsonPath("$.modules[0].subtopics[0].objectives[0].description") { value("Rotar") }
            jsonPath("$.published") { value(true) }
        }
    }

    @Test
    fun `GET temario responde 404 sin publicar y 403 sin matricula aprobada`() {
        whenever(syllabusService.getSyllabus(11L)).thenThrow(NotFoundException("El temario todavía no se publica"))
        whenever(syllabusService.getSyllabus(12L)).thenThrow(ForbiddenException("Sin matrícula aprobada"))

        mockMvc.get("/api/v1/cursos/11/temario").status(404)
        mockMvc.get("/api/v1/cursos/12/temario").status(403)
    }

    @Test
    @WithAnonymousUser
    fun `sin sesion responde 401`() {
        mockMvc.get("/api/v1/cursos/10/temario").andExpect { status { isUnauthorized() } }
    }

    private val pdf = MockMultipartFile("file", "programa.pdf", "application/pdf", "%PDF-1.4".toByteArray())

    @Test
    fun `POST temario extraer devuelve el arbol propuesto con la confianza de cada nodo`() {
        val subtopic = ExtractedSubtopicDTO("1.1", "AVL", 0.45, listOf("Rotar"))
        val tree = SyllabusExtractionDTO(10L, "mock", "mock", "temario-v1", listOf(ExtractedModuleDTO(1, "Árboles", 0.9, listOf(subtopic))))
        whenever(syllabusExtractionService.extract(eq(10L), anyOrNull(), anyOrNull())).thenReturn(tree)

        mockMvc.multipart("/api/v1/cursos/10/temario/extraer") { file(pdf); with(csrf()) }.andExpect {
            status { isOk() }
            jsonPath("$.modules[0].number") { value(1) }
            jsonPath("$.modules[0].subtopics[0].confidence") { value(0.45) }
            jsonPath("$.modules[0].subtopics[0].objectives[0]") { value("Rotar") }
        }
    }

    @Test
    fun `POST temario extraer responde 400, 403 y 503 segun el error del servicio`() {
        whenever(syllabusExtractionService.extract(eq(11L), anyOrNull(), anyOrNull())).thenThrow(InvalidInputException("El archivo debe ser un PDF válido"))
        whenever(syllabusExtractionService.extract(eq(12L), anyOrNull(), anyOrNull())).thenThrow(ForbiddenException("Solo el profesor dueño del curso puede hacer esto"))
        whenever(syllabusExtractionService.extract(eq(13L), anyOrNull(), anyOrNull())).thenThrow(AiUnavailableException("Gemini respondió HTTP 500"))

        mockMvc.multipart("/api/v1/cursos/11/temario/extraer") { file(pdf); with(csrf()) }.status(400)
        mockMvc.multipart("/api/v1/cursos/12/temario/extraer") { file(pdf); with(csrf()) }.status(403)
        mockMvc.multipart("/api/v1/cursos/13/temario/extraer") { file(pdf); with(csrf()) }.status(503)
    }

    @Test
    fun `POST temario extraer sin archivo responde 400`() {
        mockMvc.multipart("/api/v1/cursos/10/temario/extraer") { with(csrf()) }.status(400)
    }

    @Test
    fun `POST subtema responde 201 con Location, 400, 403 y 404`() {
        whenever(syllabusService.createSubtopic(eq(20L), any())).thenReturn(subtopic)
        whenever(syllabusService.createSubtopic(eq(21L), any())).thenThrow(ForbiddenException("Un estudiante no modifica el temario"))
        whenever(syllabusService.createSubtopic(eq(99L), any())).thenThrow(NotFoundException("Módulo no encontrado"))
        val body = json.writeValueAsString(SubtopicCreateDTO(code = "4.1", title = "AVL"))

        mockMvc.post("/api/v1/modulos/20/subtemas") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.andExpect {
            status { isCreated() }
            header { string("Location", "/api/v1/subtemas/30") }
        }
        mockMvc.post("/api/v1/modulos/20/subtemas") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"code":" ","title":""}""" }.status(400)
        mockMvc.post("/api/v1/modulos/21/subtemas") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(403)
        mockMvc.post("/api/v1/modulos/99/subtemas") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(404)
    }

    @Test
    fun `PATCH y DELETE de subtema`() {
        whenever(syllabusService.updateSubtopic(eq(30L), any())).thenReturn(subtopic)
        whenever(syllabusService.updateSubtopic(eq(31L), any())).thenThrow(ForbiddenException("No es el dueño"))
        whenever(syllabusService.updateSubtopic(eq(99L), any())).thenThrow(NotFoundException("No existe"))
        whenever(syllabusService.deleteSubtopic(32L)).thenThrow(BusinessRuleException("Tiene apuntes"))
        whenever(syllabusService.deleteSubtopic(31L)).thenThrow(ForbiddenException("No es el dueño"))
        whenever(syllabusService.deleteSubtopic(99L)).thenThrow(NotFoundException("No existe"))
        val patch = """{"title":"AVL balanceado"}"""

        mockMvc.patch("/api/v1/subtemas/30") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(200)
        mockMvc.patch("/api/v1/subtemas/30") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"title":"  "}""" }.status(400)
        mockMvc.patch("/api/v1/subtemas/31") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(403)
        mockMvc.patch("/api/v1/subtemas/99") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(404)

        mockMvc.delete("/api/v1/subtemas/30") { with(csrf()) }.status(204)
        mockMvc.delete("/api/v1/subtemas/32") { with(csrf()) }.status(422)
        mockMvc.delete("/api/v1/subtemas/31") { with(csrf()) }.status(403)
        mockMvc.delete("/api/v1/subtemas/99") { with(csrf()) }.status(404)
    }

    @Test
    fun `modulos - PATCH, PUT de orden y DELETE`() {
        val module = ModuleDTO(20L, 10L, 5, "Grafos")
        whenever(syllabusService.updateModule(eq(20L), any())).thenReturn(module)
        whenever(syllabusService.updateModule(eq(21L), any())).thenThrow(ForbiddenException("No es el dueño"))
        whenever(syllabusService.updateModule(eq(22L), any())).thenThrow(ConflictException("El curso ya tiene un módulo 5"))
        whenever(syllabusService.updateModule(eq(99L), any())).thenThrow(NotFoundException("No existe"))
        whenever(syllabusService.reorderModules(eq(10L), any())).thenReturn(listOf(module))
        whenever(syllabusService.reorderModules(eq(11L), any())).thenThrow(BusinessRuleException("La lista debe traer cada módulo"))
        whenever(syllabusService.reorderModules(eq(12L), any())).thenThrow(ForbiddenException("No es el dueño"))
        whenever(syllabusService.deleteModule(23L)).thenThrow(BusinessRuleException("Tiene apuntes asociados"))
        whenever(syllabusService.deleteModule(21L)).thenThrow(ForbiddenException("No es el dueño"))
        whenever(syllabusService.deleteModule(99L)).thenThrow(NotFoundException("No existe"))
        val patch = """{"title":"Grafos","number":5}"""
        val order = """{"moduleIds":[20,21]}"""

        mockMvc.patch("/api/v1/modulos/20") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Grafos") }
        }
        mockMvc.patch("/api/v1/modulos/20") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"title":"  "}""" }.status(400)
        mockMvc.patch("/api/v1/modulos/20") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"number":0}""" }.status(400)
        mockMvc.patch("/api/v1/modulos/21") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(403)
        mockMvc.patch("/api/v1/modulos/22") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(409)
        mockMvc.patch("/api/v1/modulos/99") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(404)

        mockMvc.put("/api/v1/cursos/10/modulos/orden") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = order }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(20) }
        }
        mockMvc.put("/api/v1/cursos/10/modulos/orden") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"moduleIds":[]}""" }.status(400)
        mockMvc.put("/api/v1/cursos/11/modulos/orden") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = order }.status(422)
        mockMvc.put("/api/v1/cursos/12/modulos/orden") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = order }.status(403)

        mockMvc.delete("/api/v1/modulos/20") { with(csrf()) }.status(204)
        mockMvc.delete("/api/v1/modulos/23") { with(csrf()) }.status(422)
        mockMvc.delete("/api/v1/modulos/21") { with(csrf()) }.status(403)
        mockMvc.delete("/api/v1/modulos/99") { with(csrf()) }.status(404)
    }

    @Test
    fun `objetivos - POST 201 con Location, PATCH y DELETE`() {
        val objective = SubtopicObjectiveDTO(40L, "Rotar")
        whenever(syllabusService.addObjective(eq(30L), any())).thenReturn(objective)
        whenever(syllabusService.addObjective(eq(31L), any())).thenThrow(ForbiddenException("No es el dueño"))
        whenever(syllabusService.addObjective(eq(99L), any())).thenThrow(NotFoundException("No existe"))
        whenever(syllabusService.updateObjective(eq(40L), any())).thenReturn(objective)
        whenever(syllabusService.updateObjective(eq(99L), any())).thenThrow(NotFoundException("No existe"))
        whenever(syllabusService.deleteObjective(41L)).thenThrow(ForbiddenException("No es el dueño"))
        whenever(syllabusService.deleteObjective(99L)).thenThrow(NotFoundException("No existe"))
        val body = """{"description":"Rotar"}"""

        mockMvc.post("/api/v1/subtemas/30/objetivos") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.andExpect {
            status { isCreated() }
            header { string("Location", "/api/v1/objetivos/40") }
        }
        mockMvc.post("/api/v1/subtemas/30/objetivos") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"description":""}""" }.status(400)
        mockMvc.post("/api/v1/subtemas/31/objetivos") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(403)
        mockMvc.post("/api/v1/subtemas/99/objetivos") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(404)
        mockMvc.patch("/api/v1/objetivos/40") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(200)
        mockMvc.patch("/api/v1/objetivos/99") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(404)
        mockMvc.delete("/api/v1/objetivos/40") { with(csrf()) }.status(204)
        mockMvc.delete("/api/v1/objetivos/41") { with(csrf()) }.status(403)
        mockMvc.delete("/api/v1/objetivos/99") { with(csrf()) }.status(404)
    }

    @Test
    fun `publicar responde 200 con advertencias, 422 sin subtemas, 403 y 404`() {
        whenever(syllabusService.publish(10L)).thenReturn(
            SyllabusPublishDTO(true, Instant.parse("2026-10-12T15:00:00Z"), listOf(SyllabusWarningDTO(31L, "4.2", "Sin objetivos"))),
        )
        whenever(syllabusService.publish(11L)).thenThrow(BusinessRuleException("Sin subtemas"))
        whenever(syllabusService.publish(12L)).thenThrow(ForbiddenException("No es el dueño"))
        whenever(syllabusService.publish(99L)).thenThrow(NotFoundException("No existe"))

        mockMvc.post("/api/v1/cursos/10/temario/publicar") { with(csrf()) }.andExpect {
            status { isOk() }
            jsonPath("$.warnings[0].subtopicCode") { value("4.2") }
        }
        mockMvc.post("/api/v1/cursos/11/temario/publicar") { with(csrf()) }.status(422)
        mockMvc.post("/api/v1/cursos/12/temario/publicar") { with(csrf()) }.status(403)
        mockMvc.post("/api/v1/cursos/99/temario/publicar") { with(csrf()) }.status(404)
    }
}

// ==================== API-06 Apuntes ====================

@WebMvcTest(ApunteController::class)
@WithMockUser
class ApunteControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockBean
    lateinit var noteService: NoteService

    private val note = NoteDTO(100L, 10L, 50L, "req-1", "Rotación simple", "SYNCED", Instant.parse("2026-10-12T15:00:00Z"))

    @Test
    fun `POST apunte responde 201 con Location y los errores del servicio`() {
        whenever(noteService.createNote(any())).thenAnswer {
            when ((it.arguments[0] as NoteCreateDTO).enrollmentId) {
                10L -> note
                11L -> throw ForbiddenException("Matrícula no aprobada")
                12L -> throw NotFoundException("Matrícula no existe")
                else -> throw BusinessRuleException("Subtema de otro curso")
            }
        }
        fun body(enrollmentId: Long) = """{"enrollmentId":$enrollmentId,"confirmedText":"Rotación simple"}"""

        mockMvc.post("/api/v1/apuntes") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body(10) }.andExpect {
            status { isCreated() }
            header { string("Location", "/api/v1/apuntes/100") }
            jsonPath("$.audited") { value(false) }
        }
        mockMvc.post("/api/v1/apuntes") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"enrollmentId":10,"confirmedText":"  "}""" }.status(400)
        mockMvc.post("/api/v1/apuntes") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body(11) }.status(403)
        mockMvc.post("/api/v1/apuntes") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body(12) }.status(404)
        mockMvc.post("/api/v1/apuntes") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body(13) }.status(422)
    }

    @Test
    fun `GET lista pagina con filtros y valores por defecto`() {
        whenever(noteService.search(anyOrNull(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(
            PageResponse(listOf(NoteSummaryDTO(100L, 50L, "SYNCED", false, Instant.parse("2026-10-12T15:00:00Z"))), 0, 20, 1, 1),
        )

        mockMvc.get("/api/v1/apuntes?grupoId=5&subtemaId=50&q=avl").andExpect {
            status { isOk() }
            jsonPath("$.content[0].id") { value(100) }
            jsonPath("$.size") { value(20) }
        }
        org.mockito.kotlin.verify(noteService).search(5L, 50L, "avl", 0, 20)
    }

    @Test
    fun `size mayor a 100 o page negativo responden 400`() {
        mockMvc.get("/api/v1/apuntes?size=101").status(400)
        mockMvc.get("/api/v1/apuntes?size=0").status(400)
        mockMvc.get("/api/v1/apuntes?page=-1").status(400)
    }

    @Test
    fun `un profesor recibe 403 al listar`() {
        whenever(noteService.search(anyOrNull(), anyOrNull(), anyOrNull(), any(), any())).thenThrow(ForbiddenException("Profesor"))

        mockMvc.get("/api/v1/apuntes").status(403)
    }

    @Test
    fun `GET apunte por id - 200 el dueno, 403 el estudiante B o un profesor, 404 si no existe`() {
        whenever(noteService.findById(100L)).thenReturn(note)
        whenever(noteService.findById(200L)).thenThrow(ForbiddenException("Apunte ajeno"))
        whenever(noteService.findById(999L)).thenThrow(NotFoundException("No existe"))

        mockMvc.get("/api/v1/apuntes/100").andExpect {
            status { isOk() }
            jsonPath("$.confirmedText") { value("Rotación simple") }
        }
        mockMvc.get("/api/v1/apuntes/200").status(403)
        mockMvc.get("/api/v1/apuntes/999").status(404)
    }

    @Test
    fun `PATCH y DELETE de apunte`() {
        whenever(noteService.update(eq(100L), any())).thenReturn(note)
        whenever(noteService.update(eq(200L), any())).thenThrow(ForbiddenException("Apunte ajeno"))
        whenever(noteService.update(eq(999L), any())).thenThrow(NotFoundException("No existe"))
        whenever(noteService.delete(200L)).thenThrow(ForbiddenException("Apunte ajeno"))
        whenever(noteService.delete(999L)).thenThrow(NotFoundException("No existe"))
        val patch = """{"confirmedText":"Texto corregido"}"""

        mockMvc.patch("/api/v1/apuntes/100") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(200)
        mockMvc.patch("/api/v1/apuntes/100") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"confirmedText":""}""" }.status(400)
        mockMvc.patch("/api/v1/apuntes/200") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(403)
        mockMvc.patch("/api/v1/apuntes/999") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = patch }.status(404)
        mockMvc.delete("/api/v1/apuntes/100") { with(csrf()) }.status(204)
        mockMvc.delete("/api/v1/apuntes/200") { with(csrf()) }.status(403)
        mockMvc.delete("/api/v1/apuntes/999") { with(csrf()) }.status(404)
    }

    @Test
    @WithAnonymousUser
    fun `sin sesion responde 401`() {
        mockMvc.get("/api/v1/apuntes").andExpect { status { isUnauthorized() } }
    }
}

// ==================== API-07 Cobertura ====================

@WebMvcTest(CoberturaController::class)
@WithMockUser
class CoberturaControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockBean
    lateinit var coverageService: CoverageService

    @MockBean
    lateinit var noteAuditService: NoteAuditService

    @Test
    fun `POST auditar responde 200 con los veredictos y mapea los errores`() {
        whenever(noteAuditService.audit(100L)).thenReturn(listOf(SubtopicCoverageDTO(50L, "4.1", "AVL", "COVERED")))
        whenever(noteAuditService.audit(200L)).thenThrow(ForbiddenException("Apunte ajeno"))
        whenever(noteAuditService.audit(300L)).thenThrow(BusinessRuleException("El apunte no tiene texto confirmado"))
        whenever(noteAuditService.audit(400L)).thenThrow(AiUnavailableException("timeout"))

        mockMvc.post("/api/v1/apuntes/100/auditar") { with(csrf()) }.andExpect {
            status { isOk() }
            jsonPath("$[0].status") { value("COVERED") }
        }
        mockMvc.post("/api/v1/apuntes/200/auditar") { with(csrf()) }.status(403)
        mockMvc.post("/api/v1/apuntes/300/auditar") { with(csrf()) }.status(422)
        mockMvc.post("/api/v1/apuntes/400/auditar") { with(csrf()) }.status(503)
    }

    @Test
    fun `PUT auditoria responde 200 y los errores del servicio`() {
        whenever(coverageService.registerAudit(eq(100L), any())).thenReturn(listOf(SubtopicCoverageDTO(50L, "4.1", "AVL", "COVERED")))
        whenever(coverageService.registerAudit(eq(200L), any())).thenThrow(ForbiddenException("Apunte ajeno"))
        whenever(coverageService.registerAudit(eq(999L), any())).thenThrow(NotFoundException("No existe"))
        whenever(coverageService.registerAudit(eq(300L), any())).thenThrow(BusinessRuleException("Subtema de otro curso"))
        val body = """{"verdicts":[{"subtopicId":50,"status":"COVERED","evidence":"Explica la rotación"}]}"""

        mockMvc.put("/api/v1/apuntes/100/auditoria") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.andExpect {
            status { isOk() }
            jsonPath("$[0].status") { value("COVERED") }
        }
        mockMvc.put("/api/v1/apuntes/100/auditoria") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"verdicts":[]}""" }.status(400)
        mockMvc.put("/api/v1/apuntes/100/auditoria") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"verdicts":[{"subtopicId":50,"status":"CUBIERTO"}]}""" }.status(400)
        mockMvc.put("/api/v1/apuntes/200/auditoria") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(403)
        mockMvc.put("/api/v1/apuntes/999/auditoria") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(404)
        mockMvc.put("/api/v1/apuntes/300/auditoria") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }.status(422)
    }

    @Test
    fun `GET mi cobertura - 200, 403 para un profesor y 404`() {
        whenever(coverageService.personalCoverage(5L)).thenReturn(PersonalCoverageDTO(5L, 50.0, listOf(SubtopicCoverageDTO(50L, "4.1", "AVL", "COVERED"))))
        whenever(coverageService.personalCoverage(6L)).thenThrow(ForbiddenException("Profesor"))
        whenever(coverageService.personalCoverage(99L)).thenThrow(NotFoundException("Grupo no existe"))

        mockMvc.get("/api/v1/grupos/5/cobertura/mia").andExpect {
            status { isOk() }
            jsonPath("$.coveredPercentage") { value(50.0) }
        }
        mockMvc.get("/api/v1/grupos/6/cobertura/mia").status(403)
        mockMvc.get("/api/v1/grupos/99/cobertura/mia").status(404)
    }

    @Test
    fun `GET cobertura del grupo - 200 sin datos de estudiantes, 403 para un estudiante y 404`() {
        whenever(coverageService.groupCoverage(5L)).thenReturn(
            GroupCoverageDTO(5L, 3, listOf(AggregatedSubtopicCoverageDTO(50L, "4.1", "AVL", 33.3, 33.3, 33.4))),
        )
        whenever(coverageService.groupCoverage(6L)).thenThrow(ForbiddenException("Estudiante"))
        whenever(coverageService.groupCoverage(99L)).thenThrow(NotFoundException("Grupo no existe"))

        val body = mockMvc.get("/api/v1/grupos/5/cobertura").andExpect {
            status { isOk() }
            jsonPath("$.totalStudents") { value(3) }
        }.andReturn().response.contentAsString
        listOf("studentId", "userId", "email", "\"name\"", "firstName", "estudianteId", "usuarioId", "correo", "nombre").forEach {
            assertTrue(it !in body, "La cobertura del grupo contiene $it")
        }
        mockMvc.get("/api/v1/grupos/6/cobertura").status(403)
        mockMvc.get("/api/v1/grupos/99/cobertura").status(404)
    }

    @Test
    @WithAnonymousUser
    fun `sin sesion responde 401`() {
        mockMvc.get("/api/v1/grupos/5/cobertura").andExpect { status { isUnauthorized() } }
    }
}

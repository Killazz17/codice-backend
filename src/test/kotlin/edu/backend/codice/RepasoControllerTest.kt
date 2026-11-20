package edu.backend.codice

import edu.backend.codice.exception.AiUnavailableException
import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ForbiddenException
import edu.backend.codice.exception.InvalidInputException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.time.LocalDate

private fun ResultActionsDsl.status(code: Int) = andExpect { status { isEqualTo(code) } }

// IA-05: traducción HTTP de los repasos. El servicio está simulado; aquí se prueban rutas, códigos y el contrato JSON.
@WebMvcTest(RepasoController::class)
@WithMockUser
class RepasoControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockBean
    lateinit var reviewService: ReviewService

    private val pending = PendingReviewDTO(
        id = 5L, subtopicId = 50L, subtopicCode = "1.1", subtopicTitle = "AVL", intervalDay = 3,
        nextDate = LocalDate.of(2026, 11, 3), result = "PENDING",
        questions = listOf(QuizQuestionDTO("¿Qué es un AVL?", listOf("Un árbol", "Una lista", "Un mapa", "Una pila"), 0, "Es un árbol balanceado")),
        origin = QuizOriginDTO(100L, "No menciona rotaciones", Instant.parse("2026-10-27T15:00:00Z")),
    )

    private val resultBody = """{"result":"PASSED"}"""

    @Test
    fun `GET pendientes devuelve las preguntas y el origen del vacio`() {
        whenever(reviewService.pending()).thenReturn(listOf(pending))

        mockMvc.get("/api/v1/repasos/pendientes").andExpect {
            status { isOk() }
            jsonPath("$[0].intervalDay") { value(3) }
            jsonPath("$[0].nextDate") { value("2026-11-03") }
            jsonPath("$[0].questions[0].opciones.length()") { value(4) }
            jsonPath("$[0].questions[0].correcta") { value(0) }
            jsonPath("$[0].origin.noteId") { value(100) }
        }
    }

    @Test
    fun `GET programados devuelve los repasos que aun no vencen y responde 403 a un profesor`() {
        whenever(reviewService.scheduled()).thenReturn(listOf(pending)).thenThrow(ForbiddenException("Los repasos son del estudiante"))

        mockMvc.get("/api/v1/repasos/programados").andExpect {
            status { isOk() }
            jsonPath("$[0].nextDate") { value("2026-11-03") }
            jsonPath("$[0].questions[0].correcta") { value(0) }
        }
        mockMvc.get("/api/v1/repasos/programados").status(403)
    }

    @Test
    fun `GET pendientes responde 403 a un profesor`() {
        whenever(reviewService.pending()).thenThrow(ForbiddenException("Los repasos son del estudiante"))

        mockMvc.get("/api/v1/repasos/pendientes").status(403)
    }

    @Test
    @WithAnonymousUser
    fun `sin sesion responde 401`() {
        mockMvc.get("/api/v1/repasos/pendientes").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `POST generar responde 201 con los repasos creados, y 503 si la IA falla`() {
        whenever(reviewService.generate()).thenReturn(listOf(pending)).thenThrow(AiUnavailableException("timeout"))

        mockMvc.post("/api/v1/repasos/generar") { with(csrf()) }.andExpect {
            status { isCreated() }
            header { string("Location", "/api/v1/repasos/pendientes") }
            jsonPath("$[0].subtopicId") { value(50) }
        }
        mockMvc.post("/api/v1/repasos/generar") { with(csrf()) }.status(503)
    }

    @Test
    fun `POST resultado responde 200 con el repaso reprogramado`() {
        val updated = ReviewDTO(5L, 1L, 50L, "1.1", "AVL", 7, LocalDate.of(2026, 11, 7), "PASSED", Instant.parse("2026-10-27T15:00:00Z"))
        whenever(reviewService.recordResult(5L, "PASSED")).thenReturn(updated)

        mockMvc.post("/api/v1/repasos/5/resultado") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = resultBody }.andExpect {
            status { isOk() }
            jsonPath("$.intervalDay") { value(7) }
            jsonPath("$.result") { value("PASSED") }
        }
    }

    @Test
    fun `POST resultado mapea 400, 403 y 422 segun el error del servicio`() {
        whenever(reviewService.recordResult(6L, "PASSED")).thenThrow(InvalidInputException("El resultado debe ser PASSED o FAILED"))
        whenever(reviewService.recordResult(7L, "PASSED")).thenThrow(ForbiddenException("Solo el dueño del repaso"))
        whenever(reviewService.recordResult(8L, "PASSED")).thenThrow(BusinessRuleException("Este repaso todavía no vence"))

        mockMvc.post("/api/v1/repasos/6/resultado") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = resultBody }.status(400)
        mockMvc.post("/api/v1/repasos/7/resultado") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = resultBody }.status(403)
        mockMvc.post("/api/v1/repasos/8/resultado") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = resultBody }.status(422)
    }

    @Test
    fun `POST resultado sin resultado en el cuerpo responde 400`() {
        mockMvc.post("/api/v1/repasos/5/resultado") { with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"result":"  "}""" }.status(400)
    }
}

package edu.backend.codice

import edu.backend.codice.exception.BusinessRuleException
import edu.backend.codice.exception.ForbiddenException
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant

/*
 * Lo que el profesor gestiona de su grupo desde la app: la lista de matrículas (pendientes e
 * inscritas), regenerar el código de invitación y si unirse pide aprobación. La lógica y los 403
 * los decide el servicio; aquí se prueba la traducción HTTP.
 */
@WebMvcTest(StudentGroupController::class)
@WithMockUser
class TeacherGroupControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockBean
    lateinit var studentGroupService: StudentGroupService

    @MockBean
    lateinit var enrollmentService: EnrollmentService

    private fun group(code: String = "EIF411-01-7K2A", requiresApproval: Boolean = true) = StudentGroupDTO(
        id = 20L, courseId = 10L, courseCode = "EIF411", courseName = "Móviles", teacherId = 1L,
        teacherName = "Laura Jiménez", number = "01", period = "2026-II", schedule = null,
        inviteCode = code, requiresApproval = requiresApproval, inviteExpiresAt = null,
    )

    @Test
    fun `GET matriculas del grupo filtra por estado`() {
        val ana = EnrollmentRequestSummaryDTO(5L, "Ana Vargas", "ana@example.com", "ACTIVE", Instant.parse("2026-10-12T15:00:00Z"))
        whenever(enrollmentService.listRequests(20L, "ACTIVE")).thenReturn(listOf(ana))
        whenever(enrollmentService.listRequests(20L, null)).thenReturn(listOf(ana))

        mockMvc.get("/api/v1/groups/20/enrollments?status=ACTIVE").andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(5) }
            jsonPath("$[0].studentName") { value("Ana Vargas") }
            jsonPath("$[0].status") { value("ACTIVE") }
        }
        mockMvc.get("/api/v1/groups/20/enrollments").andExpect { status { isOk() } }
    }

    @Test
    fun `GET matriculas responde 403 a quien no es el profesor y 422 con un estado invalido`() {
        whenever(enrollmentService.listRequests(21L, null)).thenThrow(ForbiddenException("Solo el profesor del grupo"))
        whenever(enrollmentService.listRequests(20L, "OTRO")).thenThrow(BusinessRuleException("Estado inválido: OTRO"))

        mockMvc.get("/api/v1/groups/21/enrollments").andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/groups/20/enrollments?status=OTRO").andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `POST codigo regenera el codigo de invitacion`() {
        whenever(studentGroupService.regenerateInviteCode(20L)).thenReturn(group(code = "EIF411-01-NEW1"))

        mockMvc.post("/api/v1/groups/20/invite-code") { with(csrf()) }.andExpect {
            status { isOk() }
            jsonPath("$.inviteCode") { value("EIF411-01-NEW1") }
        }
    }

    @Test
    fun `PATCH grupo cambia si unirse pide aprobacion y valida el body`() {
        whenever(studentGroupService.updateRequiresApproval(20L, false)).thenReturn(group(requiresApproval = false))

        mockMvc.patch("/api/v1/groups/20") {
            with(csrf()); contentType = MediaType.APPLICATION_JSON; content = """{"requiresApproval":false}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.requiresApproval") { value(false) }
        }
        mockMvc.patch("/api/v1/groups/20") {
            with(csrf()); contentType = MediaType.APPLICATION_JSON; content = "{}"
        }.andExpect { status { isBadRequest() } }
    }
}

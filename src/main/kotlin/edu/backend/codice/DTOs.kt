package edu.backend.codice

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

// ==================== TRANSVERSAL DTOs ====================

data class FieldErrorDto(
    val field: String,
    val message: String
)

data class ErrorResponse(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val error: String,
    val message: String? = null,
    val path: String,
    val errors: List<FieldErrorDto> = emptyList()
)

data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

// ==================== AUTH & USER DTOs (DTO-02) ====================

/*
 * Los nombres del enunciado y los de este proyecto no coinciden porque la app móvil ya manda y lee estos campos
 * (AuthDto.kt); renombrarlos la rompería. Equivalencias:
 *   RegistroRequest(nombre, correo, password, rol) → UserSignUpInput(firstName + lastName, email, password, roleName)
 *   LoginRequest(correo, password)                  → UserLoginInput(username = el correo, password)
 *   UsuarioResponse(id, nombre, correo, rol, creadoEn) → UserResult(id, firstName, lastName, email, roles, createdAt)
 *   AuthResponse(token, tipo, expiraEn, usuario)    → AuthResponse(token, tokenType, expiresAt, user)
 * Las fechas salen en ISO-8601 UTC y ningún DTO de salida lleva la contraseña ni su hash.
 */

// Al menos una letra (de cualquier idioma) y un número; el largo mínimo lo valida @Size.
private const val PASSWORD_WITH_LETTER_AND_DIGIT = "^(?=.*\\p{L})(?=.*\\p{Nd}).+\$"

// Los dos únicos roles que existen en la tabla role (CHECK de V1__schema.sql).
private const val ALLOWED_ROLES = "ROLE_STUDENT|ROLE_TEACHER"

data class UserSignUpInput(
    @field:NotBlank(message = "El nombre es obligatorio")
    @field:Size(min = 2, max = 100, message = "El nombre debe tener entre 2 y 100 caracteres")
    val firstName: String? = null,

    @field:NotBlank(message = "El apellido es obligatorio")
    @field:Size(min = 2, max = 100, message = "El apellido debe tener entre 2 y 100 caracteres")
    val lastName: String? = null,

    @field:NotBlank(message = "El email es obligatorio")
    @field:Email(message = "Formato de email inválido")
    @field:Size(max = 255, message = "El email no puede pasar de 255 caracteres")
    val email: String? = null,

    @field:NotBlank(message = "La contraseña es obligatoria")
    @field:Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
    @field:Pattern(regexp = PASSWORD_WITH_LETTER_AND_DIGIT, message = "La contraseña debe tener al menos una letra y un número")
    val password: String? = null,

    // Obligatorio: la app siempre lo manda. Nulo (y no un valor por defecto) para que su ausencia sea un 400 por campo.
    @field:NotBlank(message = "El rol es obligatorio")
    @field:Pattern(regexp = ALLOWED_ROLES, message = "El rol debe ser ROLE_STUDENT o ROLE_TEACHER")
    val roleName: String? = null
)

// Sigue siendo var con valores por defecto: el filtro del login clásico (/v1/users/login) lo lee con un ObjectMapper
// sin el módulo de Kotlin, que necesita un constructor vacío y setters.
data class UserLoginInput(
    @field:NotBlank(message = "El email/username es obligatorio")
    @field:Email(message = "Formato de email inválido")
    var username: String = "",

    @field:NotBlank(message = "La contraseña es obligatoria")
    var password: String = ""
)

// Cambiar la contraseña estando con sesión iniciada. Pide la actual para confirmar que es la persona y no alguien con el
// teléfono prestado; la nueva sigue las mismas reglas que al registrarse. Campos nulos para que su ausencia sea un 400 por campo.
data class ChangePasswordInput(
    @field:NotBlank(message = "La contraseña actual es obligatoria")
    val currentPassword: String? = null,

    @field:NotBlank(message = "La contraseña nueva es obligatoria")
    @field:Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
    @field:Pattern(regexp = PASSWORD_WITH_LETTER_AND_DIGIT, message = "La contraseña debe tener al menos una letra y un número")
    val newPassword: String? = null
)

data class AuthResponse(
    val token: String,
    val tokenType: String = "Bearer",
    // Cuándo vence el token (ISO-8601 UTC): el cliente sabe cuándo debe volver a iniciar sesión.
    val expiresAt: Instant,
    val user: UserResult
)

data class UserResult(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val email: String,
    val enabled: Boolean,
    val roles: List<String>,
    // Fecha de registro (ISO-8601 UTC).
    val createdAt: Instant
)

typealias UserSignUpRequest = UserSignUpInput
typealias UserLoginRequest = UserLoginInput
typealias UserResponse = UserResult

// ==================== CURSOS, GRUPOS Y MATRÍCULA DTOs ====================

/*
 * DTO-03. Equivalencias con los nombres del enunciado (la app móvil ya usa estos campos, por eso no se renombran):
 *   CursoCreateRequest(nombre, codigo, color)        → CourseCreateDTO(name, code, color)            alias CourseCreateRequest
 *   CursoUpdateRequest (campos opcionales)           → CourseUpdateDTO(name?, code?, color?)         alias CourseUpdateRequest
 *   CursoResponse(..., profesorId, creadoEn)         → CourseDTO(..., teacherId, createdAt)          alias CourseResponse
 *   CursoResumenResponse(..., totalGrupos)           → CourseSummaryDTO(..., totalGroups)            alias CourseSummaryResponse
 *   GrupoCreateRequest(numero, horario, requiere...) → GroupCreateDTO(number, period, schedule, ...)   alias GroupCreateRequest
 *   GrupoUpdateRequest(horario?, requiere...?)       → GroupUpdateDTO(schedule?, requiresApproval?)    alias GroupUpdateRequest
 *   GrupoResponse (profesor dueño, con código)       → StudentGroupDTO                              alias StudentGroupResponse
 *   GrupoPublicoResponse (estudiante, sin código)    → StudentGroupPublicDTO                         alias StudentGroupPublicResponse
 * GroupCreateDTO lleva además period porque la columna student_group.period es NOT NULL y no hay de dónde deducirlo.
 */

// 'EIF411': entre 5 y 10 letras mayúsculas y números, con al menos una letra y un número. La app ya los manda en
// mayúsculas y sin espacios (TeacherCreateCourseViewModel); el UNIQUE de course.code distingue mayúsculas de minúsculas.
private const val COURSE_CODE_PATTERN = "^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{5,10}\$"

private const val HEX_COLOR_PATTERN = "^#[0-9A-Fa-f]{6}\$"

// Entero positivo de uno o dos dígitos: '1', '01', '12'. Solo dos porque el número va en dos caracteres dentro del
// código de invitación (EIF411-01-7K2A) y la app únicamente reconoce ese largo (ResolveInviteCodeUseCase).
private const val GROUP_NUMBER_PATTERN = "^(0[1-9]|[1-9][0-9]?)\$"

// Para los campos opcionales de un PATCH: ausente (null) significa "no cambiar", pero si viene no puede ser vacío.
private const val NOT_ONLY_SPACES_PATTERN = "(?s).*\\S.*"

@Schema(example = """{"code": "EIF411", "name": "Diseño y Programación de Plataformas Móviles", "color": "#C8F250"}""")
data class CourseCreateDTO(
    @field:NotBlank(message = "El código del curso es obligatorio")
    @field:Pattern(regexp = COURSE_CODE_PATTERN, message = "El código del curso debe tener de 5 a 10 letras mayúsculas y números, por ejemplo EIF411")
    val code: String,

    @field:NotBlank(message = "El nombre del curso es obligatorio")
    @field:Size(max = 255, message = "El nombre del curso no puede pasar de 255 caracteres")
    val name: String,

    @field:Pattern(regexp = HEX_COLOR_PATTERN, message = "El color debe tener el formato #RRGGBB, por ejemplo #C8F250")
    val color: String = "#C8F250"
)

// PATCH de un curso: solo cambia lo que viene. Cada campo, si viene, cumple la misma regla que al crear.
@Schema(example = """{"name": "Plataformas Móviles II", "color": "#1E90FF"}""")
data class CourseUpdateDTO(
    @field:Pattern(regexp = COURSE_CODE_PATTERN, message = "El código del curso debe tener de 5 a 10 letras mayúsculas y números, por ejemplo EIF411")
    val code: String? = null,

    @field:Pattern(regexp = NOT_ONLY_SPACES_PATTERN, message = "El nombre del curso no puede estar vacío")
    @field:Size(max = 255, message = "El nombre del curso no puede pasar de 255 caracteres")
    val name: String? = null,

    @field:Pattern(regexp = HEX_COLOR_PATTERN, message = "El color debe tener el formato #RRGGBB, por ejemplo #C8F250")
    val color: String? = null
)

@Schema(example = """{"id": 1, "code": "EIF411", "name": "Diseño y Programación de Plataformas Móviles", "color": "#C8F250", "teacherId": 3, "createdAt": "2026-10-13T20:00:00Z", "syllabusPublishedAt": null}""")
data class CourseDTO(
    val id: Long,
    val code: String,
    val name: String,
    val color: String,
    // Profesor dueño. Nulo solo en cursos anteriores a la migración V3 que nunca tuvieron grupos.
    val teacherId: Long?,
    val createdAt: Instant,
    val syllabusPublishedAt: Instant?
)

// Lo mínimo de un curso para los listados: sin temario ni fechas, con cuántos grupos tiene.
@Schema(example = """{"id": 1, "code": "EIF411", "name": "Diseño y Programación de Plataformas Móviles", "color": "#C8F250", "totalGroups": 2}""")
data class CourseSummaryDTO(
    val id: Long,
    val code: String,
    val name: String,
    val color: String,
    val totalGroups: Int
)

typealias CourseCreateRequest = CourseCreateDTO
typealias CourseUpdateRequest = CourseUpdateDTO
typealias CourseResponse = CourseDTO
typealias CourseSummaryResponse = CourseSummaryDTO

data class StudentGroupCreateDTO(
    @field:NotNull(message = "El ID del curso es obligatorio")
    val courseId: Long,

    @field:NotBlank(message = "El número de grupo es obligatorio")
    @field:Pattern(regexp = GROUP_NUMBER_PATTERN, message = "El número de grupo debe ser un entero positivo de uno o dos dígitos, por ejemplo 01")
    val number: String,

    @field:NotBlank(message = "El periodo es obligatorio")
    @field:Size(max = 50, message = "El periodo no puede pasar de 50 caracteres")
    val period: String,

    @field:Size(max = 255, message = "El horario no puede pasar de 255 caracteres")
    val schedule: String? = null,

    // Lo genera el servidor (CURSO-NN-XXXX, vence a los 30 días); si el cliente lo manda, se ignora.
    val inviteCode: String? = null,

    val requiresApproval: Boolean = true,

    val inviteExpiresAt: Instant? = null
)

// Cuerpo de crear un grupo dentro de un curso: el curso viene en la ruta, no en el cuerpo.
@Schema(example = """{"number": "01", "period": "2026-II", "schedule": "Lunes y jueves 18:00-19:30", "requiresApproval": true}""")
data class GroupCreateDTO(
    @field:NotBlank(message = "El número de grupo es obligatorio")
    @field:Pattern(regexp = GROUP_NUMBER_PATTERN, message = "El número de grupo debe ser un entero positivo de uno o dos dígitos, por ejemplo 01")
    val number: String,

    @field:NotBlank(message = "El periodo es obligatorio")
    @field:Size(max = 50, message = "El periodo no puede pasar de 50 caracteres")
    val period: String,

    @field:Size(max = 255, message = "El horario no puede pasar de 255 caracteres")
    val schedule: String? = null,

    val requiresApproval: Boolean = true
)

// PATCH de un grupo: solo cambia lo que viene. Un horario ausente no se toca (no hay forma de dejarlo en blanco).
@Schema(example = """{"schedule": "Martes y viernes 08:00-09:30", "requiresApproval": false}""")
data class GroupUpdateDTO(
    @field:Size(max = 255, message = "El horario no puede pasar de 255 caracteres")
    val schedule: String? = null,

    val requiresApproval: Boolean? = null
)

// Un listado de grupos devuelve una u otra forma según quién pregunta: StudentGroupDTO (con el código de invitación) al
// profesor dueño y StudentGroupPublicDTO (sin él) al estudiante. Jackson serializa cada elemento con los campos de su clase.
sealed interface GroupView

// Lo que ve el profesor dueño: incluye el código de invitación.
@Schema(example = """{"id": 20, "courseId": 1, "courseCode": "EIF411", "courseName": "Diseño y Programación de Plataformas Móviles", "teacherId": 3, "teacherName": "Carlos Mora", "number": "01", "period": "2026-II", "schedule": "Lunes y jueves 18:00-19:30", "inviteCode": "EIF411-01-7K2A", "requiresApproval": true, "inviteExpiresAt": "2026-11-12T20:00:00Z"}""")
data class StudentGroupDTO(
    val id: Long,
    val courseId: Long,
    val courseCode: String?,
    val courseName: String?,
    val teacherId: Long,
    val teacherName: String?,
    val number: String,
    val period: String,
    val schedule: String?,
    val inviteCode: String,
    val requiresApproval: Boolean,
    val inviteExpiresAt: Instant?
) : GroupView

// Lo que ve el estudiante: el grupo sin el código de invitación. Quien lista los grupos de un curso no debe poder
// unirse a uno que el profesor no le compartió, por eso aquí no existe ningún campo con el código.
@Schema(example = """{"id": 20, "courseId": 1, "number": "01", "schedule": "Lunes y jueves 18:00-19:30"}""")
data class StudentGroupPublicDTO(
    val id: Long,
    val courseId: Long,
    val number: String,
    val schedule: String?
) : GroupView

typealias StudentGroupCreateRequest = StudentGroupCreateDTO
typealias GroupCreateRequest = GroupCreateDTO
typealias GroupUpdateRequest = GroupUpdateDTO
typealias StudentGroupResponse = StudentGroupDTO
typealias StudentGroupPublicResponse = StudentGroupPublicDTO

data class EnrollmentRequestDTO(
    @field:NotNull(message = "El ID del grupo es obligatorio")
    val groupId: Long
)

data class EnrollmentStatusUpdateDTO(
    @field:NotBlank(message = "El estado es obligatorio")
    val status: String
)

@Schema(example = """{"id": 7, "groupId": 20, "groupNumber": "01", "courseId": 1, "courseCode": "EIF411", "courseName": "Diseño y Programación de Plataformas Móviles", "studentId": 12, "studentName": "Ana Vargas", "status": "PENDING", "requestedAt": "2026-10-13T20:15:00Z", "resolvedAt": null}""")
data class EnrollmentDTO(
    val id: Long,
    val groupId: Long,
    val groupNumber: String?,
    val courseId: Long,
    val courseCode: String?,
    val courseName: String?,
    val studentId: Long,
    val studentName: String?,
    val status: String,
    val requestedAt: Instant,
    val resolvedAt: Instant?
)

typealias EnrollmentRequest = EnrollmentRequestDTO
typealias EnrollmentStatusUpdateRequest = EnrollmentStatusUpdateDTO
typealias EnrollmentResponse = EnrollmentDTO

// El estudiante se une con el código que le pasa el profesor: CURSO-GRUPO-SUFIJO, como 'EIF411-01-7K2A'.
// Solo se valida la forma (tres partes alfanuméricas); el código lo escribe el profesor al crear el grupo,
// así que el largo de cada parte varía. Se aceptan minúsculas: el servicio lo busca en mayúsculas.
@Schema(example = """{"inviteCode": "EIF411-01-7K2A"}""")
data class JoinGroupDTO(
    @field:NotBlank(message = "El código de invitación es obligatorio")
    @field:Size(max = 30, message = "El código de invitación no puede pasar de 30 caracteres")
    @field:Pattern(
        regexp = "^(?i)[A-Z0-9]+-[A-Z0-9]+-[A-Z0-9]+$",
        message = "El código de invitación debe tener el formato CURSO-GRUPO-SUFIJO, por ejemplo EIF411-01-7K2A"
    )
    val inviteCode: String
)

// Decisión del profesor sobre una solicitud: APPROVE la deja ACTIVE y REJECT la deja REJECTED.
@Schema(example = """{"decision": "APPROVE"}""")
data class EnrollmentDecisionDTO(
    @field:NotBlank(message = "La decisión es obligatoria")
    @field:Pattern(regexp = "^(APPROVE|REJECT)$", message = "La decisión debe ser APPROVE o REJECT")
    val decision: String
)

// Lo que el profesor ve de cada solicitud: solo nombre y correo del estudiante, nunca sus apuntes ni su cobertura.
@Schema(example = """{"id": 7, "studentName": "Ana Vargas", "studentEmail": "ana.vargas@est.una.ac.cr", "status": "PENDING", "requestedAt": "2026-10-13T20:15:00Z"}""")
data class EnrollmentRequestSummaryDTO(
    val id: Long,
    val studentName: String,
    val studentEmail: String,
    val status: String,
    val requestedAt: Instant
)

// PATCH /groups/{id}: por ahora solo si unirse con el código pide la aprobación del profesor.
data class GroupSettingsDTO(
    @field:NotNull(message = "requiresApproval es obligatorio")
    val requiresApproval: Boolean?
)

typealias JoinGroupRequest = JoinGroupDTO
typealias EnrollmentDecisionRequest = EnrollmentDecisionDTO
typealias EnrollmentRequestSummaryResponse = EnrollmentRequestSummaryDTO

// ==================== TEMARIO DTOs ====================

data class ModuleCreateDTO(
    @field:NotNull(message = "El número de módulo es obligatorio")
    val number: Int,

    @field:NotBlank(message = "El título es obligatorio")
    @field:Size(max = 255, message = "El título no puede pasar de 255 caracteres")
    val title: String
)

// Actualización parcial del módulo: un campo en null no se toca, pero si viene no puede quedar en blanco.
data class ModuleUpdateDTO(
    @field:Pattern(regexp = "(?s).*\\S.*", message = "El título no puede quedar en blanco")
    @field:Size(max = 255, message = "El título no puede pasar de 255 caracteres")
    val title: String? = null,

    @field:Positive(message = "El número de módulo debe ser mayor que cero")
    val number: Int? = null
)

// El orden nuevo de los módulos de un curso: sus ids de primero a último. Debe traer cada módulo una vez.
data class ModuleOrderDTO(
    @field:NotEmpty(message = "La lista de módulos no puede estar vacía")
    val moduleIds: List<Long> = emptyList()
)

data class SubtopicCreateDTO(
    @field:NotBlank(message = "El código es obligatorio")
    val code: String,

    @field:NotBlank(message = "El título es obligatorio")
    val title: String,

    val position: Int = 0
)

data class SubtopicObjectiveDTO(
    val id: Long?,
    val description: String
)

data class SubtopicDTO(
    val id: Long,
    val moduleId: Long,
    val code: String,
    val title: String,
    val position: Int,
    val objectives: List<SubtopicObjectiveDTO> = emptyList()
)

data class ModuleDTO(
    val id: Long,
    val courseId: Long,
    val number: Int,
    val title: String,
    val subtopics: List<SubtopicDTO> = emptyList()
)

typealias ModuleCreateRequest = ModuleCreateDTO
typealias ModuleResponse = ModuleDTO
typealias ModuleUpdateRequest = ModuleUpdateDTO
typealias ModuleOrderRequest = ModuleOrderDTO
typealias SubtopicCreateRequest = SubtopicCreateDTO
typealias SubtopicResponse = SubtopicDTO

// Actualización parcial: un campo en null no se toca, pero si viene no puede quedar en blanco.
data class SubtopicUpdateDTO(
    @field:Pattern(regexp = "(?s).*\\S.*", message = "El código no puede quedar en blanco")
    @field:Size(max = 20, message = "El código no puede pasar de 20 caracteres")
    val code: String? = null,

    @field:Pattern(regexp = "(?s).*\\S.*", message = "El título no puede quedar en blanco")
    @field:Size(max = 255, message = "El título no puede pasar de 255 caracteres")
    val title: String? = null,

    @field:PositiveOrZero(message = "La posición no puede ser negativa")
    val position: Int? = null
)

data class ObjectiveCreateDTO(
    @field:NotBlank(message = "La descripción del objetivo es obligatoria")
    @field:Size(max = 500, message = "La descripción no puede pasar de 500 caracteres")
    val description: String
)

// El temario completo de un curso: módulo → subtemas (por position) → objetivos.
data class SyllabusDTO(
    val courseId: Long,
    val published: Boolean,
    val publishedAt: Instant?,
    val modules: List<ModuleDTO> = emptyList()
)

data class SyllabusWarningDTO(
    val subtopicId: Long,
    val subtopicCode: String,
    val message: String
)

// Publicar no se bloquea por subtemas sin objetivos: se publica y se devuelven como advertencias.
data class SyllabusPublishDTO(
    val published: Boolean,
    val publishedAt: Instant?,
    val warnings: List<SyllabusWarningDTO> = emptyList()
)

typealias SubtopicUpdateRequest = SubtopicUpdateDTO
typealias ObjectiveCreateRequest = ObjectiveCreateDTO
typealias ObjectiveResponse = SubtopicObjectiveDTO
typealias SyllabusResponse = SyllabusDTO
typealias SyllabusPublishResponse = SyllabusPublishDTO

// ==================== APUNTES, COBERTURA Y REPASO DTOs ====================

data class NoteCreateDTO(
    @field:NotNull(message = "El ID de matrícula es obligatorio")
    val enrollmentId: Long,

    val subtopicId: Long? = null,

    @field:Size(max = 100, message = "El clientRequestId no puede pasar de 100 caracteres")
    val clientRequestId: String? = null,

    // Opcional al crear: la foto se sube primero y el texto se confirma después. Si viene, no puede estar en blanco.
    @field:Pattern(regexp = "(?s).*\\S.*", message = "El texto confirmado no puede quedar en blanco")
    @field:Size(max = NOTE_TEXT_MAX, message = "El texto confirmado no puede pasar de 20000 caracteres")
    val confirmedText: String? = null
)

// Tope del texto de un apunte: unas 10 páginas de cuaderno transcritas; evita guardar entradas desmedidas.
const val NOTE_TEXT_MAX = 20_000

// Actualización parcial: cambiar el subtema o corregir el texto confirmado. Un campo en null no se toca.
data class NoteUpdateDTO(
    val subtopicId: Long? = null,

    @field:Pattern(regexp = "(?s).*\\S.*", message = "El texto confirmado no puede quedar en blanco")
    @field:Size(max = NOTE_TEXT_MAX, message = "El texto confirmado no puede pasar de 20000 caracteres")
    val confirmedText: String? = null
)

// Para listados: sin texto ni páginas. audited indica si el apunte ya pasó por la auditoría.
// La biblioteca de la app muestra cada tarjeta con su módulo y subtema, y busca sobre el texto, por
// eso el listado trae esos datos además de los básicos (no trae las páginas ni la auditoría completa).
data class NoteSummaryDTO(
    val id: Long,
    val subtopicId: Long?,
    val syncStatus: String,
    val audited: Boolean,
    val createdAt: Instant,
    val confirmedText: String? = null,
    val pageCount: Int = 0,
    val subtopicCode: String? = null,
    val subtopicTitle: String? = null,
    val moduleId: Long? = null,
    // Etiqueta lista para mostrar: "Módulo 4 · Árboles".
    val moduleLabel: String? = null
)

typealias NoteUpdateRequest = NoteUpdateDTO
typealias NoteSummaryResponse = NoteSummaryDTO

data class NotePageDTO(
    val id: Long?,
    val pageIndex: Int,
    val filePath: String
)

data class NoteDTO(
    val id: Long,
    val enrollmentId: Long,
    val subtopicId: Long?,
    val clientRequestId: String?,
    val confirmedText: String?,
    val syncStatus: String,
    val createdAt: Instant,
    val pages: List<NotePageDTO> = emptyList(),
    // true cuando el apunte ya tiene auditoría (note.audit no es null).
    val audited: Boolean = false
)

typealias NoteCreateRequest = NoteCreateDTO
typealias NoteResponse = NoteDTO

data class CoverageDTO(
    val id: Long,
    val studentId: Long,
    val subtopicId: Long,
    val subtopicCode: String?,
    val subtopicTitle: String?,
    val status: String,
    val justification: String?,
    val noteOriginId: Long?,
    val updatedAt: Instant
)

typealias CoverageResponse = CoverageDTO

data class CoverageAuditStubRequest(
    @field:NotNull(message = "El ID del apunte es obligatorio")
    val noteId: Long,

    @field:NotNull(message = "El ID del subtópico es obligatorio")
    val subtopicId: Long
)

data class CoverageAuditStubResponse(
    val coverageId: Long,
    val subtopicId: Long,
    val calculatedStatus: String,
    val justification: String,
    val auditTimestamp: Instant
)

// Auditoría simulada (stub de la futura IA): un veredicto por subtema. REINFORCED no se envía: lo calcula
// el servicio cuando un subtema ya cubierto vuelve a quedar COVERED.
data class SubtopicVerdictDTO(
    @field:NotNull(message = "El ID del subtema es obligatorio")
    val subtopicId: Long,

    @field:NotBlank(message = "El estado es obligatorio")
    @field:Pattern(regexp = "^(COVERED|PARTIAL|NO_NOTES)$", message = "El estado debe ser COVERED, PARTIAL o NO_NOTES")
    val status: String,

    // Termina en coverage.justification, que admite 500 caracteres.
    @field:Size(max = 500, message = "La evidencia no puede pasar de 500 caracteres")
    val evidence: String? = null
)

data class NoteAuditRequestDTO(
    @field:NotEmpty(message = "La auditoría debe traer al menos un veredicto")
    @field:Valid
    val verdicts: List<SubtopicVerdictDTO>
)

typealias NoteAuditRequest = NoteAuditRequestDTO

/*
 * Cobertura. Todos los porcentajes van de 0 a 100 (Double, sin redondear; la app decide cómo mostrarlos).
 * Un subtema REINFORCED cuenta como cubierto.
 */

data class SubtopicCoverageDTO(
    val subtopicId: Long,
    val subtopicCode: String,
    val subtopicTitle: String,
    val status: String,
    // Módulo del subtema, para que la app agrupe el mapa de cobertura sin pedir el temario aparte.
    val moduleNumber: Int? = null,
    val moduleTitle: String? = null
)

// Cobertura del propio estudiante sobre el temario del curso del grupo. coveredPercentage: 0–100.
data class PersonalCoverageDTO(
    val groupId: Long,
    val coveredPercentage: Double,
    val subtopics: List<SubtopicCoverageDTO> = emptyList()
)

// Por subtema, qué parte del grupo lo tiene en cada estado. Los tres porcentajes (0–100) suman 100.
data class AggregatedSubtopicCoverageDTO(
    val subtopicId: Long,
    val subtopicCode: String,
    val subtopicTitle: String,
    val coveredPercentage: Double,
    val partialPercentage: Double,
    val noNotesPercentage: Double,
    // Módulo del subtema, para que la app agrupe la cobertura del grupo igual que el mapa del estudiante.
    val moduleNumber: Int? = null,
    val moduleTitle: String? = null
)

// Lo que ve el profesor: agregada y anónima por diseño. Ningún campo identifica a un estudiante; solo
// se expone cuántos hay en el grupo.
data class GroupCoverageDTO(
    val groupId: Long,
    val totalStudents: Int,
    val subtopics: List<AggregatedSubtopicCoverageDTO> = emptyList()
)

typealias PersonalCoverageResponse = PersonalCoverageDTO
typealias GroupCoverageResponse = GroupCoverageDTO

data class ReviewDTO(
    val id: Long,
    val studentId: Long,
    val subtopicId: Long,
    val subtopicCode: String?,
    val subtopicTitle: String?,
    val intervalDay: Int,
    val nextDate: LocalDate,
    val result: String?,
    val createdAt: Instant
)

data class ReviewResultDTO(
    @field:NotBlank(message = "El resultado es obligatorio (PASSED o FAILED)")
    val result: String
)

typealias ReviewResponse = ReviewDTO

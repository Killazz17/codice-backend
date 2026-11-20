package edu.backend.codice

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.net.URI

// ==================== AUTH CONTROLLER (API-02) ====================

/*
 * Solo valida (@Valid), delega en AuthService y devuelve DTOs. Registro y login son públicos (JwtSecurityConfiguration);
 * /me exige el token. Los errores (400, 401, 409) los convierte GlobalExceptionHandler en el ErrorResponse común.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticación", description = "Endpoints de registro, login y perfil del usuario autenticado")
class AuthController(
    private val authService: AuthService
) {

    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "Registro de usuario", description = "Registra un estudiante (ROLE_STUDENT) o un profesor (ROLE_TEACHER). La contraseña lleva al menos 8 caracteres, con una letra y un número")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Usuario creado"),
        ApiResponse(responseCode = "400", description = "Nombre, correo, contraseña o rol inválidos", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "El correo ya está registrado", content = [Content(schema = Schema(implementation = ErrorResponse::class))])
    )
    fun register(@Valid @RequestBody input: UserSignUpInput): ResponseEntity<UserResult> {
        val result = authService.signUp(input)
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    // El registro se llamaba /signup y la app móvil ya lo usa (AuthApiService); /register es el nombre del enunciado.
    // Es la misma operación: se deja oculto en Swagger para que solo aparezca una.
    @Hidden
    @PostMapping("/signup")
    fun signUp(@Valid @RequestBody input: UserSignUpInput): ResponseEntity<UserResult> = register(input)

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Inicio de sesión", description = "Valida correo (en username) y contraseña, y devuelve el token JWT para el header Authorization: Bearer <token>")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Token, fecha de vencimiento y datos del usuario"),
        ApiResponse(responseCode = "400", description = "Correo o contraseña vacíos o con formato inválido", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Credenciales inválidas", content = [Content(schema = Schema(implementation = ErrorResponse::class))])
    )
    fun login(@Valid @RequestBody input: UserLoginInput): ResponseEntity<AuthResponse> {
        return ResponseEntity.ok(authService.login(input))
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Perfil del usuario", description = "Retorna la información del usuario dueño del token")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Perfil del usuario autenticado"),
        ApiResponse(responseCode = "401", description = "Sin token, o token inválido o vencido", content = [Content(schema = Schema(implementation = ErrorResponse::class))])
    )
    fun getMe(): ResponseEntity<UserResult> {
        val result = authService.getCurrentUser()
        return ResponseEntity.ok(result)
    }

    @PostMapping("/change-password")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Cambiar la contraseña", description = "El usuario con sesión iniciada manda su contraseña actual y la nueva (al menos 8 caracteres, con una letra y un número). La sesión actual sigue valiendo")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Contraseña cambiada"),
        ApiResponse(responseCode = "400", description = "Falta algún campo o la contraseña nueva no cumple las reglas", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "401", description = "Sin token, o token inválido o vencido", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "422", description = "La contraseña actual no es correcta, o la nueva es igual a la actual", content = [Content(schema = Schema(implementation = ErrorResponse::class))])
    )
    fun changePassword(@Valid @RequestBody input: ChangePasswordInput): ResponseEntity<Void> {
        authService.changePassword(input)
        return ResponseEntity.noContent().build()
    }
}

// ==================== COURSE CONTROLLER (API-02) ====================

@RestController
@RequestMapping("/api/v1/courses")
@Tag(name = "Cursos", description = "Endpoints del catálogo de cursos")
class CourseController(
    private val courseService: CourseService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Crear curso", description = "Registra un nuevo curso en el catálogo (Requiere rol PROFESOR)")
    fun create(@Valid @RequestBody input: CourseCreateDTO): ResponseEntity<CourseDTO> {
        val result = courseService.create(input)
        return created(uri("/api/v1/courses/${result.id}"), result)
    }

    @GetMapping
    @Operation(summary = "Listar cursos", description = "Obtiene todos los cursos del catálogo")
    fun findAll(): ResponseEntity<List<CourseDTO>> {
        return ResponseEntity.ok(courseService.findAll())
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtener curso por ID", description = "Obtiene los detalles de un curso por su identificador")
    fun findById(@PathVariable id: Long): ResponseEntity<CourseDTO> {
        return ResponseEntity.ok(courseService.findById(id))
    }
}

// ==================== GROUP CONTROLLER (API-03) ====================

@RestController
@RequestMapping("/api/v1/groups")
@Tag(name = "Grupos / Secciones", description = "Endpoints para la gestión de grupos y secciones de un curso")
class StudentGroupController(
    private val studentGroupService: StudentGroupService,
    private val enrollmentService: EnrollmentService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Crear grupo", description = "Profesor crea una sección/grupo para un curso existente")
    fun createGroup(@Valid @RequestBody input: StudentGroupCreateDTO): ResponseEntity<StudentGroupDTO> {
        val result = studentGroupService.createGroup(input)
        return created(uri("/api/v1/groups/${result.id}"), result)
    }

    @GetMapping("/invite/{code}")
    @Operation(summary = "Buscar grupo por código de invitación", description = "Permite a un estudiante buscar un grupo usando su código QR / de invitación")
    fun findByInviteCode(@PathVariable code: String): ResponseEntity<StudentGroupDTO> {
        return ResponseEntity.ok(studentGroupService.findByInviteCode(code))
    }

    @GetMapping("/course/{courseId}")
    @Operation(
        summary = "Listar grupos de un curso",
        description = "El profesor dueño recibe los grupos con su código de invitación; el estudiante matriculado, solo los datos públicos (sin código)"
    )
    fun findByCourseId(@PathVariable courseId: Long): ResponseEntity<List<GroupView>> {
        return ResponseEntity.ok(studentGroupService.listForCourse(courseId))
    }

    // Lo que el profesor gestiona de su grupo. Aquí se exige el rol; que sea el profesor DE ESE grupo lo decide el servicio.

    @GetMapping("/{groupId}/enrollments")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Matrículas del grupo", description = "Profesor del grupo: solicitudes e inscritos, filtrables por estado (PENDING, ACTIVE o REJECTED). Solo nombre y correo, nunca apuntes ni cobertura")
    fun listEnrollments(@PathVariable groupId: Long, @RequestParam(required = false) status: String?): ResponseEntity<List<EnrollmentRequestSummaryDTO>> {
        return ResponseEntity.ok(enrollmentService.listRequests(groupId, status))
    }

    @DeleteMapping("/{groupId}/enrollments/{enrollmentId}")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(
        summary = "Quitar a un estudiante del grupo",
        description = "Profesor del grupo: la matrícula pasa a REMOVED y el estudiante deja de ver el curso. No se borran sus apuntes. Si quiere volver queda pendiente de aprobación aunque el grupo sea abierto"
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Estudiante quitado"),
        ApiResponse(responseCode = "403", description = "No es el profesor de este grupo", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "El grupo no existe, o la matrícula no es de este grupo", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "La matrícula no está activa", content = [Content(schema = Schema(implementation = ErrorResponse::class))])
    )
    fun removeStudent(@PathVariable groupId: Long, @PathVariable enrollmentId: Long): ResponseEntity<Void> {
        enrollmentService.remove(groupId, enrollmentId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/invite-code")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Regenerar código de invitación", description = "Profesor del grupo: el código anterior deja de funcionar")
    fun regenerateInviteCode(@PathVariable id: Long): ResponseEntity<StudentGroupDTO> {
        return ResponseEntity.ok(studentGroupService.regenerateInviteCode(id))
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Configurar el grupo", description = "Profesor del grupo: si unirse con el código pide su aprobación")
    fun updateSettings(@PathVariable id: Long, @Valid @RequestBody input: GroupSettingsDTO): ResponseEntity<StudentGroupDTO> {
        return ResponseEntity.ok(studentGroupService.updateRequiresApproval(id, input.requiresApproval!!))
    }
}

// ==================== ENROLLMENT CONTROLLER (API-04) ====================

@RestController
@RequestMapping("/api/v1/enrollments")
@Tag(name = "Matrículas", description = "Endpoints para solicitudes y gestión de matrícula")
class EnrollmentController(
    private val enrollmentService: EnrollmentService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(summary = "Solicitar matrícula", description = "Estudiante solicita unirse a un grupo")
    fun requestEnrollment(@Valid @RequestBody input: EnrollmentRequestDTO): ResponseEntity<EnrollmentDTO> {
        val result = enrollmentService.requestEnrollment(input)
        return created(uri("/api/v1/enrollments/${result.id}"), result)
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(summary = "Mis matrículas", description = "Lista las matrículas del estudiante autenticado")
    fun getMyEnrollments(): ResponseEntity<List<EnrollmentDTO>> {
        return ResponseEntity.ok(enrollmentService.getMyEnrollments())
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(
        summary = "Salir del grupo",
        description = "Estudiante sale de su grupo o cancela una solicitud pendiente: la matrícula pasa a LEFT y no se borran sus apuntes. Puede volver a unirse con el código"
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Salió del grupo"),
        ApiResponse(responseCode = "403", description = "La matrícula es de otro estudiante", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "404", description = "La matrícula no existe", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
        ApiResponse(responseCode = "409", description = "La matrícula ya no está activa ni pendiente", content = [Content(schema = Schema(implementation = ErrorResponse::class))])
    )
    fun leave(@PathVariable id: Long): ResponseEntity<Void> {
        enrollmentService.leave(id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/group/{groupId}")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Solicitudes del grupo", description = "Profesor obtiene solicitudes de matrícula de su grupo")
    fun getGroupEnrollments(@PathVariable groupId: Long): ResponseEntity<List<EnrollmentDTO>> {
        return ResponseEntity.ok(enrollmentService.getGroupEnrollments(groupId))
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Resolver matrícula", description = "Profesor aprueba (ACTIVE) o rechaza (REJECTED) una matrícula")
    fun updateStatus(
        @PathVariable id: Long,
        @Valid @RequestBody input: EnrollmentStatusUpdateDTO
    ): ResponseEntity<EnrollmentDTO> {
        val updated = enrollmentService.updateStatus(id, input.status)
        return ResponseEntity.ok(updated)
    }
}

// ==================== TEMARIO CONTROLLER (API-05) ====================
/*
 * Estos controladores (temario, apuntes y cobertura) no llevan @PreAuthorize: aquí no basta el rol, depende de quién es el
 * dueño (profesor del curso, estudiante aprobado, dueño del apunte), y eso lo decide el servicio, que lanza
 * ForbiddenException → 403. Así la regla vive en un solo lugar. Las rutas que son solo de un rol (crear cursos y grupos,
 * resolver matrículas...) sí llevan @PreAuthorize arriba, ahora que el filtro JWT carga los roles (API-02).
 */

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Temario", description = "Temario del curso: módulos, subtemas, objetivos y publicación (API-05)")
class TemarioController(
    private val syllabusService: SyllabusService,
    private val syllabusExtractionService: SyllabusExtractionService
) {

    @GetMapping("/cursos/{cursoId}/temario")
    @Operation(
        summary = "Temario del curso",
        description = "Árbol módulo → subtemas → objetivos. Lo ve el profesor dueño siempre y un estudiante con matrícula " +
            "aprobada solo si está publicado (antes: 404). Sin matrícula aprobada: 403."
    )
    fun getTemario(@PathVariable cursoId: Long): ResponseEntity<SyllabusResponse> =
        ResponseEntity.ok(syllabusService.getSyllabus(cursoId))

    @PostMapping("/cursos/{cursoId}/modulos")
    @Operation(summary = "Crear módulo", description = "Profesor dueño. El número de módulo es único en el curso (409).")
    fun createModulo(@PathVariable cursoId: Long, @Valid @RequestBody input: ModuleCreateRequest): ResponseEntity<ModuleResponse> {
        val created = syllabusService.createModule(cursoId, input)
        return created(uri("/api/v1/cursos/$cursoId/temario"), created)
    }

    @PatchMapping("/modulos/{id}")
    @Operation(
        summary = "Actualizar módulo",
        description = "Profesor dueño. Solo cambia los campos enviados. El número es único en el curso (409); " +
            "para reordenar varios módulos a la vez usa PUT /cursos/{cursoId}/modulos/orden."
    )
    fun updateModulo(@PathVariable id: Long, @Valid @RequestBody input: ModuleUpdateRequest): ResponseEntity<ModuleResponse> =
        ResponseEntity.ok(syllabusService.updateModule(id, input))

    @PutMapping("/cursos/{cursoId}/modulos/orden")
    @Operation(
        summary = "Reordenar módulos",
        description = "Profesor dueño. Recibe los ids de TODOS los módulos del curso en el orden nuevo y los renumera " +
            "1..n en una sola transacción. 422 si falta alguno, sobra alguno o se repite."
    )
    fun ordenarModulos(@PathVariable cursoId: Long, @Valid @RequestBody input: ModuleOrderRequest): ResponseEntity<List<ModuleResponse>> =
        ResponseEntity.ok(syllabusService.reorderModules(cursoId, input))

    @DeleteMapping("/modulos/{id}")
    @Operation(
        summary = "Eliminar módulo",
        description = "Profesor dueño. Borra también sus subtemas y objetivos. 422 si algún subtema tiene apuntes asociados."
    )
    fun deleteModulo(@PathVariable id: Long): ResponseEntity<Void> {
        syllabusService.deleteModule(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/modulos/{moduloId}/subtemas")
    @Operation(summary = "Crear subtema", description = "Profesor dueño. El código del subtema es único en su módulo (409).")
    fun createSubtema(@PathVariable moduloId: Long, @Valid @RequestBody input: SubtopicCreateRequest): ResponseEntity<SubtopicResponse> {
        val created = syllabusService.createSubtopic(moduloId, input)
        return created(uri("/api/v1/subtemas/${created.id}"), created)
    }

    @PatchMapping("/subtemas/{id}")
    @Operation(summary = "Actualizar subtema", description = "Profesor dueño. Solo cambia los campos enviados.")
    fun updateSubtema(@PathVariable id: Long, @Valid @RequestBody input: SubtopicUpdateRequest): ResponseEntity<SubtopicResponse> =
        ResponseEntity.ok(syllabusService.updateSubtopic(id, input))

    @DeleteMapping("/subtemas/{id}")
    @Operation(summary = "Eliminar subtema", description = "Profesor dueño. 422 si el subtema tiene apuntes asociados.")
    fun deleteSubtema(@PathVariable id: Long): ResponseEntity<Void> {
        syllabusService.deleteSubtopic(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/subtemas/{id}/objetivos")
    @Operation(summary = "Agregar objetivo", description = "Profesor dueño.")
    fun createObjetivo(@PathVariable id: Long, @Valid @RequestBody input: ObjectiveCreateRequest): ResponseEntity<ObjectiveResponse> {
        val created = syllabusService.addObjective(id, input)
        return created(uri("/api/v1/objetivos/${created.id}"), created)
    }

    @PatchMapping("/objetivos/{id}")
    @Operation(summary = "Actualizar objetivo", description = "Profesor dueño.")
    fun updateObjetivo(@PathVariable id: Long, @Valid @RequestBody input: ObjectiveCreateRequest): ResponseEntity<ObjectiveResponse> =
        ResponseEntity.ok(syllabusService.updateObjective(id, input))

    @DeleteMapping("/objetivos/{id}")
    @Operation(summary = "Eliminar objetivo", description = "Profesor dueño.")
    fun deleteObjetivo(@PathVariable id: Long): ResponseEntity<Void> {
        syllabusService.deleteObjective(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/cursos/{cursoId}/temario/publicar")
    @Operation(
        summary = "Publicar temario",
        description = "Profesor dueño. 422 si no hay subtemas. Los subtemas sin objetivos no bloquean: vuelven como advertencias."
    )
    fun publicar(@PathVariable cursoId: Long): ResponseEntity<SyllabusPublishResponse> =
        ResponseEntity.ok(syllabusService.publish(cursoId))

    @PostMapping("/cursos/{cursoId}/temario/extraer", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Extraer el temario desde un PDF con IA",
        description = "Profesor dueño (403 en otro caso). Recibe el PDF del programa (hasta 10 MB) en el campo multipart " +
            "'file' y devuelve el árbol propuesto con la confianza de cada nodo; no publica nada. 400 si no es un PDF, " +
            "503 si la IA no responde (el profesor carga el temario a mano)."
    )
    fun extraer(@PathVariable cursoId: Long, @RequestPart("file") file: MultipartFile): ResponseEntity<SyllabusExtractionDTO> =
        ResponseEntity.ok(syllabusExtractionService.extract(cursoId, file.bytes, file.contentType))
}

// ==================== APUNTE CONTROLLER (API-06) ====================

@RestController
@RequestMapping("/api/v1/apuntes")
@Validated
@Tag(name = "Apuntes", description = "Apuntes privados del estudiante: CRUD, búsqueda y paginación (API-06)")
class ApunteController(
    private val noteService: NoteService
) {

    @PostMapping
    @Operation(
        summary = "Crear apunte",
        description = "Estudiante con matrícula aprobada. Idempotente con clientRequestId: un reintento devuelve el mismo apunte."
    )
    fun create(@Valid @RequestBody input: NoteCreateRequest): ResponseEntity<NoteResponse> {
        val created = noteService.createNote(input)
        return created(uri("/api/v1/apuntes/${created.id}"), created)
    }

    @GetMapping
    @Operation(
        summary = "Mis apuntes",
        description = "Solo los apuntes propios, del más reciente al más antiguo. Filtros opcionales grupoId, subtemaId y q " +
            "(texto, sin distinguir mayúsculas). page desde 0; size de 1 a 100 (por defecto 20); fuera de rango: 400."
    )
    fun search(
        @RequestParam(required = false) grupoId: Long?,
        @RequestParam(required = false) subtemaId: Long?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<PageResponse<NoteSummaryResponse>> =
        ResponseEntity.ok(noteService.search(grupoId, subtemaId, q, page, size))

    @GetMapping("/{id}")
    @Operation(summary = "Obtener apunte", description = "Solo el dueño. Un apunte ajeno o un profesor reciben 403.")
    fun findById(@PathVariable id: Long): ResponseEntity<NoteResponse> = ResponseEntity.ok(noteService.findById(id))

    @PatchMapping("/{id}")
    @Operation(summary = "Actualizar apunte", description = "Solo el dueño. Cambia el subtema y/o el texto confirmado.")
    fun update(@PathVariable id: Long, @Valid @RequestBody input: NoteUpdateRequest): ResponseEntity<NoteResponse> =
        ResponseEntity.ok(noteService.update(id, input))

    @DeleteMapping("/{id}")
    @Operation(summary = "Eliminar apunte", description = "Solo el dueño.")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        noteService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/paginas")
    @Operation(summary = "Agregar página", description = "Solo el dueño. Registra la ruta de una foto del apunte.")
    fun addPage(
        @PathVariable id: Long,
        @RequestParam @Min(0) pageIndex: Int,
        @RequestParam filePath: String,
    ): ResponseEntity<NotePageDTO> {
        val created = noteService.addPage(id, pageIndex, filePath)
        return created(uri("/api/v1/apuntes/$id"), created)
    }

    @PostMapping(value = ["/extraer", "/transcripcion"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Transcribir la foto de un apunte con IA",
        description = "Recibe la foto (JPEG, PNG o WebP, hasta 10 MB) en el campo multipart 'file' y devuelve los bloques de " +
            "texto con su confianza; los que quedan bajo el umbral (0.70) vienen marcados con requiereRevision. " +
            "Si la IA no responde, devuelve 503 y el estudiante escribe el texto a mano."
    )
    fun extraer(@RequestPart("file") file: MultipartFile): ResponseEntity<TranscriptionResultDTO> =
        ResponseEntity.ok(noteService.extractAndTranscribe(file.bytes, file.contentType))

    @PostMapping(value = ["/{id}/transcripcion"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Transcribir y vincular a un apunte existente",
        description = "Solo el dueño. Guarda la transcripción cruda en el apunte; el estudiante debe revisar los bloques " +
            "dudosos y confirmar el texto (PATCH confirmedText) antes de que el apunte pase a la auditoría."
    )
    fun transcribeNote(@PathVariable id: Long, @RequestPart("file") file: MultipartFile): ResponseEntity<NoteDTO> =
        ResponseEntity.ok(noteService.transcribeAndAttach(id, file.bytes, file.contentType))
}

// ==================== COBERTURA CONTROLLER (API-07) ====================

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Cobertura", description = "Cobertura del temario: personal del estudiante y agregada y anónima del grupo (API-07)")
class CoberturaController(
    private val coverageService: CoverageService,
    private val noteAuditService: NoteAuditService
) {

    @PostMapping("/apuntes/{apunteId}/auditar")
    @Operation(
        summary = "Auditar un apunte con IA",
        description = "Solo el dueño del apunte. La IA compara el texto confirmado con los subtemas del curso y devuelve un " +
            "veredicto por subtema (COVERED, PARTIAL, NO_NOTES); la cobertura conserva el mejor estado. 422 si el apunte no " +
            "tiene texto confirmado, 503 si la IA falla (el apunte queda sin auditar y se puede reintentar)."
    )
    fun auditarConIa(@PathVariable apunteId: Long): ResponseEntity<List<SubtopicCoverageDTO>> =
        ResponseEntity.ok(noteAuditService.audit(apunteId))


    @PutMapping("/apuntes/{apunteId}/auditoria")
    @Operation(
        summary = "[STUB TEMPORAL] Registrar auditoría de un apunte",
        description = "Stub temporal: recibe los veredictos por subtema que en el futuro calculará la llamada de IA 3 del " +
            "servidor, y será reemplazado por ella. Solo el dueño del apunte. Conserva el mejor estado por subtema.",
        deprecated = true
    )
    fun auditar(@PathVariable apunteId: Long, @Valid @RequestBody input: NoteAuditRequest): ResponseEntity<List<SubtopicCoverageDTO>> =
        ResponseEntity.ok(coverageService.registerAudit(apunteId, input))

    @GetMapping("/grupos/{grupoId}/cobertura/mia")
    @Operation(summary = "Mi cobertura", description = "Estudiante con matrícula aprobada en el grupo. Un profesor recibe 403.")
    fun mia(@PathVariable grupoId: Long): ResponseEntity<PersonalCoverageResponse> =
        ResponseEntity.ok(coverageService.personalCoverage(grupoId))

    @GetMapping("/grupos/{grupoId}/cobertura")
    @Operation(
        summary = "Cobertura del grupo",
        description = "Profesor del grupo. Agregada y anónima: porcentajes por subtema y total de estudiantes, sin ningún " +
            "dato que identifique a un estudiante. Un estudiante recibe 403."
    )
    fun grupo(@PathVariable grupoId: Long): ResponseEntity<GroupCoverageResponse> =
        ResponseEntity.ok(coverageService.groupCoverage(grupoId))
}

// ==================== REPASO CONTROLLER ====================

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Repaso", description = "Quiz de repaso con repetición espaciada de 1, 3, 7, 14 y 30 días (IA-05)")
class RepasoController(
    private val reviewService: ReviewService
) {

    @GetMapping("/repasos/pendientes")
    @Operation(
        summary = "Repasos pendientes",
        description = "Solo el estudiante dueño. Los repasos que vencen hoy o antes, con su pregunta de opción múltiple y el " +
            "origen del vacío (el apunte que lo detectó). Un profesor recibe 403."
    )
    fun pendientes(): ResponseEntity<List<PendingReviewDTO>> = ResponseEntity.ok(reviewService.pending())

    @GetMapping("/repasos/programados")
    @Operation(
        summary = "Repasos programados que todavía no vencen",
        description = "Solo el estudiante dueño. Los repasos ya generados cuya fecha es posterior a hoy, del más próximo al más " +
            "lejano, con su pregunta. Sirven para practicarlos antes de tiempo: practicar no cambia el calendario, porque " +
            "el resultado solo se registra cuando el repaso vence (POST /repasos/{id}/resultado). Un profesor recibe 403."
    )
    fun programados(): ResponseEntity<List<PendingReviewDTO>> = ResponseEntity.ok(reviewService.scheduled())

    @PostMapping("/repasos/generar")
    @Operation(
        summary = "Generar repasos desde los vacíos",
        description = "Solo el estudiante dueño. La IA crea una pregunta por cada subtema con vacío (PARTIAL o NO_NOTES) que " +
            "todavía no tenga repaso, hasta 5 por llamada. Sin vacíos devuelve una lista vacía; 503 si la IA falla."
    )
    fun generar(): ResponseEntity<List<PendingReviewDTO>> =
        created(uri("/api/v1/repasos/pendientes"), reviewService.generate())

    @PostMapping("/repasos/{id}/resultado")
    @Operation(
        summary = "Registrar el resultado de un repaso",
        description = "Solo el dueño del repaso (403 en otro caso). PASSED sube al siguiente intervalo y refuerza la cobertura " +
            "del subtema; FAILED vuelve a 1 día. 422 si el repaso todavía no vence; 400 si el resultado no es PASSED ni FAILED."
    )
    fun resultado(@PathVariable id: Long, @Valid @RequestBody input: ReviewResultDTO): ResponseEntity<ReviewDTO> =
        ResponseEntity.ok(reviewService.recordResult(id, input.result))
}

// ==================== CURSOS, GRUPOS Y MATRÍCULAS: RUTAS DEL LAB 5 (API-03 y API-04) ====================

/*
 * Las rutas en español del Lab 5. Las de antes (/courses, /groups, /enrollments) siguen funcionando porque la app móvil
 * las usa; unas y otras llaman a los mismos servicios. El rol se exige con @PreAuthorize; que el profesor sea el dueño de
 * ESE curso, grupo o matrícula (403 si no) lo decide el servicio. Aquí solo se valida, se delega y se arma la respuesta HTTP.
 */

@RestController
@RequestMapping("/api/v1/cursos")
@Tag(name = "Cursos (API-03)", description = "Cursos del profesor y los grupos de cada curso")
class CursoController(
    private val courseService: CourseService,
    private val studentGroupService: StudentGroupService
) {

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Crear curso", description = "Solo un profesor; queda como dueño del curso. 400: datos inválidos; 403: no es profesor; 409: el código ya existe.")
    @ApiResponse(responseCode = "201", description = "Curso creado; el header Location apunta a él")
    fun create(@Valid @RequestBody input: CourseCreateRequest): ResponseEntity<CourseResponse> {
        val result = courseService.create(input)
        return created(uri("/api/v1/cursos/${result.id}"), result)
    }

    @GetMapping
    @Operation(
        summary = "Listar cursos",
        description = "El profesor ve los cursos de los que es dueño y el estudiante, aquellos donde tiene matrícula aprobada. " +
            "Forma corta: sin temario y con el total de grupos."
    )
    fun findAll(): ResponseEntity<List<CourseSummaryResponse>> = ResponseEntity.ok(courseService.findAllSummaries())

    @GetMapping("/{id}")
    @Operation(summary = "Obtener curso", description = "Su dueño o un estudiante con matrícula aprobada. 403: sin acceso; 404: no existe.")
    fun findById(@PathVariable id: Long): ResponseEntity<CourseResponse> = ResponseEntity.ok(courseService.findById(id))

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(
        summary = "Actualizar curso",
        description = "Profesor dueño. Solo cambia los campos enviados. 400: datos inválidos; 403: el curso es de otro profesor; " +
            "404: no existe; 409: el nuevo código ya existe."
    )
    fun update(@PathVariable id: Long, @Valid @RequestBody input: CourseUpdateRequest): ResponseEntity<CourseResponse> =
        ResponseEntity.ok(courseService.update(id, input))

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(summary = "Eliminar curso", description = "Profesor dueño. 403: el curso es de otro profesor; 404: no existe; 422: tiene estudiantes matriculados.")
    @ApiResponse(responseCode = "204", description = "Curso eliminado")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        courseService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{cursoId}/grupos")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(
        summary = "Crear grupo",
        description = "Profesor dueño del curso. El servidor genera el código de invitación (CURSO-NN-XXXX, vence a los 30 días). " +
            "400: datos inválidos; 403: el curso es de otro profesor; 404: el curso no existe; 409: ya hay un grupo con ese número."
    )
    @ApiResponse(responseCode = "201", description = "Grupo creado; el header Location apunta a él")
    fun createGroup(@PathVariable cursoId: Long, @Valid @RequestBody input: GroupCreateRequest): ResponseEntity<StudentGroupResponse> {
        val result = studentGroupService.createGroup(cursoId, input)
        return created(uri("/api/v1/grupos/${result.id}"), result)
    }

    @GetMapping("/{cursoId}/grupos")
    @Operation(
        summary = "Listar grupos de un curso",
        description = "El profesor dueño recibe los grupos con su código de invitación; el estudiante matriculado, solo los datos " +
            "públicos, nunca el código. 403: sin acceso al curso; 404: no existe."
    )
    @ApiResponse(
        responseCode = "200",
        description = "Grupos del curso",
        content = [Content(array = ArraySchema(schema = Schema(oneOf = [StudentGroupDTO::class, StudentGroupPublicDTO::class])))]
    )
    fun findGroups(@PathVariable cursoId: Long): ResponseEntity<List<GroupView>> =
        ResponseEntity.ok(studentGroupService.listForCourse(cursoId))
}

@RestController
@RequestMapping("/api/v1/grupos")
@Tag(name = "Grupos (API-03)", description = "Ajustes de un grupo y su código de invitación")
class GrupoController(
    private val studentGroupService: StudentGroupService
) {

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(
        summary = "Actualizar grupo",
        description = "Profesor dueño. Cambia el horario y/o si unirse pide aprobación; lo que no se envía queda igual. " +
            "400: datos inválidos; 403: el grupo es de otro profesor; 404: no existe."
    )
    fun update(@PathVariable id: Long, @Valid @RequestBody input: GroupUpdateRequest): ResponseEntity<StudentGroupResponse> =
        ResponseEntity.ok(studentGroupService.update(id, input))

    @PostMapping("/{id}/codigo")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(
        summary = "Regenerar código de invitación",
        description = "Profesor dueño. Devuelve el grupo con el código nuevo; el anterior deja de funcionar. 403: el grupo es de otro profesor; 404: no existe."
    )
    fun regenerateCode(@PathVariable id: Long): ResponseEntity<StudentGroupResponse> =
        ResponseEntity.ok(studentGroupService.regenerateInviteCode(id))
}

// El filtro ?estado= acepta los estados de la base y sus nombres en español; cualquier otro valor es un 400.
private const val ENROLLMENT_STATUS_FILTER = "(?i)^(PENDING|ACTIVE|REJECTED|PENDIENTE|APROBADA|APROBADO|RECHAZADA|RECHAZADO)\$"

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Matrículas (API-04)", description = "Unirse a un grupo con su código, ver las solicitudes y aprobarlas o rechazarlas")
class MatriculaController(
    private val enrollmentService: EnrollmentService
) {

    @PostMapping("/matriculas")
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(
        summary = "Unirse a un grupo con su código",
        description = "Estudiante. La matrícula queda PENDING si el grupo pide aprobación, o ACTIVE si no. 400: código mal formado; " +
            "403: es profesor; 404: el código no existe; 409: ya está matriculado; 422: el código venció."
    )
    @ApiResponse(responseCode = "201", description = "Matrícula creada; el header Location apunta a ella")
    fun join(@Valid @RequestBody input: JoinGroupRequest): ResponseEntity<EnrollmentResponse> {
        val result = enrollmentService.joinGroup(input)
        return created(uri("/api/v1/matriculas/${result.id}"), result)
    }

    @GetMapping("/matriculas/mias")
    @PreAuthorize("hasRole('STUDENT')")
    @Operation(summary = "Mis matrículas", description = "Estudiante: sus solicitudes y matrículas, de la más reciente a la más antigua. 403: es profesor.")
    fun mine(): ResponseEntity<List<EnrollmentResponse>> = ResponseEntity.ok(enrollmentService.getMyEnrollments())

    @GetMapping("/grupos/{grupoId}/matriculas")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(
        summary = "Solicitudes y matriculados de un grupo",
        description = "Profesor dueño. Solo nombre y correo del estudiante, nunca sus apuntes ni su cobertura. " +
            "400: estado inválido; 403: el grupo es de otro profesor o es estudiante; 404: el grupo no existe."
    )
    fun requests(
        @PathVariable grupoId: Long,
        @Parameter(description = "Filtro opcional: PENDING (PENDIENTE), ACTIVE (APROBADA) o REJECTED (RECHAZADA)")
        @RequestParam(required = false)
        @Pattern(regexp = ENROLLMENT_STATUS_FILTER, message = "El estado debe ser PENDING, ACTIVE o REJECTED (o PENDIENTE, APROBADA, RECHAZADA)")
        estado: String?,
    ): ResponseEntity<List<EnrollmentRequestSummaryResponse>> = ResponseEntity.ok(enrollmentService.listRequests(grupoId, estado))

    @PatchMapping("/matriculas/{id}")
    @PreAuthorize("hasRole('TEACHER')")
    @Operation(
        summary = "Aprobar o rechazar una solicitud",
        description = "Profesor dueño del grupo. decision: APPROVE deja la matrícula ACTIVE y REJECT la deja REJECTED. " +
            "400: decisión inválida; 403: el grupo es de otro profesor; 404: no existe; 409: ya fue decidida."
    )
    fun decide(@PathVariable id: Long, @Valid @RequestBody input: EnrollmentDecisionRequest): ResponseEntity<EnrollmentResponse> =
        ResponseEntity.ok(enrollmentService.decide(id, input))
}

// 201 Created con el header Location apuntando al recurso creado.
private fun <T> created(location: URI, body: T): ResponseEntity<T> = ResponseEntity.created(location).body(body)

private fun uri(path: String): URI = URI.create(path)

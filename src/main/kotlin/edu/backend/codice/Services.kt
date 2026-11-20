package edu.backend.codice

import edu.backend.codice.exception.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

// ==================== AUTH SERVICE (SVC-01) ====================

interface AuthService {
    fun signUp(input: UserSignUpInput): UserResult
    fun login(input: UserLoginInput): AuthResponse
    fun getCurrentUser(): UserResult
    fun changePassword(input: ChangePasswordInput)
}

@Service
@Transactional
class AbstractAuthService(
    @Autowired val userRepository: UserRepository,
    @Autowired val roleRepository: RoleRepository,
    @Autowired val passwordEncoder: PasswordEncoder,
    @Autowired val jwtService: JwtService
) : AuthService {

    override fun signUp(input: UserSignUpInput): UserResult {
        // El controlador ya validó el rol (@Valid); se revisa también aquí porque el servicio no debe confiar en quien lo llame.
        val roleName = input.roleName ?: ""
        if (roleName != "ROLE_STUDENT" && roleName != "ROLE_TEACHER") {
            throw BusinessRuleException("El rol debe ser únicamente ROLE_STUDENT o ROLE_TEACHER")
        }

        userRepository.findByEmail(input.email ?: "").ifPresent {
            throw ConflictException("El email '${input.email}' ya está registrado")
        }

        val role = roleRepository.findByName(roleName).orElseGet {
            roleRepository.save(Role(name = roleName))
        }

        val user = User(
            firstName = input.firstName ?: "",
            lastName = input.lastName ?: "",
            email = input.email ?: "",
            password = passwordEncoder.encode(input.password ?: ""),
            enabled = true,
            roleList = mutableSetOf(role)
        )

        return toResult(userRepository.save(user))
    }

    override fun login(input: UserLoginInput): AuthResponse {
        val user = userRepository.findByEmail(input.username).orElseThrow {
            UnauthorizedException("Credenciales inválidas")
        }

        // Mismo mensaje para "no existe", "contraseña incorrecta" y "cuenta deshabilitada": no se revela cuál fue.
        if (!user.enabled || !passwordEncoder.matches(input.password, user.password)) {
            throw UnauthorizedException("Credenciales inválidas")
        }

        val token = jwtService.generateToken(user)
        return AuthResponse(
            token = token,
            tokenType = "Bearer",
            expiresAt = jwtService.getExpirationFromToken(token),
            user = toResult(user)
        )
    }

    override fun getCurrentUser(): UserResult {
        // Sin identidad (nadie autenticado o usuario borrado) es 401, no 404: el recurso /me siempre existe.
        val email = LoggedUser.get()
        val user = userRepository.findByEmail(email).orElseThrow {
            UnauthorizedException("Usuario autenticado no encontrado")
        }
        return toResult(user)
    }

    override fun changePassword(input: ChangePasswordInput) {
        val email = LoggedUser.get()
        val user = userRepository.findByEmail(email).orElseThrow {
            UnauthorizedException("Usuario autenticado no encontrado")
        }
        val current = input.currentPassword ?: ""
        val new = input.newPassword ?: ""

        // 422 y no 401: un 401 le haría creer a la app que la sesión venció y cerraría la sesión por un simple error al teclear.
        if (!passwordEncoder.matches(current, user.password)) {
            throw BusinessRuleException("La contraseña actual no es correcta")
        }
        if (passwordEncoder.matches(new, user.password)) {
            throw BusinessRuleException("La contraseña nueva debe ser distinta de la actual")
        }
        user.password = passwordEncoder.encode(new)
        userRepository.save(user)
    }

    private fun toResult(user: User) = UserResult(
        id = user.id!!,
        firstName = user.firstName,
        lastName = user.lastName,
        email = user.email,
        enabled = user.enabled,
        roles = user.roleList.map { it.name },
        createdAt = user.createDate
    )
}

// ==================== USER DETAILS (login) ====================

/*
 * Carga el usuario del login (JwtAuthenticationFilter → /v1/users/login) por correo, con sus roles y
 * privilegios como GrantedAuthority. Se había perdido al reescribir este archivo en el commit c1018dd;
 * sin él Spring usaba su usuario en memoria por defecto y ningún usuario de la base podía iniciar sesión.
 */
@Service
@Transactional(readOnly = true)
class AppUserDetailsService(
    @Autowired val userRepository: UserRepository
) : UserDetailsService {

    @Throws(UsernameNotFoundException::class)
    override fun loadUserByUsername(username: String): UserDetails {
        val user = userRepository.findByEmail(username).orElseThrow {
            UsernameNotFoundException("Credenciales inválidas")
        }
        return org.springframework.security.core.userdetails.User(
            user.email, user.password, user.enabled, true, true, true, authorities(user.roleList)
        )
    }

    private fun authorities(roles: Collection<Role>): Collection<GrantedAuthority> =
        roles.flatMap { role -> listOf(SimpleGrantedAuthority(role.name)) + role.privilegeList.map { SimpleGrantedAuthority(it.name) } }
}

// ==================== ACCESO COMPARTIDO (SVC-02 / SVC-03) ====================

/*
 * Reglas de quién es quién, compartidas por los servicios de cursos, grupos, matrícula y temario.
 * El usuario sale siempre de LoggedUser (el filtro JWT lo fija); los servicios no dependen de HTTP.
 */
@Service
class AccessGuard(
    @Autowired val userRepository: UserRepository,
    @Autowired val enrollmentRepository: EnrollmentRepository
) {
    fun currentUser(): User = userRepository.findByEmail(LoggedUser.get()).orElseThrow {
        UnauthorizedException("Usuario autenticado no encontrado")
    }

    fun isTeacher(user: User) = user.roleList.any { it.name == "ROLE_TEACHER" }

    fun requireTeacher(user: User) {
        if (!isTeacher(user)) throw ForbiddenException("Esta acción es solo para profesores")
    }

    fun isOwner(course: Course, user: User) = course.teacher?.id == user.id

    fun requireOwner(course: Course, user: User) {
        if (!isOwner(course, user)) throw ForbiddenException("Solo el profesor dueño del curso puede hacer esto")
    }

    // El profesor del grupo o el dueño del curso pueden gestionar sus matrículas.
    fun canManage(group: StudentGroup, user: User) = group.teacher?.id == user.id || group.course?.teacher?.id == user.id

    fun isApprovedStudent(course: Course, user: User) =
        enrollmentRepository.existsByGroupCourseIdAndStudentIdAndStatus(course.id!!, user.id!!, EnrollmentStatus.ACTIVE)

    // Lectura de un curso: su dueño o un estudiante con matrícula aprobada en alguno de sus grupos.
    fun requireCanRead(course: Course, user: User) {
        if (!isOwner(course, user) && !isApprovedStudent(course, user)) {
            throw ForbiddenException("No tiene acceso a este curso")
        }
    }
}

// ==================== COURSE SERVICE (SVC-02) ====================

interface CourseService {
    fun create(input: CourseCreateDTO): CourseDTO
    fun findAll(): List<CourseDTO>

    // Lo mismo que findAll pero con la forma corta de los listados (DTO-03).
    fun findAllSummaries(): List<CourseSummaryDTO>
    fun findById(id: Long): CourseDTO
    fun update(id: Long, input: CourseCreateDTO): CourseDTO

    // PATCH: solo cambia los campos que vienen (DTO-03).
    fun update(id: Long, input: CourseUpdateDTO): CourseDTO
    fun delete(id: Long)
}

@Service
@Transactional
class AbstractCourseService(
    @Autowired val courseRepository: CourseRepository,
    @Autowired val enrollmentRepository: EnrollmentRepository,
    @Autowired val courseMapper: CourseMapper,
    @Autowired val access: AccessGuard
) : CourseService {

    override fun create(input: CourseCreateDTO): CourseDTO {
        val teacher = access.currentUser().also { access.requireTeacher(it) }
        courseRepository.findByCode(input.code).ifPresent {
            throw ConflictException("El curso con código '${input.code}' ya existe")
        }
        val course = courseMapper.toCourse(input).apply {
            this.teacher = teacher
            createdAt = Instant.now()
        }
        return courseMapper.toCourseResponse(courseRepository.save(course))
    }

    override fun findAll(): List<CourseDTO> = courseMapper.toCourseResponseList(coursesOfCurrentUser())

    override fun findAllSummaries(): List<CourseSummaryDTO> = courseMapper.toCourseSummaryList(coursesOfCurrentUser())

    // El profesor ve los cursos de los que es dueño; el estudiante, los cursos donde está aprobado.
    private fun coursesOfCurrentUser(): List<Course> {
        val user = access.currentUser()
        return if (access.isTeacher(user)) {
            courseRepository.findByTeacherIdOrderByCode(user.id!!)
        } else {
            courseRepository.findByStudentEnrollment(user.id!!, EnrollmentStatus.ACTIVE)
        }
    }

    override fun findById(id: Long): CourseDTO {
        val course = findCourse(id)
        access.requireCanRead(course, access.currentUser())
        return courseMapper.toCourseResponse(course)
    }

    // El formato anterior (siempre los tres campos) es un caso particular del PATCH.
    override fun update(id: Long, input: CourseCreateDTO): CourseDTO =
        update(id, CourseUpdateDTO(code = input.code, name = input.name, color = input.color))

    override fun update(id: Long, input: CourseUpdateDTO): CourseDTO {
        val course = findCourse(id)
        access.requireOwner(course, access.currentUser())
        if (input.code != null && input.code != course.code) {
            courseRepository.findByCode(input.code).ifPresent {
                throw ConflictException("El curso con código '${input.code}' ya existe")
            }
        }
        courseMapper.updateCourseFromRequest(input, course)
        return courseMapper.toCourseResponse(courseRepository.save(course))
    }

    // No se borra un curso con estudiantes aprobados: perderían sus apuntes y su cobertura.
    override fun delete(id: Long) {
        val course = findCourse(id)
        access.requireOwner(course, access.currentUser())
        if (enrollmentRepository.existsByGroupCourseIdAndStatus(id, EnrollmentStatus.ACTIVE)) {
            throw BusinessRuleException("No se puede eliminar un curso con estudiantes matriculados")
        }
        courseRepository.delete(course)
    }

    private fun findCourse(id: Long) = courseRepository.findById(id).orElseThrow {
        NotFoundException("Curso no encontrado con ID: $id")
    }
}

// ==================== GROUP SERVICE (SVC-02) ====================

interface StudentGroupService {
    fun createGroup(input: StudentGroupCreateDTO): StudentGroupDTO

    // El curso viene de la ruta (POST /cursos/{cursoId}/grupos) y no del cuerpo (DTO-03).
    fun createGroup(courseId: Long, input: GroupCreateDTO): StudentGroupDTO
    fun findByInviteCode(inviteCode: String): StudentGroupDTO
    fun findByCourseId(courseId: Long): List<StudentGroupDTO>

    // Los grupos de un curso según quién pregunta: completos (con código) para el dueño; sin código para el estudiante.
    fun listForCourse(courseId: Long): List<GroupView>
    fun regenerateInviteCode(groupId: Long): StudentGroupDTO
    fun updateRequiresApproval(groupId: Long, requiresApproval: Boolean): StudentGroupDTO

    // PATCH: solo cambia lo que viene (DTO-03).
    fun update(groupId: Long, input: GroupUpdateDTO): StudentGroupDTO
}

@Service
@Transactional
class AbstractStudentGroupService(
    @Autowired val studentGroupRepository: StudentGroupRepository,
    @Autowired val courseRepository: CourseRepository,
    @Autowired val studentGroupMapper: StudentGroupMapper,
    @Autowired val access: AccessGuard
) : StudentGroupService {

    companion object {
        const val INVITE_VALIDITY_DAYS = 30L
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // sin 0/O ni 1/I: se dictan en clase
        private val random = java.security.SecureRandom()

        // <CODIGO_CURSO>-<NN>-<4 alfanuméricos>, por ejemplo EIF411-01-7K2A.
        fun newInviteCode(courseCode: String, groupNumber: String): String {
            val suffix = (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            return "${courseCode.uppercase()}-${groupNumber.padStart(2, '0')}-$suffix"
        }
    }

    // La ruta plana (POST /groups) trae el curso en el cuerpo; es el mismo caso que la anidada.
    override fun createGroup(input: StudentGroupCreateDTO): StudentGroupDTO = createGroup(
        input.courseId,
        GroupCreateDTO(number = input.number, period = input.period, schedule = input.schedule, requiresApproval = input.requiresApproval),
    )

    override fun createGroup(courseId: Long, input: GroupCreateDTO): StudentGroupDTO {
        val teacher = access.currentUser().also { access.requireTeacher(it) }
        val course = courseRepository.findById(courseId).orElseThrow {
            NotFoundException("Curso no encontrado con ID: $courseId")
        }
        access.requireOwner(course, teacher)
        if (studentGroupRepository.existsByCourseIdAndNumber(course.id!!, input.number)) {
            throw ConflictException("El curso ya tiene un grupo ${input.number}")
        }

        val group = StudentGroup(
            course = course,
            teacher = teacher,
            number = input.number,
            period = input.period,
            schedule = input.schedule,
            requiresApproval = input.requiresApproval,
        )
        assignNewInviteCode(group)
        return studentGroupMapper.toStudentGroupResponse(studentGroupRepository.save(group))
    }

    override fun findByInviteCode(inviteCode: String): StudentGroupDTO {
        val group = studentGroupRepository.findByInviteCode(inviteCode.trim().uppercase()).orElseThrow {
            NotFoundException("Grupo no encontrado con código de invitación: $inviteCode")
        }
        return studentGroupMapper.toStudentGroupResponse(group)
    }

    // Los grupos de un curso los ve su dueño o un estudiante aprobado en el curso.
    override fun findByCourseId(courseId: Long): List<StudentGroupDTO> {
        val course = courseRepository.findById(courseId).orElseThrow { NotFoundException("Curso no encontrado con ID: $courseId") }
        access.requireCanRead(course, access.currentUser())
        return studentGroupMapper.toStudentGroupResponseList(studentGroupRepository.findByCourseId(courseId))
    }

    // Quien administra el curso recibe los grupos completos, con el código de invitación; el estudiante aprobado,
    // solo lo público: con el código podría unirse a un grupo que el profesor no le compartió.
    override fun listForCourse(courseId: Long): List<GroupView> {
        val course = courseRepository.findById(courseId).orElseThrow { NotFoundException("Curso no encontrado con ID: $courseId") }
        val user = access.currentUser()
        access.requireCanRead(course, user)
        val groups = studentGroupRepository.findByCourseId(courseId)
        return if (access.isOwner(course, user)) {
            studentGroupMapper.toStudentGroupResponseList(groups)
        } else {
            studentGroupMapper.toStudentGroupPublicResponseList(groups)
        }
    }

    // El código anterior deja de existir: quien lo tenga ya no encuentra el grupo.
    override fun regenerateInviteCode(groupId: Long): StudentGroupDTO {
        val group = ownedGroup(groupId)
        assignNewInviteCode(group)
        return studentGroupMapper.toStudentGroupResponse(studentGroupRepository.save(group))
    }

    override fun updateRequiresApproval(groupId: Long, requiresApproval: Boolean): StudentGroupDTO =
        update(groupId, GroupUpdateDTO(requiresApproval = requiresApproval))

    override fun update(groupId: Long, input: GroupUpdateDTO): StudentGroupDTO {
        val group = ownedGroup(groupId)
        input.schedule?.let { group.schedule = it }
        input.requiresApproval?.let { group.requiresApproval = it }
        return studentGroupMapper.toStudentGroupResponse(studentGroupRepository.save(group))
    }

    private fun ownedGroup(groupId: Long): StudentGroup {
        val group = studentGroupRepository.findById(groupId).orElseThrow { NotFoundException("Grupo no encontrado con ID: $groupId") }
        if (!access.canManage(group, access.currentUser())) throw ForbiddenException("Solo el profesor del grupo puede hacer esto")
        return group
    }

    // Reintenta en el caso (muy improbable) de que el sufijo aleatorio ya exista.
    private fun assignNewInviteCode(group: StudentGroup) {
        val code = generateSequence { newInviteCode(group.course!!.code, group.number) }
            .take(10)
            .firstOrNull { !studentGroupRepository.existsByInviteCode(it) }
            ?: throw ConflictException("No se pudo generar un código de invitación único")
        group.inviteCode = code
        group.inviteExpiresAt = Instant.now().plus(java.time.Duration.ofDays(INVITE_VALIDITY_DAYS))
    }
}

// ==================== ENROLLMENT SERVICE (SVC-03) ====================

interface EnrollmentService {
    fun joinGroup(input: JoinGroupDTO): EnrollmentDTO
    fun requestEnrollment(input: EnrollmentRequestDTO): EnrollmentDTO
    fun getMyEnrollments(): List<EnrollmentDTO>
    fun listRequests(groupId: Long, status: String?): List<EnrollmentRequestSummaryDTO>
    fun getGroupEnrollments(groupId: Long): List<EnrollmentDTO>
    fun decide(enrollmentId: Long, input: EnrollmentDecisionDTO): EnrollmentDTO
    fun updateStatus(enrollmentId: Long, statusStr: String): EnrollmentDTO

    /** El estudiante sale de su grupo (o cancela su solicitud): la matrícula pasa a LEFT y conserva sus apuntes. */
    fun leave(enrollmentId: Long)

    /** El profesor del grupo quita a un estudiante inscrito: la matrícula pasa a REMOVED y conserva sus apuntes. */
    fun remove(groupId: Long, enrollmentId: Long)
}

@Service
@Transactional
class AbstractEnrollmentService(
    @Autowired val enrollmentRepository: EnrollmentRepository,
    @Autowired val studentGroupRepository: StudentGroupRepository,
    @Autowired val enrollmentMapper: EnrollmentMapper,
    @Autowired val access: AccessGuard
) : EnrollmentService {

    // El estudiante se une con el código que le pasó el profesor.
    override fun joinGroup(input: JoinGroupDTO): EnrollmentDTO {
        val group = studentGroupRepository.findByInviteCode(input.inviteCode.trim().uppercase()).orElseThrow {
            NotFoundException("No existe un grupo con el código ${input.inviteCode}")
        }
        if (group.inviteExpiresAt?.isBefore(Instant.now()) == true) {
            throw BusinessRuleException("El código de invitación venció; pídale uno nuevo al profesor")
        }
        return join(group)
    }

    // Variante por id de grupo que ya usaba el controlador; aplica las mismas reglas que el código.
    override fun requestEnrollment(input: EnrollmentRequestDTO): EnrollmentDTO {
        val group = studentGroupRepository.findById(input.groupId).orElseThrow {
            NotFoundException("Grupo no encontrado con ID: ${input.groupId}")
        }
        return join(group)
    }

    override fun getMyEnrollments(): List<EnrollmentDTO> {
        val student = access.currentUser()
        // Las matrículas terminadas (LEFT, REMOVED) no se listan: ya no son del estudiante, y las versiones de la app que
        // no conocen esos estados no sabrían qué hacer con ellas.
        return enrollmentMapper.toEnrollmentResponseList(
            enrollmentRepository.findByStudentIdOrderByRequestedAtDesc(student.id!!).filterNot { it.ended() }
        )
    }

    override fun listRequests(groupId: Long, status: String?): List<EnrollmentRequestSummaryDTO> {
        managedGroup(groupId)
        val enrollments = if (status.isNullOrBlank()) {
            enrollmentRepository.findByGroupIdOrderByRequestedAtDesc(groupId).filterNot { it.ended() }
        } else {
            enrollmentRepository.findByGroupIdAndStatus(groupId, parseStatus(status))
        }
        return enrollmentMapper.toEnrollmentRequestSummaryList(enrollments)
    }

    // El filtro acepta los estados de la base (PENDING, ACTIVE, REJECTED) y sus nombres en español (PENDIENTE, APROBADA,
    // RECHAZADA, que son los del enunciado de la API). Las respuestas siguen usando los de la base.
    private fun parseStatus(raw: String): EnrollmentStatus = when (raw.trim().uppercase()) {
        "PENDING", "PENDIENTE" -> EnrollmentStatus.PENDING
        "ACTIVE", "APROBADA", "APROBADO" -> EnrollmentStatus.ACTIVE
        "REJECTED", "RECHAZADA", "RECHAZADO" -> EnrollmentStatus.REJECTED
        else -> throw BusinessRuleException("Estado inválido: $raw (PENDING, ACTIVE o REJECTED)")
    }

    // Las solicitudes pendientes del grupo, con la forma de respuesta anterior.
    override fun getGroupEnrollments(groupId: Long): List<EnrollmentDTO> {
        managedGroup(groupId)
        return enrollmentMapper.toEnrollmentResponseList(enrollmentRepository.findByGroupIdAndStatus(groupId, EnrollmentStatus.PENDING))
    }

    override fun decide(enrollmentId: Long, input: EnrollmentDecisionDTO): EnrollmentDTO {
        val enrollment = enrollmentRepository.findById(enrollmentId).orElseThrow {
            NotFoundException("Matrícula no encontrada con ID: $enrollmentId")
        }
        val user = access.currentUser()
        if (!access.canManage(enrollment.group!!, user)) throw ForbiddenException("Solo el profesor del grupo decide sus solicitudes")
        if (enrollment.status != EnrollmentStatus.PENDING) {
            throw ConflictException("La solicitud ya fue decidida (${enrollment.status})")
        }
        val newStatus = enrollmentMapper.toEnrollmentStatus(input)
        if (newStatus == EnrollmentStatus.ACTIVE) requireNoOtherActive(enrollment.group!!, enrollment.student!!)
        enrollment.status = newStatus
        enrollment.resolvedAt = Instant.now()
        return enrollmentMapper.toEnrollmentResponse(enrollmentRepository.save(enrollment))
    }

    // Compatibilidad con EnrollmentStatusUpdateDTO: ACTIVE/APPROVE aprueba, REJECTED/REJECT rechaza.
    override fun updateStatus(enrollmentId: Long, statusStr: String): EnrollmentDTO {
        val decision = when (statusStr.trim().uppercase()) {
            "ACTIVE", "APPROVE" -> "APPROVE"
            "REJECTED", "REJECT" -> "REJECT"
            else -> throw BusinessRuleException("Estado inválido: $statusStr (ACTIVE o REJECTED)")
        }
        return decide(enrollmentId, EnrollmentDecisionDTO(decision))
    }

    override fun leave(enrollmentId: Long) {
        val student = access.currentUser()
        val enrollment = enrollmentRepository.findById(enrollmentId).orElseThrow {
            NotFoundException("Matrícula no encontrada con ID: $enrollmentId")
        }
        if (enrollment.student?.id != student.id) throw ForbiddenException("Solo puede salir de sus propios grupos")
        // Se puede salir de un grupo donde se está inscrito y también cancelar una solicitud que aún no se resuelve.
        if (enrollment.status != EnrollmentStatus.ACTIVE && enrollment.status != EnrollmentStatus.PENDING) {
            throw ConflictException("Ya no está inscrito en este grupo (${enrollment.status})")
        }
        enrollment.status = EnrollmentStatus.LEFT
        enrollment.resolvedAt = Instant.now()
        enrollmentRepository.save(enrollment)
    }

    override fun remove(groupId: Long, enrollmentId: Long) {
        val group = studentGroupRepository.findById(groupId).orElseThrow { NotFoundException("Grupo no encontrado con ID: $groupId") }
        if (!access.canManage(group, access.currentUser())) throw ForbiddenException("Solo el profesor del grupo puede quitar estudiantes")

        val enrollment = enrollmentRepository.findById(enrollmentId).orElseThrow {
            NotFoundException("Matrícula no encontrada con ID: $enrollmentId")
        }
        // La matrícula tiene que ser de ESTE grupo: si no, un profesor podría quitar estudiantes de otro grupo cambiando el id.
        if (enrollment.group?.id != group.id) throw NotFoundException("La matrícula $enrollmentId no pertenece al grupo $groupId")
        if (enrollment.status != EnrollmentStatus.ACTIVE) {
            throw ConflictException("Solo se puede quitar a un estudiante inscrito (${enrollment.status})")
        }
        enrollment.status = EnrollmentStatus.REMOVED
        enrollment.resolvedAt = Instant.now()
        enrollmentRepository.save(enrollment)
    }

    // LEFT y REMOVED: matrículas que ya terminaron. No se listan ni cuentan como inscripción.
    private fun Enrollment.ended() = status == EnrollmentStatus.LEFT || status == EnrollmentStatus.REMOVED

    private fun join(group: StudentGroup): EnrollmentDTO {
        val student = access.currentUser()
        if (access.isTeacher(student)) throw ForbiddenException("Un profesor no puede unirse a un grupo como estudiante")

        // La tabla admite una sola fila por grupo y estudiante: una solicitud rechazada, o una matrícula que terminó
        // (el estudiante salió o lo quitaron), se reabre en la misma fila y recupera sus apuntes.
        val existing = enrollmentRepository.findByGroupIdAndStudentId(group.id!!, student.id!!).orElse(null)
        if (existing != null && existing.status != EnrollmentStatus.REJECTED && !existing.ended()) {
            throw ConflictException("Ya tiene una matrícula ${existing.status} en este grupo")
        }

        // A quien el profesor quitó no se le abre la puerta sola aunque el grupo sea abierto: vuelve a quedar pendiente.
        val wasRemoved = existing?.status == EnrollmentStatus.REMOVED
        val initialStatus = if (group.requiresApproval || wasRemoved) EnrollmentStatus.PENDING else EnrollmentStatus.ACTIVE
        if (initialStatus == EnrollmentStatus.ACTIVE) requireNoOtherActive(group, student)
        val enrollment = (existing ?: Enrollment(group = group, student = student)).apply {
            status = initialStatus
            requestedAt = Instant.now()
            resolvedAt = if (initialStatus == EnrollmentStatus.ACTIVE) requestedAt else null
        }
        return enrollmentMapper.toEnrollmentResponse(enrollmentRepository.save(enrollment))
    }

    // Un estudiante puede estar aprobado en un solo grupo por curso (índice único parcial de la base).
    private fun requireNoOtherActive(group: StudentGroup, student: User) {
        if (enrollmentRepository.existsByGroupCourseIdAndStudentIdAndStatus(group.course!!.id!!, student.id!!, EnrollmentStatus.ACTIVE)) {
            throw ConflictException("El estudiante ya está matriculado en otro grupo de este curso")
        }
    }

    private fun managedGroup(groupId: Long): StudentGroup {
        val group = studentGroupRepository.findById(groupId).orElseThrow { NotFoundException("Grupo no encontrado con ID: $groupId") }
        if (!access.canManage(group, access.currentUser())) throw ForbiddenException("Solo el profesor del grupo ve sus solicitudes")
        return group
    }
}

// ==================== SYLLABUS SERVICE (SVC-04) ====================

/*
 * SVC-04: el temario cuelga del curso (nunca del grupo). Lo edita y lo publica el profesor dueño;
 * lo lee un estudiante con matrícula aprobada, y solo una vez publicado.
 */
interface SyllabusService {
    fun getSyllabus(courseId: Long): SyllabusDTO
    fun createModule(courseId: Long, input: ModuleCreateDTO): ModuleDTO
    fun updateModule(moduleId: Long, input: ModuleUpdateDTO): ModuleDTO
    fun reorderModules(courseId: Long, input: ModuleOrderDTO): List<ModuleDTO>
    fun deleteModule(moduleId: Long)
    fun getCourseModules(courseId: Long): List<ModuleDTO>
    fun createSubtopic(moduleId: Long, input: SubtopicCreateDTO): SubtopicDTO
    fun updateSubtopic(subtopicId: Long, input: SubtopicUpdateDTO): SubtopicDTO
    fun deleteSubtopic(subtopicId: Long)
    fun addObjective(subtopicId: Long, input: ObjectiveCreateDTO): SubtopicObjectiveDTO
    fun updateObjective(objectiveId: Long, input: ObjectiveCreateDTO): SubtopicObjectiveDTO
    fun deleteObjective(objectiveId: Long)
    fun publish(courseId: Long): SyllabusPublishDTO
}

@Service
@Transactional
class AbstractSyllabusService(
    @Autowired val courseModuleRepository: CourseModuleRepository,
    @Autowired val subtopicRepository: SubtopicRepository,
    @Autowired val subtopicObjectiveRepository: SubtopicObjectiveRepository,
    @Autowired val courseRepository: CourseRepository,
    @Autowired val noteRepository: NoteRepository,
    @Autowired val syllabusMapper: SyllabusMapper,
    @Autowired val access: AccessGuard
) : SyllabusService {

    override fun getSyllabus(courseId: Long): SyllabusDTO {
        val course = readableCourse(courseId)
        return syllabusMapper.toSyllabusDTO(course, courseModuleRepository.findByCourseIdOrderByNumber(courseId))
    }

    override fun getCourseModules(courseId: Long): List<ModuleDTO> = getSyllabus(courseId).modules

    override fun createModule(courseId: Long, input: ModuleCreateDTO): ModuleDTO {
        val course = ownedCourse(courseId)
        if (courseModuleRepository.existsByCourseIdAndNumber(courseId, input.number)) {
            throw ConflictException("El curso ya tiene un módulo ${input.number}")
        }
        val module = CourseModule(course = course, number = input.number, title = input.title)
        return syllabusMapper.moduleToModuleDTO(courseModuleRepository.save(module))
    }

    override fun updateModule(moduleId: Long, input: ModuleUpdateDTO): ModuleDTO {
        val module = ownedModule(moduleId)
        val newNumber = input.number
        if (newNumber != null && newNumber != module.number &&
            courseModuleRepository.existsByCourseIdAndNumber(module.course!!.id!!, newNumber)
        ) {
            throw ConflictException("El curso ya tiene un módulo $newNumber")
        }
        input.title?.let { module.title = it }
        newNumber?.let { module.number = it }
        return syllabusMapper.moduleToModuleDTO(courseModuleRepository.save(module))
    }

    /*
     * Reordenar es intercambiar números, y (course_id, number) es único: PostgreSQL lo revisa fila por fila, así que
     * pasar el módulo 1 al 2 y el 2 al 1 choca a mitad de camino. Primero se apartan todos en números negativos
     * (flush) y después se asignan los definitivos 1..n, todo en la misma transacción.
     */
    override fun reorderModules(courseId: Long, input: ModuleOrderDTO): List<ModuleDTO> {
        ownedCourse(courseId)
        val modules = courseModuleRepository.findByCourseIdOrderByNumber(courseId)
        val ids = input.moduleIds
        if (ids.toSet().size != ids.size || ids.toSet() != modules.mapNotNull { it.id }.toSet()) {
            throw BusinessRuleException("La lista debe traer cada módulo del curso exactamente una vez")
        }
        val byId = modules.associateBy { it.id }
        ids.forEachIndexed { index, id -> byId.getValue(id).number = -(index + 1) }
        courseModuleRepository.saveAllAndFlush(modules)
        ids.forEachIndexed { index, id -> byId.getValue(id).number = index + 1 }
        courseModuleRepository.saveAllAndFlush(modules)
        return ids.map { syllabusMapper.moduleToModuleDTO(byId.getValue(it)) }
    }

    // Borrar el módulo borra sus subtemas y objetivos; si algún subtema tiene apuntes, los apuntes perderían su tema.
    override fun deleteModule(moduleId: Long) {
        val module = ownedModule(moduleId)
        if (noteRepository.existsBySubtopicModuleId(moduleId)) {
            throw BusinessRuleException("No se puede eliminar el módulo ${module.number}: alguno de sus subtemas tiene apuntes asociados")
        }
        courseModuleRepository.delete(module)
    }

    override fun createSubtopic(moduleId: Long, input: SubtopicCreateDTO): SubtopicDTO {
        val module = ownedModule(moduleId)
        if (subtopicRepository.existsByModuleIdAndCode(moduleId, input.code)) {
            throw ConflictException("El módulo ya tiene un subtema ${input.code}")
        }
        val subtopic = syllabusMapper.toSubtopic(input)
        module.addSubtopic(subtopic)
        return syllabusMapper.subtopicToSubtopicDTO(subtopicRepository.save(subtopic))
    }

    override fun updateSubtopic(subtopicId: Long, input: SubtopicUpdateDTO): SubtopicDTO {
        val subtopic = ownedSubtopic(subtopicId)
        val newCode = input.code
        if (newCode != null && newCode != subtopic.code && subtopicRepository.existsByModuleIdAndCode(subtopic.module!!.id!!, newCode)) {
            throw ConflictException("El módulo ya tiene un subtema $newCode")
        }
        syllabusMapper.updateSubtopicFromRequest(input, subtopic)
        return syllabusMapper.subtopicToSubtopicDTO(subtopicRepository.save(subtopic))
    }

    // Un subtema con apuntes no se borra: los apuntes de los estudiantes perderían su tema.
    override fun deleteSubtopic(subtopicId: Long) {
        val subtopic = ownedSubtopic(subtopicId)
        if (noteRepository.existsBySubtopicId(subtopicId)) {
            throw BusinessRuleException("No se puede eliminar el subtema ${subtopic.code}: tiene apuntes asociados")
        }
        subtopic.module?.subtopics?.remove(subtopic)
        subtopicRepository.delete(subtopic)
    }

    override fun addObjective(subtopicId: Long, input: ObjectiveCreateDTO): SubtopicObjectiveDTO {
        val subtopic = ownedSubtopic(subtopicId)
        val objective = syllabusMapper.toObjective(input)
        subtopic.addObjective(objective)
        return syllabusMapper.subtopicObjectiveToSubtopicObjectiveDTO(subtopicObjectiveRepository.save(objective))
    }

    override fun updateObjective(objectiveId: Long, input: ObjectiveCreateDTO): SubtopicObjectiveDTO {
        val objective = ownedObjective(objectiveId)
        objective.description = input.description
        return syllabusMapper.subtopicObjectiveToSubtopicObjectiveDTO(subtopicObjectiveRepository.save(objective))
    }

    override fun deleteObjective(objectiveId: Long) {
        val objective = ownedObjective(objectiveId)
        objective.subtopic?.objectives?.remove(objective)
        subtopicObjectiveRepository.delete(objective)
    }

    private fun ownedObjective(objectiveId: Long): SubtopicObjective {
        val objective = subtopicObjectiveRepository.findById(objectiveId).orElseThrow {
            NotFoundException("Objetivo no encontrado con ID: $objectiveId")
        }
        requireOwner(objective.subtopic!!.module!!.course!!)
        return objective
    }

    // Publicar exige al menos un subtema. Los subtemas sin objetivos no bloquean: se avisan.
    override fun publish(courseId: Long): SyllabusPublishDTO {
        val course = ownedCourse(courseId)
        val subtopics = subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(courseId)
        if (subtopics.isEmpty()) throw BusinessRuleException("No se puede publicar un temario sin subtemas")

        course.syllabusPublishedAt = Instant.now()
        courseRepository.save(course)
        val warnings = subtopics.filter { it.objectives.isEmpty() }.map {
            SyllabusWarningDTO(subtopicId = it.id!!, subtopicCode = it.code, message = "El subtema ${it.code} no tiene objetivos")
        }
        return SyllabusPublishDTO(published = true, publishedAt = course.syllabusPublishedAt, warnings = warnings)
    }

    /*
     * Lectura: el dueño siempre; un estudiante solo con matrícula ACTIVE en el curso y solo si el temario
     * está publicado (antes de publicarse, para él no existe).
     */
    private fun readableCourse(courseId: Long): Course {
        val course = findCourse(courseId)
        val user = access.currentUser()
        if (access.isOwner(course, user)) return course
        if (access.isTeacher(user) || !access.isApprovedStudent(course, user)) {
            throw ForbiddenException("No tiene acceso al temario de este curso")
        }
        if (course.syllabusPublishedAt == null) throw NotFoundException("El temario de este curso todavía no se publica")
        return course
    }

    private fun ownedCourse(courseId: Long) = findCourse(courseId).also { requireOwner(it) }

    private fun ownedModule(moduleId: Long): CourseModule {
        val module = courseModuleRepository.findById(moduleId).orElseThrow { NotFoundException("Módulo no encontrado con ID: $moduleId") }
        requireOwner(module.course!!)
        return module
    }

    private fun ownedSubtopic(subtopicId: Long): Subtopic {
        val subtopic = subtopicRepository.findById(subtopicId).orElseThrow { NotFoundException("Subtema no encontrado con ID: $subtopicId") }
        requireOwner(subtopic.module!!.course!!)
        return subtopic
    }

    private fun requireOwner(course: Course) = access.requireOwner(course, access.currentUser())

    private fun findCourse(courseId: Long) = courseRepository.findById(courseId).orElseThrow {
        NotFoundException("Curso no encontrado con ID: $courseId")
    }
}

// ==================== NOTE SERVICE ====================

/*
 * SVC-05: los apuntes son privados. Cada operación se hace en nombre del usuario autenticado y solo
 * sobre sus propios apuntes; un profesor nunca ve apuntes individuales (para eso está la cobertura
 * agregada). No depende de tipos HTTP: los filtros y la página llegan como valores simples.
 */
interface NoteService {
    fun createNote(input: NoteCreateDTO): NoteDTO
    fun getMyNotes(): List<NoteDTO>
    fun search(groupId: Long?, subtopicId: Long?, query: String?, page: Int, size: Int): PageResponse<NoteSummaryDTO>
    fun findById(id: Long): NoteDTO
    fun update(id: Long, input: NoteUpdateDTO): NoteDTO
    fun delete(id: Long)
    fun addPage(noteId: Long, pageIndex: Int, filePath: String): NotePageDTO
    fun extractAndTranscribe(fileBytes: ByteArray?, mimeType: String?): TranscriptionResultDTO
    fun transcribeAndAttach(noteId: Long, fileBytes: ByteArray?, mimeType: String?): NoteDTO
}

@Service
@Transactional
class AbstractNoteService(
    @Autowired val noteRepository: NoteRepository,
    @Autowired val notePageRepository: NotePageRepository,
    @Autowired val enrollmentRepository: EnrollmentRepository,
    @Autowired val subtopicRepository: SubtopicRepository,
    @Autowired val userRepository: UserRepository,
    @Autowired val noteMapper: NoteMapper,
    @Autowired val transcriptionService: TranscriptionService,
    @Autowired val coverageService: CoverageService
) : NoteService {

    companion object {
        const val MAX_PAGE_SIZE = 100
    }

    override fun createNote(input: NoteCreateDTO): NoteDTO {
        val student = currentStudent()

        // Idempotencia: si el teléfono reintenta una subida que sí llegó, se devuelve el mismo apunte.
        if (!input.clientRequestId.isNullOrBlank()) {
            val existing = noteRepository.findByClientRequestId(input.clientRequestId)
            if (existing.isPresent) {
                val note = existing.get()
                if (!isOwner(note, student)) throw ConflictException("El clientRequestId ya fue usado por otro apunte")
                return noteMapper.noteToNoteDTO(note)
            }
        }

        val enrollment = enrollmentRepository.findById(input.enrollmentId).orElseThrow {
            NotFoundException("Matrícula no encontrada con ID: ${input.enrollmentId}")
        }
        if (enrollment.student?.id != student.id) {
            throw ForbiddenException("Solo puede crear apuntes en sus propias matrículas")
        }
        if (enrollment.status != EnrollmentStatus.ACTIVE) {
            throw ForbiddenException("La matrícula todavía no está aprobada")
        }

        val note = noteMapper.toNote(input).apply {
            this.enrollment = enrollment
            this.subtopic = input.subtopicId?.let { subtopicOfCourse(it, enrollment) }
            this.syncStatus = SyncStatus.SYNCED
            this.createdAt = Instant.now()
        }
        return noteMapper.noteToNoteDTO(noteRepository.save(note))
    }

    override fun getMyNotes(): List<NoteDTO> =
        noteMapper.noteListToNoteDTOList(noteRepository.findByEnrollmentStudentIdOrderByCreatedAtDesc(currentStudent().id!!))

    override fun search(groupId: Long?, subtopicId: Long?, query: String?, page: Int, size: Int): PageResponse<NoteSummaryDTO> {
        val student = currentStudent()
        val pageable = PageRequest.of(
            page.coerceAtLeast(0),
            size.coerceIn(1, MAX_PAGE_SIZE),
            Sort.by(Sort.Direction.DESC, "createdAt"),
        )
        // El studentId sale siempre del usuario autenticado: los filtros del cliente solo pueden acotar.
        val result = noteRepository.searchByStudent(student.id!!, groupId, subtopicId, query?.trim() ?: "", pageable)
        return PageResponse(
            content = noteMapper.toNoteSummaryList(result.content),
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    override fun findById(id: Long): NoteDTO = noteMapper.noteToNoteDTO(ownedNote(id))

    override fun update(id: Long, input: NoteUpdateDTO): NoteDTO {
        val note = ownedNote(id)
        noteMapper.updateNoteFromRequest(input, note)
        input.subtopicId?.let { note.subtopic = subtopicOfCourse(it, note.enrollment!!) }
        return noteMapper.noteToNoteDTO(noteRepository.save(note))
    }

    override fun delete(id: Long) {
        val note = ownedNote(id)
        // La cobertura no puede quedarse con lo que este apunte respaldaba: se recalcula con los apuntes que quedan. Va
        // ANTES del borrado: la cobertura todavía apunta al apunte (note_origin_id) y hay que soltarla primero.
        coverageService.refreshAfterNoteRemoval(note)
        noteRepository.delete(note)
    }

    override fun addPage(noteId: Long, pageIndex: Int, filePath: String): NotePageDTO {
        val note = ownedNote(noteId)
        val saved = notePageRepository.save(NotePage(note = note, pageIndex = pageIndex, filePath = filePath))
        return noteMapper.notePageToNotePageDTO(saved)
    }

    override fun extractAndTranscribe(fileBytes: ByteArray?, mimeType: String?): TranscriptionResultDTO {
        currentStudent()
        return transcriptionService.transcribe(fileBytes, mimeType)
    }

    override fun transcribeAndAttach(noteId: Long, fileBytes: ByteArray?, mimeType: String?): NoteDTO {
        val note = ownedNote(noteId)
        val result = transcriptionService.transcribe(fileBytes, mimeType)

        // La salida cruda va a note.transcription (jsonb). confirmedText NO se toca: el estudiante revisa los
        // bloques dudosos y confirma el texto él mismo (PATCH); solo el texto confirmado pasa a la auditoría.
        note.transcription = mapOf(
            "proveedor" to result.proveedor,
            "modelo" to result.modelo,
            "promptVersion" to result.promptVersion,
            "textoCompleto" to result.textoCompleto,
            "bloquesPorRevisar" to result.bloquesPorRevisar,
            "paginas" to result.paginas.map { page ->
                mapOf(
                    "indice" to page.indice,
                    "bloques" to page.bloques.map { block ->
                        mapOf(
                            "texto" to block.texto,
                            "confianza" to block.confianza,
                            "tipo" to block.tipo,
                            "requiereRevision" to block.requiereRevision
                        )
                    }
                )
            }
        )
        note.syncStatus = SyncStatus.SYNCED
        return noteMapper.noteToNoteDTO(noteRepository.save(note))
    }

    // Usuario autenticado. Un profesor queda fuera de todos los apuntes individuales.
    private fun currentStudent(): User {
        val user = userRepository.findByEmail(LoggedUser.get()).orElseThrow {
            UnauthorizedException("Usuario autenticado no encontrado")
        }
        if (user.roleList.any { it.name == "ROLE_TEACHER" }) {
            throw ForbiddenException("Los profesores no tienen acceso a los apuntes individuales")
        }
        return user
    }

    // Primero existe (404), después es del usuario (403).
    private fun ownedNote(id: Long): Note {
        val student = currentStudent()
        val note = noteRepository.findById(id).orElseThrow { NotFoundException("Apunte no encontrado con ID: $id") }
        if (!isOwner(note, student)) throw ForbiddenException("No tiene acceso a este apunte")
        return note
    }

    private fun isOwner(note: Note, student: User) = note.enrollment?.student?.id == student.id

    // El subtema tiene que ser del temario del curso del grupo donde está matriculado.
    private fun subtopicOfCourse(subtopicId: Long, enrollment: Enrollment): Subtopic {
        val subtopic = subtopicRepository.findById(subtopicId).orElseThrow {
            NotFoundException("Subtema no encontrado con ID: $subtopicId")
        }
        if (subtopic.module?.course?.id != enrollment.group?.course?.id) {
            throw BusinessRuleException("El subtema no pertenece al curso de este grupo")
        }
        return subtopic
    }
}

// ==================== COVERAGE SERVICE (SVC-06) ====================

/*
 * SVC-06: cobertura del temario. El estudiante ve la suya; el profesor dueño del grupo ve solo la
 * agregada y anónima. La privacidad se impone aquí, en el servidor: la vista del grupo se arma con
 * conteos que agrega la base y nunca pasa por una fila de un estudiante.
 */
interface CoverageService {
    fun registerAudit(noteId: Long, request: NoteAuditRequestDTO): List<SubtopicCoverageDTO>

    /** Aplica los veredictos de una auditoría (simulada o de IA): valida, guarda la salida en el apunte y consolida la cobertura. */
    fun applyAudit(noteId: Long, input: List<SubtopicVerdictDTO>, model: String, promptVersion: String): List<SubtopicCoverageDTO>

    /**
     * Recalcula la cobertura de los subtemas que aportó un apunte que se acaba de borrar, usando solo los apuntes que le
     * quedan al estudiante. Sin esto la cobertura es un trinquete: sube con cada auditoría pero nunca baja.
     */
    fun refreshAfterNoteRemoval(note: Note)
    fun personalCoverage(groupId: Long): PersonalCoverageDTO
    fun groupCoverage(groupId: Long): GroupCoverageDTO
}

@Service
@Transactional
class AbstractCoverageService(
    @Autowired val noteRepository: NoteRepository,
    @Autowired val subtopicRepository: SubtopicRepository,
    @Autowired val coverageRepository: CoverageRepository,
    @Autowired val enrollmentRepository: EnrollmentRepository,
    @Autowired val studentGroupRepository: StudentGroupRepository,
    @Autowired val userRepository: UserRepository,
    @Autowired val coverageMapper: CoverageMapper,
    @Autowired val reviewRepository: ReviewRepository
) : CoverageService {

    companion object {
        // Identifican la auditoría simulada; la llamada de IA 3 los reemplazará con su modelo y versión.
        const val AUDIT_MODEL = "stub"
        const val AUDIT_PROMPT_VERSION = "stub-v1"

        private val RANK = mapOf(
            CoverageStatus.NO_NOTES to 0, CoverageStatus.PARTIAL to 1,
            CoverageStatus.COVERED to 2, CoverageStatus.REINFORCED to 3,
        )
        private val COVERED_STATES = setOf(CoverageStatus.COVERED, CoverageStatus.REINFORCED)

        /*
         * Consolidación: se conserva el mejor estado. Un subtema ya cubierto que vuelve a salir cubierto
         * en otro apunte pasa a REINFORCED; un veredicto peor nunca baja lo que el estudiante ya logró.
         */
        fun consolidate(current: CoverageStatus?, verdict: CoverageStatus): CoverageStatus = when {
            current == null -> verdict
            verdict == CoverageStatus.COVERED && current in COVERED_STATES -> CoverageStatus.REINFORCED
            RANK.getValue(verdict) > RANK.getValue(current) -> verdict
            else -> current
        }

        /*
         * Estado final de un subtema cuando se borra un apunte que lo respaldaba. [fromRemainingNotes] es lo que sostienen
         * los apuntes que quedan (null si ninguno lo toca) y [hasPassedReview] dice si el estudiante aprobó un repaso de
         * ese subtema. Devolver null borra la fila de cobertura (el subtema vuelve a "sin apuntes").
         */
        fun statusAfterRemoval(fromRemainingNotes: CoverageStatus?, hasPassedReview: Boolean): CoverageStatus? {
            // TODO(human): decidir cuánto vale un repaso aprobado cuando ya no queda el apunte que lo originó.
            // Hoy: un repaso aprobado conserva al menos COVERED; sin repaso, manda lo que sostienen los apuntes.
            return when {
                !hasPassedReview -> fromRemainingNotes
                fromRemainingNotes == null || RANK.getValue(fromRemainingNotes) < RANK.getValue(CoverageStatus.COVERED) -> CoverageStatus.COVERED
                else -> fromRemainingNotes
            }
        }
    }

    // Veredictos por subtema que quedaron guardados en note.audit: subtemaId -> (estado, evidencia).
    private fun verdictsOf(note: Note): Map<Long, Pair<CoverageStatus, String?>> =
        (note.audit?.get("verdicts") as? List<*>).orEmpty().mapNotNull { raw ->
            val verdict = raw as? Map<*, *> ?: return@mapNotNull null
            val subtopicId = (verdict["subtopicId"] as? Number)?.toLong() ?: return@mapNotNull null
            val status = runCatching { CoverageStatus.valueOf(verdict["status"].toString()) }.getOrNull() ?: return@mapNotNull null
            subtopicId to (status to verdict["evidence"]?.toString())
        }.toMap()

    override fun refreshAfterNoteRemoval(note: Note) {
        val studentId = note.enrollment?.student?.id ?: return
        val affected = verdictsOf(note).keys
        if (affected.isEmpty()) return

        // Del más antiguo al más reciente, que es el orden en que se consolidaron: de él depende que un subtema cubierto
        // en dos apuntes llegue a REINFORCED.
        val remaining = noteRepository.findByEnrollmentStudentIdOrderByCreatedAtDesc(studentId)
            .filter { it.id != note.id }
            .sortedBy { it.createdAt }
        val passedSubtopics = reviewRepository.findByStudentId(studentId)
            .filter { it.result == ReviewResult.PASSED }
            .mapNotNull { it.subtopic?.id }
            .toSet()

        for (subtopicId in affected) {
            val existing = coverageRepository.findByStudentIdAndSubtopicId(studentId, subtopicId).orElse(null) ?: continue

            var fromNotes: CoverageStatus? = null
            var origin: Note? = null
            var evidence: String? = null
            for (other in remaining) {
                val (status, text) = verdictsOf(other)[subtopicId] ?: continue
                val consolidated = consolidate(fromNotes, status)
                if (fromNotes == null || consolidated != fromNotes) {
                    origin = other
                    evidence = text
                }
                fromNotes = consolidated
            }

            val finalStatus = statusAfterRemoval(fromNotes, subtopicId in passedSubtopics)
            when {
                finalStatus == null -> coverageRepository.delete(existing)
                finalStatus != existing.status || existing.noteOrigin?.id == note.id -> {
                    val fromReview = finalStatus != fromNotes
                    existing.status = finalStatus
                    existing.justification = if (fromReview) "Aprobó un repaso de este subtema." else evidence
                    existing.noteOrigin = if (fromReview) null else origin
                    existing.updatedAt = Instant.now()
                    coverageRepository.save(existing)
                }
            }
        }
    }

    override fun registerAudit(noteId: Long, request: NoteAuditRequestDTO): List<SubtopicCoverageDTO> =
        applyAudit(noteId, request.verdicts, AUDIT_MODEL, AUDIT_PROMPT_VERSION)

    override fun applyAudit(noteId: Long, input: List<SubtopicVerdictDTO>, model: String, promptVersion: String): List<SubtopicCoverageDTO> {
        val student = currentUser().also { requireNotTeacher(it) }
        val note = noteRepository.findById(noteId).orElseThrow { NotFoundException("Apunte no encontrado con ID: $noteId") }
        if (note.enrollment?.student?.id != student.id) throw ForbiddenException("Solo el dueño del apunte puede auditarlo")
        val courseId = note.enrollment?.group?.course?.id

        // Se validan todos los subtemas antes de tocar nada: o se registra la auditoría completa o nada.
        val verdicts = input.map { verdict ->
            val subtopic = subtopicRepository.findById(verdict.subtopicId).orElseThrow {
                NotFoundException("Subtema no encontrado con ID: ${verdict.subtopicId}")
            }
            if (subtopic.module?.course?.id != courseId) {
                throw BusinessRuleException("El subtema ${subtopic.code} no pertenece al curso del apunte")
            }
            Triple(subtopic, CoverageStatus.valueOf(verdict.status), verdict.evidence)
        }

        note.audit = mapOf(
            "model" to model,
            "promptVersion" to promptVersion,
            "auditedAt" to Instant.now().toString(),
            "verdicts" to input.map { mapOf("subtopicId" to it.subtopicId, "status" to it.status, "evidence" to it.evidence) },
        )
        noteRepository.save(note)

        return verdicts.map { (subtopic, status, evidence) ->
            val existing = coverageRepository.findByStudentIdAndSubtopicId(student.id!!, subtopic.id!!).orElse(null)
            val coverage = existing ?: Coverage(student = student, subtopic = subtopic)
            val consolidated = consolidate(existing?.status, status)
            if (existing == null || consolidated != existing.status) {
                coverage.status = consolidated
                coverage.justification = evidence
                coverage.noteOrigin = note
                coverage.updatedAt = Instant.now()
            }
            coverageMapper.toSubtopicCoverage(subtopic, coverageRepository.save(coverage))
        }
    }

    override fun personalCoverage(groupId: Long): PersonalCoverageDTO {
        val student = currentUser().also { requireNotTeacher(it) }
        val group = studentGroupRepository.findById(groupId).orElseThrow { NotFoundException("Grupo no encontrado con ID: $groupId") }
        if (!enrollmentRepository.existsByGroupIdAndStudentIdAndStatus(groupId, student.id!!, EnrollmentStatus.ACTIVE)) {
            throw ForbiddenException("Solo un estudiante con matrícula aprobada en el grupo ve su cobertura")
        }
        val courseId = group.course!!.id!!
        val subtopics = subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(courseId)
        val bySubtopic = coverageRepository.findByStudentIdAndSubtopicModuleCourseId(student.id!!, courseId)
            .associateBy { it.subtopic?.id }

        val items = subtopics.map { coverageMapper.toSubtopicCoverage(it, bySubtopic[it.id]) }
        val covered = items.count { CoverageStatus.valueOf(it.status) in COVERED_STATES }
        val percentage = if (items.isEmpty()) 0.0 else covered * 100.0 / items.size
        return coverageMapper.toPersonalCoverage(groupId, percentage, items)
    }

    override fun groupCoverage(groupId: Long): GroupCoverageDTO {
        val user = currentUser()
        val group = studentGroupRepository.findById(groupId).orElseThrow { NotFoundException("Grupo no encontrado con ID: $groupId") }
        if (group.teacher?.id != user.id) throw ForbiddenException("Solo el profesor del grupo ve su cobertura")

        val totalStudents = enrollmentRepository.countByGroupIdAndStatus(groupId, EnrollmentStatus.ACTIVE).toInt()
        val subtopics = subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(group.course!!.id!!)
        val totals = coverageRepository.countByStatusForGroup(groupId)
            .groupBy { it.subtopicId }
            .mapValues { (_, rows) -> rows.associate { it.status to it.total } }

        // Quien no tiene fila de cobertura (o la tiene en NO_NOTES) cuenta como "sin apuntes".
        val counts = subtopics.map { subtopic ->
            val byStatus = totals[subtopic.id].orEmpty()
            val covered = COVERED_STATES.sumOf { byStatus[it] ?: 0L }
            val partial = byStatus[CoverageStatus.PARTIAL] ?: 0L
            SubtopicCoverageCounts(
                subtopicId = subtopic.id!!,
                subtopicCode = subtopic.code,
                subtopicTitle = subtopic.title,
                covered = covered,
                partial = partial,
                noNotes = (totalStudents - covered - partial).coerceAtLeast(0),
                moduleNumber = subtopic.module?.number,
                moduleTitle = subtopic.module?.title,
            )
        }
        return coverageMapper.toGroupCoverage(groupId, totalStudents, counts)
    }

    private fun currentUser(): User = userRepository.findByEmail(LoggedUser.get()).orElseThrow {
        UnauthorizedException("Usuario autenticado no encontrado")
    }

    private fun requireNotTeacher(user: User) {
        if (user.roleList.any { it.name == "ROLE_TEACHER" }) {
            throw ForbiddenException("Un profesor no accede a la cobertura individual de un estudiante")
        }
    }
}

// ==================== COVERAGE & REVIEW SERVICE (SVC-06 STUB) ====================

interface CoverageReviewService {
    fun getMyCoverage(): List<CoverageDTO>
    fun auditCoverageStub(input: CoverageAuditStubRequest): CoverageAuditStubResponse
}

@Service
@Transactional
class AbstractCoverageReviewService(
    @Autowired val coverageRepository: CoverageRepository,
    @Autowired val noteRepository: NoteRepository,
    @Autowired val subtopicRepository: SubtopicRepository,
    @Autowired val userRepository: UserRepository
) : CoverageReviewService {

    override fun getMyCoverage(): List<CoverageDTO> {
        val email = LoggedUser.get()
        val student = userRepository.findByEmail(email).orElseThrow {
            NotFoundException("Estudiante autenticado no encontrado")
        }
        return coverageRepository.findByStudentId(student.id!!).map { mapCoverage(it) }
    }

    // SVC-06: Endpoint stub de auditoría de cobertura simulado sin IA
    override fun auditCoverageStub(input: CoverageAuditStubRequest): CoverageAuditStubResponse {
        val note = noteRepository.findById(input.noteId).orElseThrow {
            NotFoundException("Apunte no encontrado con ID: ${input.noteId}")
        }
        val subtopic = subtopicRepository.findById(input.subtopicId).orElseThrow {
            NotFoundException("Subtópico no encontrado con ID: ${input.subtopicId}")
        }

        val student = note.enrollment?.student ?: throw NotFoundException("Estudiante no asociado a apunte")

        val textLength = note.confirmedText?.length ?: 0
        val calculatedStatus = when {
            textLength > 100 -> CoverageStatus.REINFORCED
            textLength > 30 -> CoverageStatus.COVERED
            textLength > 0 -> CoverageStatus.PARTIAL
            else -> CoverageStatus.NO_NOTES
        }

        val justification = "Auditoría de cobertura simulada (Stub SVC-06) para el apunte #${note.id} sobre el subtópico ${subtopic.code}"

        var coverage = coverageRepository.findByStudentIdAndSubtopicId(student.id!!, subtopic.id!!).orElse(null)
        if (coverage == null) {
            coverage = Coverage(
                student = student,
                subtopic = subtopic,
                status = calculatedStatus,
                justification = justification,
                noteOrigin = note,
                updatedAt = Instant.now()
            )
        } else {
            coverage.status = calculatedStatus
            coverage.justification = justification
            coverage.noteOrigin = note
            coverage.updatedAt = Instant.now()
        }

        val saved = coverageRepository.save(coverage)

        return CoverageAuditStubResponse(
            coverageId = saved.id!!,
            subtopicId = subtopic.id!!,
            calculatedStatus = saved.status.name,
            justification = saved.justification ?: "",
            auditTimestamp = saved.updatedAt
        )
    }

    private fun mapCoverage(c: Coverage) = CoverageDTO(
        id = c.id!!,
        studentId = c.student?.id ?: 0L,
        subtopicId = c.subtopic?.id ?: 0L,
        subtopicCode = c.subtopic?.code,
        subtopicTitle = c.subtopic?.title,
        status = c.status.name,
        justification = c.justification,
        noteOriginId = c.noteOrigin?.id,
        updatedAt = c.updatedAt
    )
}

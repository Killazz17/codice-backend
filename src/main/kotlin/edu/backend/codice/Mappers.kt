package edu.backend.codice

import org.mapstruct.*

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
interface UserMapper {
    @Mapping(target = "password", ignore = true)
    fun toUser(request: UserSignUpRequest): User

    @Mapping(target = "createdAt", source = "createDate")
    @Mapping(target = "roles", expression = "java(user.getRoleList().stream().map(r -> r.getName()).collect(java.util.stream.Collectors.toList()))")
    fun toUserResponse(user: User): UserResponse
}

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
interface CourseMapper {
    fun toCourse(dto: CourseCreateRequest): Course

    @Mapping(target = "teacherId", source = "teacher.id")
    fun toCourseResponse(course: Course): CourseResponse

    fun toCourseResponseList(courseList: List<Course>): List<CourseResponse>

    // Para los listados (DTO-03). Contar los grupos recorre la colección del curso; con los pocos cursos de un profesor basta.
    @Mapping(target = "totalGroups", expression = "java(course.getGroups().size())")
    fun toCourseSummary(course: Course): CourseSummaryResponse

    fun toCourseSummaryList(courseList: List<Course>): List<CourseSummaryResponse>

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    fun updateCourseFromRequest(dto: CourseCreateRequest, @MappingTarget course: Course)

    // PATCH (DTO-03): los campos ausentes (null) no tocan el curso.
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    fun updateCourseFromRequest(dto: CourseUpdateRequest, @MappingTarget course: Course)
}

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
interface StudentGroupMapper {
    fun toStudentGroup(dto: StudentGroupCreateRequest): StudentGroup

    @Mapping(target = "courseId", source = "group.course.id")
    @Mapping(target = "courseCode", source = "group.course.code")
    @Mapping(target = "courseName", source = "group.course.name")
    @Mapping(target = "teacherId", source = "group.teacher.id")
    @Mapping(target = "teacherName", expression = "java(group.getTeacher() != null ? group.getTeacher().getFirstName() + \" \" + group.getTeacher().getLastName() : null)")
    fun toStudentGroupResponse(group: StudentGroup): StudentGroupResponse

    fun toStudentGroupResponseList(groupList: List<StudentGroup>): List<StudentGroupResponse>

    // Lo que ve el estudiante (DTO-03): el grupo sin el código de invitación.
    @Mapping(target = "courseId", source = "group.course.id")
    fun toStudentGroupPublicResponse(group: StudentGroup): StudentGroupPublicResponse

    fun toStudentGroupPublicResponseList(groupList: List<StudentGroup>): List<StudentGroupPublicResponse>
}

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
interface EnrollmentMapper {
    // El curso se toma del grupo: enrollment.course lo llena un trigger de la base y en una matrícula
    // recién creada todavía es null.
    @Mapping(target = "groupId", source = "enrollment.group.id")
    @Mapping(target = "groupNumber", source = "enrollment.group.number")
    @Mapping(target = "courseId", source = "enrollment.group.course.id")
    @Mapping(target = "courseCode", source = "enrollment.group.course.code")
    @Mapping(target = "courseName", source = "enrollment.group.course.name")
    @Mapping(target = "studentId", source = "enrollment.student.id")
    @Mapping(target = "studentName", expression = "java(enrollment.getStudent() != null ? enrollment.getStudent().getFirstName() + \" \" + enrollment.getStudent().getLastName() : null)")
    @Mapping(target = "status", expression = "java(enrollment.getStatus() != null ? enrollment.getStatus().name() : null)")
    fun toEnrollmentResponse(enrollment: Enrollment): EnrollmentResponse

    fun toEnrollmentResponseList(enrollmentList: List<Enrollment>): List<EnrollmentResponse>

    // Lo que ve el profesor en la lista de solicitudes: solo nombre y correo del estudiante.
    @Mapping(target = "studentName", expression = "java(enrollment.getStudent().getFirstName() + \" \" + enrollment.getStudent().getLastName())")
    @Mapping(target = "studentEmail", source = "enrollment.student.email")
    @Mapping(target = "status", expression = "java(enrollment.getStatus().name())")
    fun toEnrollmentRequestSummary(enrollment: Enrollment): EnrollmentRequestSummaryResponse

    fun toEnrollmentRequestSummaryList(enrollmentList: List<Enrollment>): List<EnrollmentRequestSummaryResponse>

    // Explícito a propósito: la decisión (APPROVE/REJECT) no se llama igual que el estado al que lleva.
    fun toEnrollmentStatus(request: EnrollmentDecisionRequest): EnrollmentStatus = when (request.decision) {
        "APPROVE" -> EnrollmentStatus.ACTIVE
        "REJECT" -> EnrollmentStatus.REJECTED
        else -> throw IllegalArgumentException("Decisión inválida: ${request.decision}")
    }
}

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
interface SyllabusMapper {
    // ---------- Entrada ----------
    // El servicio cuelga el subtema de su módulo y el objetivo de su subtema; el mapper solo copia datos.

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "module", ignore = true)
    @Mapping(target = "objectives", ignore = true)
    fun toSubtopic(request: SubtopicCreateRequest): Subtopic

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "module", ignore = true)
    @Mapping(target = "objectives", ignore = true)
    fun updateSubtopicFromRequest(request: SubtopicUpdateRequest, @MappingTarget subtopic: Subtopic)

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "subtopic", ignore = true)
    fun toObjective(request: ObjectiveCreateRequest): SubtopicObjective

    // ---------- Salida ----------

    @Mapping(target = "courseId", source = "module.course.id")
    @Mapping(target = "subtopics", expression = "java(toSubtopicDTOList(module.getSubtopics()))")
    fun moduleToModuleDTO(module: CourseModule): ModuleDTO

    fun moduleListToModuleDTOList(moduleList: List<CourseModule>): List<ModuleDTO>

    @Mapping(target = "moduleId", source = "subtopic.module.id")
    @Mapping(target = "objectives", expression = "java(toObjectiveDTOList(subtopic.getObjectives()))")
    fun subtopicToSubtopicDTO(subtopic: Subtopic): SubtopicDTO

    fun subtopicObjectiveToSubtopicObjectiveDTO(objective: SubtopicObjective): SubtopicObjectiveDTO

    // MapStruct no ordena ni agrupa por sí solo; el orden del árbol se resuelve en estos métodos default.

    // Subtemas por position: el código no sirve para ordenar ('4.10' < '4.2' como texto).
    fun toSubtopicDTOList(subtopics: List<Subtopic>): List<SubtopicDTO> =
        subtopics.sortedBy { it.position }.map { subtopicToSubtopicDTO(it) }

    // Los objetivos no tienen columna de orden: se listan en el orden en que se crearon (por id).
    fun toObjectiveDTOList(objectives: List<SubtopicObjective>): List<SubtopicObjectiveDTO> =
        objectives.sortedBy { it.id ?: Long.MAX_VALUE }.map { subtopicObjectiveToSubtopicObjectiveDTO(it) }

    // El temario completo del curso: módulos por número y, dentro de cada uno, subtemas y objetivos ordenados.
    fun toSyllabusDTO(course: Course, modules: List<CourseModule>): SyllabusResponse = SyllabusDTO(
        courseId = requireNotNull(course.id) { "El curso debe estar guardado para armar su temario" },
        published = course.syllabusPublishedAt != null,
        publishedAt = course.syllabusPublishedAt,
        modules = modules.sortedBy { it.number }.map { moduleToModuleDTO(it) },
    )
}

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
interface NoteMapper {
    // Las relaciones (matrícula y subtema) las resuelve el servicio a partir de los ids; el estado de
    // sincronización, la fecha y la auditoría arrancan con los valores por defecto de la entidad.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "enrollment", ignore = true)
    @Mapping(target = "subtopic", ignore = true)
    @Mapping(target = "syncStatus", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "transcription", ignore = true)
    @Mapping(target = "audit", ignore = true)
    @Mapping(target = "pages", ignore = true)
    fun toNote(request: NoteCreateRequest): Note

    // Solo el texto: si el subtema cambia, el servicio busca el nuevo y lo asigna.
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "enrollment", ignore = true)
    @Mapping(target = "subtopic", ignore = true)
    @Mapping(target = "clientRequestId", ignore = true)
    @Mapping(target = "syncStatus", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "transcription", ignore = true)
    @Mapping(target = "audit", ignore = true)
    @Mapping(target = "pages", ignore = true)
    fun updateNoteFromRequest(request: NoteUpdateRequest, @MappingTarget note: Note)

    @Mapping(target = "enrollmentId", source = "note.enrollment.id")
    @Mapping(target = "subtopicId", source = "note.subtopic.id")
    @Mapping(target = "syncStatus", expression = "java(note.getSyncStatus() != null ? note.getSyncStatus().name() : null)")
    @Mapping(target = "audited", expression = "java(note.getAudit() != null)")
    fun noteToNoteDTO(note: Note): NoteDTO

    fun noteListToNoteDTOList(noteList: List<Note>): List<NoteDTO>
    fun notePageToNotePageDTO(page: NotePage): NotePageDTO

    @Mapping(target = "subtopicId", source = "note.subtopic.id")
    @Mapping(target = "syncStatus", expression = "java(note.getSyncStatus().name())")
    @Mapping(target = "audited", expression = "java(note.getAudit() != null)")
    @Mapping(target = "pageCount", expression = "java(note.getPages().size())")
    @Mapping(target = "subtopicCode", source = "note.subtopic.code")
    @Mapping(target = "subtopicTitle", source = "note.subtopic.title")
    @Mapping(target = "moduleId", source = "note.subtopic.module.id")
    @Mapping(target = "moduleLabel", expression = "java(moduleLabel(note))")
    fun toNoteSummary(note: Note): NoteSummaryResponse

    fun moduleLabel(note: Note): String? = note.subtopic?.module?.let { "Módulo ${it.number} · ${it.title}" }

    fun toNoteSummaryList(noteList: List<Note>): List<NoteSummaryResponse>
}

/*
 * Conteos por subtema que calcula el servicio de cobertura (SVC-06) para un grupo. Es la única entrada
 * de la vista del profesor: solo números por subtema, sin ninguna fila de un estudiante. Los conteos son
 * Long porque así los devuelve COUNT en una consulta JPQL con constructor.
 */
data class SubtopicCoverageCounts(
    val subtopicId: Long,
    val subtopicCode: String,
    val subtopicTitle: String,
    // COVERED + REINFORCED.
    val covered: Long,
    val partial: Long,
    // Estudiantes sin apuntes del subtema, tengan o no fila de cobertura.
    val noNotes: Long,
    val moduleNumber: Int? = null,
    val moduleTitle: String? = null
)

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
interface CoverageMapper {
    @Mapping(target = "studentId", source = "coverage.student.id")
    @Mapping(target = "subtopicId", source = "coverage.subtopic.id")
    @Mapping(target = "subtopicCode", source = "coverage.subtopic.code")
    @Mapping(target = "subtopicTitle", source = "coverage.subtopic.title")
    @Mapping(target = "status", expression = "java(coverage.getStatus() != null ? coverage.getStatus().name() : null)")
    @Mapping(target = "noteOriginId", source = "coverage.noteOrigin.id")
    fun coverageToCoverageDTO(coverage: Coverage): CoverageDTO

    fun coverageListToCoverageDTOList(coverageList: List<Coverage>): List<CoverageDTO>

    // ---------- Cobertura personal ----------

    // Un subtema sin fila de cobertura es un subtema del que el estudiante todavía no tiene apuntes.
    fun toSubtopicCoverage(subtopic: Subtopic, coverage: Coverage?): SubtopicCoverageDTO = SubtopicCoverageDTO(
        subtopicId = requireNotNull(subtopic.id) { "El subtema debe estar guardado" },
        subtopicCode = subtopic.code,
        subtopicTitle = subtopic.title,
        status = (coverage?.status ?: CoverageStatus.NO_NOTES).name,
        moduleNumber = subtopic.module?.number,
        moduleTitle = subtopic.module?.title,
    )

    // El porcentaje lo calcula el servicio; el mapper solo arma la respuesta.
    fun toPersonalCoverage(groupId: Long, coveredPercentage: Double, subtopics: List<SubtopicCoverageDTO>): PersonalCoverageResponse =
        PersonalCoverageDTO(groupId = groupId, coveredPercentage = coveredPercentage, subtopics = subtopics)

    // ---------- Cobertura del grupo (agregada y anónima) ----------
    // Estos métodos solo reciben conteos y el total del grupo: nunca una entidad ni un id de estudiante.

    // Conteo → porcentaje de 0 a 100. Un grupo sin estudiantes da 0 en todo en vez de dividir entre cero.
    fun toAggregatedCoverage(counts: SubtopicCoverageCounts, totalStudents: Int): AggregatedSubtopicCoverageDTO {
        fun percentage(count: Long) = if (totalStudents == 0) 0.0 else count * 100.0 / totalStudents
        return AggregatedSubtopicCoverageDTO(
            subtopicId = counts.subtopicId,
            subtopicCode = counts.subtopicCode,
            subtopicTitle = counts.subtopicTitle,
            coveredPercentage = percentage(counts.covered),
            partialPercentage = percentage(counts.partial),
            noNotesPercentage = percentage(counts.noNotes),
            moduleNumber = counts.moduleNumber,
            moduleTitle = counts.moduleTitle,
        )
    }

    fun toGroupCoverage(groupId: Long, totalStudents: Int, counts: List<SubtopicCoverageCounts>): GroupCoverageResponse =
        GroupCoverageDTO(
            groupId = groupId,
            totalStudents = totalStudents,
            subtopics = counts.map { toAggregatedCoverage(it, totalStudents) },
        )
}

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
interface ReviewMapper {
    @Mapping(target = "studentId", source = "review.student.id")
    @Mapping(target = "subtopicId", source = "review.subtopic.id")
    @Mapping(target = "subtopicCode", source = "review.subtopic.code")
    @Mapping(target = "subtopicTitle", source = "review.subtopic.title")
    @Mapping(target = "result", expression = "java(review.getResult() != null ? review.getResult().name() : null)")
    fun reviewToReviewDTO(review: Review): ReviewDTO

    fun reviewListToReviewDTOList(reviewList: List<Review>): List<ReviewDTO>
}

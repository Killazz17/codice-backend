package edu.backend.codice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ServicesAndControllersTest(
    @Autowired val authService: AuthService,
    @Autowired val courseService: CourseService,
    @Autowired val studentGroupService: StudentGroupService,
    @Autowired val enrollmentService: EnrollmentService,
    @Autowired val syllabusService: SyllabusService,
    @Autowired val noteService: NoteService,
    @Autowired val coverageReviewService: CoverageReviewService,
    @Autowired val userRepository: UserRepository,
    @Autowired val courseRepository: CourseRepository,
    @Autowired val studentGroupRepository: StudentGroupRepository,
    @Autowired val enrollmentRepository: EnrollmentRepository,
    @Autowired val noteRepository: NoteRepository,
    @Autowired val subtopicRepository: SubtopicRepository,
    @Autowired val roleRepository: RoleRepository
) {

    // Desde SVC-02 los cursos y grupos los crea un profesor autenticado (ROLE_TEACHER), dueño del curso.
    private fun teacher(email: String): User {
        val role = roleRepository.findByName("ROLE_TEACHER").orElseGet { roleRepository.save(Role(name = "ROLE_TEACHER")) }
        return userRepository.save(User(firstName = "Profe", lastName = "Test", email = email, password = "pass", roleList = mutableSetOf(role)))
    }

    @Test
    fun `test AuthService signUp and getCurrentUser`() {
        val input = UserSignUpInput(
            firstName = "Carlos",
            lastName = "Mora",
            email = "carlos.mora@una.cr",
            password = "password123",
            roleName = "ROLE_STUDENT"
        )
        val created = authService.signUp(input)
        assertNotNull(created.id)
        assertEquals("carlos.mora@una.cr", created.email)

        LoggedUser.logIn("carlos.mora@una.cr")
        val current = authService.getCurrentUser()
        assertEquals("Carlos", current.firstName)
        LoggedUser.logOut()
    }

    @Test
    fun `test CourseService create and findAll`() {
        teacher("profe.course@una.cr")
        LoggedUser.logIn("profe.course@una.cr")
        try {
            val input = CourseCreateDTO(
                code = "TEST101",
                name = "Curso de Prueba",
                color = "#FF0000"
            )
            val created = courseService.create(input)
            assertNotNull(created.id)
            assertEquals("TEST101", created.code)

            val all = courseService.findAll()
            assertTrue(all.any { it.code == "TEST101" })
        } finally {
            LoggedUser.logOut()
        }
    }

    @Test
    fun `test StudentGroupService createGroup and findByInviteCode`() {
        val teacher = teacher("profe.test@una.cr")
        val course = courseRepository.save(Course(code = "GRP101", name = "Programación", teacher = teacher))

        LoggedUser.logIn("profe.test@una.cr")
        try {
            val groupInput = StudentGroupCreateDTO(
                courseId = course.id!!,
                number = "01",
                period = "2026-I",
                schedule = "L/J 08:00-10:00"
            )
            val group = studentGroupService.createGroup(groupInput)
            assertEquals("01", group.number)
            // El código lo genera el servidor: GRP101-01-XXXX.
            assertTrue(group.inviteCode.startsWith("GRP101-01-"))

            val found = studentGroupService.findByInviteCode(group.inviteCode)
            assertEquals(group.id, found.id)
        } finally {
            LoggedUser.logOut()
        }
    }

    @Test
    fun `test NoteService createNote idempotency`() {
        val course = courseRepository.save(Course(code = "NOTE101", name = "Apuntes Test"))
        val teacher = userRepository.save(User(firstName = "Profe", lastName = "Notes", email = "profe.notes@una.cr", password = "pass"))
        val group = studentGroupRepository.save(StudentGroup(course = course, teacher = teacher, number = "01", period = "2026", inviteCode = "NOTE-CODE-1"))
        val student = userRepository.save(User(firstName = "Estudiante", lastName = "Notes", email = "estudiante.notes@una.cr", password = "pass"))
        val enrollment = enrollmentRepository.save(Enrollment(group = group, course = course, student = student, status = EnrollmentStatus.ACTIVE))

        val noteInput = NoteCreateDTO(
            enrollmentId = enrollment.id!!,
            clientRequestId = "REQ-ID-12345",
            confirmedText = "Texto del apunte comprobado para idempotencia"
        )

        // Los apuntes se crean en nombre del estudiante dueño de la matrícula (SVC-05).
        LoggedUser.logIn("estudiante.notes@una.cr")
        try {
            val note1 = noteService.createNote(noteInput)
            val note2 = noteService.createNote(noteInput)

            assertEquals(note1.id, note2.id)
            assertEquals("REQ-ID-12345", note2.clientRequestId)
        } finally {
            LoggedUser.logOut()
        }
    }

    @Test
    fun `test CoverageAuditStub (SVC-06)`() {
        val course = courseRepository.save(Course(code = "STUB101", name = "Stub Test"))
        val module = CourseModule(course = course, number = 1, title = "Módulo 1")
        val subtopic = Subtopic(module = module, code = "1.1", title = "Introducción")
        module.addSubtopic(subtopic)
        course.addModule(module)
        courseRepository.save(course)

        val teacher = userRepository.save(User(firstName = "Profe", lastName = "Stub", email = "profe.stub@una.cr", password = "pass"))
        val group = studentGroupRepository.save(StudentGroup(course = course, teacher = teacher, number = "01", period = "2026", inviteCode = "STUB-CODE-1"))
        val student = userRepository.save(User(firstName = "Estudiante", lastName = "Stub", email = "estudiante.stub@una.cr", password = "pass"))
        val enrollment = enrollmentRepository.save(Enrollment(group = group, course = course, student = student, status = EnrollmentStatus.ACTIVE))

        val savedSubtopic = subtopicRepository.findAll().first { it.code == "1.1" }
        val note = noteRepository.save(Note(enrollment = enrollment, subtopic = savedSubtopic, confirmedText = "Este es un texto largo para simular una cobertura completa en el Stub SVC-06 de la auditoría de apunte de prueba"))

        val auditResult = coverageReviewService.auditCoverageStub(
            CoverageAuditStubRequest(noteId = note.id!!, subtopicId = savedSubtopic.id!!)
        )

        assertNotNull(auditResult.coverageId)
        assertEquals("REINFORCED", auditResult.calculatedStatus)
        assertTrue(auditResult.justification.contains("Stub SVC-06"))
    }
}

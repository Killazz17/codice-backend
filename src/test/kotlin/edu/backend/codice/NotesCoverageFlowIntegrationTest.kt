package edu.backend.codice

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/*
 * Recorrido completo sobre H2 con los servicios reales (API-06 y API-07): el profesor arma el curso, el
 * grupo y el temario; tres estudiantes se unen con el código y él los aprueba; dos escriben apuntes y los
 * auditan. Se comprueba la búsqueda con q de extremo a extremo y los números de la cobertura personal y
 * de la del grupo.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotesCoverageFlowIntegrationTest(
    @Autowired val courseService: CourseService,
    @Autowired val groupService: StudentGroupService,
    @Autowired val enrollmentService: EnrollmentService,
    @Autowired val syllabusService: SyllabusService,
    @Autowired val noteService: NoteService,
    @Autowired val coverageService: CoverageService,
    @Autowired val userRepository: UserRepository,
    @Autowired val roleRepository: RoleRepository,
) {

    private data class Setup(val group: StudentGroupDTO, val avl: Long, val heaps: Long)

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun user(email: String, role: String): String {
        val saved = roleRepository.findByName(role).orElseGet { roleRepository.save(Role(name = role)) }
        userRepository.save(User(firstName = email.substringBefore("."), lastName = "Flow", email = email, password = "x", roleList = mutableSetOf(saved)))
        return email
    }

    private fun <T> asUser(email: String, block: () -> T): T {
        LoggedUser.logIn(email)
        try {
            return block()
        } finally {
            LoggedUser.logOut()
        }
    }

    @Test
    fun `crear apuntes, buscarlos, auditarlos y consultar la cobertura personal y del grupo`() {
        val teacher = user("profe.flow@una.cr", "ROLE_TEACHER")
        val (s1, s2, s3) = listOf("s1", "s2", "s3").map { user("$it.flow@est.una.cr", "ROLE_STUDENT") }

        // El profesor arma curso, grupo y temario, y lo publica.
        val setup = asUser(teacher) {
            val course = courseService.create(CourseCreateDTO(code = "FLOW411", name = "Flujo"))
            val group = groupService.createGroup(StudentGroupCreateDTO(courseId = course.id, number = "01", period = "2026-II"))
            val module = syllabusService.createModule(course.id, ModuleCreateDTO(number = 4, title = "Árboles"))
            val avl = syllabusService.createSubtopic(module.id, SubtopicCreateDTO(code = "4.1", title = "AVL", position = 1))
            val heaps = syllabusService.createSubtopic(module.id, SubtopicCreateDTO(code = "4.2", title = "Heaps", position = 2))
            syllabusService.addObjective(avl.id, ObjectiveCreateDTO("Explicar la rotación simple"))
            syllabusService.publish(course.id)
            Setup(group, avl.id, heaps.id)
        }

        // Los tres se unen con el código y el profesor los aprueba.
        val enrollments = listOf(s1, s2, s3).associateWith { asUser(it) { enrollmentService.joinGroup(JoinGroupDTO(setup.group.inviteCode)) } }
        asUser(teacher) {
            assertEquals(3, enrollmentService.listRequests(setup.group.id, "PENDING").size)
            enrollments.values.forEach { enrollmentService.decide(it.id, EnrollmentDecisionDTO("APPROVE")) }
        }

        // Apuntes: s1 escribe dos de AVL, s2 uno de heaps, s3 ninguno.
        val noteS1 = asUser(s1) {
            val enrollmentId = enrollments.getValue(s1).id
            val first = noteService.createNote(NoteCreateDTO(enrollmentId = enrollmentId, subtopicId = setup.avl, confirmedText = "Rotación simple en un AVL"))
            noteService.createNote(NoteCreateDTO(enrollmentId = enrollmentId, confirmedText = "Repaso general de árboles avl"))
            first
        }
        val noteS2 = asUser(s2) {
            noteService.createNote(NoteCreateDTO(enrollmentId = enrollments.getValue(s2).id, subtopicId = setup.heaps, confirmedText = "Heap binario"))
        }

        // q de extremo a extremo: sin distinguir mayúsculas y nunca con apuntes de otro estudiante.
        asUser(s1) {
            assertEquals(2, noteService.search(null, null, "AVL", 0, 20).totalElements)
            assertEquals(0, noteService.search(null, null, "heap", 0, 20).totalElements)
            assertEquals(1, noteService.search(null, setup.avl, null, 0, 20).totalElements)
        }

        // Auditoría simulada: s1 cubre AVL; s2 deja AVL parcial y cubre heaps.
        asUser(s1) { coverageService.registerAudit(noteS1.id, NoteAuditRequestDTO(listOf(SubtopicVerdictDTO(setup.avl, "COVERED")))) }
        asUser(s2) {
            coverageService.registerAudit(
                noteS2.id,
                NoteAuditRequestDTO(listOf(SubtopicVerdictDTO(setup.avl, "PARTIAL"), SubtopicVerdictDTO(setup.heaps, "COVERED"))),
            )
        }

        // Cobertura personal de s1: AVL cubierto, heaps sin apuntes → 50 %.
        val personal = asUser(s1) { coverageService.personalCoverage(setup.group.id) }
        assertEquals(listOf("COVERED", "NO_NOTES"), personal.subtopics.map { it.status })
        assertEquals(50.0, personal.coveredPercentage)

        // Cobertura del grupo, 3 estudiantes. AVL: 1 cubierto, 1 parcial, 1 sin apuntes. Heaps: 1 cubierto, 2 sin apuntes.
        val groupCoverage = asUser(teacher) { coverageService.groupCoverage(setup.group.id) }
        assertEquals(3, groupCoverage.totalStudents)
        val byCode = groupCoverage.subtopics.associateBy { it.subtopicCode }
        assertEquals(100.0 / 3, byCode.getValue("4.1").coveredPercentage, 1e-9)
        assertEquals(100.0 / 3, byCode.getValue("4.1").partialPercentage, 1e-9)
        assertEquals(100.0 / 3, byCode.getValue("4.1").noNotesPercentage, 1e-9)
        assertEquals(100.0 / 3, byCode.getValue("4.2").coveredPercentage, 1e-9)
        assertEquals(0.0, byCode.getValue("4.2").partialPercentage)
        assertEquals(200.0 / 3, byCode.getValue("4.2").noNotesPercentage, 1e-9)
    }

    @Test
    fun `borrar el apunte que respaldaba la cobertura la baja en la vista del estudiante y en la del profesor`() {
        val teacher = user("profe.borrar@una.cr", "ROLE_TEACHER")
        val student = user("est.borrar@est.una.cr", "ROLE_STUDENT")
        val setup = asUser(teacher) {
            val course = courseService.create(CourseCreateDTO(code = "BORR411", name = "Borrado"))
            val group = groupService.createGroup(StudentGroupCreateDTO(courseId = course.id, number = "01", period = "2026-II"))
            val module = syllabusService.createModule(course.id, ModuleCreateDTO(number = 1, title = "Fundamentos"))
            val avl = syllabusService.createSubtopic(module.id, SubtopicCreateDTO(code = "1.1", title = "AVL", position = 1))
            val heaps = syllabusService.createSubtopic(module.id, SubtopicCreateDTO(code = "1.2", title = "Heaps", position = 2))
            syllabusService.addObjective(avl.id, ObjectiveCreateDTO("Explicar la rotación simple"))
            syllabusService.publish(course.id)
            Setup(group, avl.id, heaps.id)
        }
        val enrollment = asUser(student) { enrollmentService.joinGroup(JoinGroupDTO(setup.group.inviteCode)) }
        asUser(teacher) { enrollmentService.decide(enrollment.id, EnrollmentDecisionDTO("APPROVE")) }

        // Dos apuntes del mismo estudiante: el primero cubre AVL y heaps, el segundo solo AVL.
        val (big, small) = asUser(student) {
            val big = noteService.createNote(NoteCreateDTO(enrollmentId = enrollment.id, confirmedText = "AVL y heaps completos"))
            val small = noteService.createNote(NoteCreateDTO(enrollmentId = enrollment.id, confirmedText = "AVL otra vez"))
            coverageService.registerAudit(big.id, NoteAuditRequestDTO(listOf(SubtopicVerdictDTO(setup.avl, "COVERED"), SubtopicVerdictDTO(setup.heaps, "COVERED"))))
            coverageService.registerAudit(small.id, NoteAuditRequestDTO(listOf(SubtopicVerdictDTO(setup.avl, "COVERED"))))
            big to small
        }
        assertEquals(100.0, asUser(student) { coverageService.personalCoverage(setup.group.id) }.coveredPercentage)

        // Borra el apunte grande: AVL sigue respaldado por el pequeño, heaps ya no tiene apuntes.
        asUser(student) { noteService.delete(big.id) }
        val afterFirst = asUser(student) { coverageService.personalCoverage(setup.group.id) }
        assertEquals(listOf("COVERED", "NO_NOTES"), afterFirst.subtopics.map { it.status })
        assertEquals(50.0, afterFirst.coveredPercentage)

        // Borra también el pequeño: todo vuelve a "sin apuntes" y el profesor lo ve igual.
        asUser(student) { noteService.delete(small.id) }
        val afterSecond = asUser(student) { coverageService.personalCoverage(setup.group.id) }
        assertEquals(listOf("NO_NOTES", "NO_NOTES"), afterSecond.subtopics.map { it.status })
        assertEquals(0.0, afterSecond.coveredPercentage)
        val groupView = asUser(teacher) { coverageService.groupCoverage(setup.group.id) }.subtopics.associateBy { it.subtopicCode }
        assertEquals(0.0, groupView.getValue("1.1").coveredPercentage)
        assertEquals(0.0, groupView.getValue("1.2").coveredPercentage)
    }
}

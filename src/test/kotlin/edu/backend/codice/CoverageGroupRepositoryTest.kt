package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.test.context.ActiveProfiles

// SVC-06: las consultas de cobertura sobre H2. La del grupo agrega en la base y solo cuenta a los
// estudiantes con matrícula ACTIVE en ese grupo.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CoverageGroupRepositoryTest(
    @Autowired val em: TestEntityManager,
    @Autowired val coverageRepository: CoverageRepository,
    @Autowired val subtopicRepository: SubtopicRepository,
    @Autowired val enrollmentRepository: EnrollmentRepository,
) {

    private lateinit var course: Course
    private lateinit var group1: StudentGroup
    private lateinit var avl: Subtopic
    private lateinit var heaps: Subtopic
    private lateinit var studentA: User

    @BeforeEach
    fun setUp() {
        val teacher = em.persist(User(firstName = "Profe", lastName = "T", email = "profe@codice.test", password = "x"))
        course = em.persist(Course(code = "EIF411", name = "Móviles"))
        // Módulos y subtemas creados fuera de orden: la consulta es la que ordena.
        val m5 = em.persist(CourseModule(course = course, number = 5, title = "Grafos"))
        val m4 = em.persist(CourseModule(course = course, number = 4, title = "Árboles"))
        val bfs = em.persist(Subtopic(module = m5, code = "5.1", title = "BFS", position = 1))
        heaps = em.persist(Subtopic(module = m4, code = "4.2", title = "Heaps", position = 2))
        avl = em.persist(Subtopic(module = m4, code = "4.1", title = "AVL", position = 1))

        group1 = em.persist(StudentGroup(course = course, teacher = teacher, number = "01", period = "2026-II", inviteCode = "EIF411-01-AAAA"))
        val group2 = em.persist(StudentGroup(course = course, teacher = teacher, number = "02", period = "2026-II", inviteCode = "EIF411-02-BBBB"))

        studentA = student("a", group1, EnrollmentStatus.ACTIVE)
        val studentB = student("b", group1, EnrollmentStatus.ACTIVE)
        val pending = student("c", group1, EnrollmentStatus.PENDING)
        val otherGroup = student("d", group2, EnrollmentStatus.ACTIVE)

        coverage(studentA, avl, CoverageStatus.COVERED)
        coverage(studentB, avl, CoverageStatus.PARTIAL)
        coverage(studentA, heaps, CoverageStatus.REINFORCED)
        coverage(pending, avl, CoverageStatus.COVERED)    // no aprobado: no cuenta
        coverage(otherGroup, avl, CoverageStatus.COVERED) // otro grupo: no cuenta
        coverage(otherGroup, bfs, CoverageStatus.PARTIAL)
        em.flush()
    }

    private fun student(name: String, group: StudentGroup, status: EnrollmentStatus): User {
        val user = em.persist(User(firstName = name, lastName = "T", email = "$name@codice.test", password = "x"))
        em.persist(Enrollment(group = group, student = user, status = status))
        return user
    }

    private fun coverage(student: User, subtopic: Subtopic, status: CoverageStatus) =
        em.persist(Coverage(student = student, subtopic = subtopic, status = status))

    @Test
    fun `el conteo del grupo solo incluye a sus estudiantes aprobados`() {
        val rows = coverageRepository.countByStatusForGroup(group1.id!!)
            .map { Triple(it.subtopicId, it.status, it.total) }
            .toSet()

        assertEquals(
            setOf(
                Triple(avl.id!!, CoverageStatus.COVERED, 1L),
                Triple(avl.id!!, CoverageStatus.PARTIAL, 1L),
                Triple(heaps.id!!, CoverageStatus.REINFORCED, 1L),
            ),
            rows,
        )
    }

    @Test
    fun `los subtemas del curso salen en el orden del programa`() {
        assertEquals(
            listOf("4.1", "4.2", "5.1"),
            subtopicRepository.findByModuleCourseIdOrderByModuleNumberAscPositionAsc(course.id!!).map { it.code },
        )
    }

    @Test
    fun `cuenta y verifica matriculas aprobadas del grupo`() {
        assertEquals(2L, enrollmentRepository.countByGroupIdAndStatus(group1.id!!, EnrollmentStatus.ACTIVE))
        assertTrue(enrollmentRepository.existsByGroupIdAndStudentIdAndStatus(group1.id!!, studentA.id!!, EnrollmentStatus.ACTIVE))
        assertFalse(enrollmentRepository.existsByGroupIdAndStudentIdAndStatus(group1.id!!, studentA.id!!, EnrollmentStatus.PENDING))
    }

    @Test
    fun `la cobertura personal de un estudiante se limita al curso`() {
        val statuses = coverageRepository.findByStudentIdAndSubtopicModuleCourseId(studentA.id!!, course.id!!)
            .associate { it.subtopic!!.code to it.status }

        assertEquals(mapOf("4.1" to CoverageStatus.COVERED, "4.2" to CoverageStatus.REINFORCED), statuses)
    }
}

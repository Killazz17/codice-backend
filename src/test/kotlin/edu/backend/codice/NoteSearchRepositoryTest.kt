package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

// SVC-05: la búsqueda de apuntes sobre H2. Comprueba la consulta real: filtros, búsqueda sin
// distinguir mayúsculas, orden, paginación y que nunca devuelve apuntes de otro estudiante.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NoteSearchRepositoryTest(
    @Autowired val em: TestEntityManager,
    @Autowired val noteRepository: NoteRepository,
) {

    private lateinit var studentA: User
    private lateinit var studentB: User
    private lateinit var groupA: StudentGroup
    private lateinit var groupB: StudentGroup
    private lateinit var avl: Subtopic
    private lateinit var heaps: Subtopic

    private val newest = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))

    @BeforeEach
    fun setUp() {
        val teacher = em.persist(User(firstName = "Profe", lastName = "Test", email = "profe@codice.test", password = "x"))
        studentA = em.persist(User(firstName = "Ana", lastName = "Mora", email = "a@codice.test", password = "x"))
        studentB = em.persist(User(firstName = "Beto", lastName = "Solís", email = "b@codice.test", password = "x"))

        val course = em.persist(Course(code = "EIF411", name = "Móviles"))
        val module = em.persist(CourseModule(course = course, number = 4, title = "Árboles"))
        avl = em.persist(Subtopic(module = module, code = "4.1", title = "AVL", position = 1))
        heaps = em.persist(Subtopic(module = module, code = "4.2", title = "Heaps", position = 2))

        groupA = em.persist(StudentGroup(course = course, teacher = teacher, number = "01", period = "2026-II", inviteCode = "EIF411-01-AAAA"))
        groupB = em.persist(StudentGroup(course = course, teacher = teacher, number = "02", period = "2026-II", inviteCode = "EIF411-02-BBBB"))
        val enrollmentA = em.persist(Enrollment(group = groupA, student = studentA, status = EnrollmentStatus.ACTIVE))
        val enrollmentB = em.persist(Enrollment(group = groupB, student = studentB, status = EnrollmentStatus.ACTIVE))

        note(enrollmentA, avl, "Rotación simple en un AVL", "2026-10-01T10:00:00Z")
        note(enrollmentA, heaps, "Heap binario y montículos", "2026-10-02T10:00:00Z")
        note(enrollmentA, null, "Repaso general de árboles AVL", "2026-10-03T10:00:00Z")
        note(enrollmentB, avl, "Apunte privado de Beto sobre AVL", "2026-10-04T10:00:00Z")
        em.flush()
    }

    private fun note(enrollment: Enrollment, subtopic: Subtopic?, text: String, at: String) =
        em.persist(Note(enrollment = enrollment, subtopic = subtopic, confirmedText = text, createdAt = Instant.parse(at)))

    private fun texts(groupId: Long? = null, subtopicId: Long? = null, query: String = "", student: User = studentA) =
        noteRepository.searchByStudent(student.id!!, groupId, subtopicId, query, newest).content.map { it.confirmedText }

    @Test
    fun `sin filtros devuelve solo los apuntes del estudiante, del mas reciente al mas antiguo`() {
        assertEquals(
            listOf("Repaso general de árboles AVL", "Heap binario y montículos", "Rotación simple en un AVL"),
            texts(),
        )
    }

    @Test
    fun `filtra por subtema y por grupo`() {
        assertEquals(listOf("Rotación simple en un AVL"), texts(subtopicId = avl.id))
        assertEquals(3, texts(groupId = groupA.id).size)
    }

    @Test
    fun `busca en el texto sin distinguir mayusculas`() {
        assertEquals(listOf("Repaso general de árboles AVL", "Rotación simple en un AVL"), texts(query = "avl"))
        assertEquals(listOf("Heap binario y montículos"), texts(query = "HEAP"))
    }

    @Test
    fun `filtrar por el grupo o el subtema de otro estudiante no devuelve sus apuntes`() {
        assertTrue(texts(groupId = groupB.id).isEmpty())
        assertTrue(texts(query = "Beto").isEmpty())
        assertTrue(texts(subtopicId = avl.id, query = "privado").isEmpty())
    }

    @Test
    fun `pagina los resultados`() {
        val firstPage = noteRepository.searchByStudent(studentA.id!!, null, null, "", PageRequest.of(0, 2, newest.sort))

        assertEquals(2, firstPage.content.size)
        assertEquals(3L, firstPage.totalElements)
        assertEquals(2, firstPage.totalPages)
    }
}

package edu.backend.codice

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles

// Pruebas del mapeo JPA del agregado Cursos y Matrícula (issue #7) sobre H2.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CourseEnrollmentRepositoriesTest(
    @Autowired val em: TestEntityManager,
    @Autowired val courseRepository: CourseRepository,
    @Autowired val studentGroupRepository: StudentGroupRepository,
    @Autowired val enrollmentRepository: EnrollmentRepository,
) {

    private fun user(email: String) = em.persist(
        User(firstName = "Prueba", lastName = "Códice", email = email, password = "hash-de-prueba"),
    )

    private fun course(code: String = "EIF411") = courseRepository.save(Course(code = code, name = "Aplicaciones Móviles"))

    private fun group(course: Course, teacher: User, number: String = "01", inviteCode: String = "EIF411-$number-7K2A") =
        studentGroupRepository.save(
            StudentGroup(course = course, teacher = teacher, number = number, period = "II-2026", inviteCode = inviteCode),
        )

    @Test
    fun `guarda curso, grupo y matrícula y los recorre por sus relaciones`() {
        val teacher = user("profe@codice.test")
        val student = user("estudiante@codice.test")
        val group = group(course(), teacher)
        val enrollment = enrollmentRepository.save(Enrollment(group = group, student = student))
        em.flush()
        em.clear()

        val found = enrollmentRepository.findById(enrollment.id!!).orElseThrow()
        assertThat(found.status).isEqualTo(EnrollmentStatus.PENDING)
        assertThat(found.student!!.email).isEqualTo("estudiante@codice.test")
        assertThat(found.group!!.course!!.code).isEqualTo("EIF411")
        assertThat(found.group!!.teacher!!.email).isEqualTo("profe@codice.test")

        val course = courseRepository.findById(found.group!!.course!!.id!!).orElseThrow()
        assertThat(course.groups).extracting<String> { it.number }.containsExactly("01")
    }

    @Test
    fun `el estado de la matrícula se guarda como texto, no como número`() {
        val group = group(course(), user("profe@codice.test"))
        enrollmentRepository.save(Enrollment(group = group, student = user("e@codice.test"), status = EnrollmentStatus.ACTIVE))
        em.flush()

        val status = em.entityManager.createNativeQuery("select status from enrollment").singleResult
        assertThat(status).isEqualTo("ACTIVE")
    }

    @Test
    fun `el temario extraído por la IA se guarda y se lee como json`() {
        val saved = courseRepository.save(
            Course(
                code = "EIF411",
                name = "Aplicaciones Móviles",
                extractedSyllabus = mapOf("modulos" to listOf(mapOf("numero" to 4, "titulo" to "Programación distribuida"))),
            ),
        )
        em.flush()
        em.clear()

        val found = courseRepository.findById(saved.id!!).orElseThrow()
        assertThat(found.extractedSyllabus!!["modulos"]).isEqualTo(
            listOf(mapOf("numero" to 4, "titulo" to "Programación distribuida")),
        )
    }

    @Test
    fun `no permite dos grupos con el mismo código de invitación`() {
        val course = course()
        val teacher = user("profe@codice.test")
        group(course, teacher, number = "01", inviteCode = "REPETIDO")

        assertThatThrownBy {
            studentGroupRepository.saveAndFlush(
                StudentGroup(course = course, teacher = teacher, number = "02", period = "II-2026", inviteCode = "REPETIDO"),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `no permite que un estudiante pida dos veces el mismo grupo`() {
        val group = group(course(), user("profe@codice.test"))
        val student = user("e@codice.test")
        enrollmentRepository.saveAndFlush(Enrollment(group = group, student = student))

        assertThatThrownBy {
            enrollmentRepository.saveAndFlush(Enrollment(group = group, student = student))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}

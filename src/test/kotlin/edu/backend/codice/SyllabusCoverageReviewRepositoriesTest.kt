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
import java.time.LocalDate

// Pruebas del mapeo JPA del agregado Temario, Cobertura y Repaso (issue #8) sobre H2.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SyllabusCoverageReviewRepositoriesTest(
    @Autowired val em: TestEntityManager,
    @Autowired val courseRepository: CourseRepository,
    @Autowired val courseModuleRepository: CourseModuleRepository,
    @Autowired val subtopicRepository: SubtopicRepository,
    @Autowired val subtopicObjectiveRepository: SubtopicObjectiveRepository,
    @Autowired val coverageRepository: CoverageRepository,
    @Autowired val reviewRepository: ReviewRepository,
) {

    private fun student() = em.persist(
        User(firstName = "Prueba", lastName = "Códice", email = "estudiante@codice.test", password = "hash-de-prueba"),
    )

    // Arma el Módulo 4 del temario de EIF411 con dos subtemas; el primero con dos objetivos.
    private fun syllabus(): Course {
        val module = CourseModule(number = 4, title = "Programación distribuida")
            .addSubtopic(
                Subtopic(code = "4.4", title = "Consumo de API de LLM", position = 2)
                    .addObjective(SubtopicObjective(description = "Autenticar sin exponer la API key"))
                    .addObjective(SubtopicObjective(description = "Manejar tiempos de espera")),
            )
            .addSubtopic(Subtopic(code = "4.1", title = "Cliente HTTP con Retrofit", position = 0))
        return courseRepository.save(Course(code = "EIF411", name = "Aplicaciones Móviles").addModule(module))
    }

    @Test
    fun `guardar el curso guarda en cascada todo el árbol del temario`() {
        val course = syllabus()
        em.flush()
        em.clear()

        val found = courseRepository.findById(course.id!!).orElseThrow()
        val module = found.modules.single()
        assertThat(module.number).isEqualTo(4)
        // @OrderBy("position"): 4.1 va antes que 4.4 aunque se agregó después.
        assertThat(module.subtopics).extracting<String> { it.code }.containsExactly("4.1", "4.4")
        assertThat(module.subtopics.last().objectives).hasSize(2)
    }

    @Test
    fun `quitar un subtema del módulo lo borra junto con sus objetivos`() {
        syllabus()
        em.flush()
        em.clear()

        val module = courseModuleRepository.findAll().single()
        module.subtopics.removeIf { it.code == "4.4" }
        em.flush()

        assertThat(subtopicRepository.findAll()).extracting<String> { it.code }.containsExactly("4.1")
        assertThat(subtopicObjectiveRepository.count()).isZero()
    }

    @Test
    fun `no permite dos módulos con el mismo número en el mismo curso`() {
        val course = syllabus()

        assertThatThrownBy {
            courseModuleRepository.saveAndFlush(CourseModule(course = course, number = 4, title = "Duplicado"))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `la cobertura es única por estudiante y subtema`() {
        syllabus()
        val subtopic = subtopicRepository.findAll().first()
        val student = student()
        coverageRepository.saveAndFlush(Coverage(student = student, subtopic = subtopic, status = CoverageStatus.PARTIAL))

        assertThatThrownBy {
            coverageRepository.saveAndFlush(Coverage(student = student, subtopic = subtopic, status = CoverageStatus.COVERED))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `el repaso guarda su fecha, su intervalo y las preguntas en json`() {
        syllabus()
        val subtopic = subtopicRepository.findAll().first { it.code == "4.4" }
        val questions = mapOf(
            "modelo" to "modelo-de-ejemplo",
            "preguntas" to listOf(mapOf("enunciado" to "¿Dónde vive la API key del LLM?", "correcta" to 0)),
        )
        val saved = reviewRepository.save(
            Review(
                student = student(),
                subtopic = subtopic,
                intervalDay = 3,
                nextDate = LocalDate.of(2026, 10, 2),
                result = ReviewResult.PENDING,
                questions = questions,
            ),
        )
        em.flush()
        em.clear()

        val found = reviewRepository.findById(saved.id!!).orElseThrow()
        assertThat(found.intervalDay).isEqualTo(3)
        assertThat(found.nextDate).isEqualTo(LocalDate.of(2026, 10, 2))
        assertThat(found.result).isEqualTo(ReviewResult.PENDING)
        assertThat(found.questions).isEqualTo(questions)
        assertThat(found.subtopic!!.code).isEqualTo("4.4")
    }
}

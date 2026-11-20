package edu.backend.codice

import edu.backend.codice.exception.ConflictException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

/*
 * Editar, reordenar y eliminar módulos contra H2 con los servicios reales. Los tests con mocks no pueden probar
 * lo importante del reordenamiento: que intercambiar números respete la restricción UNIQUE (course_id, number),
 * que la base revisa al hacer flush.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SyllabusModulesIntegrationTest(
    @Autowired val courseService: CourseService,
    @Autowired val syllabusService: SyllabusService,
    @Autowired val userRepository: UserRepository,
    @Autowired val roleRepository: RoleRepository,
) {

    @AfterEach
    fun logOut() = LoggedUser.logOut()

    private fun loginAsTeacher() {
        val role = roleRepository.findByName("ROLE_TEACHER").orElseGet { roleRepository.save(Role(name = "ROLE_TEACHER")) }
        userRepository.save(User(firstName = "profe", lastName = "Modulos", email = "profe.modulos@una.cr", password = "x", roleList = mutableSetOf(role)))
        LoggedUser.logIn("profe.modulos@una.cr")
    }

    // Tres módulos (1, 2 y 3), cada uno con un subtema; devuelve el curso y los ids de los módulos en ese orden.
    private fun courseWithThreeModules(): Pair<Long, List<Long>> {
        val course = courseService.create(CourseCreateDTO(code = "MOD411", name = "Módulos"))
        val modules = (1..3).map { number ->
            val module = syllabusService.createModule(course.id, ModuleCreateDTO(number = number, title = "Módulo $number"))
            syllabusService.createSubtopic(module.id, SubtopicCreateDTO(code = "$number.1", title = "Tema $number", position = 1))
            module.id
        }
        return course.id to modules
    }

    @Test
    fun `reordenar renumera los modulos 1 a n sin chocar con la unicidad y conserva sus subtemas`() {
        loginAsTeacher()
        val (courseId, ids) = courseWithThreeModules()

        // Un intercambio completo: el 3 pasa al inicio, el 1 al medio y el 2 al final.
        val reordered = syllabusService.reorderModules(courseId, ModuleOrderDTO(listOf(ids[2], ids[0], ids[1])))

        assertEquals(listOf(ids[2], ids[0], ids[1]), reordered.map { it.id })
        val syllabus = syllabusService.getSyllabus(courseId)
        assertEquals(listOf(1, 2, 3), syllabus.modules.map { it.number })
        assertEquals(listOf("Módulo 3", "Módulo 1", "Módulo 2"), syllabus.modules.map { it.title })
        assertEquals(listOf("3.1", "1.1", "2.1"), syllabus.modules.map { it.subtopics.single().code })
    }

    @Test
    fun `cambiar el numero de un modulo a uno ocupado responde conflicto y a uno libre funciona`() {
        loginAsTeacher()
        val (courseId, ids) = courseWithThreeModules()

        assertThrows<ConflictException> { syllabusService.updateModule(ids[0], ModuleUpdateDTO(number = 2)) }

        val updated = syllabusService.updateModule(ids[0], ModuleUpdateDTO(title = "Fundamentos", number = 7))

        assertEquals("Fundamentos", updated.title)
        assertEquals(listOf(2, 3, 7), syllabusService.getSyllabus(courseId).modules.map { it.number })
    }

    @Test
    fun `eliminar un modulo borra sus subtemas y deja los demas intactos`() {
        loginAsTeacher()
        val (courseId, ids) = courseWithThreeModules()

        syllabusService.deleteModule(ids[1])

        val syllabus = syllabusService.getSyllabus(courseId)
        assertEquals(listOf(1, 3), syllabus.modules.map { it.number })
        assertEquals(listOf("1.1", "3.1"), syllabus.modules.map { it.subtopics.single().code })
    }
}

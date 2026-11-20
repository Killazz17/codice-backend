package edu.backend.codice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.persistence.Entity
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mapstruct.factory.Mappers
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/*
 * DTO-03: reglas de validación y serialización de los DTOs de cursos y grupos. Cada regla tiene su caso válido y su
 * caso inválido. Los nombres del enunciado (CursoCreateRequest, GrupoPublicoResponse...) son en este proyecto
 * CourseCreateDTO, StudentGroupPublicDTO, etc.; la equivalencia está documentada en DTOs.kt.
 */
class CourseGroupDtoTest {

    private lateinit var validator: Validator
    private lateinit var mapper: ObjectMapper
    private val courseMapper = Mappers.getMapper(CourseMapper::class.java)
    private val groupMapper = Mappers.getMapper(StudentGroupMapper::class.java)

    @BeforeEach
    fun setUp() {
        validator = Validation.buildDefaultValidatorFactory().validator
        // Igual que el ObjectMapper de Spring Boot: fechas en ISO-8601 y no como números.
        mapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    private fun course(code: String = "EIF411", name: String = "Plataformas Móviles", color: String = "#C8F250") =
        CourseCreateDTO(code = code, name = name, color = color)

    private fun group(number: String = "01", period: String = "2026-II", schedule: String? = "L/J 18:00-19:30", requiresApproval: Boolean = true) =
        GroupCreateDTO(number = number, period = period, schedule = schedule, requiresApproval = requiresApproval)

    /** Mensajes de las violaciones que cayeron sobre [field]; vacío si el campo es válido. */
    private fun errorsOn(input: Any, field: String): List<String> =
        validator.validate(input).filter { it.propertyPath.toString() == field }.map { it.message }

    private fun JsonNode.names(): Set<String> = fieldNames().asSequence().toSet()

    private val teacher = User(id = 9L, firstName = "Carlos", lastName = "Mora", email = "carlos@una.cr", password = "hash")

    private fun entityCourse(groups: MutableList<StudentGroup> = mutableListOf(), teacher: User? = this.teacher) = Course(
        id = 1L, code = "EIF411", name = "Plataformas Móviles", color = "#112233", teacher = teacher, groups = groups,
    )

    private fun entityGroup(course: Course) = StudentGroup(
        id = 5L, course = course, teacher = teacher, number = "01", period = "2026-II", schedule = "L/J 18:00-19:30",
        inviteCode = "EIF411-01-7K2A", requiresApproval = true, inviteExpiresAt = Instant.parse("2026-11-12T10:00:00Z"),
    )

    // ==================== CURSO: ENTRADA ====================

    @Test
    fun `un curso correcto no tiene violaciones`() {
        assertTrue(validator.validate(course()).isEmpty())
        // Sin color se usa el por defecto, que también cumple la regla.
        assertTrue(validator.validate(CourseCreateDTO(code = "EIF411", name = "Móviles")).isEmpty())
    }

    @Test
    fun `el codigo del curso tiene formato tipo EIF411`() {
        for (valid in listOf("EIF411", "MAT101", "ISW2024", "PM41234567", "AB12CD")) {
            assertTrue(errorsOn(course(code = valid), "code").isEmpty(), "'$valid' debió ser válido")
            assertTrue(errorsOn(CourseUpdateDTO(code = valid), "code").isEmpty(), "'$valid' debió ser válido en el PATCH")
        }

        val invalid = listOf(
            "eif411",           // minúsculas
            "EIF-411",          // guion
            "EIF 411",          // espacio
            "ABCDEF",           // sin números
            "123456",           // sin letras
            "EI41",             // muy corto
            "EIF4111111A",      // muy largo
            "",
            "   ",
        )
        for (code in invalid) {
            assertFalse(errorsOn(course(code = code), "code").isEmpty(), "'$code' debió ser inválido")
            if (code.isNotBlank()) {
                assertFalse(errorsOn(CourseUpdateDTO(code = code), "code").isEmpty(), "'$code' debió ser inválido en el PATCH")
            }
        }
        assertTrue(errorsOn(course(code = "eif411"), "code").single().contains("EIF411"))
    }

    @Test
    fun `el nombre del curso es obligatorio`() {
        assertTrue(errorsOn(course(name = "Diseño y Programación de Plataformas Móviles"), "name").isEmpty())
        assertTrue(errorsOn(course(name = "N".repeat(255)), "name").isEmpty())

        assertFalse(errorsOn(course(name = ""), "name").isEmpty())
        assertFalse(errorsOn(course(name = "    "), "name").isEmpty())
        assertFalse(errorsOn(course(name = "N".repeat(256)), "name").isEmpty())
    }

    @Test
    fun `el color tiene formato hexadecimal RRGGBB`() {
        for (valid in listOf("#C8F250", "#000000", "#abcdef", "#AbCdEf")) {
            assertTrue(errorsOn(course(color = valid), "color").isEmpty(), "'$valid' debió ser válido")
        }
        for (invalid in listOf("C8F250", "#C8F25", "#C8F2500", "#GGGGGG", "red", "#C8F25 ", "")) {
            assertFalse(errorsOn(course(color = invalid), "color").isEmpty(), "'$invalid' debió ser inválido")
        }
        assertTrue(errorsOn(course(color = "rojo"), "color").single().contains("#RRGGBB"))
    }

    @Test
    fun `un curso con todo mal reporta cada campo`() {
        val violated = validator.validate(course(code = "x", name = "", color = "azul")).map { it.propertyPath.toString() }.toSet()

        assertEquals(setOf("code", "name", "color"), violated)
    }

    // ==================== CURSO: ACTUALIZACIÓN PARCIAL ====================

    @Test
    fun `CursoUpdateRequest sin campos es valido y cada campo, si viene, se valida`() {
        assertTrue(validator.validate(CourseUpdateDTO()).isEmpty(), "un PATCH vacío no cambia nada y no es un error")

        assertTrue(validator.validate(CourseUpdateDTO(name = "Nuevo nombre")).isEmpty())
        assertTrue(validator.validate(CourseUpdateDTO(color = "#FFFFFF")).isEmpty())

        assertFalse(errorsOn(CourseUpdateDTO(name = ""), "name").isEmpty())
        assertFalse(errorsOn(CourseUpdateDTO(name = "   "), "name").isEmpty())
        assertFalse(errorsOn(CourseUpdateDTO(name = "N".repeat(256)), "name").isEmpty())
        assertFalse(errorsOn(CourseUpdateDTO(color = "verde"), "color").isEmpty())
    }

    @Test
    fun `una actualizacion parcial solo cambia lo que viene`() {
        val entity = entityCourse()

        // Solo el nombre: el código y el color se quedan como estaban.
        courseMapper.updateCourseFromRequest(CourseUpdateDTO(name = "Móviles II"), entity)
        assertEquals("Móviles II", entity.name)
        assertEquals("EIF411", entity.code)
        assertEquals("#112233", entity.color)

        // Solo el color.
        courseMapper.updateCourseFromRequest(CourseUpdateDTO(color = "#FFFFFF"), entity)
        assertEquals("#FFFFFF", entity.color)
        assertEquals("Móviles II", entity.name)
        assertEquals("EIF411", entity.code)

        // Todo a la vez, y vacío no toca nada.
        courseMapper.updateCourseFromRequest(CourseUpdateDTO(code = "EIF500", name = "Otro", color = "#000000"), entity)
        courseMapper.updateCourseFromRequest(CourseUpdateDTO(), entity)
        assertEquals(Triple("EIF500", "Otro", "#000000"), Triple(entity.code, entity.name, entity.color))
    }

    @Test
    fun `el JSON de un PATCH de curso solo trae los campos que cambian`() {
        val onlyName = mapper.readValue("""{"name":"Solo el nombre"}""", CourseUpdateDTO::class.java)

        assertEquals("Solo el nombre", onlyName.name)
        assertNull(onlyName.code)
        assertNull(onlyName.color)
    }

    // ==================== GRUPO: ENTRADA ====================

    @Test
    fun `un grupo correcto no tiene violaciones y pide aprobacion por defecto`() {
        assertTrue(validator.validate(group()).isEmpty())

        val minimal = GroupCreateDTO(number = "02", period = "2026-II")
        assertTrue(validator.validate(minimal).isEmpty(), "el horario es opcional")
        assertTrue(minimal.requiresApproval, "requiereAprobacion es true por defecto")
        assertNull(minimal.schedule)
    }

    @Test
    fun `el numero de grupo es un entero positivo de uno o dos digitos`() {
        for (valid in listOf("1", "01", "09", "10", "12", "99")) {
            assertTrue(errorsOn(group(number = valid), "number").isEmpty(), "'$valid' debió ser válido")
            assertTrue(
                errorsOn(StudentGroupCreateDTO(courseId = 1L, number = valid, period = "2026-II"), "number").isEmpty(),
                "'$valid' debió ser válido en la ruta plana",
            )
        }
        // 0 y 00 no son positivos; 100 y 001 no caben en los dos caracteres del código de invitación.
        for (invalid in listOf("0", "00", "100", "001", "-1", "+1", "1a", "A", "1.5", " 1", "", "  ")) {
            assertFalse(errorsOn(group(number = invalid), "number").isEmpty(), "'$invalid' debió ser inválido")
            assertFalse(
                errorsOn(StudentGroupCreateDTO(courseId = 1L, number = invalid, period = "2026-II"), "number").isEmpty(),
                "'$invalid' debió ser inválido en la ruta plana",
            )
        }
        assertTrue(errorsOn(group(number = "0"), "number").single().contains("entero positivo"))
    }

    @Test
    fun `el periodo es obligatorio y el horario no pasa de 255 caracteres`() {
        assertFalse(errorsOn(group(period = ""), "period").isEmpty())
        assertFalse(errorsOn(group(period = "   "), "period").isEmpty())
        assertFalse(errorsOn(group(period = "P".repeat(51)), "period").isEmpty())
        assertTrue(errorsOn(group(period = "P".repeat(50)), "period").isEmpty())

        assertTrue(errorsOn(group(schedule = "H".repeat(255)), "schedule").isEmpty())
        assertFalse(errorsOn(group(schedule = "H".repeat(256)), "schedule").isEmpty())
    }

    @Test
    fun `GrupoUpdateRequest permite cambiar solo una parte del grupo`() {
        assertTrue(validator.validate(GroupUpdateDTO()).isEmpty(), "un PATCH vacío no cambia nada y no es un error")
        assertTrue(validator.validate(GroupUpdateDTO(schedule = "Martes 10:00")).isEmpty())
        assertTrue(validator.validate(GroupUpdateDTO(requiresApproval = false)).isEmpty())
        assertFalse(errorsOn(GroupUpdateDTO(schedule = "H".repeat(256)), "schedule").isEmpty())

        // Lo que la app manda hoy a PATCH /groups/{id} sigue siendo un cuerpo válido.
        val onlyApproval = mapper.readValue("""{"requiresApproval":false}""", GroupUpdateDTO::class.java)
        assertEquals(false, onlyApproval.requiresApproval)
        assertNull(onlyApproval.schedule)

        val onlySchedule = mapper.readValue("""{"schedule":"Viernes 08:00"}""", GroupUpdateDTO::class.java)
        assertEquals("Viernes 08:00", onlySchedule.schedule)
        assertNull(onlySchedule.requiresApproval)
    }

    // ==================== RESPUESTAS ====================

    @Test
    fun `CursoResponse expone el profesor dueno y la fecha en ISO-8601 UTC`() {
        val dto = courseMapper.toCourseResponse(entityCourse())
        assertEquals(9L, dto.teacherId)

        val tree = mapper.readTree(mapper.writeValueAsString(dto))
        assertEquals(setOf("id", "code", "name", "color", "teacherId", "createdAt", "syllabusPublishedAt"), tree.names())
        assertEquals(9L, tree["teacherId"].asLong())
        assertTrue(tree["createdAt"].asText().endsWith("Z"), "createdAt va en UTC: ${tree["createdAt"]}")

        // Un curso anterior a la migración V3 puede no tener dueño.
        assertNull(courseMapper.toCourseResponse(entityCourse(teacher = null)).teacherId)
    }

    @Test
    fun `CursoResumenResponse lleva lo del listado y cuenta los grupos`() {
        val course = entityCourse()
        course.groups = mutableListOf(entityGroup(course), entityGroup(course).also { it.id = 6L; it.number = "02" })

        val summary = courseMapper.toCourseSummary(course)
        assertEquals(CourseSummaryDTO(id = 1L, code = "EIF411", name = "Plataformas Móviles", color = "#112233", totalGroups = 2), summary)
        assertEquals(listOf(2), courseMapper.toCourseSummaryList(listOf(course)).map { it.totalGroups })
        assertEquals(0, courseMapper.toCourseSummary(entityCourse()).totalGroups)

        val tree = mapper.readTree(mapper.writeValueAsString(summary))
        assertEquals(setOf("id", "code", "name", "color", "totalGroups"), tree.names())
    }

    @Test
    fun `GrupoResponse del profesor lleva el codigo de invitacion y su vencimiento`() {
        val course = entityCourse()
        val dto = groupMapper.toStudentGroupResponse(entityGroup(course))

        val tree = mapper.readTree(mapper.writeValueAsString(dto))
        assertEquals("EIF411-01-7K2A", tree["inviteCode"].asText())
        assertEquals("2026-11-12T10:00:00Z", tree["inviteExpiresAt"].asText())
        assertEquals(true, tree["requiresApproval"].asBoolean())
        assertEquals(1L, tree["courseId"].asLong())
        assertEquals("01", tree["number"].asText())
        assertEquals("L/J 18:00-19:30", tree["schedule"].asText())
    }

    @Test
    fun `GrupoPublicoResponse no tiene campo de codigo de invitacion`() {
        val group = entityGroup(entityCourse())
        val dto = groupMapper.toStudentGroupPublicResponse(group)

        assertEquals(StudentGroupPublicDTO(id = 5L, courseId = 1L, number = "01", schedule = "L/J 18:00-19:30"), dto)

        val json = mapper.writeValueAsString(dto)
        assertEquals(setOf("id", "courseId", "number", "schedule"), mapper.readTree(json).names())
        assertFalse(json.contains("invite", ignoreCase = true), json)
        assertFalse(json.contains(group.inviteCode), "el código del grupo no puede salir en la respuesta del estudiante: $json")

        // Por estructura: ninguna propiedad del DTO se llama como un código.
        val names = StudentGroupPublicDTO::class.memberProperties.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("invite") || it.contains("code") }, "$names")

        // En una lista pasa lo mismo.
        val listJson = mapper.writeValueAsString(groupMapper.toStudentGroupPublicResponseList(listOf(group)))
        assertFalse(listJson.contains(group.inviteCode), listJson)
    }

    // ==================== CONTRATO CON LA APP MÓVIL ====================

    @Test
    fun `los cuerpos que manda la app movil siguen siendo validos`() {
        // TeacherApiService.createCourse: el curso con el código en mayúsculas, sin color (se usa el de la base).
        val courseBody = mapper.readValue("""{"code":"EIF411","name":"Diseño y Programación de Plataformas Móviles"}""", CourseCreateDTO::class.java)
        assertTrue(validator.validate(courseBody).isEmpty())
        assertEquals("#C8F250", courseBody.color)

        // TeacherApiService.createGroup: el número ya viene con dos dígitos.
        val groupBody = mapper.readValue(
            """{"courseId":1,"number":"01","period":"2026-II","schedule":"L/J 08:00-10:00","requiresApproval":true}""",
            StudentGroupCreateDTO::class.java,
        )
        assertTrue(validator.validate(groupBody).isEmpty())
    }

    // ==================== AISLAMIENTO DE LAS ENTIDADES ====================

    @Test
    fun `ningun DTO de curso o grupo expone una entidad JPA`() {
        val dtos = listOf(
            CourseCreateDTO::class, CourseUpdateDTO::class, CourseDTO::class, CourseSummaryDTO::class,
            StudentGroupCreateDTO::class, GroupCreateDTO::class, GroupUpdateDTO::class,
            StudentGroupDTO::class, StudentGroupPublicDTO::class,
        )
        for (dto in dtos) {
            for (property in dto.memberProperties) {
                val type = property.returnType.classifier as? KClass<*>
                assertTrue(type?.findAnnotation<Entity>() == null, "${dto.simpleName}.${property.name} es una entidad")
            }
        }
    }
}

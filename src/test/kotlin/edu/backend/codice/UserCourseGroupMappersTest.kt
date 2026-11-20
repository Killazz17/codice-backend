package edu.backend.codice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mapstruct.factory.Mappers
import java.time.Instant

class UserCourseGroupMappersTest {

    private val userMapper = Mappers.getMapper(UserMapper::class.java)
    private val courseMapper = Mappers.getMapper(CourseMapper::class.java)
    private val studentGroupMapper = Mappers.getMapper(StudentGroupMapper::class.java)

    @Test
    fun `test UserMapper toUser and toUserResponse`() {
        val signUpRequest = UserSignUpRequest(
            firstName = "Carlos",
            lastName = "Mora",
            email = "carlos@example.com",
            password = "rawPassword123",
            roleName = "ROLE_TEACHER"
        )

        val user = userMapper.toUser(signUpRequest)
        assertEquals("Carlos", user.firstName)
        assertEquals("Mora", user.lastName)
        assertEquals("carlos@example.com", user.email)
        // La contraseña no debe mapearse directamente por el mapper ya que el servicio la encripta
        assertTrue(user.password.isEmpty())

        user.id = 100L
        user.enabled = true
        user.roleList = mutableSetOf(Role(id = 1L, name = "ROLE_TEACHER"))

        val response = userMapper.toUserResponse(user)
        assertEquals(100L, response.id)
        assertEquals("Carlos", response.firstName)
        assertEquals("Mora", response.lastName)
        assertEquals("carlos@example.com", response.email)
        assertTrue(response.enabled)
        assertTrue(response.roles.contains("ROLE_TEACHER"))

        // Verificar que la clase UserResponse no expone contraseña
        val fields = UserResponse::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("password"))
        assertFalse(fields.contains("passwordHash"))
    }

    @Test
    fun `test CourseMapper toCourse toCourseResponse and partial update`() {
        val createRequest = CourseCreateRequest(
            code = "EIF411",
            name = "Diseño Móvil",
            color = "#C8F250"
        )

        val course = courseMapper.toCourse(createRequest)
        assertEquals("EIF411", course.code)
        assertEquals("Diseño Móvil", course.name)
        assertEquals("#C8F250", course.color)

        course.id = 50L
        val now = Instant.now()
        course.createdAt = now
        course.syllabusPublishedAt = now

        val response = courseMapper.toCourseResponse(course)
        assertEquals(50L, response.id)
        assertEquals("EIF411", response.code)
        assertEquals("Diseño Móvil", response.name)
        assertEquals("#C8F250", response.color)
        assertEquals(now, response.createdAt)
        assertEquals(now, response.syllabusPublishedAt)

        // Test actualización parcial con @MappingTarget
        val updateRequest = CourseCreateRequest(
            code = "EIF411",
            name = "Diseño Móvil Actualizado",
            color = "#000000"
        )
        courseMapper.updateCourseFromRequest(updateRequest, course)
        assertEquals("Diseño Móvil Actualizado", course.name)
        assertEquals("#000000", course.color)
        assertEquals(50L, course.id) // Conserva su ID original
    }

    @Test
    fun `test StudentGroupMapper toStudentGroup and toStudentGroupResponse`() {
        val createRequest = StudentGroupCreateRequest(
            courseId = 50L,
            number = "01",
            period = "2026-I",
            schedule = "L/J 08:00",
            inviteCode = "INV-12345",
            requiresApproval = true,
            inviteExpiresAt = Instant.parse("2026-12-31T23:59:59Z")
        )

        val group = studentGroupMapper.toStudentGroup(createRequest)
        assertEquals("01", group.number)
        assertEquals("2026-I", group.period)
        assertEquals("L/J 08:00", group.schedule)
        assertEquals("INV-12345", group.inviteCode)
        assertTrue(group.requiresApproval)

        group.id = 200L
        group.course = Course(id = 50L, code = "EIF411", name = "Móviles")
        group.teacher = User(id = 10L, firstName = "Laura", lastName = "Jiménez")

        val response = studentGroupMapper.toStudentGroupResponse(group)
        assertEquals(200L, response.id)
        assertEquals(50L, response.courseId)
        assertEquals("EIF411", response.courseCode)
        assertEquals("Móviles", response.courseName)
        assertEquals(10L, response.teacherId)
        assertEquals("Laura Jiménez", response.teacherName)
        assertEquals("INV-12345", response.inviteCode)
        assertEquals(Instant.parse("2026-12-31T23:59:59Z"), response.inviteExpiresAt)
    }
}

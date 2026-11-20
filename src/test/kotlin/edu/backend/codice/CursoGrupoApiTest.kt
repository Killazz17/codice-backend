package edu.backend.codice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/*
 * API-03: cursos y grupos por REST, con la cadena de seguridad real y los servicios reales sobre H2. Un caso por cada
 * status de la tabla del issue: éxito (con Location en los 201), 400, 401, 403, 404, 409 y 422.
 */
class CursoGrupoApiTest : ApiIntegrationTest() {

    private lateinit var teacher: Actor
    private lateinit var otherTeacher: Actor
    private lateinit var student: Actor

    private val courseBody = """{"code":"EIF411","name":"Diseño y Programación de Plataformas Móviles","color":"#C8F250"}"""
    private val groupBody = """{"number":"01","period":"2026-II","schedule":"Lunes y jueves 18:00-19:30","requiresApproval":true}"""

    @BeforeEach
    fun setUp() {
        teacher = actor("profe@una.ac.cr", "ROLE_TEACHER")
        otherTeacher = actor("otro.profe@una.ac.cr", "ROLE_TEACHER")
        student = actor("ana@est.una.ac.cr", "ROLE_STUDENT")
    }

    // ==================== POST /api/v1/cursos ====================

    @Test
    fun `crear un curso responde 201 con Location y el profesor como dueno`() {
        val created = postAs("/api/v1/cursos", teacher, courseBody).andExpect { status { isCreated() } }
        val body = created.body()

        assertEquals("/api/v1/cursos/${body["id"].asLong()}", created.location())
        assertEquals("EIF411", body["code"].asText())
        assertEquals(teacher.user.id, body["teacherId"].asLong())
        assertNotNull(body["createdAt"])

        // El Location apunta a algo que existe.
        getAs(created.location()!!, teacher).andExpect { status { isOk() } }
    }

    @Test
    fun `crear un curso con datos invalidos responde 400 con un error por campo`() {
        val body = postAs("/api/v1/cursos", teacher, """{"code":"eif-411","name":"","color":"azul"}""").expectError(400, "Bad Request").body()

        assertEquals(setOf("code", "name", "color"), body["errors"].map { it["field"].asText() }.toSet())
    }

    @Test
    fun `crear un curso sin token responde 401 y un estudiante recibe 403`() {
        postAs("/api/v1/cursos", null, courseBody).expectError(401, "Unauthorized")
        postAs("/api/v1/cursos", student, courseBody).expectError(403, "Forbidden")
    }

    @Test
    fun `crear un curso con un codigo repetido responde 409`() {
        course(teacher.user, code = "EIF411")

        postAs("/api/v1/cursos", teacher, courseBody).expectError(409, "Conflict")
    }

    // ==================== GET /api/v1/cursos ====================

    @Test
    fun `el profesor lista solo sus cursos, en forma corta y con el total de grupos`() {
        val mine = course(teacher.user, code = "EIF411")
        group(mine, "01")
        group(mine, "02")
        course(teacher.user, code = "EIF500", name = "Bases de datos")
        course(otherTeacher.user, code = "MAT101", name = "Ajeno")
        reload()

        val list = getAs("/api/v1/cursos", teacher).andExpect { status { isOk() } }.body()

        assertEquals(listOf("EIF411", "EIF500"), list.map { it["code"].asText() })
        assertEquals(listOf(2, 0), list.map { it["totalGroups"].asInt() })
        assertEquals(setOf("id", "code", "name", "color", "totalGroups"), list[0].names())
    }

    @Test
    fun `el estudiante lista solo los cursos donde esta matriculado`() {
        val enrolled = course(teacher.user, code = "EIF411")
        enroll(group(enrolled), student.user, EnrollmentStatus.ACTIVE)
        val pending = course(teacher.user, code = "EIF500")
        enroll(group(pending), student.user, EnrollmentStatus.PENDING)
        course(teacher.user, code = "MAT101")
        reload()

        val list = getAs("/api/v1/cursos", student).andExpect { status { isOk() } }.body()

        assertEquals(listOf("EIF411"), list.map { it["code"].asText() })
    }

    @Test
    fun `listar cursos sin token responde 401`() {
        getAs("/api/v1/cursos", null).expectError(401, "Unauthorized")
    }

    // ==================== GET /api/v1/cursos/{id} ====================

    @Test
    fun `el dueno y el estudiante matriculado ven el curso`() {
        val course = course(teacher.user)
        enroll(group(course), student.user, EnrollmentStatus.ACTIVE)
        reload()

        getAs("/api/v1/cursos/${course.id}", teacher).andExpect {
            status { isOk() }
            jsonPath("$.code") { value("EIF411") }
            jsonPath("$.teacherId") { value(teacher.user.id) }
        }
        getAs("/api/v1/cursos/${course.id}", student).andExpect { status { isOk() } }
    }

    @Test
    fun `ver un curso sin permiso responde 403, uno inexistente 404 y sin token 401`() {
        val course = course(teacher.user)
        val unrelatedStudent = actor("beto@est.una.ac.cr", "ROLE_STUDENT")
        reload()

        getAs("/api/v1/cursos/${course.id}", otherTeacher).expectError(403, "Forbidden")
        getAs("/api/v1/cursos/${course.id}", unrelatedStudent).expectError(403, "Forbidden")
        getAs("/api/v1/cursos/999999", teacher).expectError(404, "Not Found")
        getAs("/api/v1/cursos/${course.id}", null).expectError(401, "Unauthorized")
    }

    // ==================== PATCH /api/v1/cursos/{id} ====================

    @Test
    fun `el dueno actualiza solo lo que envia`() {
        val course = course(teacher.user, code = "EIF411", name = "Nombre viejo")
        reload()

        patchAs("/api/v1/cursos/${course.id}", teacher, """{"name":"Nombre nuevo"}""").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Nombre nuevo") }
            jsonPath("$.code") { value("EIF411") }
            jsonPath("$.color") { value("#C8F250") }
        }
        patchAs("/api/v1/cursos/${course.id}", teacher, """{"color":"#1E90FF"}""").andExpect {
            status { isOk() }
            jsonPath("$.color") { value("#1E90FF") }
            jsonPath("$.name") { value("Nombre nuevo") }
        }
        // Un cuerpo vacío no cambia nada.
        patchAs("/api/v1/cursos/${course.id}", teacher, "{}").andExpect { status { isOk() } }
    }

    @Test
    fun `un profesor ajeno recibe 403 al modificar el curso de otro`() {
        val course = course(teacher.user, name = "Original")
        reload()

        patchAs("/api/v1/cursos/${course.id}", otherTeacher, """{"name":"Hackeado"}""").expectError(403, "Forbidden")
        patchAs("/api/v1/cursos/${course.id}", student, """{"name":"Hackeado"}""").expectError(403, "Forbidden")

        reload()
        assertEquals("Original", courseRepository.findById(course.id!!).get().name)
    }

    @Test
    fun `actualizar un curso con datos invalidos responde 400, uno inexistente 404 y un codigo repetido 409`() {
        val course = course(teacher.user, code = "EIF411")
        course(teacher.user, code = "EIF500", name = "Otro")
        reload()

        patchAs("/api/v1/cursos/${course.id}", teacher, """{"color":"verde","name":"   "}""").expectError(400, "Bad Request")
        patchAs("/api/v1/cursos/999999", teacher, """{"name":"X nuevo"}""").expectError(404, "Not Found")
        patchAs("/api/v1/cursos/${course.id}", teacher, """{"code":"EIF500"}""").expectError(409, "Conflict")
        patchAs("/api/v1/cursos/${course.id}", null, """{"name":"X nuevo"}""").expectError(401, "Unauthorized")
    }

    // ==================== DELETE /api/v1/cursos/{id} ====================

    @Test
    fun `el dueno elimina un curso sin matriculados y responde 204`() {
        val course = course(teacher.user)
        reload()

        deleteAs("/api/v1/cursos/${course.id}", teacher).andExpect { status { isNoContent() } }
        getAs("/api/v1/cursos/${course.id}", teacher).expectError(404, "Not Found")
    }

    @Test
    fun `eliminar el curso de otro profesor responde 403 y uno inexistente 404`() {
        val course = course(teacher.user)
        reload()

        deleteAs("/api/v1/cursos/${course.id}", otherTeacher).expectError(403, "Forbidden")
        deleteAs("/api/v1/cursos/${course.id}", student).expectError(403, "Forbidden")
        deleteAs("/api/v1/cursos/999999", teacher).expectError(404, "Not Found")
        deleteAs("/api/v1/cursos/${course.id}", null).expectError(401, "Unauthorized")
        getAs("/api/v1/cursos/${course.id}", teacher).andExpect { status { isOk() } }
    }

    @Test
    fun `eliminar un curso con estudiantes matriculados responde 422`() {
        val course = course(teacher.user)
        enroll(group(course), student.user, EnrollmentStatus.ACTIVE)
        reload()

        deleteAs("/api/v1/cursos/${course.id}", teacher).expectError(422, "Unprocessable Entity")
        getAs("/api/v1/cursos/${course.id}", teacher).andExpect { status { isOk() } }
    }

    // ==================== POST /api/v1/cursos/{cursoId}/grupos ====================

    @Test
    fun `crear un grupo responde 201 con Location y el codigo de invitacion generado`() {
        val course = course(teacher.user)
        reload()

        val created = postAs("/api/v1/cursos/${course.id}/grupos", teacher, groupBody).andExpect { status { isCreated() } }
        val body = created.body()

        assertEquals("/api/v1/grupos/${body["id"].asLong()}", created.location())
        assertTrue(body["inviteCode"].asText().startsWith("EIF411-01-"), body["inviteCode"].asText())
        assertEquals(course.id, body["courseId"].asLong())
        assertEquals("01", body["number"].asText())
        assertEquals(true, body["requiresApproval"].asBoolean())
        assertNotNull(body["inviteExpiresAt"])
    }

    @Test
    fun `crear un grupo con datos invalidos responde 400`() {
        val course = course(teacher.user)
        reload()

        val body = postAs("/api/v1/cursos/${course.id}/grupos", teacher, """{"number":"0","period":""}""").expectError(400, "Bad Request").body()
        assertEquals(setOf("number", "period"), body["errors"].map { it["field"].asText() }.toSet())

        postAs("/api/v1/cursos/${course.id}/grupos", teacher, """{"number":"100","period":"2026-II"}""").expectError(400, "Bad Request")
    }

    @Test
    fun `crear un grupo en el curso de otro profesor o siendo estudiante responde 403`() {
        val course = course(teacher.user)
        reload()

        postAs("/api/v1/cursos/${course.id}/grupos", otherTeacher, groupBody).expectError(403, "Forbidden")
        postAs("/api/v1/cursos/${course.id}/grupos", student, groupBody).expectError(403, "Forbidden")
        postAs("/api/v1/cursos/${course.id}/grupos", null, groupBody).expectError(401, "Unauthorized")
    }

    @Test
    fun `crear un grupo en un curso inexistente responde 404 y con un numero repetido 409`() {
        val course = course(teacher.user)
        group(course, "01")
        reload()

        postAs("/api/v1/cursos/999999/grupos", teacher, groupBody).expectError(404, "Not Found")
        postAs("/api/v1/cursos/${course.id}/grupos", teacher, groupBody).expectError(409, "Conflict")
    }

    // ==================== GET /api/v1/cursos/{cursoId}/grupos ====================

    @Test
    fun `el profesor dueno recibe los grupos con su codigo de invitacion`() {
        val course = course(teacher.user)
        val first = group(course, "01")
        group(course, "02")
        reload()

        val list = getAs("/api/v1/cursos/${course.id}/grupos", teacher).andExpect { status { isOk() } }.body()

        assertEquals(2, list.size())
        assertEquals(first.inviteCode, list.first { it["number"].asText() == "01" }["inviteCode"].asText())
        assertTrue(list.all { it.has("inviteCode") && it.has("requiresApproval") })
    }

    @Test
    fun `un estudiante que lista los grupos nunca recibe el codigo de invitacion`() {
        val course = course(teacher.user)
        val g1 = enroll(group(course, "01"), student.user, EnrollmentStatus.ACTIVE).group!!
        val g2 = group(course, "02")
        reload()

        val response = getAs("/api/v1/cursos/${course.id}/grupos", student).andExpect { status { isOk() } }
        val raw = response.andReturn().response.contentAsString
        val list = response.body()

        assertEquals(2, list.size())
        assertTrue(list.all { it.names() == setOf("id", "courseId", "number", "schedule") }, raw)
        assertFalse(raw.contains("invite", ignoreCase = true), raw)
        assertFalse(raw.contains(g1.inviteCode) || raw.contains(g2.inviteCode), raw)
    }

    @Test
    fun `la ruta anterior de grupos por curso tampoco le da el codigo al estudiante`() {
        val course = course(teacher.user)
        enroll(group(course, "01"), student.user, EnrollmentStatus.ACTIVE)
        reload()

        val studentView = getAs("/api/v1/groups/course/${course.id}", student).andExpect { status { isOk() } }
        assertFalse(studentView.andReturn().response.contentAsString.contains("invite", ignoreCase = true))

        // El profesor sigue recibiendo el código: la app móvil lo necesita.
        val teacherView = getAs("/api/v1/groups/course/${course.id}", teacher).andExpect { status { isOk() } }.body()
        assertTrue(teacherView[0].has("inviteCode"))
    }

    @Test
    fun `listar los grupos sin acceso responde 403, de un curso inexistente 404 y sin token 401`() {
        val course = course(teacher.user)
        group(course)
        reload()

        getAs("/api/v1/cursos/${course.id}/grupos", otherTeacher).expectError(403, "Forbidden")
        getAs("/api/v1/cursos/${course.id}/grupos", student).expectError(403, "Forbidden")
        getAs("/api/v1/cursos/999999/grupos", teacher).expectError(404, "Not Found")
        getAs("/api/v1/cursos/${course.id}/grupos", null).expectError(401, "Unauthorized")
    }

    // ==================== PATCH /api/v1/grupos/{id} ====================

    @Test
    fun `el profesor dueno actualiza solo el horario o solo la aprobacion`() {
        val group = group(course(teacher.user), requiresApproval = true)
        reload()

        patchAs("/api/v1/grupos/${group.id}", teacher, """{"schedule":"Martes 10:00-12:00"}""").andExpect {
            status { isOk() }
            jsonPath("$.schedule") { value("Martes 10:00-12:00") }
            jsonPath("$.requiresApproval") { value(true) }
        }
        patchAs("/api/v1/grupos/${group.id}", teacher, """{"requiresApproval":false}""").andExpect {
            status { isOk() }
            jsonPath("$.requiresApproval") { value(false) }
            jsonPath("$.schedule") { value("Martes 10:00-12:00") }
        }
    }

    @Test
    fun `actualizar un grupo con datos invalidos responde 400, ajeno 403, inexistente 404 y sin token 401`() {
        val group = group(course(teacher.user))
        reload()

        patchAs("/api/v1/grupos/${group.id}", teacher, """{"schedule":"${"H".repeat(256)}"}""").expectError(400, "Bad Request")
        patchAs("/api/v1/grupos/${group.id}", otherTeacher, """{"requiresApproval":false}""").expectError(403, "Forbidden")
        patchAs("/api/v1/grupos/${group.id}", student, """{"requiresApproval":false}""").expectError(403, "Forbidden")
        patchAs("/api/v1/grupos/999999", teacher, """{"requiresApproval":false}""").expectError(404, "Not Found")
        patchAs("/api/v1/grupos/${group.id}", null, """{"requiresApproval":false}""").expectError(401, "Unauthorized")
    }

    // ==================== POST /api/v1/grupos/{id}/codigo ====================

    @Test
    fun `regenerar el codigo responde 200 con el codigo nuevo y el anterior deja de servir`() {
        val course = course(teacher.user)
        val group = group(course)
        val oldCode = group.inviteCode
        reload()

        val body = postAs("/api/v1/grupos/${group.id}/codigo", teacher).andExpect { status { isOk() } }.body()
        val newCode = body["inviteCode"].asText()

        assertNotEquals(oldCode, newCode)
        assertTrue(newCode.startsWith("EIF411-01-"), newCode)
        getAs("/api/v1/groups/invite/$oldCode", student).expectError(404, "Not Found")
        getAs("/api/v1/groups/invite/$newCode", student).andExpect { status { isOk() } }
    }

    @Test
    fun `regenerar el codigo de un grupo ajeno responde 403, de uno inexistente 404 y sin token 401`() {
        val group = group(course(teacher.user))
        reload()

        postAs("/api/v1/grupos/${group.id}/codigo", otherTeacher).expectError(403, "Forbidden")
        postAs("/api/v1/grupos/${group.id}/codigo", student).expectError(403, "Forbidden")
        postAs("/api/v1/grupos/999999/codigo", teacher).expectError(404, "Not Found")
        postAs("/api/v1/grupos/${group.id}/codigo", null).expectError(401, "Unauthorized")
    }

    // ==================== SWAGGER ====================

    @Test
    fun `los endpoints aparecen en OpenAPI con ejemplos de request y de response`() {
        val doc = getAs("/api-docs", null).andExpect { status { isOk() } }.body()
        val paths = doc["paths"]

        val expected = mapOf(
            "/api/v1/cursos" to listOf("post", "get"),
            "/api/v1/cursos/{id}" to listOf("get", "patch", "delete"),
            "/api/v1/cursos/{cursoId}/grupos" to listOf("post", "get"),
            "/api/v1/grupos/{id}" to listOf("patch"),
            "/api/v1/grupos/{id}/codigo" to listOf("post"),
        )
        for ((path, methods) in expected) {
            for (method in methods) assertTrue(paths.path(path).has(method), "falta $method $path en OpenAPI")
        }

        // Ejemplos: cada DTO de entrada y de salida de estos endpoints trae su "example".
        val schemas = doc["components"]["schemas"]
        for (name in listOf("CourseCreateDTO", "CourseUpdateDTO", "CourseDTO", "CourseSummaryDTO", "GroupCreateDTO", "GroupUpdateDTO", "StudentGroupDTO", "StudentGroupPublicDTO")) {
            assertFalse(schemas.path(name).path("example").isMissingNode, "$name no tiene example")
        }
    }
}

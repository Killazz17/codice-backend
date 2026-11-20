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

// Pruebas del mapeo JPA del agregado Seguridad y Apuntes (issue #6) sobre H2.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SecurityNoteRepositoriesTest(
    @Autowired val em: TestEntityManager,
    @Autowired val userRepository: UserRepository,
    @Autowired val roleRepository: RoleRepository,
    @Autowired val privilegeRepository: PrivilegeRepository,
    @Autowired val noteRepository: NoteRepository,
    @Autowired val notePageRepository: NotePageRepository,
) {

    private fun studentRole(): Role {
        val read = privilegeRepository.save(Privilege(name = "READ_PRIVILEGE"))
        return roleRepository.save(Role(name = "ROLE_STUDENT", privilegeList = mutableSetOf(read)))
    }

    private fun student(email: String = "estudiante@codice.test", role: Role? = null) = userRepository.save(
        User(
            firstName = "Prueba",
            lastName = "Códice",
            email = email,
            password = "\$2a\$10\$hashDePruebaNoEsUnaContrasenaReal",
            roleList = listOfNotNull(role).toMutableSet(),
        ),
    )

    // Un apunte necesita una matrícula, y la matrícula un grupo con curso y profesor.
    private fun enrollment(): Enrollment {
        val teacher = student("profe@codice.test")
        val course = em.persist(Course(code = "EIF411", name = "Aplicaciones Móviles"))
        val group = em.persist(
            StudentGroup(course = course, teacher = teacher, number = "01", period = "II-2026", inviteCode = "EIF411-01-7K2A"),
        )
        return em.persist(Enrollment(group = group, student = student(), status = EnrollmentStatus.ACTIVE))
    }

    @Test
    fun `encuentra al usuario por correo con sus roles y privilegios`() {
        student(role = studentRole())
        em.flush()
        em.clear()

        val found = userRepository.findByEmail("estudiante@codice.test").orElseThrow()
        assertThat(found.roleList).extracting<String> { it.name }.containsExactly("ROLE_STUDENT")
        assertThat(found.roleList.single().privilegeList).extracting<String> { it.name }.containsExactly("READ_PRIVILEGE")
    }

    @Test
    fun `toString del usuario no expone la contraseña`() {
        val user = student()

        assertThat(user.toString()).contains("estudiante@codice.test").doesNotContain(user.password)
    }

    @Test
    fun `no permite dos usuarios con el mismo correo`() {
        student()

        assertThatThrownBy { userRepository.saveAndFlush(User(firstName = "Otro", lastName = "Más", email = "estudiante@codice.test", password = "x")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `guarda un apunte con sus páginas, estado y transcripción en json`() {
        val note = Note(
            enrollment = enrollment(),
            clientRequestId = "uuid-del-telefono-1",
            transcription = mapOf("bloques" to listOf(mapOf("texto" to "Retrofit + OkHttp", "confianza" to 0.91))),
        )
            .addPage(NotePage(pageIndex = 1, filePath = "notes/1/p1.jpg"))
            .addPage(NotePage(pageIndex = 0, filePath = "notes/1/p0.jpg"))
        val saved = noteRepository.save(note)
        em.flush()
        em.clear()

        val found = noteRepository.findById(saved.id!!).orElseThrow()
        assertThat(found.syncStatus).isEqualTo(SyncStatus.QUEUED)
        assertThat(found.enrollment!!.student!!.email).isEqualTo("estudiante@codice.test")
        assertThat(found.subtopic).isNull()
        assertThat(found.pages).extracting<Int> { it.pageIndex }.containsExactly(0, 1)
        assertThat(found.transcription!!["bloques"]).isEqualTo(
            listOf(mapOf("texto" to "Retrofit + OkHttp", "confianza" to 0.91)),
        )
    }

    @Test
    fun `borrar un apunte borra sus páginas`() {
        val saved = noteRepository.save(
            Note(enrollment = enrollment()).addPage(NotePage(pageIndex = 0, filePath = "notes/1/p0.jpg")),
        )
        em.flush()

        noteRepository.delete(saved)
        em.flush()

        assertThat(notePageRepository.count()).isZero()
    }

    @Test
    fun `no permite dos páginas con el mismo índice en el mismo apunte`() {
        val note = noteRepository.saveAndFlush(Note(enrollment = enrollment()))

        notePageRepository.saveAndFlush(NotePage(note = note, pageIndex = 0, filePath = "a.jpg"))
        assertThatThrownBy { notePageRepository.saveAndFlush(NotePage(note = note, pageIndex = 0, filePath = "b.jpg")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `el client_request_id hace idempotente la subida del apunte`() {
        val enrollment = enrollment()
        noteRepository.saveAndFlush(Note(enrollment = enrollment, clientRequestId = "reintento-1"))

        assertThatThrownBy { noteRepository.saveAndFlush(Note(enrollment = enrollment, clientRequestId = "reintento-1")) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }
}

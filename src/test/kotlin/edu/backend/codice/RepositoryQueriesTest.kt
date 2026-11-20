package edu.backend.codice

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.LocalDate

// Pruebas de las consultas de los repositorios (issues #9, #10 y #11) sobre H2.
// Cada prueba comprueba que el método devuelve lo que su nombre promete: el filtro, el orden y los
// conteos agregados. Las reglas que solo existen en PostgreSQL (trigger, índice parcial, CHECK) se
// prueban con Postman contra la API desplegada.
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryQueriesTest(
    @Autowired val em: TestEntityManager,
    @Autowired val userRepository: UserRepository,
    @Autowired val roleRepository: RoleRepository,
    @Autowired val courseRepository: CourseRepository,
    @Autowired val studentGroupRepository: StudentGroupRepository,
    @Autowired val enrollmentRepository: EnrollmentRepository,
    @Autowired val courseModuleRepository: CourseModuleRepository,
    @Autowired val subtopicRepository: SubtopicRepository,
    @Autowired val subtopicObjectiveRepository: SubtopicObjectiveRepository,
    @Autowired val noteRepository: NoteRepository,
    @Autowired val notePageRepository: NotePageRepository,
    @Autowired val coverageRepository: CoverageRepository,
    @Autowired val reviewRepository: ReviewRepository,
) {

    private fun user(email: String) = em.persist(
        User(firstName = "Prueba", lastName = "Códice", email = email, password = "hash-de-prueba"),
    )

    private fun group(course: Course, teacher: User, number: String, inviteCode: String) = em.persist(
        StudentGroup(course = course, teacher = teacher, number = number, period = "2026-II", inviteCode = inviteCode),
    )

    // En PostgreSQL el trigger trg_enrollment_course_id llena enrollment.course_id; H2 no lo tiene,
    // así que se copia a mano para poder probar las consultas que filtran por curso.
    private fun enroll(group: StudentGroup, student: User, status: EnrollmentStatus): Enrollment {
        val enrollment = em.persist(Enrollment(group = group, student = student, status = status))
        em.flush()
        em.entityManager.createNativeQuery("update enrollment set course_id = :curso where id = :id")
            .setParameter("curso", group.course!!.id)
            .setParameter("id", enrollment.id)
            .executeUpdate()
        return enrollment
    }

    // Un curso con un módulo 1 que tiene los subtemas 1.1 y 1.2. Se agregan al revés a propósito
    // (2 antes que 1, 1.2 antes que 1.1) para comprobar que las consultas ordenan de verdad.
    private fun courseWithSyllabus(code: String): Triple<Course, Subtopic, Subtopic> {
        val s11 = Subtopic(code = "1.1", title = "Primero", position = 0)
        val s12 = Subtopic(code = "1.2", title = "Segundo", position = 1)
        val module1 = CourseModule(number = 1, title = "Uno").addSubtopic(s12).addSubtopic(s11)
        val module2 = CourseModule(number = 2, title = "Dos")
        val course = courseRepository.saveAndFlush(Course(code = code, name = "Curso $code").addModule(module2).addModule(module1))
        return Triple(course, s11, s12)
    }

    @Test
    fun `usuarios y roles se buscan por correo y por nombre`() {
        val role = roleRepository.save(Role(name = "ROLE_STUDENT"))
        user("ana@codice.test")
        em.flush()

        assertThat(userRepository.findByEmail("ana@codice.test")).isPresent
        assertThat(userRepository.findByEmail("nadie@codice.test")).isEmpty
        assertThat(userRepository.existsByEmail("ana@codice.test")).isTrue()
        assertThat(userRepository.existsByEmail("nadie@codice.test")).isFalse()
        assertThat(roleRepository.findByName("ROLE_STUDENT").get().id).isEqualTo(role.id)
        assertThat(roleRepository.findByName("ROLE_TEACHER")).isEmpty
    }

    @Test
    fun `cursos, grupos y matrículas se filtran por código, curso, profesor, invitación y estado`() {
        val eif411 = em.persist(Course(code = "EIF411", name = "Móviles"))
        val eif311 = em.persist(Course(code = "EIF311", name = "Otro curso"))
        val carlos = user("carlos@codice.test")
        val laura = user("laura@codice.test")
        val g1 = group(eif411, carlos, "01", "EIF411-01-AAAA")
        val g2 = group(eif411, laura, "02", "EIF411-02-BBBB")
        group(eif311, carlos, "01", "EIF311-01-CCCC")
        val ana = user("ana@codice.test")
        val luis = user("luis@codice.test")
        val maria = user("maria@codice.test")
        enroll(g1, ana, EnrollmentStatus.ACTIVE)
        enroll(g1, maria, EnrollmentStatus.PENDING)
        enroll(g2, luis, EnrollmentStatus.ACTIVE)
        enroll(g2, ana, EnrollmentStatus.REJECTED)
        em.clear()

        assertThat(courseRepository.findByCode("EIF411").get().id).isEqualTo(eif411.id)
        assertThat(courseRepository.findByCode("NO-EXISTE")).isEmpty

        assertThat(studentGroupRepository.findByCourseId(eif411.id!!)).extracting<String> { it.number }
            .containsExactlyInAnyOrder("01", "02")
        assertThat(studentGroupRepository.findByTeacherId(carlos.id!!)).extracting<String> { it.inviteCode }
            .containsExactlyInAnyOrder("EIF411-01-AAAA", "EIF311-01-CCCC")
        assertThat(studentGroupRepository.findByInviteCode("EIF411-02-BBBB").get().teacher!!.id).isEqualTo(laura.id)
        assertThat(studentGroupRepository.findByInviteCode("NO-EXISTE")).isEmpty

        assertThat(enrollmentRepository.findByGroupIdAndStatus(g1.id!!, EnrollmentStatus.PENDING))
            .extracting<Long> { it.student!!.id }.containsExactly(maria.id)
        assertThat(enrollmentRepository.findByStudentIdAndStatus(ana.id!!, EnrollmentStatus.ACTIVE)).hasSize(1)
        assertThat(enrollmentRepository.findByStudentIdAndStatus(ana.id!!, EnrollmentStatus.REJECTED)).hasSize(1)
        assertThat(enrollmentRepository.existsByCourseIdAndStudentIdAndStatus(eif411.id!!, ana.id!!, EnrollmentStatus.ACTIVE)).isTrue()
        assertThat(enrollmentRepository.existsByCourseIdAndStudentIdAndStatus(eif411.id!!, maria.id!!, EnrollmentStatus.ACTIVE)).isFalse()
        assertThat(enrollmentRepository.existsByCourseIdAndStudentIdAndStatus(eif311.id!!, ana.id!!, EnrollmentStatus.ACTIVE)).isFalse()
    }

    @Test
    fun `el conteo del profesor agrupa matrículas por grupo y estado sin datos personales`() {
        val eif411 = em.persist(Course(code = "EIF411", name = "Móviles"))
        val carlos = user("carlos@codice.test")
        val laura = user("laura@codice.test")
        val g1 = group(eif411, carlos, "01", "EIF411-01-AAAA")
        val g2 = group(eif411, laura, "02", "EIF411-02-BBBB")
        enroll(g1, user("a@codice.test"), EnrollmentStatus.ACTIVE)
        enroll(g1, user("b@codice.test"), EnrollmentStatus.ACTIVE)
        enroll(g1, user("c@codice.test"), EnrollmentStatus.PENDING)
        enroll(g2, user("d@codice.test"), EnrollmentStatus.ACTIVE)
        em.clear()

        val conteo = enrollmentRepository.countByStatusForTeacher(carlos.id!!)

        // Solo el grupo de Carlos, con un total por estado; el grupo de Laura no aparece.
        assertThat(conteo).extracting<String> { "${it.groupId}:${it.status}:${it.total}" }
            .containsExactlyInAnyOrder("${g1.id}:ACTIVE:2", "${g1.id}:PENDING:1")
    }

    @Test
    fun `temario, apuntes y repasos se devuelven en orden y con sus filtros`() {
        val (eif411, s11, s12) = courseWithSyllabus("EIF411")
        s11.addObjective(SubtopicObjective(description = "Primer objetivo")).addObjective(SubtopicObjective(description = "Segundo objetivo"))
        em.flush()
        val carlos = user("carlos@codice.test")
        val ana = user("ana@codice.test")
        val luis = user("luis@codice.test")
        val enrollment = enroll(group(eif411, carlos, "01", "EIF411-01-AAAA"), ana, EnrollmentStatus.ACTIVE)

        val viejo = noteRepository.save(
            Note(enrollment = enrollment, subtopic = s11, syncStatus = SyncStatus.SYNCED, createdAt = Instant.parse("2026-09-01T10:00:00Z")),
        )
        val nuevo = noteRepository.save(
            Note(enrollment = enrollment, subtopic = s11, syncStatus = SyncStatus.QUEUED, createdAt = Instant.parse("2026-09-02T10:00:00Z")),
        )
        notePageRepository.save(NotePage(note = nuevo, pageIndex = 1, filePath = "p1.jpg"))
        notePageRepository.save(NotePage(note = nuevo, pageIndex = 0, filePath = "p0.jpg"))

        reviewRepository.save(Review(student = ana, subtopic = s11, intervalDay = 1, nextDate = LocalDate.of(2026, 9, 30)))
        reviewRepository.save(Review(student = ana, subtopic = s12, intervalDay = 7, nextDate = LocalDate.of(2026, 10, 10)))
        reviewRepository.save(Review(student = luis, subtopic = s11, intervalDay = 1, nextDate = LocalDate.of(2026, 9, 1)))
        em.flush()
        em.clear()

        assertThat(courseModuleRepository.findByCourseIdOrderByNumber(eif411.id!!)).extracting<Int> { it.number }
            .containsExactly(1, 2)
        assertThat(subtopicRepository.findByModuleIdOrderByPosition(s11.module!!.id!!)).extracting<String> { it.code }
            .containsExactly("1.1", "1.2")
        assertThat(subtopicObjectiveRepository.findBySubtopicId(s11.id!!)).hasSize(2)
        assertThat(subtopicObjectiveRepository.findBySubtopicId(s12.id!!)).isEmpty()

        assertThat(noteRepository.findByEnrollmentIdOrderByCreatedAtDesc(enrollment.id!!)).extracting<Long> { it.id }
            .containsExactly(nuevo.id, viejo.id)
        assertThat(noteRepository.findBySyncStatus(SyncStatus.QUEUED)).extracting<Long> { it.id }.containsExactly(nuevo.id)
        assertThat(noteRepository.findBySyncStatus(SyncStatus.FAILED)).isEmpty()
        assertThat(notePageRepository.findByNoteIdOrderByPageIndex(nuevo.id!!)).extracting<Int> { it.pageIndex }
            .containsExactly(0, 1)

        // El 30/09 entra (menor o igual); el 10/10 y el repaso de otro estudiante no.
        assertThat(reviewRepository.findByStudentIdAndNextDateLessThanEqual(ana.id!!, LocalDate.of(2026, 9, 30)))
            .extracting<Int> { it.intervalDay }.containsExactly(1)
    }

    @Test
    fun `la cobertura se consulta por estudiante y el conteo del curso no mezcla otros cursos`() {
        val (eif411, s11, s12) = courseWithSyllabus("EIF411")
        val (_, otroSubtema, _) = courseWithSyllabus("EIF311")
        val ana = user("ana@codice.test")
        val luis = user("luis@codice.test")
        val maria = user("maria@codice.test")
        coverageRepository.save(Coverage(student = ana, subtopic = s11, status = CoverageStatus.PARTIAL))
        coverageRepository.save(Coverage(student = luis, subtopic = s11, status = CoverageStatus.PARTIAL))
        coverageRepository.save(Coverage(student = maria, subtopic = s11, status = CoverageStatus.COVERED))
        coverageRepository.save(Coverage(student = ana, subtopic = s12, status = CoverageStatus.NO_NOTES))
        coverageRepository.save(Coverage(student = ana, subtopic = otroSubtema, status = CoverageStatus.REINFORCED))
        em.flush()
        em.clear()

        assertThat(coverageRepository.findByStudentId(ana.id!!)).hasSize(3)
        assertThat(coverageRepository.findByStudentIdAndSubtopicId(ana.id!!, s11.id!!).get().status)
            .isEqualTo(CoverageStatus.PARTIAL)
        assertThat(coverageRepository.findByStudentIdAndSubtopicId(maria.id!!, s12.id!!)).isEmpty

        // Solo subtemas de EIF411: el REINFORCED de Ana en el otro curso no se cuenta.
        assertThat(coverageRepository.countByStatusForCourse(eif411.id!!))
            .extracting<String> { "${it.subtopicId}:${it.status}:${it.total}" }
            .containsExactlyInAnyOrder("${s11.id}:PARTIAL:2", "${s11.id}:COVERED:1", "${s12.id}:NO_NOTES:1")
    }
}

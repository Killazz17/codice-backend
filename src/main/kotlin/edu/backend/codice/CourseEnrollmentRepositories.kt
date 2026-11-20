package edu.backend.codice

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

/*
 * Repositorios de cursos, grupos y matrículas (issue #10 del Lab 4).
 *
 * Solo consultas: qué curso, qué grupos, qué matrículas. Las reglas ("un estudiante solo puede
 * tener una matrícula ACTIVE por curso") las garantiza la base con un índice único parcial; el
 * repositorio no las duplica, solo ofrece `existsByCourseIdAndStudentIdAndStatus` para que un
 * servicio pueda avisar con un mensaje claro antes de que la base rechace el insert.
 */

@Repository
interface CourseRepository : JpaRepository<Course, Long> {

    /**
     * Busca un curso del catálogo por su código institucional (`EIF411`), que es único. Lo usa el
     * profesor al crear un grupo y la app al resolver un código de curso.
     */
    fun findByCode(code: String): Optional<Course>

    /** Cursos de los que un profesor es dueño ("Mis cursos" del profesor). */
    fun findByTeacherIdOrderByCode(teacherId: Long): List<Course>

    /** Cursos donde un estudiante tiene una matrícula en un estado (ACTIVE = "Mis cursos" del estudiante). */
    @Query("select distinct e.group.course from Enrollment e where e.student.id = :studentId and e.status = :status")
    fun findByStudentEnrollment(@Param("studentId") studentId: Long, @Param("status") status: EnrollmentStatus): List<Course>
}

@Repository
interface StudentGroupRepository : JpaRepository<StudentGroup, Long> {

    /**
     * Grupos que se ofrecen de un curso, de cualquier profesor. Lo usa el estudiante que busca el
     * curso para elegir con quién matricularse.
     */
    fun findByCourseId(courseId: Long): List<StudentGroup>

    /**
     * Grupos que imparte un profesor: es la pantalla "Mis cursos" del profesor.
     */
    fun findByTeacherId(teacherId: Long): List<StudentGroup>

    /**
     * Resuelve un código de invitación (el que se escribe o se escanea en el QR) al grupo al que
     * pertenece. El código es único en todo el sistema, por eso devuelve a lo sumo un grupo.
     */
    fun findByInviteCode(inviteCode: String): Optional<StudentGroup>

    /** El número de grupo es único dentro del curso (01, 02…). */
    fun existsByCourseIdAndNumber(courseId: Long, number: String): Boolean

    fun existsByInviteCode(inviteCode: String): Boolean
}

/**
 * Fila del conteo agregado de matrículas: cuántas hay en cada estado dentro de un grupo.
 * Es una proyección a propósito: solo lleva números, así que el profesor ve totales y nunca
 * datos personales de los estudiantes.
 */
interface GroupEnrollmentCount {
    val groupId: Long
    val status: EnrollmentStatus
    val total: Long
}

@Repository
interface EnrollmentRepository : JpaRepository<Enrollment, Long> {

    /**
     * Matrículas de un grupo en un estado. El profesor la usa para ver las solicitudes PENDING de
     * su grupo y los inscritos ACTIVE.
     */
    fun findByGroupIdAndStatus(groupId: Long, status: EnrollmentStatus): List<Enrollment>

    /**
     * Matrículas de un estudiante en un estado. La app del estudiante la usa para listar "Mis
     * cursos" (ACTIVE) y sus solicitudes en espera (PENDING).
     */
    fun findByStudentIdAndStatus(studentId: Long, status: EnrollmentStatus): List<Enrollment>

    /**
     * Cuántos estudiantes tiene un grupo en un estado. La cobertura del grupo la usa con ACTIVE como
     * denominador de los porcentajes.
     */
    fun countByGroupIdAndStatus(groupId: Long, status: EnrollmentStatus): Long

    /** Todas las matrículas de un grupo (la lista de solicitudes sin filtro de estado). */
    fun findByGroupIdOrderByRequestedAtDesc(groupId: Long): List<Enrollment>

    /** Todas las matrículas de un estudiante, de la más reciente a la más antigua. */
    fun findByStudentIdOrderByRequestedAtDesc(studentId: Long): List<Enrollment>

    /** La matrícula de un estudiante en un grupo (hay a lo sumo una por la restricción UNIQUE). */
    fun findByGroupIdAndStudentId(groupId: Long, studentId: Long): Optional<Enrollment>

    /** Si hay matrículas en un estado en cualquier grupo del curso (p. ej. ACTIVE bloquea borrar el curso). */
    fun existsByGroupCourseIdAndStatus(courseId: Long, status: EnrollmentStatus): Boolean

    /** Si un estudiante tiene una matrícula en un estado en algún grupo del curso. */
    fun existsByGroupCourseIdAndStudentIdAndStatus(courseId: Long, studentId: Long, status: EnrollmentStatus): Boolean

    /**
     * Si un estudiante tiene una matrícula en un estado dentro de un grupo. La cobertura personal
     * exige ACTIVE.
     */
    fun existsByGroupIdAndStudentIdAndStatus(groupId: Long, studentId: Long, status: EnrollmentStatus): Boolean

    /**
     * Dice si el estudiante ya tiene una matrícula en ese estado en el curso, en cualquier grupo.
     * Con ACTIVE sirve para avisar antes de que el índice único de la base rechace el insert.
     */
    fun existsByCourseIdAndStudentIdAndStatus(courseId: Long, studentId: Long, status: EnrollmentStatus): Boolean

    /**
     * Vista agregada del profesor: cuántas matrículas hay por estado en cada uno de sus grupos.
     * Devuelve solo grupo, estado y total (sin nombres ni correos): la privacidad se impone aquí,
     * en la consulta, y no solo en la pantalla.
     */
    @Query(
        """
        select e.group.id as groupId, e.status as status, count(e) as total
        from Enrollment e
        where e.group.teacher.id = :teacherId
        group by e.group.id, e.status
        order by e.group.id, e.status
        """,
    )
    fun countByStatusForTeacher(@Param("teacherId") teacherId: Long): List<GroupEnrollmentCount>
}

package edu.backend.codice

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.Optional

/*
 * Repositorios del temario, los apuntes, la cobertura y los repasos (issues #9 y #11 del Lab 4).
 *
 * Solo consultas, sin lógica de negocio: decidir qué estado de cobertura le toca a un subtema
 * (parcial, cubierto, reforzado) es trabajo de un servicio. Aquí solo se leen y se guardan filas.
 */

@Repository
interface CourseModuleRepository : JpaRepository<CourseModule, Long> {

    /**
     * Módulos de un curso en el orden del programa. Lo usa la pantalla del temario (profesor y
     * estudiante) y el mapa de cobertura.
     */
    fun findByCourseIdOrderByNumber(courseId: Long): List<CourseModule>

    /** El número de módulo es único dentro del curso. */
    fun existsByCourseIdAndNumber(courseId: Long, number: Int): Boolean
}

@Repository
interface SubtopicRepository : JpaRepository<Subtopic, Long> {

    /**
     * Subtemas de un módulo en el orden en que el profesor los acomodó (`position`).
     */
    fun findByModuleIdOrderByPosition(moduleId: Long): List<Subtopic>

    /** El código de subtema ('4.4') es único dentro de su módulo. */
    fun existsByModuleIdAndCode(moduleId: Long, code: String): Boolean

    /**
     * Todos los subtemas del temario de un curso, en el orden del programa (módulo y posición).
     * Es la lista de filas del mapa de cobertura.
     */
    fun findByModuleCourseIdOrderByModuleNumberAscPositionAsc(courseId: Long): List<Subtopic>
}

@Repository
interface SubtopicObjectiveRepository : JpaRepository<SubtopicObjective, Long> {

    /**
     * Objetivos de aprendizaje de un subtema. La auditoría de un apunte compara su texto contra
     * estos objetivos; la pantalla de revisión marca en ámbar los subtemas que no tienen ninguno.
     */
    fun findBySubtopicId(subtopicId: Long): List<SubtopicObjective>
}

@Repository
interface NoteRepository : JpaRepository<Note, Long> {

    /**
     * Busca un apunte por el ID de solicitud de cliente (idempotencia de subida).
     */
    fun findByClientRequestId(clientRequestId: String): Optional<Note>

    /**
     * Apuntes de una matrícula, del más reciente al más antiguo. Es la biblioteca de apuntes del
     * estudiante; el apunte cuelga de la matrícula, que ya determina estudiante y grupo.
     */
    fun findByEnrollmentIdOrderByCreatedAtDesc(enrollmentId: Long): List<Note>

    /**
     * Apuntes en un estado de sincronización. El trabajo en segundo plano de la app la usa para
     * reintentar los `QUEUED` y `FAILED` sin recorrer toda la tabla.
     */
    fun findBySyncStatus(syncStatus: SyncStatus): List<Note>

    /**
     * Apuntes de un estudiante, del más reciente al más antiguo, en todos sus grupos.
     */
    fun findByEnrollmentStudentIdOrderByCreatedAtDesc(studentId: Long): List<Note>

    /** Si algún apunte está asociado a un subtema (bloquea borrarlo del temario). */
    fun existsBySubtopicId(subtopicId: Long): Boolean

    /** Si algún apunte está asociado a un subtema de este módulo (bloquea borrar el módulo). */
    fun existsBySubtopicModuleId(moduleId: Long): Boolean

    /**
     * Biblioteca de apuntes con filtros, siempre limitada al estudiante: aunque el cliente mande el
     * grupo o el subtema de otra persona, el `studentId` lo pone el servicio con el usuario autenticado.
     * Filtros opcionales (null = sin filtro) y búsqueda sin distinguir mayúsculas en el texto confirmado;
     * `query` vacío equivale a no buscar. Se pasa "" en vez de null porque PostgreSQL no puede inferir
     * el tipo de un parámetro de texto nulo dentro de `like`.
     */
    @Query(
        """
        select n from Note n
        where n.enrollment.student.id = :studentId
          and (:groupId is null or n.enrollment.group.id = :groupId)
          and (:subtopicId is null or n.subtopic.id = :subtopicId)
          and (:query = '' or lower(n.confirmedText) like lower(concat('%', :query, '%')))
        """
    )
    fun searchByStudent(
        @Param("studentId") studentId: Long,
        @Param("groupId") groupId: Long?,
        @Param("subtopicId") subtopicId: Long?,
        @Param("query") query: String,
        pageable: Pageable,
    ): Page<Note>
}

@Repository
interface NotePageRepository : JpaRepository<NotePage, Long> {

    /**
     * Páginas de un apunte en orden de captura. Cada página es una fila (1FN) en vez de un arreglo
     * dentro del apunte, y este orden es el que ve el estudiante al abrirlo.
     */
    fun findByNoteIdOrderByPageIndex(noteId: Long): List<NotePage>
}

/**
 * Fila del conteo agregado de cobertura: cuántos estudiantes están en cada estado dentro de un
 * subtema. Solo lleva números; el profesor ve cuántos estudiantes no tienen apuntes de un tema,
 * nunca cuáles son.
 */
interface SubtopicCoverageCount {
    val subtopicId: Long
    val status: CoverageStatus
    val total: Long
}

@Repository
interface CoverageRepository : JpaRepository<Coverage, Long> {

    /**
     * Todo el mapa de cobertura de un estudiante. Lo pinta la pantalla "Mi cobertura".
     */
    fun findByStudentId(studentId: Long): List<Coverage>

    /**
     * La cobertura de un estudiante en un subtema concreto (hay a lo sumo una por la restricción
     * UNIQUE). Un servicio la lee para actualizarla cuando llega un apunte auditado.
     */
    fun findByStudentIdAndSubtopicId(studentId: Long, subtopicId: Long): Optional<Coverage>

    /**
     * Vista agregada del profesor: cuántos estudiantes hay en cada estado de cobertura por subtema
     * de un curso. Devuelve solo subtema, estado y total (nunca datos del estudiante): la
     * privacidad se impone en la consulta y no solo en la pantalla.
     */
    @Query(
        """
        select c.subtopic.id as subtopicId, c.status as status, count(c) as total
        from Coverage c
        where c.subtopic.module.course.id = :courseId
        group by c.subtopic.id, c.status
        order by c.subtopic.id, c.status
        """,
    )
    fun countByStatusForCourse(@Param("courseId") courseId: Long): List<SubtopicCoverageCount>

    /**
     * Cobertura de un estudiante sobre el temario de un curso (las filas que existen; un subtema sin
     * fila es un subtema sin apuntes).
     */
    fun findByStudentIdAndSubtopicModuleCourseId(studentId: Long, courseId: Long): List<Coverage>

    /**
     * Vista del profesor para un grupo: cuántos estudiantes con matrícula ACTIVE en el grupo hay en
     * cada estado, por subtema del curso. La agregación ocurre en la base (GROUP BY) y solo salen
     * subtema, estado y total: el servicio nunca carga filas de un estudiante.
     */
    @Query(
        """
        select c.subtopic.id as subtopicId, c.status as status, count(c) as total
        from Coverage c, Enrollment e
        where e.student = c.student
          and e.group.id = :groupId
          and e.status = edu.backend.codice.EnrollmentStatus.ACTIVE
          and c.subtopic.module.course.id = e.group.course.id
        group by c.subtopic.id, c.status
        """,
    )
    fun countByStatusForGroup(@Param("groupId") groupId: Long): List<SubtopicCoverageCount>
}

@Repository
interface ReviewRepository : JpaRepository<Review, Long> {

    /**
     * Repasos de un estudiante que ya toca hacer: su fecha programada es hoy o anterior. Es la
     * lista de "Próximos repasos" y la que usa el recordatorio de repetición espaciada.
     */
    fun findByStudentIdAndNextDateLessThanEqual(studentId: Long, date: LocalDate): List<Review>

    /** Los repasos ya programados que todavía no vencen (fecha posterior a [date]): los que el estudiante puede practicar antes. */
    fun findByStudentIdAndNextDateGreaterThan(studentId: Long, date: LocalDate): List<Review>

    /** Todos los repasos del estudiante: sirve para no generar dos veces el repaso de un mismo subtema. */
    fun findByStudentId(studentId: Long): List<Review>
}

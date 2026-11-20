package edu.backend.codice

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/*
 * Agregado Cursos y Matrícula.
 *
 * Distinción clave del modelo: el CURSO es el catálogo (EIF411 existe una sola vez y tiene un
 * solo temario), el GRUPO es la sección que abre un profesor en un periodo, y la MATRÍCULA une
 * a un estudiante con un grupo. El temario cuelga del curso, no del grupo, para que dos
 * profesores del mismo curso auditen contra el mismo programa.
 *
 * Todas las entidades son `class` (no `data class`): una data class genera equals/hashCode/toString
 * con todas las propiedades, lo que con relaciones bidireccionales provoca recursión infinita y
 * carga perezosa accidental. Aquí equals/hashCode van por id y toString no toca relaciones.
 */

@Entity
@Table(name = "course")
class Course(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // Código institucional (p. ej. 'EIF411'); único porque el catálogo no repite cursos.
    @Column(nullable = false, unique = true, length = 20)
    var code: String = "",

    @Column(nullable = false)
    var name: String = "",

    // Color con el que la app Android pinta el curso; mismo valor por defecto que el DDL.
    @Column(nullable = false, length = 20)
    var color: String = "#C8F250",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    // Salida cruda de la llamada de IA 1 (extracción del temario desde el PDF). Es jsonb porque su
    // forma la dicta el modelo de IA; lo que la app consulta ya vive normalizado en module/subtopic.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extracted_syllabus")
    var extractedSyllabus: Map<String, Any?>? = null,

    // Null mientras el profesor revisa el árbol extraído; con fecha, el temario es visible al grupo.
    @Column(name = "syllabus_published_at")
    var syllabusPublishedAt: Instant? = null,

    // Profesor dueño del curso (V3): solo él lo edita, lo borra y crea grupos en él. Nullable porque los
    // cursos anteriores a V3 se llenan desde sus grupos y un curso sin grupos podía quedar sin dueño.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    var teacher: User? = null,

    // Un curso se oferta en varios grupos. mappedBy: la llave foránea vive en student_group.
    @OneToMany(mappedBy = "course")
    var groups: MutableList<StudentGroup> = mutableListOf(),

    // El temario (módulos → subtemas → objetivos) es del curso, no del grupo: todos los grupos del
    // mismo curso comparten un solo programa. El curso es dueño del árbol, por eso cascade/orphanRemoval.
    @OneToMany(mappedBy = "course", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("number ASC")
    var modules: MutableList<CourseModule> = mutableListOf(),
) {
    fun addModule(module: CourseModule) = apply {
        module.course = this
        modules.add(module)
    }

    override fun equals(other: Any?) = this === other || (other is Course && id != null && id == other.id)
    override fun hashCode() = Course::class.hashCode()
    override fun toString() = "Course(id=$id, code=$code, name=$name)"
}

@Entity
@Table(
    name = "student_group",
    // Un profesor no puede abrir dos veces el mismo número de grupo en el mismo curso.
    uniqueConstraints = [UniqueConstraint(columnNames = ["course_id", "teacher_id", "number"])],
)
class StudentGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // El grupo pertenece a un curso del catálogo; si se borra el curso, la base borra sus grupos.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    var course: Course? = null,

    // Profesor que imparte el grupo: es quien aprueba las solicitudes de matrícula de este grupo.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    var teacher: User? = null,

    @Column(nullable = false, length = 10)
    var number: String = "",

    @Column(nullable = false, length = 50)
    var period: String = "",

    var schedule: String? = null,

    // Código que el estudiante escribe o escanea (QR) para unirse; único en todo el sistema.
    @Column(name = "invite_code", nullable = false, unique = true, length = 30)
    var inviteCode: String = "",

    @Column(name = "requires_approval", nullable = false)
    var requiresApproval: Boolean = true,

    @Column(name = "invite_expires_at")
    var inviteExpiresAt: Instant? = null,

    @OneToMany(mappedBy = "group")
    var enrollments: MutableList<Enrollment> = mutableListOf(),
) {
    override fun equals(other: Any?) = this === other || (other is StudentGroup && id != null && id == other.id)
    override fun hashCode() = StudentGroup::class.hashCode()
    override fun toString() = "StudentGroup(id=$id, number=$number, period=$period, inviteCode=$inviteCode)"
}

// Mismos valores que el CHECK de enrollment.status. Se guarda como texto (EnumType.STRING) para
// que reordenar el enum nunca cambie el significado de las filas ya guardadas.
//
// LEFT y REMOVED son matrículas terminadas: LEFT cuando el estudiante salió del grupo y REMOVED cuando lo quitó el profesor.
// La fila NO se borra: los apuntes cuelgan de ella (ON DELETE CASCADE) y borrarla los eliminaría. Dejan de contar como
// matrícula (ya no es ACTIVE) pero conservan los apuntes, que reaparecen si el estudiante vuelve al mismo grupo.
enum class EnrollmentStatus { PENDING, ACTIVE, REJECTED, LEFT, REMOVED }

@Entity
@Table(
    name = "enrollment",
    // Un estudiante no puede pedir dos veces entrar al mismo grupo.
    uniqueConstraints = [UniqueConstraint(columnNames = ["group_id", "student_id"])],
)
class Enrollment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // La matrícula apunta a un GRUPO, no a un curso: la solicitud le llega al profesor de ese grupo.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    var group: StudentGroup? = null,

    /*
     * course_id está desnormalizado a propósito: existe solo para que la base garantice la regla
     * "un estudiante tiene como máximo UNA matrícula ACTIVE por curso", aunque esté en otro grupo
     * del mismo curso. Esa regla la impone el índice único parcial
     * uq_enrollment_one_active_group_per_course (course_id, student_id) WHERE status = 'ACTIVE',
     * y un índice no puede mirar otra tabla, por eso el curso se copia aquí.
     *
     * Lo llena el trigger trg_enrollment_course_id a partir del grupo, así que JPA lo mapea de solo
     * lectura: si la aplicación lo escribiera podría contradecir al grupo.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", insertable = false, updatable = false)
    var course: Course? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    var student: User? = null,

    // Toda matrícula nace PENDING; el profesor la resuelve a ACTIVE o REJECTED.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EnrollmentStatus = EnrollmentStatus.PENDING,

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant = Instant.now(),

    // Null mientras está PENDING; se llena cuando el profesor aprueba o rechaza.
    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
) {
    override fun equals(other: Any?) = this === other || (other is Enrollment && id != null && id == other.id)
    override fun hashCode() = Enrollment::class.hashCode()
    override fun toString() = "Enrollment(id=$id, status=$status, requestedAt=$requestedAt)"
}

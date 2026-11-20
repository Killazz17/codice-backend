package edu.backend.codice

import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/*
 * Agregado Temario, Cobertura y Repaso.
 *
 * El temario es un árbol de tres niveles que cuelga del curso: módulo → subtema → objetivo.
 * Es la versión normalizada y ya revisada por el profesor de lo que la IA extrajo del PDF
 * (course.extracted_syllabus); contra estos subtemas se audita cada apunte del estudiante.
 *
 * Igual que en el resto de entidades: `class` con equals/hashCode por id y toString sin relaciones.
 */

// Se llama CourseModule y no Module porque Module choca con java.lang.Module, que Kotlin importa
// implícitamente. La tabla sigue siendo "module".
@Entity
@Table(
    name = "module",
    // El número de módulo es único dentro de su curso (Módulo 4 aparece una sola vez en EIF411).
    uniqueConstraints = [UniqueConstraint(columnNames = ["course_id", "number"])],
)
class CourseModule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    var course: Course? = null,

    @Column(nullable = false)
    var number: Int = 0,

    @Column(nullable = false)
    var title: String = "",

    // El módulo es dueño de sus subtemas: guardar el módulo guarda el árbol, y quitar un subtema de
    // la lista lo borra (orphanRemoval). Así el profesor edita el temario como una sola unidad.
    @OneToMany(mappedBy = "module", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("position ASC")
    var subtopics: MutableList<Subtopic> = mutableListOf(),
) {
    // Mantiene los dos lados de la relación sincronizados en memoria.
    fun addSubtopic(subtopic: Subtopic) = apply {
        subtopic.module = this
        subtopics.add(subtopic)
    }

    override fun equals(other: Any?) = this === other || (other is CourseModule && id != null && id == other.id)
    override fun hashCode() = CourseModule::class.hashCode()
    override fun toString() = "CourseModule(id=$id, number=$number, title=$title)"
}

@Entity
@Table(
    name = "subtopic",
    // El código ('4.4') es único dentro de su módulo.
    uniqueConstraints = [UniqueConstraint(columnNames = ["module_id", "code"])],
)
class Subtopic(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id", nullable = false)
    var module: CourseModule? = null,

    @Column(nullable = false, length = 20)
    var code: String = "",

    @Column(nullable = false)
    var title: String = "",

    // Orden de aparición dentro del módulo; el código no sirve para ordenar ('4.10' < '4.2' como texto).
    @Column(nullable = false)
    var position: Int = 0,

    // Los objetivos son lo que la auditoría de IA verifica en el apunte: viven y mueren con el subtema.
    @OneToMany(mappedBy = "subtopic", cascade = [CascadeType.ALL], orphanRemoval = true)
    var objectives: MutableList<SubtopicObjective> = mutableListOf(),
) {
    fun addObjective(objective: SubtopicObjective) = apply {
        objective.subtopic = this
        objectives.add(objective)
    }

    override fun equals(other: Any?) = this === other || (other is Subtopic && id != null && id == other.id)
    override fun hashCode() = Subtopic::class.hashCode()
    override fun toString() = "Subtopic(id=$id, code=$code, title=$title, position=$position)"
}

@Entity
@Table(name = "subtopic_objective")
class SubtopicObjective(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subtopic_id", nullable = false)
    var subtopic: Subtopic? = null,

    @Column(nullable = false, length = 500)
    var description: String = "",
) {
    override fun equals(other: Any?) = this === other || (other is SubtopicObjective && id != null && id == other.id)
    override fun hashCode() = SubtopicObjective::class.hashCode()
    override fun toString() = "SubtopicObjective(id=$id, description=$description)"
}

// Estado de subida del apunte, igual que el CHECK de note.sync_status. Refleja la cola offline de
// la app: la foto se toma sin señal (QUEUED) y el worker la sube cuando vuelve la conexión.
enum class SyncStatus { QUEUED, UPLOADING, SYNCED, FAILED }

@Entity
@Table(name = "note")
class Note(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // El apunte cuelga de la MATRÍCULA (no solo del estudiante): así se sabe contra qué curso y
    // temario se audita, y si la matrícula se borra, la base borra sus apuntes en cascada.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    var enrollment: Enrollment? = null,

    // Subtema sugerido al tomar la foto. Opcional: el estudiante puede no saberlo, y si el profesor
    // borra el subtema del temario la base deja NULL en vez de borrar el apunte (ON DELETE SET NULL).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subtopic_id")
    var subtopic: Subtopic? = null,

    // Id que genera el teléfono antes de subir. Si el worker reintenta una subida que sí había
    // llegado, el UNIQUE rechaza el duplicado: la subida es idempotente.
    @Column(name = "client_request_id", unique = true, length = 100)
    var clientRequestId: String? = null,

    // Texto que el estudiante revisó y confirmó; la auditoría solo corre sobre texto confirmado.
    @Column(name = "confirmed_text", columnDefinition = "TEXT")
    var confirmedText: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 20)
    var syncStatus: SyncStatus = SyncStatus.QUEUED,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    // Salida cruda de la llamada de IA 2 (transcripción por bloques con su confianza).
    @JdbcTypeCode(SqlTypes.JSON)
    var transcription: Map<String, Any?>? = null,

    // Salida cruda de la llamada de IA 3 (auditoría del apunte contra los objetivos del subtema).
    @JdbcTypeCode(SqlTypes.JSON)
    var audit: Map<String, Any?>? = null,

    // Un apunte son una o varias fotos. Las páginas no existen sin su apunte: cascade y orphanRemoval.
    @OneToMany(mappedBy = "note", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("pageIndex ASC")
    var pages: MutableList<NotePage> = mutableListOf(),
) {
    fun addPage(page: NotePage) = apply {
        page.note = this
        pages.add(page)
    }

    override fun equals(other: Any?) = this === other || (other is Note && id != null && id == other.id)
    override fun hashCode() = Note::class.hashCode()
    override fun toString() = "Note(id=$id, clientRequestId=$clientRequestId, syncStatus=$syncStatus, createdAt=$createdAt)"
}

@Entity
@Table(
    name = "note_page",
    // Una nota no puede tener dos páginas con el mismo índice.
    uniqueConstraints = [UniqueConstraint(columnNames = ["note_id", "page_index"])],
)
class NotePage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    var note: Note? = null,

    // Orden de la foto dentro del apunte (0, 1, 2…).
    @Column(name = "page_index", nullable = false)
    var pageIndex: Int = 0,

    // Ruta del archivo en el almacenamiento; la base guarda la referencia, no la imagen.
    @Column(name = "file_path", nullable = false, length = 500)
    var filePath: String = "",
) {
    override fun equals(other: Any?) = this === other || (other is NotePage && id != null && id == other.id)
    override fun hashCode() = NotePage::class.hashCode()
    override fun toString() = "NotePage(id=$id, pageIndex=$pageIndex, filePath=$filePath)"
}

// Mismos cuatro estados que el CHECK de coverage.status y que CoverageStatus de la app Android
// (una/codice/core/domain/model): el mapa de cobertura pinta cada subtema según este valor.
enum class CoverageStatus { NO_NOTES, PARTIAL, COVERED, REINFORCED }

@Entity
@Table(
    name = "coverage",
    // Una sola fila de cobertura por estudiante y subtema: se actualiza, no se acumula.
    uniqueConstraints = [UniqueConstraint(columnNames = ["student_id", "subtopic_id"])],
)
class Coverage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // La cobertura es del estudiante (usuario), no de la matrícula: si cambia de grupo dentro del
    // mismo curso conserva su progreso sobre el temario, que es del curso.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    var student: User? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subtopic_id", nullable = false)
    var subtopic: Subtopic? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CoverageStatus = CoverageStatus.NO_NOTES,

    // Explicación de la auditoría de IA de por qué el subtema quedó en este estado.
    @Column(length = 500)
    var justification: String? = null,

    // Apunte que produjo este estado, para poder llevar al estudiante a la evidencia. Opcional: si
    // el apunte se borra, la base deja la columna en NULL y la cobertura se conserva.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_origin_id")
    var noteOrigin: Note? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?) = this === other || (other is Coverage && id != null && id == other.id)
    override fun hashCode() = Coverage::class.hashCode()
    override fun toString() = "Coverage(id=$id, status=$status, updatedAt=$updatedAt)"
}

enum class ReviewResult { PENDING, PASSED, FAILED }

@Entity
@Table(name = "review")
class Review(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    var student: User? = null,

    // El repaso se genera sobre un subtema con vacíos detectados en la auditoría.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subtopic_id", nullable = false)
    var subtopic: Subtopic? = null,

    // Repetición espaciada: el CHECK de la base solo admite 1, 3, 7, 14 o 30 días. Al aprobar se
    // pasa al siguiente intervalo; al fallar se vuelve a 1.
    @Column(name = "interval_day", nullable = false)
    var intervalDay: Int = 1,

    // Es DATE en la base (sin hora): el repaso vence un día, no a una hora concreta.
    @Column(name = "next_date", nullable = false)
    var nextDate: LocalDate = LocalDate.now().plusDays(1),

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var result: ReviewResult? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    // Salida de la llamada de IA 4: un objeto con el modelo, la versión del prompt y la lista `preguntas`
    // (enunciado, opciones, correcta, subtemaId, explicacion). Es la misma forma que ya guarda la semilla.
    @JdbcTypeCode(SqlTypes.JSON)
    var questions: Map<String, Any?>? = null,
) {
    override fun equals(other: Any?) = this === other || (other is Review && id != null && id == other.id)
    override fun hashCode() = Review::class.hashCode()
    override fun toString() = "Review(id=$id, intervalDay=$intervalDay, nextDate=$nextDate, result=$result)"
}

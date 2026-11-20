-- Salir del curso (LEFT) y quitar a un estudiante (REMOVED).
--
-- No se borra la matrícula: note.enrollment_id es ON DELETE CASCADE y borrarla eliminaría los apuntes del estudiante.
-- Basta con ampliar los valores permitidos de enrollment.status; las filas existentes no cambian.
--
-- El índice único parcial uq_enrollment_one_active_group_per_course solo mira status = 'ACTIVE', así que una matrícula
-- LEFT o REMOVED no impide que el estudiante se matricule en otro grupo del mismo curso.

ALTER TABLE enrollment DROP CONSTRAINT IF EXISTS enrollment_status_check;

ALTER TABLE enrollment
    ADD CONSTRAINT enrollment_status_check
    CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'LEFT', 'REMOVED'));

-- V3: dueño del curso.
-- El curso necesita un profesor dueño para las reglas de SVC-02 (solo el dueño edita, borra y ve sus
-- cursos). Hasta ahora el profesor solo existía en cada grupo, y un curso recién creado no tiene grupos.
-- Es nullable para no romper los cursos que ya existen; se llena con el profesor de su primer grupo.

ALTER TABLE course ADD COLUMN IF NOT EXISTS teacher_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

UPDATE course c
SET teacher_id = (SELECT g.teacher_id FROM student_group g WHERE g.course_id = c.id ORDER BY g.id LIMIT 1)
WHERE c.teacher_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_course_teacher ON course(teacher_id);

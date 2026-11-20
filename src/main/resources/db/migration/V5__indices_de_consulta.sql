-- Índices para las consultas que más se repiten y hoy recorren la tabla completa.

-- existsBySubtopicId / existsBySubtopicModuleId: borrar un subtema o un módulo revisa si tiene apuntes.
CREATE INDEX IF NOT EXISTS idx_note_subtopic ON note(subtopic_id);

-- "Mis matrículas": se filtran por estudiante, y el índice que existe empieza por grupo o por curso.
CREATE INDEX IF NOT EXISTS idx_enrollment_student ON enrollment(student_id, status);

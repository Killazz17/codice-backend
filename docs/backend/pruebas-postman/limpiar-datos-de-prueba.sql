-- Limpieza de los datos que crea la colección de Postman (carpetas Auth, Cursos y Grupos, Matrículas, PM-04 y Casos negativos).
--
-- Cada corrida registra usuarios con correo @codice.test y crea cursos con código PM3/PM4/PM5 + 7 dígitos. La API no tiene
-- endpoints para borrar usuarios ni matrículas, y un curso con estudiantes matriculados no se puede eliminar (422), así que
-- estos datos se acumulan. Este script los purga directamente en la base; el resto se va en cascada (grupos, matrículas,
-- temario, apuntes, cobertura y repasos tienen ON DELETE CASCADE).
--
-- Cómo correrlo (siempre contra la base de PRUEBA, nunca con datos reales):
--   Local:        psql -U codice -h localhost -d codice -f limpiar-datos-de-prueba.sql
--   Cloud SQL:    gcloud sql connect <instancia> --user=codice --database=codice < limpiar-datos-de-prueba.sql
--
-- Solo toca filas de prueba: usuarios cuyo correo termina en @codice.test y cursos que ellos crearon (o con código de la
-- colección). Los usuarios y cursos de la semilla (V2__seed.sql, correos @example.com) no se tocan.

BEGIN;

-- 1) Los cursos de los usuarios de prueba, y los de la colección que hayan quedado sin dueño.
DELETE FROM course
 WHERE teacher_id IN (SELECT id FROM users WHERE email LIKE '%@codice.test')
    OR code ~ '^PM[3-6][0-9]{7}$';

-- 2) Los usuarios de prueba.
DELETE FROM users WHERE email LIKE '%@codice.test';

COMMIT;

-- Comprobación: las dos cuentas deben dar 0.
SELECT (SELECT count(*) FROM users  WHERE email LIKE '%@codice.test')      AS usuarios_de_prueba,
       (SELECT count(*) FROM course WHERE code ~ '^PM[3-6][0-9]{7}$')      AS cursos_de_prueba;

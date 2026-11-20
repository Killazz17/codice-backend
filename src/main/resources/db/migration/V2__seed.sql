-- =====================================================================
--  Códice · datos de prueba (issue #5 del Lab 4)
-- =====================================================================

INSERT INTO role (name) VALUES ('ROLE_STUDENT'), ('ROLE_TEACHER')
ON CONFLICT (name) DO NOTHING;

INSERT INTO privilege (name) VALUES
    ('READ_COURSE'),
    ('WRITE_NOTES'),
    ('READ_OWN_COVERAGE'),
    ('PUBLISH_SYLLABUS'),
    ('MANAGE_GROUPS'),
    ('APPROVE_ENROLLMENT'),
    ('READ_GROUP_COVERAGE')
ON CONFLICT (name) DO NOTHING;

INSERT INTO role_privilege (role_id, privilege_id)
SELECT r.id, p.id
FROM (VALUES
    ('ROLE_STUDENT', 'READ_COURSE'),
    ('ROLE_STUDENT', 'WRITE_NOTES'),
    ('ROLE_STUDENT', 'READ_OWN_COVERAGE'),
    ('ROLE_TEACHER', 'READ_COURSE'),
    ('ROLE_TEACHER', 'PUBLISH_SYLLABUS'),
    ('ROLE_TEACHER', 'MANAGE_GROUPS'),
    ('ROLE_TEACHER', 'APPROVE_ENROLLMENT'),
    ('ROLE_TEACHER', 'READ_GROUP_COVERAGE')
) AS v(role_name, privilege_name)
JOIN role      r ON r.name = v.role_name
JOIN privilege p ON p.name = v.privilege_name
ON CONFLICT DO NOTHING;

INSERT INTO users (first_name, last_name, email, password) VALUES
    ('Carlos', 'Mora',    'carlos.mora@example.com',    '$2a$10$ABxI/wb75ljpc25t11M/4eyBh/Snzx0.9J1P10/bpsGnVZiKxzsf2'),
    ('Laura',  'Jiménez', 'laura.jimenez@example.com',  '$2a$10$ABxI/wb75ljpc25t11M/4eyBh/Snzx0.9J1P10/bpsGnVZiKxzsf2'),
    ('Ana',    'Vargas',  'ana.vargas@example.com',     '$2a$10$ABxI/wb75ljpc25t11M/4eyBh/Snzx0.9J1P10/bpsGnVZiKxzsf2'),
    ('Luis',   'Rojas',   'luis.rojas@example.com',     '$2a$10$ABxI/wb75ljpc25t11M/4eyBh/Snzx0.9J1P10/bpsGnVZiKxzsf2'),
    ('María',  'Solís',   'maria.solis@example.com',    '$2a$10$ABxI/wb75ljpc25t11M/4eyBh/Snzx0.9J1P10/bpsGnVZiKxzsf2'),
    ('Diego',  'Araya',   'diego.araya@example.com',    '$2a$10$ABxI/wb75ljpc25t11M/4eyBh/Snzx0.9J1P10/bpsGnVZiKxzsf2')
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_role (user_id, role_id)
SELECT u.id, r.id
FROM (VALUES
    ('carlos.mora@example.com',   'ROLE_TEACHER'),
    ('laura.jimenez@example.com', 'ROLE_TEACHER'),
    ('ana.vargas@example.com',    'ROLE_STUDENT'),
    ('luis.rojas@example.com',    'ROLE_STUDENT'),
    ('maria.solis@example.com',   'ROLE_STUDENT'),
    ('diego.araya@example.com',   'ROLE_STUDENT')
) AS v(email, role_name)
JOIN users u ON u.email = v.email
JOIN role  r ON r.name  = v.role_name
ON CONFLICT DO NOTHING;

INSERT INTO course (code, name, color, extracted_syllabus) VALUES (
    'EIF411',
    'Diseño y Programación de Plataformas Móviles',
    '#C8F250',
    '{
        "archivoOriginal": "programa-eif411.pdf",
        "modelo": "modelo-de-ejemplo",
        "promptVersion": "v3",
        "confianzaGlobal": 0.86,
        "arbol": [
            { "modulo": 4, "titulo": "Programación distribuida y consumo de APIs de IA",
              "subtemas": [
                { "codigo": "4.4", "titulo": "Consumo de API de LLM",
                  "objetivos": ["Autenticar sin exponer la API key"], "confianza": 0.91 }
              ] },
            { "modulo": 5, "titulo": "Tipos de almacenamiento",
              "subtemas": [
                { "codigo": "5.2", "titulo": "Room: entidades y DAO",
                  "objetivos": ["Definir entidades y relaciones en Room"], "confianza": 0.64 }
              ] }
        ]
    }'::jsonb
), (
    'EIF311',
    'Curso de ejemplo sin grupos ni temario',
    '#5D9BFF',
    NULL
)
ON CONFLICT (code) DO NOTHING;

INSERT INTO student_group (course_id, teacher_id, number, period, schedule,
                           invite_code, requires_approval, invite_expires_at)
SELECT c.id, t.id, v.number, v.period, v.schedule, v.invite_code, v.requires_approval,
       now() + interval '30 days'
FROM (VALUES
    ('EIF411', 'carlos.mora@example.com',   '01', '2026-II', 'Lunes y jueves 18:00-19:30',   'EIF411-01-7K2A', true),
    ('EIF411', 'laura.jimenez@example.com', '02', '2026-II', 'Martes y viernes 08:00-09:30', 'EIF411-02-9M4C', false)
) AS v(course_code, teacher_email, number, period, schedule, invite_code, requires_approval)
JOIN course c ON c.code  = v.course_code
JOIN users  t ON t.email = v.teacher_email
ON CONFLICT DO NOTHING;

INSERT INTO enrollment (group_id, student_id, status, resolved_at)
SELECT g.id, s.id, v.status, v.resolved_at
FROM (VALUES
    ('EIF411-01-7K2A', 'ana.vargas@example.com',  'ACTIVE',   now()),
    ('EIF411-02-9M4C', 'luis.rojas@example.com',  'ACTIVE',   now()),
    ('EIF411-01-7K2A', 'maria.solis@example.com', 'PENDING',  NULL),
    ('EIF411-01-7K2A', 'diego.araya@example.com', 'REJECTED', now()),
    ('EIF411-02-9M4C', 'ana.vargas@example.com',  'REJECTED', now())
) AS v(invite_code, student_email, status, resolved_at)
JOIN student_group g ON g.invite_code = v.invite_code
JOIN users         s ON s.email       = v.student_email
ON CONFLICT DO NOTHING;

INSERT INTO module (course_id, number, title)
SELECT c.id, v.number, v.title
FROM (VALUES
    (1, 'Patrones de diseño y Kotlin'),
    (2, 'Plataformas móviles'),
    (3, 'GUI y prototipado asistido por IA'),
    (4, 'Programación distribuida y consumo de APIs de IA'),
    (5, 'Tipos de almacenamiento'),
    (6, 'Seguridad, rendimiento y publicación')
) AS v(number, title)
JOIN course c ON c.code = 'EIF411'
ON CONFLICT DO NOTHING;

INSERT INTO subtopic (module_id, code, title, position)
SELECT m.id, v.code, v.title, v.orden
FROM (VALUES
    (1, '1.1', 'MVVM y arquitectura por capas',              0),
    (1, '1.2', 'Kotlin: corrutinas y Flow',                  1),
    (2, '2.1', 'Ciclo de vida y permisos',                   0),
    (2, '2.2', 'Cámara con CameraX',                         1),
    (3, '3.1', 'Compose: estado y recomposición',            0),
    (3, '3.2', 'Prototipado en Figma asistido por IA',       1),
    (4, '4.1', 'Retrofit y OkHttp',                          0),
    (4, '4.4', 'Consumo de API de LLM',                      1),
    (5, '5.1', 'DataStore y preferencias',                   0),
    (5, '5.2', 'Room: entidades y DAO',                      1),
    (6, '6.1', 'Seguridad de credenciales',                  0),
    (6, '6.2', 'Publicación y build de release',             1)
) AS v(module_number, code, title, orden)
JOIN course c ON c.code = 'EIF411'
JOIN module m ON m.course_id = c.id AND m.number = v.module_number
ON CONFLICT DO NOTHING;

INSERT INTO subtopic_objective (subtopic_id, description)
SELECT s.id, v.description
FROM (VALUES
    ('1.1', 'Separar presentación, dominio y datos en capas'),
    ('1.1', 'Explicar por qué el ViewModel sobrevive a un cambio de configuración'),
    ('1.2', 'Diferenciar una función suspend de una bloqueante'),
    ('2.1', 'Explicar cada estado del ciclo de vida de una Activity'),
    ('2.2', 'Capturar varias páginas y mostrar una vista previa'),
    ('3.1', 'Modelar el estado de una pantalla como UiState inmutable'),
    ('3.2', 'Traducir un wireframe a componentes reutilizables'),
    ('4.1', 'Declarar un endpoint REST con Retrofit y convertir su JSON'),
    ('4.4', 'Autenticar sin exponer la API key en el APK'),
    ('4.4', 'Manejar tiempos de espera y reintentos de la llamada'),
    ('5.1', 'Elegir entre DataStore, Room y un archivo según el dato'),
    ('5.2', 'Definir entidades y relaciones en Room'),
    ('5.2', 'Escribir un DAO con consultas y Flow'),
    ('6.1', 'Guardar credenciales fuera del repositorio y del APK')
) AS v(subtopic_code, description)
JOIN course c ON c.code = 'EIF411'
JOIN module m ON m.course_id = c.id
JOIN subtopic s ON s.module_id = m.id AND s.code = v.subtopic_code
WHERE NOT EXISTS (
    SELECT 1 FROM subtopic_objective o
    WHERE o.subtopic_id = s.id AND o.description = v.description
);

INSERT INTO note (enrollment_id, subtopic_id, confirmed_text, sync_status, transcription, audit)
SELECT e.id, s.id, v.confirmed_text, v.sync_status,
       v.transcription::jsonb,
       CASE WHEN v.estado IS NOT NULL THEN jsonb_build_object(
            'modelo', 'modelo-de-ejemplo',
            'veredictos', jsonb_build_array(jsonb_build_object(
                'subtemaId',     s.id,
                'estado',        v.estado,
                'justificacion', v.justificacion,
                'evidencia',     v.evidencia))
       ) END
FROM (VALUES
    ('EIF411-01-7K2A', 'ana.vargas@example.com', '5.2',
     'Room usa entidades anotadas con @Entity y un DAO con consultas. El DAO devuelve Flow para observar cambios.',
     'SYNCED',
     '{"modelo": "modelo-de-ejemplo", "paginas": [
        {"indice": 0, "bloques": [
            {"texto": "Room usa entidades anotadas con @Entity y un DAO con consultas.", "confianza": 0.94, "tipo": "parrafo"},
            {"texto": "El DAO devuelve Flow para observar cambios.", "confianza": 0.88, "tipo": "parrafo"}]},
        {"indice": 1, "bloques": [
            {"texto": "Las relaciones se declaran con claves foráneas.", "confianza": 0.41, "tipo": "parrafo"}]}
     ]}',
     'parcial',
     'Menciona entidades y DAO, pero no explica las relaciones entre entidades.',
     'Room usa entidades anotadas con @Entity y un DAO con consultas'),
    ('EIF411-01-7K2A', 'ana.vargas@example.com', '4.4',
     NULL, 'QUEUED', NULL, NULL, NULL, NULL),
    ('EIF411-02-9M4C', 'luis.rojas@example.com', '1.2',
     'Una función suspend puede pausarse sin bloquear el hilo; una bloqueante detiene el hilo hasta terminar.',
     'SYNCED',
     '{"modelo": "modelo-de-ejemplo", "paginas": [
        {"indice": 0, "bloques": [
            {"texto": "Una función suspend puede pausarse sin bloquear el hilo.", "confianza": 0.96, "tipo": "parrafo"},
            {"texto": "Una bloqueante detiene el hilo hasta terminar.", "confianza": 0.92, "tipo": "parrafo"}]}
     ]}',
     'cubierto',
     'Diferencia con claridad suspend de bloqueante.',
     'Una función suspend puede pausarse sin bloquear el hilo')
) AS v(invite_code, student_email, subtopic_code, confirmed_text, sync_status,
       transcription, estado, justificacion, evidencia)
JOIN student_group g ON g.invite_code = v.invite_code
JOIN users         u ON u.email = v.student_email
JOIN enrollment    e ON e.group_id = g.id AND e.student_id = u.id
JOIN module        m ON m.course_id = g.course_id
JOIN subtopic      s ON s.module_id = m.id AND s.code = v.subtopic_code
WHERE NOT EXISTS (
    SELECT 1 FROM note n WHERE n.enrollment_id = e.id AND n.subtopic_id = s.id
);

INSERT INTO note_page (note_id, page_index, file_path)
SELECT n.id, v.page_index, v.file_path
FROM (VALUES
    ('ana.vargas@example.com', '5.2', 0, 'notes/ana-vargas/room-entidades-dao/p0.jpg'),
    ('ana.vargas@example.com', '5.2', 1, 'notes/ana-vargas/room-entidades-dao/p1.jpg'),
    ('ana.vargas@example.com', '4.4', 0, 'notes/ana-vargas/consumo-api-llm/p0.jpg'),
    ('luis.rojas@example.com', '1.2', 0, 'notes/luis-rojas/corrutinas-flow/p0.jpg')
) AS v(student_email, subtopic_code, page_index, file_path)
JOIN users      u ON u.email = v.student_email
JOIN enrollment e ON e.student_id = u.id AND e.status = 'ACTIVE'
JOIN note       n ON n.enrollment_id = e.id
JOIN subtopic   s ON s.id = n.subtopic_id AND s.code = v.subtopic_code
ON CONFLICT DO NOTHING;

INSERT INTO coverage (student_id, subtopic_id, status, justification, note_origin_id)
SELECT u.id, s.id, v.status, v.justification, n.id
FROM (VALUES
    ('ana.vargas@example.com', '5.2', 'PARTIAL',    'Menciona entidades y DAO, pero no las relaciones.', true),
    ('ana.vargas@example.com', '4.4', 'NO_NOTES',   NULL,                                                false),
    ('ana.vargas@example.com', '1.1', 'COVERED',    'Objetivos cubiertos en la clase 2.',                false),
    ('luis.rojas@example.com', '1.2', 'COVERED',    'Diferencia suspend de bloqueante.',                 true),
    ('luis.rojas@example.com', '5.1', 'REINFORCED', 'Aprobó el repaso de 3 días.',                       false),
    ('luis.rojas@example.com', '6.1', 'NO_NOTES',   NULL,                                                false)
) AS v(student_email, subtopic_code, status, justification, con_origen)
JOIN users     u ON u.email = v.student_email
JOIN course    c ON c.code = 'EIF411'
JOIN module    m ON m.course_id = c.id
JOIN subtopic  s ON s.module_id = m.id AND s.code = v.subtopic_code
LEFT JOIN enrollment e ON e.student_id = u.id AND e.course_id = c.id AND e.status = 'ACTIVE'
LEFT JOIN note       n ON v.con_origen AND n.enrollment_id = e.id AND n.subtopic_id = s.id
ON CONFLICT DO NOTHING;

INSERT INTO review (student_id, subtopic_id, interval_day, next_date, result, questions)
SELECT u.id, s.id, v.interval_day, current_date + v.interval_day, v.result,
       jsonb_build_object(
           'modelo', 'modelo-de-ejemplo',
           'promptVersion', 'v2',
           'preguntas', jsonb_build_array(jsonb_build_object(
               'enunciado',   v.enunciado,
               'opciones',    jsonb_build_array(v.opcion_a, v.opcion_b, v.opcion_c),
               'correcta',    v.correcta,
               'subtemaId',   s.id,
               'explicacion', v.explicacion)))
FROM (VALUES
    ('ana.vargas@example.com', '5.2', 1, 'PENDING',
     '¿Cómo se declara una relación entre dos entidades en Room?',
     'Con una clave foránea en la entidad hija', 'Con una anotación @Relation en el DAO', 'Room no admite relaciones',
     0, 'La relación se declara con ForeignKey en la entidad y se lee con @Relation en un POJO.'),
    ('ana.vargas@example.com', '1.1', 7, 'PENDING',
     '¿Qué capa NO debe conocer a la capa de datos?',
     'La presentación', 'El dominio', 'Ninguna, todas se conocen',
     1, 'El dominio es Kotlin puro y no depende de la capa de datos.'),
    ('luis.rojas@example.com', '5.1', 3, 'PASSED',
     '¿Qué almacenamiento conviene para una preferencia booleana?',
     'DataStore', 'Room', 'Un archivo de texto',
     0, 'DataStore está pensado para preferencias pequeñas y asíncronas.')
) AS v(student_email, subtopic_code, interval_day, result, enunciado,
       opcion_a, opcion_b, opcion_c, correcta, explicacion)
JOIN users    u ON u.email = v.student_email
JOIN course   c ON c.code = 'EIF411'
JOIN module   m ON m.course_id = c.id
JOIN subtopic s ON s.module_id = m.id AND s.code = v.subtopic_code
WHERE NOT EXISTS (
    SELECT 1 FROM review r
    WHERE r.student_id = u.id AND r.subtopic_id = s.id AND r.interval_day = v.interval_day
);

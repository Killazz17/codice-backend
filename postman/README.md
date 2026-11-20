# Postman - Colección y Entornos de Pruebas (Códice)

Esta carpeta contiene la colección de Postman y los entornos para las pruebas de integración de la API de **Códice**: endpoints, headers y status codes.

---

## 1. Archivos incluidos

* **`codice-api.postman_collection.json`**: la colección completa. Se corre **entera y en orden** (ver la sección 2): cada carpeta deja variables (tokens, ids) que usa la siguiente.
* **`desarrollo.postman_environment.json`**: entorno local (`baseUrl` = `http://localhost:8080`).
* **`produccion.postman_environment.json`**: entorno de Google Cloud (`baseUrl` = URL pública de Cloud Run).
* **`fixtures/`**: archivos de prueba para las llamadas de IA (`temario.pdf` y `apunte.png`, con contenido inventado). Los usa la carpeta PM-07 y se leen con rutas relativas a la raíz del repositorio, por eso Newman se corre desde ahí (o con `--working-dir`).

> [!NOTE]
> **Seguridad:** los entornos **no contienen secretos ni tokens reales**: los tokens y los ids empiezan vacíos y los llena la propia colección con `pm.environment.set`.

> `local.postman_environment.json` es un resto del Lab 4 (REST de Supabase): el backend ahora usa PostgreSQL con conexión directa y ese archivo ya no se usa.

---

## 2. Orden de la colección

| # | Carpeta | Issue | Qué hace |
| :-: | :--- | :--- | :--- |
| 1 | **Auth** | PM-02 | Registro (profesor y estudiante), duplicado (`409`), datos inválidos (`400`), login, contraseña incorrecta (`401`) y `/auth/me` con token, sin token y con token manipulado. Deja `tokenProfesor` y `tokenEstudiante`. |
| 2 | **Cursos y Grupos** | PM-03 | Registra al segundo profesor (`tokenProfesor2`); el profesor crea, lista, consulta y actualiza un curso, crea un grupo y repite el número (`409`). |
| 3 | **Matrículas** | PM-03 | El estudiante se une con el código (`PENDING`), repite (`409`), usa un código inexistente (`404`); el profesor lista las solicitudes, aprueba (`ACTIVE`) y no puede decidir dos veces (`409`); el estudiante ve su curso y los grupos **sin código de invitación**; el profesor regenera el código; un estudiante (`403`) y un profesor ajeno (`403`) intentan lo que no les toca. |
| 4 | **PM-04 Temario, Apuntes y Cobertura** | PM-04 | Flujo autosuficiente: registra sus propios usuarios, curso y grupo, y prueba temario, apuntes y cobertura. |
| 5 | **Casos negativos** | PM-05 | Un caso por cada status de error (`400`, `401`, `403`, `404`, `405`, `406`, `409`, `415`, `422`) y los headers (`Content-Type`, `Location`, CORS). Reutiliza los datos de PM-03 y PM-04, por eso va al final. |
| 6 | **PM-07 Seguridad JWT e IA** | PM-07 | Autosuficiente (sus propios usuarios, curso y grupo, variables `ia*`). **Seguridad JWT:** el token lleva usuario, rol y vencimiento; sin token, con el rol manipulado y vencido responden `401`; un estudiante o un profesor fuera de su rol reciben `403`. **IA con Gemini real:** extraer el temario de un PDF, transcribir una foto, auditar un apunte, generar el quiz y listar los repasos programados (`GET /repasos/programados`: `200` con las preguntas ordenadas por fecha, `403` a un profesor y `401` sin token), validando la forma del JSON; más sus casos negativos (archivo inválido `400`, ajeno `403`, sin texto confirmado `422`). Va al final y se puede correr sola con `--folder "PM-07 Seguridad JWT e IA"`. |
| 7 | **PM-08 Módulos del temario** | API-05 | Autosuficiente (sus propios usuarios, curso, grupo y apunte, variables `mod*`). **Editar:** renombrar un módulo, número ocupado `409`, título en blanco, número `0` o de más de 255 caracteres `400`, otro profesor o estudiante `403`, sin token `401`, módulo inexistente `404`. **Reordenar:** `PUT /cursos/{id}/modulos/orden` renumera 1 a n sin chocar con la unicidad; lista incompleta o con ids repetidos `422`, vacía `400`. **Eliminar:** un módulo con apuntes `422`, sin apuntes `204`, y el temario queda sin él. Se puede correr sola con `--folder "PM-08 Módulos del temario"`. |

Los status de los estados de matrícula son los de la base: `PENDING`, `ACTIVE` y `REJECTED`. El enunciado los llama `PENDIENTE`, `APROBADA` y `RECHAZADA`; el filtro `?estado=` acepta los dos juegos de nombres y la colección usa `?estado=PENDIENTE`.

---

## 3. Variables de entorno

| Variable | Quién la llena | Para qué |
| :--- | :--- | :--- |
| `baseUrl` | el entorno | URL base del servidor. |
| `run`, `run7` | Auth | Valor único de la corrida (`{{$timestamp}}` más dos dígitos). Los correos y los códigos de curso salen de aquí. |
| `emailProfesor`, `emailEstudiante`, `emailProfesor2` | Auth | Correos únicos (`...@codice.test`). |
| `tokenProfesor`, `tokenEstudiante`, `tokenProfesor2` | Auth y Cursos y Grupos | Tokens JWT de cada usuario. PM-04 vuelve a llenar `tokenProfesor`, `tokenEstudiante` y `tokenEstudiante2` con sus propios usuarios. |
| `profesorId`, `estudianteId`, `profesor2Id` | Auth | Ids de los usuarios registrados. |
| `cursoId`, `cursoCodigo`, `grupoId`, `codigoInvitacion`, `matriculaId` | PM-03 y PM-04 | Recursos del flujo. |
| `moduloId`, `subtemaId`, `subtemaId2`, `objetivoId`, `apunteId`, `apunteIdB`, `matriculaId2`, `pm4Run` | PM-04 | Recursos del temario, los apuntes y el segundo estudiante. |
| `tokenManipulado`, `codigoAnterior`, `codigoDuplicado`, `cursoVacioId` | Auth, Matrículas y Casos negativos | Auxiliares de pruebas puntuales. |
| `iaRun`, `iaEmailProfesor`, `iaEmailEstudiante`, `iaEmailEstudiante2`, `iaTokenProfesor`, `iaTokenEstudiante`, `iaTokenEstudiante2`, `iaCursoId`, `iaGrupoId`, `iaModuloId`, `iaSubtemaId`, `iaMatriculaA`, `iaMatriculaB`, `iaNotaId`, `iaNotaVaciaId`, `iaRepasoId`, `iaTokenAlterado`, `iaTokenVencido` | PM-07 | Los mismos recursos de PM-04 con prefijo `ia`, para que PM-07 no pise los datos de las otras carpetas. `iaRepasoId` queda vacío si la IA no deja ningún vacío de cobertura; las pruebas que lo usan se omiten. |

---

## 4. Correr la colección

### En Postman
1. **Import** → arrastra `codice-api.postman_collection.json` y el entorno que vayas a usar.
2. Elige el entorno arriba a la derecha (**Códice - Desarrollo (localhost)** o **Códice - Producción**).
3. Con el backend levantado (`./gradlew bootRun` para el local), menú de la colección **⋯ → Run collection → Run**. Debe terminar con 0 pruebas fallidas.

### Con Newman (línea de comandos)
```bash
# Contra el backend local
npx newman run postman/codice-api.postman_collection.json -e postman/desarrollo.postman_environment.json

# Contra Google Cloud (Cloud Run), con el reporte JSON como evidencia
npx newman run postman/codice-api.postman_collection.json -e postman/produccion.postman_environment.json \
  --reporters cli,json --reporter-json-export docs/backend/pruebas-postman/newman-frio.json
```
Solo la carpeta de seguridad e IA (PM-07), desde la raíz del repositorio y contra Google Cloud:
```bash
npx newman run postman/codice-api.postman_collection.json -e postman/produccion.postman_environment.json --folder "PM-07 Seguridad JWT e IA" --working-dir .
```
Solo la carpeta de módulos del temario (PM-08), que no usa IA:
```bash
npx newman run postman/codice-api.postman_collection.json -e postman/produccion.postman_environment.json --folder "PM-08 Módulos del temario"
```
**La colección completa contra producción y el límite de intentos.** La API frena los logins a 30 por minuto por IP y responde `429` al pasarse. Una corrida completa hace más intentos que eso en un minuto, así que hay que dejar una pausa entre requests (2200 ms alcanzan) y no hace falta tocar la configuración del servidor:
```bash
npx newman run postman/codice-api.postman_collection.json -e postman/produccion.postman_environment.json --working-dir . --delay-request 2200
```
`scripts/newman-evidencia.ps1` ya lo hace por su cuenta contra producción (parámetro `-DelayRequest`) y lo deja anotado en `resumen.md`. La pausa no cuenta en los tiempos de respuesta, pero alarga la duración total. En el Collection Runner de Postman el equivalente es el campo **Delay** (2200 ms). Contra el backend local el límite no estorba si se levanta con valores altos (`RATE_LIMIT_AUTH_PER_MINUTE`).

Las pruebas de IA llaman a Gemini de verdad: necesitan la llave configurada en el servicio (`AI_API_KEY`, ver el README principal). Sin ella esas llamadas responden `503`. Para ellas el tiempo máximo de respuesta es de 60 s (el resto de la API sigue con 10 s). Las pruebas validan la forma del JSON y lo esencial del contenido, no el texto exacto que devuelve el modelo.

Cloud Run escala a cero: el primer request tras un rato sin tráfico arranca la JVM y es lento. Por eso la evidencia se guarda con dos corridas, una en frío y una en caliente (ver `docs/backend/pruebas-postman/`).

---

## 5. Pruebas a nivel de colección

El script de la colección corre en **cada respuesta**:

1. **Content-Type** contiene `application/json` (no aplica a un `204` ni a la respuesta de un `OPTIONS`), también en los errores.
2. **Tiempo de respuesta** menor de 10 s (con holgura para el arranque en frío).
3. **Location**: todo `201` trae el header con la URL del recurso. La excepción es el registro de usuarios, porque un usuario no tiene URL propia en la API.
4. **ErrorResponse**: todo status `>= 400` trae `timestamp`, `status`, `error`, `message`, `path` y `errors`, y `status` coincide con el HTTP real.
5. **Sin datos internos**: ningún error expone trazas, nombres de clases ni excepciones.
6. **Sin contraseña**: ninguna respuesta exitosa de `/auth/` contiene `password` ni el hash.

Además, cada request tiene sus propios `pm.test` de status, header y cuerpo (esquema con `pm.response.to.have.jsonSchema`).

---

## 6. Repetibilidad y limpieza de datos

* Cada corrida usa valores nuevos: correos `...@codice.test` y códigos de curso (`PM3`, `PM4`, `PM5`, `PM6` más siete dígitos) que salen de `{{$timestamp}}`, así que se puede volver a correr sin tocar la base.
* La API no tiene endpoints para borrar usuarios ni matrículas, y un curso con estudiantes matriculados no se puede eliminar (`422`), así que los datos de prueba se acumulan. Para purgarlos hay un script SQL: `docs/backend/pruebas-postman/limpiar-datos-de-prueba.sql` (borra los usuarios `@codice.test` y los cursos que crearon; el resto se va en cascada).

---

## 7. Lo que la colección no puede probar

* **`422` por código de invitación vencido**: el servidor fija el vencimiento a 30 días y desde afuera no hay forma de forzar uno vencido sin escribir en la base. Lo cubre `MatriculaApiTest` (prueba JUnit).
* **Token vencido**: la colección usa un JWT fijo con `exp` en el pasado y firmado con otra llave, así que se prueba que el servidor lo rechace (`401`), pero no distingue «vencido» de «firma inválida»; el caso del vencido de verdad lo cubre `AuthControllerSecurityTest`.
* **Aprobar o fallar un repaso vencido**: un repaso recién generado vence mañana y no se puede aprobar antes (`422`), así que desde afuera no se puede recorrer 1, 3, 7, 14 y 30 días. El cálculo de intervalos y el refuerzo de la cobertura los cubre `ReviewServiceTest`.
* **El contenido exacto de la IA**: el modelo no responde siempre igual. Las pruebas de PM-07 comprueban la forma del JSON, los límites (confianza entre 0 y 1, estados válidos) y que se lea lo esencial de la foto, no el texto palabra por palabra.

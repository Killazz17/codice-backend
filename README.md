# Códice · Backend (API REST)

**Códice** es un asistente de estudio con inteligencia artificial para estudiantes y profesores universitarios.
Los estudiantes fotografían sus apuntes escritos a mano; la IA los transcribe, los compara con el temario del curso y
les muestra qué temas ya cubren y cuáles les faltan, con repasos de repetición espaciada. Los profesores publican el
temario (que la IA extrae de un PDF) y ven la cobertura del grupo de forma agregada y anónima.

Este repositorio es el **servidor**. La aplicación Android está en
[`codice-android`](https://github.com/Killazz17/codice-android).

![Arquitectura de Códice](docs/investigacion/figura1-arquitectura.png)

| | |
|---|---|
| **Lenguaje y framework** | Kotlin, Spring Boot 3, Spring Security (JWT con roles de profesor y estudiante) |
| **Base de datos** | PostgreSQL 16: un núcleo relacional de 15 tablas más cuatro columnas `jsonb` para la salida de la IA |
| **Migraciones** | Flyway |
| **IA** | API de Gemini: extracción de temarios desde PDF, transcripción de manuscritos, auditoría de cobertura y generación de preguntas de repaso |
| **Despliegue** | Docker multi-etapa en Google Cloud Run con Cloud SQL |
| **Documentación de la API** | OpenAPI / Swagger UI, y colección de Postman con pruebas de Newman |

### Lo más interesante técnicamente

La decisión de diseño central es un **modelo híbrido relacional + documental dentro de un solo PostgreSQL**: lo que se
consulta por clave o se restringe vive en tablas con llaves foráneas y restricciones; la salida cruda y cambiante de la
IA vive en columnas `jsonb`. Esa decisión, con un *benchmark* de índices GIN contra una tabla normalizada, está
documentada como artículo en [`docs/investigacion`](docs/investigacion/articulo-hybrid-postgres-jsonb.md).

### Contexto

Proyecto realizado en equipo para el curso **EIF 411, Diseño y Programación de Plataformas Móviles**, de la
Universidad Nacional de Costa Rica (2026), por Sebastián Benavides Madrigal, Mishelle Rojas Chacón y Johnny Corrales
Zamora. Es un proyecto académico: no tiene licencia de uso abierta y el servidor de demostración puede no estar
disponible.

Para levantarlo en tu equipo, ve a la [sección 5](#5-guía-de-ejecución-local-y-pruebas).

---

## Arquitectura del backend

El backend de **Códice** está diseñado como un sistema distribuido robusto basado en una arquitectura multicapa en Kotlin y Spring Boot 3, utilizando PostgreSQL como capa de persistencia relacional.

> [!NOTE]
> **Requisito del Entorno de Desarrollo:** El proyecto está desarrollado con Spring Boot 3.1.2 y Kotlin 1.8.22. El Gradle Wrapper requiere **JDK 17** instalado y configurado para compilar (`./gradlew build`) y ejecutar la aplicación.

---

## 1. Arquitectura en Capas

El software está organizado en capas horizontales para lograr desacoplamiento y alta mantenibilidad:

```
+-------------------------------------------------------+
|  Capa Webservice / Controllers (@RestController)       |
|  - Expone los endpoints REST para clientes HTTP/Móvil |
+-------------------------------------------------------+
                           |
                           v
+-------------------------------------------------------+
|  Capa de Servicios / Negocio (@Service)              |
|  - Coordina casos de uso, lógica y reglas de dominio  |
+-------------------------------------------------------+
                           |
                           v
+-------------------------------------------------------+
|  Capa de Persistencia / Repositorios (@Repository)    |
|  - Mapeo ORM (Spring Data JPA) y acceso a PostgreSQL   |
+-------------------------------------------------------+
```

### Convenciones de DTOs
* **Aislamiento Total**: Ningún DTO importa ni expone directamente clases del paquete `entity`.
* **Privacidad de Credenciales**: Nunca se expone `password` ni `passwordHash` en DTOs de salida/respuesta.
* **Formato de Fechas**: Todas las fechas se transfieren en formato estándar **ISO-8601 UTC** (`Instant` / `2026-10-10T12:00:00Z`).
* **Nombres de Campos**: Todos los nombres de propiedades usan estricto **`camelCase`**.
* **DTOs Transversales**:
  * `ErrorResponse`: Contiene `timestamp`, `status`, `error`, `message`, `path` y `errors: List<FieldErrorDto>` para validaciones por campo.
  * `PageResponse<T>`: Estructura estándar para respuestas paginadas (`content`, `page`, `size`, `totalElements`, `totalPages`).
* **DTOs de Auth y Usuario**: reglas de validación y equivalencia de nombres en la [sección 8](#8-autenticación-y-control-de-acceso-lab-5-dto-02-y-api-02).
* **DTOs de Cursos y Grupos**: lo que ve el profesor y lo que ve el estudiante, en la [sección 9](#9-dtos-de-cursos-y-grupos-lab-5-dto-03).


---

## 2. Programación Distribuida y Modelo Cliente-Servidor

Códice se compone de múltiples nodos e interacciones cliente-servidor en un entorno distribuido:

```
[ Cliente Móvil Android ] ──(HTTP/REST)──> [ Backend Spring Boot ] ──(JDBC/SSL)──> [ Cloud PostgreSQL ]
           |                                       |
  (Postman / Newman)                               +──(HTTPS)──> [ Proveedor de IA / LLM ]
```

* **Cliente Móvil (Android / Jetpack Compose)**: Aplicación cliente que consume servicios REST y presenta la experiencia de usuario.
* **Cliente HTTP de Pruebas (Postman / Newman)**: Permite probar directamente las reglas relacionales, restricciones y respuestas de la API de datos antes de la integración visual.
* **Servidor de Aplicación (Spring Boot)**: Nodo central distribuido que gestiona la autenticación JWT, lógica de negocio y orquestación.
* **Base de Datos Distribuida (PostgreSQL)**: Nodo de almacenamiento en la nube, protegido con SSL y gestionado con migraciones Flyway.
* **Proveedor de IA (LLM as a Service)**: Servicio externo invocado para procesamiento asíncrono de programas (syllabus), transcripciones de apuntes manuscritos, auditorías pedagógicas y baterías de repaso.

---

## 3. Modelo Entidad-Relación (Esquema v6)

El modelo relacional consta de **15 tablas relacionales en Tercera Forma Normal (3NF)** y **4 columnas estratégicas `jsonb`**:

```mermaid
erDiagram
    users ||--o{ user_role : "tiene"
    role ||--o{ user_role : "pertenece"
    role ||--o{ role_privilege : "posee"
    privilege ||--o{ role_privilege : "asignado"

    users ||--o{ student_group : "imparte (docente)"
    course ||--o{ student_group : "oferta secciones"
    student_group ||--o{ enrollment : "recibe solicitudes"
    course ||--o{ enrollment : "garantiza unicidad curso"
    users ||--o{ enrollment : "se matricula (estudiante)"

    course ||--o{ module : "contiene"
    module ||--o{ subtopic : "desglosa"
    subtopic ||--o{ subtopic_objective : "define"

    enrollment ||--o{ note : "registra"
    subtopic ||--o{ note : "asocia opcionalmente"
    note ||--o{ note_page : "contiene paginas"

    users ||--o{ coverage : "registra progreso"
    subtopic ||--o{ coverage : "mide cobertura"
    note ||--o{ coverage : "origen de cobertura"

    users ||--o{ review : "realiza repasos"
    subtopic ||--o{ review : "evalua tema"

    users {
        bigserial id PK
        string first_name
        string last_name
        string email UK
        string password
        boolean enabled
        boolean token_expired
        timestamptz create_date
    }

    role {
        bigserial id PK
        string name UK
    }

    privilege {
        bigserial id PK
        string name UK
    }

    user_role {
        bigint user_id PK_FK
        bigint role_id PK_FK
    }

    role_privilege {
        bigint role_id PK_FK
        bigint privilege_id PK_FK
    }

    course {
        bigserial id PK
        string code UK
        string name
        string color
        timestamptz created_at
        jsonb extracted_syllabus
        timestamptz syllabus_published_at
    }

    student_group {
        bigserial id PK
        bigint course_id FK
        bigint teacher_id FK
        string number
        string period
        string schedule
        string invite_code UK
        boolean requires_approval
        timestamptz invite_expires_at
    }

    enrollment {
        bigserial id PK
        bigint group_id FK
        bigint course_id FK
        bigint student_id FK
        string status
        timestamptz requested_at
        timestamptz resolved_at
    }

    module {
        bigserial id PK
        bigint course_id FK
        integer number
        string title
    }

    subtopic {
        bigserial id PK
        bigint module_id FK
        string code
        string title
        integer position
    }

    subtopic_objective {
        bigserial id PK
        bigint subtopic_id FK
        string description
    }

    note {
        bigserial id PK
        bigint enrollment_id FK
        bigint subtopic_id FK
        string client_request_id UK
        text confirmed_text
        string sync_status
        timestamptz created_at
        jsonb transcription
        jsonb audit
    }

    note_page {
        bigserial id PK
        bigint note_id FK
        integer page_index
        string file_path
    }

    coverage {
        bigserial id PK
        bigint student_id FK
        bigint subtopic_id FK
        string status
        string justification
        bigint note_origin_id FK
        timestamptz updated_at
    }

    review {
        bigserial id PK
        bigint student_id FK
        bigint subtopic_id FK
        integer interval_day
        date next_date
        string result
        timestamptz created_at
        jsonb questions
    }
```

### Explicación de las 4 Columnas `jsonb`
1. **`course.extracted_syllabus`**: Estructura cruda extraída por la IA al procesar el archivo PDF del programa del curso.
2. **`note.transcription`**: Resultado del reconocimiento y transcripción de apuntes manuscritos o audios.
3. **`note.audit`**: Diagnóstico y auditoría pedagógica generada por la IA para evaluar la completitud del apunte.
4. **`review.questions`**: Preguntas y respuestas dinámicas para la sesión de repaso espaciado del estudiante.

---

## 4. Despliegue en Google Cloud (Cloud Run + Cloud SQL)

La API corre en **Cloud Run** a partir del `Dockerfile`, y la base es **PostgreSQL 16 en Cloud SQL** con conexión directa por JDBC (sin Supabase ni otra capa intermedia). Todo el despliegue está en [`scripts/deploy-gcp.ps1`](scripts/deploy-gcp.ps1), que se puede volver a correr para publicar una versión nueva.

```
Cliente (app / Postman) ──HTTPS──▶ Cloud Run: codice-api (perfil prod)
                                        │  conector de Cloud SQL (socket con IAM, sin IP pública abierta)
                                        ▼
                                  Cloud SQL: codice-db (PostgreSQL 16)  ◀── Flyway aplica V1 (esquema) y V2 (seed)
Secret Manager: db-url, jwt-secret, ai-api-key ──▶ variables DATABASE_URL, JWT_SECRET y AI_API_KEY
```

### Requisitos (una sola vez)
1. Instalar Google Cloud CLI: `winget install Google.CloudSDK`.
2. Iniciar sesión: `gcloud auth login`.
3. Activar la facturación o el free trial (USD 300 de crédito) en https://console.cloud.google.com/freetrial.
4. Crear una llave de Gemini en https://aistudio.google.com/apikey (ver [Configuración de la IA](#configuración-de-la-ia)).

### Desplegar
```powershell
.\scripts\deploy-gcp.ps1 -ProjectId codice-backend-eif411
```
El script crea el proyecto, habilita las APIs, crea la instancia `db-f1-micro` (edición Enterprise, HDD de 10 GB, sin respaldos para gastar lo mínimo), la base y el usuario `codice`, genera los secretos, da permisos a la cuenta de servicio y despliega con `gcloud run deploy --source`. Cloud Build construye la imagen en la nube, así que no hace falta Docker en la máquina. Si el proyecto no tiene facturación, el script lista las cuentas y pide el parámetro `-BillingAccount`.

Los secretos de la base y del JWT se generan dentro del script y van directo a Secret Manager: nunca se imprimen ni quedan en el repositorio. La llave de la IA no se genera: el script la pide la primera vez en un campo oculto (o con `-UpdateAiKey`) y también la guarda en Secret Manager.

### Docker multi-etapa
El [`Dockerfile`](Dockerfile) compila con `eclipse-temurin:17-jdk-alpine` y ejecuta con `17-jre-alpine` como usuario **no root**, limitando el heap al 70 % de la RAM del contenedor. `.dockerignore` y `.gcloudignore` dejan fuera `build/`, `postman/`, `docs/` y todo lo que el `Dockerfile` no copia.

### Conexión a la base
[`ProdDatabaseConfiguration.kt`](src/main/kotlin/edu/backend/codice/ProdDatabaseConfiguration.kt) arma el `DataSource` a partir de `DATABASE_URL` (`postgres://usuario:clave@host:puerto/base`):
- **Con `CLOUD_SQL_INSTANCE`:** usa el conector de Cloud SQL (`jdbc:postgresql:///base?cloudSqlInstance=...&socketFactory=...`) e ignora el host.
- **Sin esa variable:** conecta directo al host con `sslmode=require`, así que sirve en cualquier PaaS que entregue un `DATABASE_URL`.

### Variables del servicio

| Variable | Origen | Descripción |
| :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | variable | `prod`: activa Flyway y `ProdDatabaseConfiguration`. |
| `CLOUD_SQL_INSTANCE` | variable | `proyecto:us-central1:codice-db`. |
| `DATABASE_URL` | secreto `db-url` | Usuario, clave y base. |
| `JWT_SECRET` | secreto `jwt-secret` | Llave HS512 en Base64 (64 bytes). En `prod` no hay valor por defecto: si falta, la app no arranca. |
| `AI_PROVIDER` | variable | Proveedor de IA: `gemini` (por defecto en `prod`) o `mock` (desarrollo y pruebas, por defecto en `dev`). |
| `AI_MODEL` | variable | Modelo de Gemini. Por defecto `gemini-3.5-flash`; se cambia sin tocar el código. Google retira modelos: `gemini-2.5-flash` ya responde `404` (ver el motivo en los logs: `gcloud run services logs read codice-api --region=us-central1`). |
| `AI_FALLBACK_MODEL` | variable | Modelo de respaldo (por defecto `gemini-3.5-flash-lite` en el despliegue). Si el principal agota su cuota (`429`), se retira (`404`) o está saturado (`5xx`), se prueba este. Cada modelo tiene su propia cuota. Vacío = sin respaldo. |
| `AI_API_KEY` | secreto `ai-api-key` | Llave de Google AI Studio. Sin ella los endpoints de IA responden `503` y el resto de la API sigue funcionando. |
| `RATE_LIMIT_AUTH_PER_MINUTE` | variable (opcional) | Intentos de login y registro por minuto y por IP. Por defecto `30`. Ver «Límite de intentos» en la sección 8. |
| `RATE_LIMIT_AI_PER_HOUR` | variable (opcional) | Llamadas de IA por hora y por token. Por defecto `60`. |
| `PORT` | Cloud Run | Puerto que inyecta la plataforma. |

### Configuración de la IA
La IA usa el patrón **Strategy**: `AiGateway` elige el proveedor con `AI_PROVIDER` y los servicios (transcripción, temario, auditoría y quiz) solo conocen la interfaz `AiClient`.

- **Crear la llave:** en https://aistudio.google.com/apikey. Desde mayo de 2026 las llaves nuevas empiezan con `AQ.` y se envían en el header `x-goog-api-key`, que es lo que usa el backend. Usar solo datos de prueba: el nivel gratuito puede usar lo enviado para mejorar los productos de Google.
- **Guardarla:** `.\scripts\deploy-gcp.ps1 -ProjectId codice-511601` la pide la primera vez y la guarda en Secret Manager; para cambiarla se agrega `-UpdateAiKey`. Nunca va en el código, en un `.env` versionado ni en el APK.
- **Probarla sin escribirla en ningún archivo:** en PowerShell, `$k = Read-Host "Llave" -AsSecureString`, convertirla a texto y llamar a `https://generativelanguage.googleapis.com/v1beta/models` con el header `x-goog-api-key`.
- **Cambiar de modelo:** variable `AI_MODEL`. Sin recompilar: `gcloud run services update codice-api --region=us-central1 --update-env-vars AI_MODEL=<modelo>`. Para saber qué modelo responde se prueba una llamada a `generateContent`: estar en la lista de modelos no garantiza que se sirva.
- **Agregar otro proveedor (por ejemplo Claude):** una clase `@Component` que implemente `AiClient`, con su nombre en `provider`; se activa con `AI_PROVIDER`. Ningún servicio de negocio cambia.
- **Desarrollo local:** `AI_PROVIDER=mock` devuelve respuestas simuladas sin llave.
- **Fallos:** si la IA no responde, no está configurada o devuelve algo inválido, la API responde `503` con un mensaje claro y el apunte, el curso o la cobertura quedan como estaban; nunca se devuelve texto inventado.

### Costos y operación
- **Cloud Run** escala a cero (`--min-instances=0`): sin tráfico no cobra, pero la primera petición después de un rato sin uso tarda unos segundos porque arranca la JVM.
- **Cloud SQL `db-f1-micro`** cuesta ~USD 9–10 al mes, cubiertos por el crédito del free trial (válido 90 días).
- **Ver logs:** `gcloud run services logs read codice-api --region=us-central1 --limit=100`.
- **Si Flyway falla con `permission denied for schema public`** (PostgreSQL 15+ restringe ese esquema): en Cloud SQL Studio, como `postgres`, correr una vez `GRANT ALL ON SCHEMA public TO codice;` y volver a correr el script.

---

## 5. Guía de Ejecución Local y Pruebas

### Requisitos
* **JDK 17** instalado y configurado (`java -version`).
* **Docker / Docker Compose** para levantar PostgreSQL localmente.

### Desarrollo Local con Docker Compose
```bash
# Levantar PostgreSQL 16 local
docker compose up -d

# Compilar y ejecutar pruebas unitarias/integración
./gradlew test

# Ejecutar el servidor backend con perfil 'dev'
./gradlew bootRun
```

### Verificación de Estado (`Health Check`)
El endpoint de salud de Spring Boot Actuator responde `200 OK` públicamente sin requerir autenticación:
```bash
curl http://localhost:8080/actuator/health
# Respuesta: {"status":"UP"}
```

### Pruebas de API REST con Postman y Newman CLI
```bash
# Contra la API desplegada en Google Cloud
npx newman run postman/codice-api.postman_collection.json -e postman/produccion.postman_environment.json
```

La colección (195 requests y 1023 assertions: Auth, Cursos y Grupos, Matrículas, Temario/Apuntes/Cobertura, Casos negativos, PM-07 de seguridad e IA y PM-08 de módulos del temario) se corre **entera y en orden**; sus carpetas, variables y las pruebas que corren en cada respuesta están en [`postman/README.md`](postman/README.md). La evidencia contra Google Cloud (reportes de Newman de la corrida en frío y en caliente, tabla resumen y capturas del Collection Runner) y cómo reproducirla están en [`docs/backend/pruebas-postman/`](docs/backend/pruebas-postman/README.md); el script `scripts/newman-evidencia.ps1` hace las dos corridas y genera el resumen.

---

## 6. Contrato HTTP, Status Codes y Documentación OpenAPI

### Convención de Status por Verbo HTTP
| Verbo HTTP | Status Code | Descripción / Comportamiento |
| :--- | :--- | :--- |
| **GET** | `200 OK` | Consulta exitosa de recursos o listados. |
| **POST** | `201 Created` | Creación exitosa de recursos, incluye header `Location` con la URI del recurso creado. |
| **PUT / PATCH** | `200 OK` | Actualización o modificación exitosa de recursos. |
| **DELETE** | `204 No Content` | Eliminación exitosa sin cuerpo de respuesta. |

### Contrato Uniforme de Errores (`ErrorResponse`)
Todas las respuestas de error de la API devuelven un objeto JSON estructurado con `ErrorResponse`:
```json
{
  "timestamp": "2026-10-10T17:08:17Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Violación de validación de campos",
  "path": "/api/v1/courses",
  "errors": [
    {
      "field": "code",
      "message": "El código del curso es obligatorio"
    }
  ]
}
```

### Tabla de Códigos de Estado y Excepciones
| Excepción / Escenario | Status Code | Error (`error`) |
| :--- | :--- | :--- |
| `MethodArgumentNotValidException`, `ConstraintViolationException`, JSON mal formado (`HttpMessageNotReadableException`) | `400` | `Bad Request` |
| `UnauthorizedException`, Token inválido / vencido, `BadCredentialsException` | `401` | `Unauthorized` |
| `ForbiddenException`, `AccessDeniedException` | `403` | `Forbidden` |
| `NotFoundException`, `NoSuchElementException` | `404` | `Not Found` |
| Verbo no soportado (`HttpRequestMethodNotSupportedException`) | `405` | `Method Not Allowed` |
| Accept no soportado (`HttpMediaTypeNotAcceptableException`) | `406` | `Not Acceptable` |
| `ConflictException`, `DataIntegrityViolationException` | `409` | `Conflict` |
| Content-Type no soportado (`HttpMediaTypeNotSupportedException`) | `415` | `Unsupported Media Type` |
| `BusinessRuleException` | `422` | `Unprocessable Entity` |
| Cualquier otra excepción no controlada | `500` | `Internal Server Error` (sin exponer stack trace) |

### Documentación OpenAPI & Swagger UI
* **Documentación JSON**: `/v3/api-docs` (expone la especificación OpenAPI 3.0 completa).
* **Interfaz Gráfica**: `/swagger-ui.html` / `/swagger-ui/index.html` (disponible en entorno local y PaaS).
* **Seguridad Declarada**: Esquema de seguridad **Bearer JWT** configurado globalmente para autenticar solicitudes protegidas en Swagger UI.

> [!IMPORTANT]
> **Seguridad:** Ninguna credencial ni secreto (`JWT_SECRET`, contraseñas de BD) está versionada en Git. El archivo `.env` local se encuentra protegido por `.gitignore`.

---

## 7. Endpoints de Temario, Apuntes y Cobertura (Lab 5: API-05, API-06 y API-07)

Todos requieren el token JWT (`Authorization: Bearer <token>`); sin token responden `401`. Los permisos (profesor dueño, estudiante aprobado, dueño del apunte) los decide la capa de servicio, que responde `403`.

| Verbo y ruta | Quién | Éxito |
| :--- | :--- | :--- |
| `GET /api/v1/cursos/{cursoId}/temario` | dueño, o estudiante aprobado si está publicado | `200` |
| `POST /api/v1/cursos/{cursoId}/modulos` | profesor dueño | `201` + `Location` |
| `PATCH /api/v1/modulos/{id}` | profesor dueño | `200`, solo cambia título y número enviados (`409` si el número está ocupado) |
| `PUT /api/v1/cursos/{cursoId}/modulos/orden` | profesor dueño | `200`: recibe los ids de **todos** los módulos en el orden nuevo y los renumera 1 a n en una transacción (`422` si falta, sobra o se repite alguno) |
| `DELETE /api/v1/modulos/{id}` | profesor dueño | `204`, borra también sus subtemas y objetivos (`422` si algún subtema tiene apuntes) |
| `POST /api/v1/modulos/{moduloId}/subtemas` | profesor dueño | `201` + `Location` |
| `PATCH` / `DELETE /api/v1/subtemas/{id}` | profesor dueño | `200` / `204` (`422` si tiene apuntes) |
| `POST /api/v1/subtemas/{id}/objetivos` | profesor dueño | `201` + `Location` |
| `PATCH` / `DELETE /api/v1/objetivos/{id}` | profesor dueño | `200` / `204` |
| `POST /api/v1/cursos/{cursoId}/temario/publicar` | profesor dueño | `200` (`422` sin subtemas) |
| `POST /api/v1/apuntes` | estudiante aprobado | `201` + `Location` |
| `GET /api/v1/apuntes?grupoId=&subtemaId=&q=&page=0&size=20` | estudiante | `200` (`size` de 1 a 100; fuera de rango `400`) |
| `GET` / `PATCH` / `DELETE /api/v1/apuntes/{id}` | dueño del apunte | `200` / `200` / `204` |
| `PUT /api/v1/apuntes/{apunteId}/auditoria` | dueño del apunte | `200` (**stub temporal** de la IA 3) |
| `GET /api/v1/grupos/{grupoId}/cobertura/mia` | estudiante aprobado | `200` |
| `GET /api/v1/grupos/{grupoId}/cobertura` | profesor del grupo | `200`, agregada y anónima |

**Decisiones del equipo**
- **Apunte ajeno → `403`** (no `404`): el estudiante sabe que el recurso existe pero no es suyo. Un profesor recibe `403` en cualquier apunte individual.
- **Los subtemas se crean dentro de un módulo** (`/modulos/{moduloId}/subtemas`), porque en el modelo el módulo es una tabla propia y no un texto del subtema.
- **La cobertura del grupo nunca devuelve datos por estudiante**: solo porcentajes por subtema y el total del grupo, calculados con un `GROUP BY` en la base.
- **El endpoint de auditoría es temporal**: aparece como *deprecated* en `/swagger-ui.html` y lo reemplazará la llamada de IA 3 del servidor.
- **Reordenar es una sola operación**: `PUT /cursos/{cursoId}/modulos/orden` renumera con números negativos y luego positivos, porque la base tiene `UNIQUE (course_id, number)` y un intercambio directo chocaría a mitad de camino.

### Repasos con repetición espaciada (IA-05)

Solo el estudiante dueño; un profesor recibe `403` y sin token se responde `401`.

| Verbo y ruta | Éxito |
| :--- | :--- |
| `POST /api/v1/repasos/generar` | `201`: la IA crea una pregunta por cada subtema con vacío (`PARTIAL` o `NO_NOTES`) que todavía no tenga repaso, hasta 5 por llamada. El primero toca **al día siguiente**. `503` si la IA falla. |
| `GET /api/v1/repasos/pendientes` | `200`: los repasos que vencen hoy o antes, con su pregunta y el origen del vacío. |
| `GET /api/v1/repasos/programados` | `200`: los ya generados que todavía no vencen, del más próximo al más lejano, con su pregunta. Sirve para practicarlos antes de tiempo. |
| `POST /api/v1/repasos/{id}/resultado` | `200`: `PASSED` sube al siguiente intervalo (1, 3, 7, 14, 30 días) y cierra el vacío de cobertura; `FAILED` vuelve a 1 día. `422` si el repaso todavía no vence. |

**Practicar antes de tiempo no cambia el calendario.** La app muestra los repasos de `/repasos/programados` y los deja contestar, pero no manda el resultado: el servidor solo lo acepta cuando el repaso vence. Así nadie salta intervalos sin esperar, y la cobertura que ve el profesor no se infla al contestar la pregunta recién generada.

---

## 8. Autenticación y control de acceso (Lab 5: DTO-02 y API-02)

### Endpoints de `/api/v1/auth`

| Verbo y ruta | Quién | Éxito | Errores |
| :--- | :--- | :--- | :--- |
| `POST /api/v1/auth/register` | pública | `201` con el usuario | `400` validación, `409` correo duplicado |
| `POST /api/v1/auth/login` | pública | `200` con `AuthResponse` | `400` cuerpo inválido, `401` credenciales inválidas |
| `GET /api/v1/auth/me` | cualquier usuario con token | `200` con el usuario | `401` sin token, o token inválido, vencido o manipulado |

`POST /api/v1/auth/signup` es el nombre anterior del registro. La app móvil ya lo usa, así que sigue funcionando: es la misma operación y queda oculto en Swagger. La app inicia sesión con `POST /v1/users/login` (el token viaja en el header `Authorization`); el token de ese login y el de `/api/v1/auth/login` se firman con el mismo secreto y el filtro JWT acepta los dos.

### DTOs de Auth y Usuario

Los nombres del enunciado y los del proyecto no coinciden porque la app móvil ya manda y lee estos campos; renombrarlos la rompería.

| Enunciado | En el código | Campos |
| :--- | :--- | :--- |
| `RegistroRequest` | `UserSignUpInput` (alias `UserSignUpRequest`) | `firstName`, `lastName`, `email`, `password`, `roleName` |
| `LoginRequest` | `UserLoginInput` (alias `UserLoginRequest`) | `username` (el correo), `password` |
| `UsuarioResponse` | `UserResult` (alias `UserResponse`) | `id`, `firstName`, `lastName`, `email`, `enabled`, `roles`, `createdAt` |
| `AuthResponse` | `AuthResponse` | `token`, `tokenType` (`Bearer`), `expiresAt`, `user` |

| Campo | Regla |
| :--- | :--- |
| `firstName`, `lastName` | obligatorios, de 2 a 100 caracteres |
| `email` | obligatorio, con formato de correo, hasta 255 caracteres |
| `password` | obligatoria, al menos 8 caracteres, con una letra y un número |
| `roleName` | obligatorio: `ROLE_STUDENT` o `ROLE_TEACHER` |
| login: `username`, `password` | obligatorios; `username` con formato de correo |

Los DTOs de salida nunca llevan la contraseña ni su hash, y `createdAt` y `expiresAt` van en ISO-8601 UTC. Las pruebas están en `AuthDtoTest`.

### Qué es público y qué exige token

* **Públicas**: `POST /api/v1/auth/register`, `/signup` y `/login`, `/actuator/health`, `/api-docs/**`, `/v3/api-docs/**` y `/swagger-ui/**`.
* **Todo lo demás exige `Authorization: Bearer <token>`**, incluido `GET /api/v1/auth/me`. Antes `/api/v1/auth/**` completo era público y `/me` respondía `404` sin token; ahora responde `401`.
* Sin token, o con un token vencido, manipulado, firmado con otro secreto o de un usuario que ya no existe, la respuesta es `401` con el `ErrorResponse` en JSON.
* El filtro JWT lee el usuario y sus roles de la base en cada petición, así que `@PreAuthorize` ve los roles reales y una cuenta deshabilitada deja de entrar sin esperar a que venza el token.

### Límite de intentos

`RateLimitFilter` frena el abuso antes de llegar a los controladores:

* **Login y registro**: 30 intentos por minuto por IP en `/api/v1/auth/login`, `/signup`, `/register` y `/v1/users/login`.
* **IA**: 60 llamadas por hora por token en lo que gasta cuota (extraer el temario, transcribir, auditar y generar repasos).
* Al pasarse responde `429` con el header `Retry-After` y el `ErrorResponse` habitual.
* Se ajusta con `RATE_LIMIT_AUTH_PER_MINUTE` y `RATE_LIMIT_AI_PER_HOUR`; las pruebas de Gradle usan valores altos para no chocar con él.
* **Para Newman**: una corrida completa contra producción hace más de 30 logins en un minuto. `scripts/newman-evidencia.ps1` espera 2200 ms entre requests y se queda por debajo del límite sin tocar la configuración del servidor.

### Control de acceso por rol

Las rutas que son solo de un rol llevan `@PreAuthorize`; con el rol equivocado responden `403` en JSON. Que el profesor sea el dueño de *ese* curso o grupo lo sigue decidiendo el servicio.

| Rol | Rutas |
| :--- | :--- |
| `ROLE_TEACHER` | `POST /api/v1/courses`, `POST /api/v1/groups`, `GET /api/v1/groups/{id}/enrollments`, `POST /api/v1/groups/{id}/invite-code`, `PATCH /api/v1/groups/{id}`, `GET /api/v1/enrollments/group/{groupId}`, `PUT /api/v1/enrollments/{id}/status` |
| `ROLE_STUDENT` | `POST /api/v1/enrollments`, `GET /api/v1/enrollments/my` |

Los controladores de temario, apuntes y cobertura no usan `@PreAuthorize`: ahí importa quién es el dueño y no solo el rol, y lo decide el servicio (`403`). Las pruebas de este apartado están en `AuthControllerSecurityTest`.

---

## 9. DTOs de Cursos y Grupos (Lab 5: DTO-03)

Igual que en la sección 8, los nombres del enunciado y los del código no coinciden porque la app móvil ya usa estos campos.

| Enunciado | En el código | Quién lo ve |
| :--- | :--- | :--- |
| `CursoCreateRequest(nombre, codigo, color)` | `CourseCreateDTO(name, code, color)` | entrada |
| `CursoUpdateRequest` (campos opcionales) | `CourseUpdateDTO(name?, code?, color?)` | entrada, PATCH parcial |
| `CursoResponse(..., profesorId, creadoEn)` | `CourseDTO(..., teacherId, createdAt)` | profesor dueño y estudiante matriculado |
| `CursoResumenResponse(..., totalGrupos)` | `CourseSummaryDTO(id, code, name, color, totalGroups)` | listados |
| `GrupoCreateRequest(numero, horario, requiereAprobacion)` | `GroupCreateDTO(number, period, schedule, requiresApproval)` | entrada; el curso viene en la ruta |
| `GrupoUpdateRequest(horario?, requiereAprobacion?)` | `GroupUpdateDTO(schedule?, requiresApproval?)` | entrada, PATCH parcial |
| `GrupoResponse` (con código de invitación) | `StudentGroupDTO` | solo el profesor dueño |
| `GrupoPublicoResponse(id, cursoId, numero, horario)` | `StudentGroupPublicDTO(id, courseId, number, schedule)` | estudiante, **sin código de invitación** |

`GroupCreateDTO` lleva además `period` porque la columna `student_group.period` es `NOT NULL` y no hay de dónde deducirlo. La ruta plana que usa la app, `POST /api/v1/groups`, sigue recibiendo `StudentGroupCreateDTO` (con `courseId` en el cuerpo).

| Campo | Regla |
| :--- | :--- |
| `name` | obligatorio, hasta 255 caracteres (en el PATCH, si viene, no puede estar vacío) |
| `code` | de 5 a 10 letras mayúsculas y números, con al menos una letra y un número (`EIF411`) |
| `color` | `#RRGGBB` en hexadecimal; sin valor se usa `#C8F250` |
| `number` | entero positivo de uno o dos dígitos (`1`, `01`, `12`); no `0`, `00` ni `100`, porque el número ocupa dos caracteres en el código de invitación (`EIF411-01-7K2A`) y la app solo lo reconoce así |
| `period` | obligatorio, hasta 50 caracteres |
| `schedule` | opcional, hasta 255 caracteres |

En un PATCH, un campo ausente (`null`) significa «no cambiar»: `CourseCreateDTO` y `GroupCreateDTO` exigen sus campos, pero `CourseUpdateDTO` y `GroupUpdateDTO` los aceptan todos opcionales, y un cuerpo vacío es válido y no cambia nada. Las pruebas están en `CourseGroupDtoTest`.

---

## 10. Cursos, Grupos y Matrículas (Lab 5: API-03 y API-04)

Las rutas en español son las del Lab 5. Las anteriores (`/api/v1/courses`, `/groups` y `/enrollments`) siguen funcionando porque la app móvil las usa; unas y otras llaman a los mismos servicios. Todas exigen el token JWT. El rol se exige con `@PreAuthorize` y que el profesor sea el dueño de *ese* curso, grupo o matrícula lo decide el servicio, que responde `403`. Las pruebas están en `CursoGrupoApiTest` y `MatriculaApiTest`.

### Cursos y grupos (API-03)

| Verbo y ruta | Rol | Éxito | Errores |
| :--- | :--- | :--- | :--- |
| `POST /api/v1/cursos` | profesor | `201` + `Location` (`CourseDTO`) | `400`, `401`, `403`, `409` código repetido |
| `GET /api/v1/cursos` | ambos | `200` (`CourseSummaryDTO`, con `totalGroups`) | `401` |
| `GET /api/v1/cursos/{id}` | dueño o matriculado | `200` | `401`, `403`, `404` |
| `PATCH /api/v1/cursos/{id}` | profesor dueño | `200`, solo cambia lo enviado | `400`, `401`, `403`, `404`, `409` |
| `DELETE /api/v1/cursos/{id}` | profesor dueño | `204` | `401`, `403`, `404`, `422` con estudiantes matriculados |
| `POST /api/v1/cursos/{cursoId}/grupos` | profesor dueño | `201` + `Location` | `400`, `401`, `403`, `404`, `409` número repetido |
| `GET /api/v1/cursos/{cursoId}/grupos` | dueño o matriculado | `200` | `401`, `403`, `404` |
| `PATCH /api/v1/grupos/{id}` | profesor dueño | `200`, solo cambia lo enviado | `400`, `401`, `403`, `404` |
| `POST /api/v1/grupos/{id}/codigo` | profesor dueño | `200` con el código nuevo | `401`, `403`, `404` |

* **El profesor dueño recibe el grupo completo (`StudentGroupDTO`, con `inviteCode`); el estudiante, `StudentGroupPublicDTO`**, que no tiene ningún campo con el código. Lo mismo vale para la ruta anterior `GET /api/v1/groups/course/{courseId}`, que antes le entregaba el código a cualquier estudiante matriculado.
* Los `201` llevan `Location` con la URL del recurso (`/api/v1/cursos/{id}`, `/api/v1/grupos/{id}`).
* El código de invitación lo genera el servidor (`CURSO-NN-XXXX`) y vence a los 30 días; al regenerarlo, el anterior deja de funcionar.

### Matrículas (API-04)

| Verbo y ruta | Rol | Éxito | Errores |
| :--- | :--- | :--- | :--- |
| `POST /api/v1/matriculas` (`{"inviteCode": "EIF411-01-7K2A"}`) | estudiante | `201` + `Location`, estado `PENDING` o `ACTIVE` | `400`, `401`, `403`, `404` código inexistente, `409` ya matriculado, `422` código vencido |
| `GET /api/v1/matriculas/mias` | estudiante | `200` | `401`, `403` |
| `GET /api/v1/grupos/{grupoId}/matriculas?estado=` | profesor dueño | `200` (solo nombre y correo del estudiante) | `400` estado inválido, `401`, `403`, `404` |
| `PATCH /api/v1/matriculas/{id}` (`{"decision": "APPROVE"}`) | profesor dueño | `200` | `400`, `401`, `403`, `404`, `409` ya decidida |

* La matrícula queda `PENDING` si el grupo pide aprobación y `ACTIVE` si no. `decision` es `APPROVE` (deja la matrícula `ACTIVE`) o `REJECT` (la deja `REJECTED`).
* `estado` acepta los estados de la base (`PENDING`, `ACTIVE`, `REJECTED`) y sus nombres en español (`PENDIENTE`, `APROBADA`, `RECHAZADA`), sin distinguir mayúsculas; cualquier otro valor responde `400` con el `ErrorResponse`. Las respuestas siempre usan los nombres de la base.

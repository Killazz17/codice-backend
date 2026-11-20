# Evidencia de las pruebas Postman contra Google Cloud (PM-06, PM-07 y PM-08)

Aquí queda la evidencia de que la colección de Postman pasa contra la API desplegada en Google Cloud (Cloud Run + Cloud SQL).

## Qué hay en esta carpeta

| Archivo | Qué es |
| :--- | :--- |
| `newman-frio.json` | Reporte JSON de Newman de la corrida en frío (la API había escalado a cero). |
| `newman-caliente.json` | Reporte JSON de Newman de la corrida en caliente, justo después. |
| `resumen.md` | Tabla resumen: total de requests y de assertions, aprobadas, fallidas, tiempo promedio de respuesta, lo que tardó el primer request (el arranque en frío) y el resultado por carpeta, de cada corrida. |
| `newman-final-completa.json` | Reporte de Newman de la corrida **final del Lab 6** con la colección completa (195 requests), hecha con una pausa de 2200 ms entre requests por el límite de intentos. Depurado igual que los otros. |
| `capturas/` | Capturas del resultado: las 01 a 07 son del Collection Runner de Postman del Lab 5 (PM-06), la 08 es de Newman en el Lab 6 (PM-07) y la 09, del Collection Runner en la corrida final del Lab 6. Ver la tabla de abajo. |
| `limpiar-datos-de-prueba.sql` | Script que purga los usuarios y cursos de prueba que deja cada corrida. |

> Los reportes y el resumen los genera `scripts/newman-evidencia.ps1`; no se editan a mano.

> **Los reportes están depurados.** El reporte crudo de Newman trae los tokens JWT (válidos por 10 días contra la API), las contraseñas de prueba y el cuerpo de cada respuesta, así que no se sube al repositorio. El script lo escribe en la carpeta temporal, lo pasa por `scripts/depurar-reporte-newman.js` y lo borra. Lo que queda por cada request es la carpeta, el método, la URL, el status, el tiempo y el resultado de cada aserción. Si el depurador encuentra algo que parece un secreto, no guarda el archivo.

## Resultado

Las dos corridas son contra `https://codice-api-771575174411.us-central1.run.app` (Cloud Run + Cloud SQL), el 2026-10-27, con la colección completa: **154 requests y 839 assertions** (los 110 requests y 636 assertions del Lab 5, más los 44 requests y 203 assertions de PM-07).

| Corrida | Aprobadas | Fallidas | Tiempo promedio de respuesta | Primer request |
| :--- | ---: | ---: | ---: | ---: |
| Newman, en frío | 839 de 839 | 0 | 280 ms | 1 531 ms |
| Newman, en caliente | 839 de 839 | 0 | 243 ms | 336 ms |

La corrida «en frío» no lo fue del todo: la API acababa de recibir tráfico, así que su primer request tardó 1,5 s y no las decenas de segundos del arranque real de Cloud Run (en el Lab 5 se midieron 33,9 s). La respuesta más lenta de la corrida en caliente fue de 5,1 s: una llamada a la IA.

El detalle de Newman, carpeta por carpeta, está en [`resumen.md`](resumen.md).

### Corrida final del Lab 6: 195 requests

Con todo lo del Lab 6 ya desplegado (revisión `codice-api-00014` de Cloud Run: límite de intentos, migraciones V4 y V5, módulos del temario y repasos programados), la colección completa pasa contra Google Cloud el 2026-10-27:

| Corrida | Requests | Aprobadas | Fallidas | Tiempo promedio de respuesta | Duración |
| :--- | ---: | ---: | ---: | ---: | ---: |
| Collection Runner de Postman (22:54), sin pausa | 195 | 1023 de 1023 | 0 | 151 ms | 39 s |
| Newman, con pausa de 2200 ms ([`newman-final-completa.json`](newman-final-completa.json)) | 195 | 1023 de 1023 | 0 | 204 ms | 489 s |

Son los 154 requests de antes, más los 38 de **PM-08** (módulos del temario) y 3 nuevos de PM-07 (`GET /repasos/programados`). Las dos corridas dan el mismo total de assertions (1023) y ninguna falla. La captura del Runner es [`09-postman-runner-lab6-final.png`](capturas/09-postman-runner-lab6-final.png). La duración de Newman incluye las pausas: contra producción la API acepta como máximo 30 intentos de login por minuto por IP (`429` al pasarse) y toda la colección tiene 28, así que se espacian (ver «Cómo reproducir la ejecución»).

Las dos tablas de arriba (154 requests y 839 assertions) son la primera corrida del Lab 6, antes de PM-08, y se dejan como historia.

> Contra una instancia con base H2 (por ejemplo la que levantan las pruebas con el perfil `test`) fallan 3 assertions de PM-04: las de borrar un apunte que tiene cobertura. H2 no aplica el `ON DELETE SET NULL` de `coverage.note_origin_id` que sí tiene el esquema de PostgreSQL (`V1`), así que el borrado responde `409` en vez de `204`. Contra Cloud SQL (PostgreSQL) pasan todas.

## Capturas

**Lab 5 (PM-06), 2026-10-14.** El Runner de Postman se corrió con el entorno «Códice - Producción (Google Cloud)»: 110 de 110 requests, una iteración y sin detenerse en el primer error. La primera captura es el resumen de la corrida; las demás son la vista «Grid», con las pruebas aprobadas y fallidas de cada request en el orden de la colección.

| Captura | Qué muestra |
| :--- | :--- |
| [`01-collection-runner-resumen.jpg`](capturas/01-collection-runner-resumen.jpg) | Resumen de la corrida: 636 pruebas, 0 errores, 181 ms de promedio, 43.9 s en total; 636 aprobadas y 0 fallidas. |
| [`02-requests-auth-cursos-matriculas.jpg`](capturas/02-requests-auth-cursos-matriculas.jpg) | Auth, Cursos y Grupos y el inicio de Matrículas. |
| [`03-requests-matriculas-pm04.jpg`](capturas/03-requests-matriculas-pm04.jpg) | El final de Matrículas y el inicio de PM-04. |
| [`04-requests-pm04-temario-apuntes-cobertura.jpg`](capturas/04-requests-pm04-temario-apuntes-cobertura.jpg) | PM-04: temario, apuntes y cobertura. |
| [`05-requests-casos-negativos-1.jpg`](capturas/05-requests-casos-negativos-1.jpg), [`06`](capturas/06-requests-casos-negativos-2.jpg), [`07`](capturas/07-requests-casos-negativos-3.jpg) | Casos negativos: validación, autenticación, permisos, recursos inexistentes, conflictos, verbos y formatos no soportados, y CORS. |
| [`08-newman-pm07-produccion.png`](capturas/08-newman-pm07-produccion.png) | **Lab 6 (PM-07), 2026-10-27.** Resultado final de `scripts/newman-evidencia.ps1` contra Google Cloud: las dos corridas con 154 requests, 839 assertions aprobadas y 0 fallidas. |
| [`09-postman-runner-lab6-final.png`](capturas/09-postman-runner-lab6-final.png) | **Lab 6, corrida final, 2026-10-27.** Collection Runner de Postman con la colección completa de 195 requests: 1023 pruebas aprobadas, 0 fallidas, 0 errores, 151 ms de promedio y 39 s en total. |

## PM-07: seguridad JWT e IA (Lab 6)

La carpeta **PM-07 Seguridad JWT e IA** (47 requests y 218 assertions desde que se agregaron las del endpoint de repasos programados; antes 44 y 203) pasó sin fallos en todas las corridas, con la IA real (Gemini) respondiendo desde Cloud Run:

| Parte | Qué comprueba |
| :--- | :--- |
| Seguridad JWT | El token lleva usuario, rol y vencimiento; sin token, con el rol manipulado y vencido responden `401`; un estudiante o un profesor fuera de su rol reciben `403`. |
| IA con Gemini | Extraer el temario de un PDF, transcribir una foto (la IA lee «AVL»), transcribir sobre un apunte sin confirmar su texto, auditar el apunte, ver la cobertura reflejada, generar el quiz con su pregunta y su origen, y ver los repasos programados que todavía no vencen (`GET /repasos/programados`: `200`, `403` a un profesor y `401` sin token). |
| Casos negativos de IA | Archivo que no es PDF o no es foto (`400`), apunte o repaso ajeno (`403`), apunte sin texto confirmado (`422`), repaso que todavía no vence (`422`) y resultado inválido (`400`). |

La IA en la nube usa el modelo `gemini-3.5-flash` y, de respaldo, `gemini-3.5-flash-lite` (variables `AI_MODEL` y `AI_FALLBACK_MODEL`). Las llamadas de IA tardaron entre 3 y 6 s; la colección les da hasta 60 s.

**Lo que apareció al correrla por primera vez contra la nube, y cómo se resolvió:**
- `404`: `gemini-2.5-flash` ya no se sirve → se cambió el modelo por defecto y se agregó el motivo de Google al log.
- `503` de Google por saturación del modelo → reintentos con espera creciente (hasta 3 intentos).
- `429` por cuota gratuita agotada → modelo de respaldo, que tiene su propia cuota.

Para correr solo esta carpeta (necesita la llave de IA en el servicio):

```bash
npx newman run postman/codice-api.postman_collection.json -e postman/produccion.postman_environment.json --folder "PM-07 Seguridad JWT e IA" --working-dir .
```

## PM-08: módulos del temario (Lab 6)

La carpeta **PM-08 Módulos del temario** (38 requests, 169 assertions) prueba lo que pidió el equipo de la app para editar el temario, sin IA: renombrar un módulo (`PATCH /modulos/{id}`), reordenarlos todos de una vez (`PUT /cursos/{id}/modulos/orden`, que renumera 1 a n sin chocar con la unicidad) y eliminarlos (`DELETE /modulos/{id}`, `422` si algún subtema tiene apuntes), más sus casos negativos (`409`, `400`, `403`, `401`, `404` y `422`). Pasó sin fallos contra Cloud Run, sola y dentro de la colección completa:

```bash
npx newman run postman/codice-api.postman_collection.json -e postman/produccion.postman_environment.json --folder "PM-08 Módulos del temario"
```

## Cómo reproducir la ejecución

Requisito: Node.js. Newman se descarga solo la primera vez (`npx newman`).

Contra producción la API acepta como máximo 30 intentos de login por minuto por IP. Por eso el script espera 2200 ms entre requests (`-DelayRequest`); en el Collection Runner de Postman basta un **Delay** de 500 ms, o correr la colección en dos tandas (las cinco primeras carpetas y, un minuto después, PM-07 y PM-08).

```powershell
# Contra Google Cloud: dos corridas seguidas (frío y caliente) y el resumen
.\scripts\newman-evidencia.ps1

# La misma colección contra un backend local (./gradlew bootRun)
.\scripts\newman-evidencia.ps1 -Entorno desarrollo -BaseUrl http://localhost:8080
```

O una sola corrida, a mano:

```bash
npx newman run postman/codice-api.postman_collection.json -e postman/produccion.postman_environment.json \
  --reporters cli,json --reporter-json-export docs/backend/pruebas-postman/newman-frio.json
```

Para que la primera corrida sea realmente **en frío**, espera unos 15 minutos sin tráfico: Cloud Run escala a cero y el primer request arranca la JVM (decenas de segundos). El primer request de cada corrida admite hasta 60 s; el resto debe responder en menos de 10 s.

## Repetibilidad

Cada corrida usa correos `@codice.test` y códigos de curso nuevos (salen de `{{$timestamp}}`), así que se puede repetir sin tocar la base. Los datos de prueba se acumulan (la API no borra usuarios ni matrículas); `limpiar-datos-de-prueba.sql` los purga cuando se quiera.

## Qué valida la colección

Ver [`postman/README.md`](../../../postman/README.md): orden de las carpetas, variables, las pruebas que corren en todas las respuestas (`Content-Type`, `Location`, esquema de `ErrorResponse`, sin trazas, sin contraseña) y lo que no se puede probar desde afuera.

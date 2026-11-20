'use strict';
/*
 * Depura el reporte JSON de Newman antes de guardarlo como evidencia (PM-06).
 *
 * El reporte crudo trae los tokens JWT (en los headers, en las respuestas del login y en las variables del entorno), las
 * contraseñas de prueba y el cuerpo de cada respuesta: no debe subirse al repositorio. Aquí solo se conserva lo que
 * demuestra el resultado: totales, tiempos, fallos y, por cada request, su carpeta, método, URL, status, tiempo y aserciones.
 *
 * Uso: node scripts/depurar-reporte-newman.js <reporte-crudo.json> <coleccion.json> <salida.json> <entorno> <baseUrl>
 */
const fs = require('fs');

const [crudo, coleccion, salida, entorno, baseUrl] = process.argv.slice(2);
if (!crudo || !coleccion || !salida) {
  console.error('Uso: node depurar-reporte-newman.js <reporte-crudo.json> <coleccion.json> <salida.json> <entorno> <baseUrl>');
  process.exit(2);
}

const run = JSON.parse(fs.readFileSync(crudo, 'utf8')).run;
const coll = JSON.parse(fs.readFileSync(coleccion, 'utf8'));

// Aplana la colección en el orden en que Newman ejecuta (profundidad primero) para saber la carpeta de cada request.
const planas = [];
(function recorrer(items, ruta) {
  for (const it of items) {
    if (it.item) recorrer(it.item, ruta.concat(it.name));
    else planas.push({ nombre: it.name, carpeta: ruta.join(' / ') });
  }
})(coll.item, []);
if (planas.length !== run.executions.length) {
  throw new Error('La colección tiene ' + planas.length + ' requests y el reporte ' + run.executions.length + ': no son la misma versión.');
}

// Último filtro por si algún mensaje de error arrastra un secreto.
const limpiar = (texto) => String(texto)
  .replace(/eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*/g, '<jwt>')
  .replace(/Bearer\s+[A-Za-z0-9._~+\/-]{30,}=*/g, 'Bearer <token>') // 30+ caracteres: la palabra "Bearer" en un nombre de prueba no cuenta
  .replace(/\$2[aby]\$[0-9]{2}\$[A-Za-z0-9.\/]{20,}/g, '<hash>')
  .replace(/Password123/g, '<contraseña>');

const urlDe = (u) => {
  if (!u) return '';
  const host = Array.isArray(u.host) ? u.host.join('.') : u.host;
  const ruta = Array.isArray(u.path) ? u.path.join('/') : (u.path || '');
  const consulta = u.query && u.query.length ? '?' + u.query.map((q) => q.key + '=' + q.value).join('&') : '';
  return (u.protocol ? u.protocol + '://' : '') + host + (u.port ? ':' + u.port : '') + '/' + ruta + consulta;
};

const ejecuciones = run.executions.map((ex, i) => ({
  carpeta: planas[i].carpeta,
  request: planas[i].nombre,
  metodo: ex.request.method,
  url: limpiar(urlDe(ex.request.url)),
  status: ex.response ? ex.response.code : null,
  tiempoMs: ex.response ? ex.response.responseTime : null,
  aserciones: (ex.assertions || []).map((a) => ({ nombre: limpiar(a.assertion), ok: !a.error, ...(a.error ? { error: limpiar(a.error.message) } : {}) })),
}));

const fallidas = ejecuciones.reduce((n, e) => n + e.aserciones.filter((a) => !a.ok).length, 0);
const totales = ejecuciones.reduce((n, e) => n + e.aserciones.length, 0);

const depurado = {
  coleccion: coll.info.name,
  entorno: entorno || '',
  baseUrl: baseUrl || '',
  generado: new Date(run.timings.completed).toISOString(),
  totales: {
    requests: run.stats.requests.total,
    requests_fallidos: run.stats.requests.failed,
    aserciones: totales,
    aserciones_fallidas: fallidas,
    scripts_de_prueba: run.stats.testScripts.total,
  },
  tiempos: {
    respuestaPromedioMs: Math.round(run.timings.responseAverage),
    respuestaMinimaMs: run.timings.responseMin,
    respuestaMaximaMs: run.timings.responseMax,
    primeraRespuestaMs: ejecuciones[0] ? ejecuciones[0].tiempoMs : null,
    duracionSegundos: Math.round((run.timings.completed - run.timings.started) / 100) / 10,
  },
  fallos: run.failures.map((f) => ({
    request: f.source && f.source.name,
    prueba: f.error && f.error.test,
    mensaje: limpiar((f.error && f.error.message) || ''),
  })),
  ejecuciones,
};

fs.writeFileSync(salida, JSON.stringify(depurado, null, 2) + '\n', 'utf8');

// Verificación final: si todavía parece haber un secreto, no se deja el archivo.
const texto = fs.readFileSync(salida, 'utf8');
if (/eyJ[A-Za-z0-9_-]{5,}\.|Bearer\s+[A-Za-z0-9._~+\/-]{30,}|\$2[aby]\$|Password123/.test(texto)) {
  fs.unlinkSync(salida);
  throw new Error('El reporte depurado todavía contiene algo que parece un secreto; no se guardó.');
}
console.log('Reporte depurado: ' + salida + ' (' + ejecuciones.length + ' requests, ' + totales + ' aserciones, ' + fallidas + ' fallidas)');

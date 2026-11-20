<#
.SYNOPSIS
  Carga datos de ejemplo para las cuentas de CUENTAS-PRUEBA-EQUIPO.txt: un curso con temario, matrículas, apuntes y cobertura.

.DESCRIPTION
  Entra como el profesor y crea el curso (con su temario publicado y dos grupos), luego entra como cada estudiante, lo
  matricula en el grupo 01 y le crea apuntes con su cobertura. La contraseña se lee del .txt que dejó crear-cuentas-prueba.ps1:
  no se escribe en ningún otro lado. Al final agrega al .txt la sección "DATOS DE EJEMPLO" con los códigos para unirse.
  Se puede correr varias veces: lo que ya existe se reutiliza (los apuntes llevan clientRequestId fijo).
  La cobertura se registra con PUT /apuntes/{id}/auditoria (la auditoría simulada, no gasta IA). Si ese endpoint se retira,
  hay que cambiar ese paso por POST /apuntes/{id}/auditar.
  Gasta 6 logins (límite de 30 por minuto por IP).

.EXAMPLE
  .\scripts\cargar-datos-demo.ps1
  .\scripts\cargar-datos-demo.ps1 -Codigo EST202 -Prefijo demo
#>
param(
    [string] $BaseUrl = "https://codice-api-771575174411.us-central1.run.app",
    [string] $Prefijo = "equipo",
    [string] $Codigo = "EQP101",
    [string] $Archivo = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Archivo) { $Archivo = Join-Path $root "CUENTAS-PRUEBA-EQUIPO.txt" }
$BaseUrl = $BaseUrl.TrimEnd("/")
$marca = "DATOS DE EJEMPLO"

if (-not (Test-Path $Archivo)) { throw "No existe $Archivo. Corre primero scripts/crear-cuentas-prueba.ps1" }
$texto = Get-Content -Path $Archivo -Encoding UTF8
$linea = $texto | Where-Object { $_ -like "Contraseña de todas las cuentas:*" } | Select-Object -First 1
if (-not $linea) { throw "No encontré la línea de la contraseña en $Archivo" }
$clave = ($linea -split ":", 2)[1].Trim()

# Llama a la API y devuelve @{ Status; Body }. Un error HTTP no lanza excepción: el que llama decide qué hacer con el código.
function Api($metodo, $ruta, $cuerpo = $null, $token = $null) {
    $p = @{ Uri = "$BaseUrl$ruta"; Method = $metodo; UseBasicParsing = $true }
    if ($token) { $p.Headers = @{ Authorization = "Bearer $token" } }
    if ($null -ne $cuerpo) {
        $p.ContentType = "application/json; charset=utf-8"
        $p.Body = [System.Text.Encoding]::UTF8.GetBytes(($cuerpo | ConvertTo-Json -Depth 6))
    }
    try {
        $r = Invoke-WebRequest @p
        $status = [int]$r.StatusCode
        $bytes = $r.RawContentStream.ToArray()
    } catch {
        if (-not $_.Exception.Response) { throw }
        $status = [int]$_.Exception.Response.StatusCode
        $ms = New-Object System.IO.MemoryStream
        $_.Exception.Response.GetResponseStream().CopyTo($ms)
        $bytes = $ms.ToArray()
    }
    $json = [System.Text.Encoding]::UTF8.GetString($bytes)
    $body = if ($json) { try { $json | ConvertFrom-Json } catch { $json } } else { $null }
    return @{ Status = $status; Body = $body }
}

function Exigir($r, $esperados, $que) {
    if ($esperados -notcontains $r.Status) {
        $detalle = if ($r.Body.message) { $r.Body.message } else { $r.Body }
        throw "$que falló ($($r.Status)): $detalle"
    }
    $r.Body
}

function Entrar($correo) {
    $r = Api "POST" "/api/v1/auth/login" @{ username = $correo; password = $clave }
    (Exigir $r @(200) "Login de $correo").token
}

# ---------- Profesor: curso, grupos y temario ----------
Write-Host "Profesor: curso $Codigo" -ForegroundColor Cyan
$tProfe = Entrar "$Prefijo.profe@codice.test"

$r = Api "POST" "/api/v1/cursos" @{ code = $Codigo; name = "Estructuras de Datos (demo del equipo)"; color = "#C8F250" } $tProfe
if ($r.Status -eq 409) {
    $curso = (Exigir (Api "GET" "/api/v1/cursos" $null $tProfe) @(200) "Listar cursos") | Where-Object { $_.code -eq $Codigo } | Select-Object -First 1
    if (-not $curso) { throw "El código $Codigo ya existe y es de otro profesor. Usa -Codigo con otro valor." }
} else {
    $curso = Exigir $r @(201) "Crear curso"
}
$cursoId = $curso.id

# Grupo 01 (entra sin aprobación, para los estudiantes de esta carga) y grupo 02 (pide aprobación, para la demo de unirse).
function Grupo($numero, $horario, $aprobacion) {
    $r = Api "POST" "/api/v1/cursos/$cursoId/grupos" @{ number = $numero; period = "2026-II"; schedule = $horario; requiresApproval = $aprobacion } $tProfe
    if ($r.Status -eq 201) { return $r.Body }
    Exigir $r @(409) "Crear grupo $numero" | Out-Null
    $grupos = Exigir (Api "GET" "/api/v1/cursos/$cursoId/grupos" $null $tProfe) @(200) "Listar grupos"
    $grupos | Where-Object { $_.number -eq $numero } | Select-Object -First 1
}
$grupo1 = Grupo "01" "Lunes y miércoles 08:00-09:30" $false
$grupo2 = Grupo "02" "Martes y jueves 13:00-14:30" $true

$temario = Exigir (Api "GET" "/api/v1/cursos/$cursoId/temario" $null $tProfe) @(200) "Leer temario"
if (-not $temario.modules -or $temario.modules.Count -eq 0) {
    $plan = @(
        @{ n = 1; t = "Fundamentos"; subs = @(
            @{ c = "1.1"; t = "Variables y tipos"; o = @("Declarar variables y elegir su tipo", "Explicar la diferencia entre valor y referencia") },
            @{ c = "1.2"; t = "Funciones"; o = @("Definir funciones con parámetros y retorno", "Distinguir el ámbito local del global") },
            @{ c = "1.3"; t = "Recursión"; o = @("Identificar el caso base y el caso recursivo", "Trazar una llamada recursiva paso a paso") }) },
        @{ n = 2; t = "Estructuras lineales"; subs = @(
            @{ c = "2.1"; t = "Listas enlazadas"; o = @("Insertar y eliminar nodos en una lista", "Comparar una lista con un arreglo") },
            @{ c = "2.2"; t = "Pilas y colas"; o = @("Aplicar LIFO y FIFO a un problema", "Implementar una pila con una lista") }) },
        @{ n = 3; t = "Árboles"; subs = @(
            @{ c = "3.1"; t = "Árboles binarios de búsqueda"; o = @("Insertar y buscar en un árbol binario de búsqueda", "Calcular la altura de un árbol") },
            @{ c = "3.2"; t = "Recorridos"; o = @("Recorrer en preorden, inorden y postorden", "Elegir el recorrido según el problema") }) }
    )
    foreach ($m in $plan) {
        $mod = Exigir (Api "POST" "/api/v1/cursos/$cursoId/modulos" @{ number = $m.n; title = $m.t } $tProfe) @(201) "Crear módulo $($m.n)"
        $pos = 1
        foreach ($s in $m.subs) {
            $sub = Exigir (Api "POST" "/api/v1/modulos/$($mod.id)/subtemas" @{ code = $s.c; title = $s.t; position = $pos } $tProfe) @(201) "Crear subtema $($s.c)"
            foreach ($d in $s.o) {
                Exigir (Api "POST" "/api/v1/subtemas/$($sub.id)/objetivos" @{ description = $d } $tProfe) @(201) "Agregar objetivo de $($s.c)" | Out-Null
            }
            $pos++
        }
    }
    Exigir (Api "POST" "/api/v1/cursos/$cursoId/temario/publicar" $null $tProfe) @(200) "Publicar temario" | Out-Null
    $temario = Exigir (Api "GET" "/api/v1/cursos/$cursoId/temario" $null $tProfe) @(200) "Releer temario"
}
$subtema = @{}
foreach ($m in $temario.modules) { foreach ($s in $m.subtopics) { $subtema[$s.code] = $s.id } }
Write-Host "  temario publicado: $($subtema.Count) subtemas" -ForegroundColor Green

# ---------- Estudiantes: matrícula, apuntes y cobertura ----------
# Cada estudiante cubre una parte distinta del temario, así el mapa de cobertura del profesor muestra porcentajes variados.
$apuntes = @{
    "est1" = @(
        @{ s = "1.1"; v = "COVERED"; t = "Variables y tipos: una variable guarda un valor y tiene un tipo (Int, Double, String, Boolean). En Kotlin val no cambia y var sí. Los tipos primitivos se copian por valor; los objetos se pasan por referencia." },
        @{ s = "1.2"; v = "COVERED"; t = "Funciones: fun suma(a: Int, b: Int): Int { return a + b }. Reciben parámetros y devuelven un valor. Lo que se declara dentro de la función solo existe ahí (ámbito local)." },
        @{ s = "2.1"; v = "PARTIAL"; t = "Lista enlazada: cada nodo guarda el dato y la referencia al siguiente. Insertar al inicio es rápido. Me falta repasar cómo se elimina un nodo del medio." }
    )
    "est2" = @(
        @{ s = "1.1"; v = "COVERED"; t = "Tipos de datos: enteros, decimales, texto y booleanos. Una referencia apunta a un objeto en el heap; un valor primitivo se copia al asignarlo." },
        @{ s = "2.2"; v = "COVERED"; t = "Pila (LIFO): push y pop sobre el tope, sirve para deshacer. Cola (FIFO): enqueue al final y dequeue al inicio, sirve para turnos. Una pila se puede armar con una lista enlazada." },
        @{ s = "3.1"; v = "PARTIAL"; t = "Árbol binario de búsqueda: los menores van a la izquierda y los mayores a la derecha. Buscar es comparar con la raíz y bajar. Pendiente: calcular la altura." }
    )
    "est3" = @(
        @{ s = "1.2"; v = "PARTIAL"; t = "Funciones: parámetros y valor de retorno. Falta anotar el ámbito de las variables." },
        @{ s = "3.1"; v = "COVERED"; t = "ABB: insertar compara con cada nodo hasta hallar un hueco. La altura es 1 + el máximo de las alturas de los hijos; un árbol vacío tiene altura 0." },
        @{ s = "3.2"; v = "COVERED"; t = "Recorridos: preorden (raíz, izq, der), inorden (izq, raíz, der; da los datos ordenados en un ABB) y postorden (izq, der, raíz; sirve para borrar el árbol)." }
    )
    "est4" = @(
        @{ s = "1.1"; v = "PARTIAL"; t = "Variables: val y var. Los tipos básicos son Int, Double, String y Boolean." },
        @{ s = "1.3"; v = "COVERED"; t = "Recursión: una función que se llama a sí misma. Siempre necesita un caso base que detenga las llamadas, por ejemplo factorial(0) = 1, y un caso recursivo, factorial(n) = n * factorial(n - 1)." }
    )
    "est5" = @(
        @{ s = "2.1"; v = "PARTIAL"; t = "Listas enlazadas vs arreglos: la lista inserta en medio sin mover elementos, pero acceder por índice cuesta recorrerla." }
    )
}

$resumen = @()
foreach ($est in ($apuntes.Keys | Sort-Object)) {
    $correo = "$Prefijo.$est@codice.test"
    Write-Host "Estudiante: $correo" -ForegroundColor Cyan
    $t = Entrar $correo

    $r = Api "POST" "/api/v1/matriculas" @{ inviteCode = $grupo1.inviteCode } $t
    if ($r.Status -eq 201) { $mat = $r.Body }
    else {
        Exigir $r @(409) "Matricular a $correo" | Out-Null
        $mat = (Exigir (Api "GET" "/api/v1/matriculas/mias" $null $t) @(200) "Mis matrículas") | Where-Object { $_.groupId -eq $grupo1.id } | Select-Object -First 1
    }
    if ($mat.status -ne "ACTIVE") { throw "La matrícula de $correo quedó en $($mat.status), no ACTIVE" }

    $i = 0
    foreach ($a in $apuntes[$est]) {
        $i++
        $nota = Exigir (Api "POST" "/api/v1/apuntes" @{
            enrollmentId = $mat.id; subtopicId = $subtema[$a.s]; clientRequestId = "demo-$Codigo-$est-$i"; confirmedText = $a.t
        } $t) @(200, 201) "Crear apunte $i de $correo"
        Exigir (Api "PUT" "/api/v1/apuntes/$($nota.id)/auditoria" @{
            verdicts = @(@{ subtopicId = $subtema[$a.s]; status = $a.v; evidence = "Apunte de demo sobre $($a.s)" })
        } $t) @(200) "Registrar cobertura del apunte $i de $correo" | Out-Null
    }
    Write-Host "  matriculado y $($apuntes[$est].Count) apuntes" -ForegroundColor Green
    $resumen += ("{0,-30} {1} apuntes" -f $correo, $apuntes[$est].Count)
}

# ---------- Resumen en el .txt (se reemplaza la sección si ya existía) ----------
$base = @()
foreach ($l in $texto) { if ($l -like "$marca*") { break }; $base += $l }
while ($base.Count -gt 0 -and [string]::IsNullOrWhiteSpace($base[-1])) { $base = $base[0..($base.Count - 2)] }
$seccion = @(
    "",
    "",
    "$marca (ya cargados en la nube)",
    "",
    "Curso: $($curso.name)  [$Codigo]",
    "Temario publicado: 3 módulos y 7 subtemas.",
    "Grupo 01 (los 5 estudiantes ya están matriculados): $($grupo1.inviteCode)",
    "Grupo 02 (pide aprobación del profesor; sirve para probar Unirse a un curso): $($grupo2.inviteCode)",
    "",
    "Apuntes por estudiante (cada uno cubre una parte distinta del temario):"
) + $resumen + @(
    "",
    "El profesor ve el mapa de cobertura del grupo 01 con porcentajes distintos por subtema.",
    "Para probar el unirse con un estudiante nuevo, usa el código del grupo 02 y apruébalo desde la cuenta del profesor."
)
Set-Content -Path $Archivo -Value ($base + $seccion) -Encoding UTF8
Write-Host ""
Write-Host "Listo. Resumen agregado a $Archivo" -ForegroundColor Cyan

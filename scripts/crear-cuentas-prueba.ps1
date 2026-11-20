<#
.SYNOPSIS
  Registra en la API unas cuentas de prueba para el equipo y las deja en un .txt para repartir.

.DESCRIPTION
  Crea 1 profesor y N estudiantes (@codice.test) con POST /api/v1/auth/register, comprueba cada una con un login real y
  escribe CUENTAS-PRUEBA-EQUIPO.txt con los correos y la contraseña. Las contraseñas se generan al azar en cada corrida
  (nunca viven en el repo) y el .txt está excluido de git (.git/info/exclude).
  Si un correo ya existe (409) se avisa y no se anota: no se puede saber su contraseña.
  Gasta 2 llamadas de auth por cuenta; el límite es de 30 por minuto por IP, así que con 5 estudiantes sobra.

.EXAMPLE
  .\scripts\crear-cuentas-prueba.ps1
  .\scripts\crear-cuentas-prueba.ps1 -Estudiantes 8 -Prefijo demo
  .\scripts\crear-cuentas-prueba.ps1 -BaseUrl http://localhost:8080
#>
param(
    [string] $BaseUrl = "https://codice-api-771575174411.us-central1.run.app",
    [ValidateRange(1, 10)] [int] $Estudiantes = 5,
    [string] $Prefijo = "equipo",
    [string] $Salida = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $Salida) { $Salida = Join-Path $root "CUENTAS-PRUEBA-EQUIPO.txt" }
$BaseUrl = $BaseUrl.TrimEnd("/")

# Sin 0/O ni 1/l/I para que nadie se equivoque al copiarla. Cumple la regla del servidor: 8+ con letra y número.
function Nueva-Contrasena {
    $letras = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    $numeros = "23456789"
    $todos = $letras + $numeros
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 12
    $rng.GetBytes($bytes)
    $chars = for ($i = 0; $i -lt 10; $i++) { $todos[$bytes[$i] % $todos.Length] }
    $chars += $letras[$bytes[10] % $letras.Length]
    $chars += $numeros[$bytes[11] % $numeros.Length]
    -join $chars
}

function Invocar($ruta, $cuerpo) {
    try {
        $r = Invoke-WebRequest -Uri "$BaseUrl$ruta" -Method Post -ContentType "application/json; charset=utf-8" `
            -Body ([System.Text.Encoding]::UTF8.GetBytes(($cuerpo | ConvertTo-Json))) -UseBasicParsing
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw
    }
}

$clave = Nueva-Contrasena
$cuentas = @(
    @{ Rol = "ROLE_TEACHER"; Etiqueta = "Profesor"; Correo = "$Prefijo.profe@codice.test"; Nombre = "Profe"; Apellido = "Prueba" }
)
1..$Estudiantes | ForEach-Object {
    $cuentas += @{ Rol = "ROLE_STUDENT"; Etiqueta = "Estudiante"; Correo = "$Prefijo.est$_@codice.test"; Nombre = "Estudiante$_"; Apellido = "Prueba" }
}

$listas = @()
foreach ($c in $cuentas) {
    $alta = Invocar "/api/v1/auth/register" @{
        firstName = $c.Nombre; lastName = $c.Apellido; email = $c.Correo; password = $clave; roleName = $c.Rol
    }
    if ($alta -eq 409) { Write-Host "YA EXISTE  $($c.Correo) (otra contraseña: no se anota; cambia -Prefijo)" -ForegroundColor Yellow; continue }
    if ($alta -ne 201) { Write-Host "FALLÓ ($alta)  $($c.Correo)" -ForegroundColor Red; continue }

    $login = Invocar "/api/v1/auth/login" @{ username = $c.Correo; password = $clave }
    if ($login -ne 200) { Write-Host "CREADA pero el login dio $login  $($c.Correo)" -ForegroundColor Red; continue }
    Write-Host "OK         $($c.Correo)" -ForegroundColor Green
    $listas += $c
}

if ($listas.Count -eq 0) { Write-Host "No quedó ninguna cuenta utilizable." -ForegroundColor Red; exit 1 }

$lineas = @(
    "CÓDICE - CUENTAS DE PRUEBA",
    "",
    "Solo para probar la app. No uses estas contraseñas en otros servicios.",
    "API: $BaseUrl",
    "Contraseña de todas las cuentas: $clave",
    "",
    "Cómo entrar: abre la app, pulsa Iniciar sesión y escribe el correo y la contraseña.",
    ""
)
foreach ($c in $listas) { $lineas += ("{0,-11} {1}" -f $c.Etiqueta, $c.Correo) }
$lineas += @(
    "",
    "Qué probar:",
    "- Profesor: crear un curso, subir el temario en PDF (la IA lo extrae), revisar, editar, reordenar y publicar.",
    "- Estudiante: unirse a un curso con el código que da el profesor, capturar un apunte y ver el mapa de cobertura.",
    "",
    "Si algo falla, anota la pantalla, qué tocaste y la hora, y mándalo al grupo."
)
Set-Content -Path $Salida -Value $lineas -Encoding UTF8
Write-Host ""
Write-Host "Listo: $($listas.Count) cuentas. Archivo: $Salida" -ForegroundColor Cyan

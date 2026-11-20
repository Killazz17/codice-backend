<#
.SYNOPSIS
  Corre la colección de Postman con Newman dos veces (en frío y en caliente) y guarda la evidencia de PM-06.

.DESCRIPTION
  Por cada corrida deja en docs/backend/pruebas-postman/ un reporte JSON DEPURADO (newman-frio.json y newman-caliente.json) y
  al final escribe resumen.md con el total de requests y de aserciones, las aprobadas, las fallidas, el tiempo promedio de
  respuesta, lo que tardó el primer request (el arranque en frío de Cloud Run) y el resultado por carpeta.
  El reporte crudo de Newman trae los tokens JWT y las contraseñas de prueba: se escribe en la carpeta temporal, se depura con
  scripts/depurar-reporte-newman.js y se borra, así que nunca llega al repositorio.
  Cloud Run escala a cero: la corrida "frio" solo es fría si la API estuvo unos 15 minutos sin tráfico.
  Requisito: Node.js (usa `npx newman`, que se descarga la primera vez).
  Termina con código 1 si alguna corrida tuvo aserciones fallidas.
  Contra producción la API limita los intentos de login a 30 por minuto por IP (RateLimitFilter). Una corrida completa hace
  más de 30 en un minuto, así que el script espera 2200 ms entre requests (-DelayRequest) y se queda por debajo del límite
  sin tocar la configuración del servidor. La pausa no cuenta en los tiempos de respuesta, pero sí alarga la duración total.

.EXAMPLE
  .\scripts\newman-evidencia.ps1
  .\scripts\newman-evidencia.ps1 -Entorno desarrollo -BaseUrl http://localhost:8080
#>
param(
    [ValidateSet("produccion", "desarrollo")] [string] $Entorno = "produccion",
    [string] $BaseUrl = "",
    [string] $Salida = "docs/backend/pruebas-postman",
    # Milisegundos de pausa entre requests. -1 (por defecto): 2200 en producción y ninguna en desarrollo.
    [int] $DelayRequest = -1
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
$coleccion = Join-Path $root "postman/codice-api.postman_collection.json"
$archivoEntorno = Join-Path $root "postman/$Entorno.postman_environment.json"
$depurador = Join-Path $PSScriptRoot "depurar-reporte-newman.js"
$carpeta = if ([System.IO.Path]::IsPathRooted($Salida)) { $Salida } else { Join-Path $root $Salida }
New-Item -ItemType Directory -Force -Path $carpeta | Out-Null

$npx = if (Get-Command npx.cmd -ErrorAction SilentlyContinue) { "npx.cmd" } else { "npx" }
if (-not $BaseUrl) {
    $BaseUrl = ((Get-Content $archivoEntorno -Raw -Encoding UTF8 | ConvertFrom-Json).values | Where-Object { $_.key -eq "baseUrl" }).value
}

$pausa = if ($DelayRequest -ge 0) { $DelayRequest } elseif ($Entorno -eq "produccion") { 2200 } else { 0 }
$pausaNewman = if ($pausa -gt 0) { @("--delay-request", "$pausa") } else { @() }

function Invoke-Corrida([string] $nombre) {
    $crudo = Join-Path ([System.IO.Path]::GetTempPath()) ("newman-crudo-$nombre-" + [guid]::NewGuid().ToString("N") + ".json")
    $final = Join-Path $carpeta "newman-$nombre.json"
    Write-Host "`n==> Corrida $nombre contra $BaseUrl" -ForegroundColor Cyan
    try {
        # --working-dir: la carpeta PM-07 sube archivos con rutas relativas a la raíz del repositorio (postman/fixtures/...).
        & $npx --yes newman@6 run $coleccion -e $archivoEntorno --env-var "baseUrl=$BaseUrl" --working-dir $root --reporters "cli,json" --reporter-json-export $crudo @pausaNewman
        $codigo = $LASTEXITCODE
        if (-not (Test-Path $crudo)) { Write-Host "Newman no generó el reporte de la corrida $nombre." -ForegroundColor Red; exit 1 }
        & node $depurador $crudo $coleccion $final $Entorno $BaseUrl
        if ($LASTEXITCODE -ne 0) { Write-Host "No se pudo depurar el reporte de la corrida $nombre." -ForegroundColor Red; exit 1 }
    } finally {
        if (Test-Path $crudo) { Remove-Item $crudo -Force }
    }
    return $codigo
}

function Get-Resumen([string] $nombre) {
    $r = Get-Content (Join-Path $carpeta "newman-$nombre.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $porCarpeta = $r.ejecuciones | Group-Object { ($_.carpeta -split " / ")[0] } | ForEach-Object {
        $aserciones = @($_.Group | ForEach-Object { $_.aserciones })
        [pscustomobject]@{
            Carpeta    = $_.Name
            Requests   = $_.Count
            Aserciones = $aserciones.Count
            Fallidas   = @($aserciones | Where-Object { -not $_.ok }).Count
        }
    }
    return [pscustomobject]@{
        Corrida    = $nombre
        Requests   = $r.totales.requests
        Total      = $r.totales.aserciones
        Fallidas   = $r.totales.aserciones_fallidas
        Aprobadas  = $r.totales.aserciones - $r.totales.aserciones_fallidas
        Promedio   = $r.tiempos.respuestaPromedioMs
        Maximo     = $r.tiempos.respuestaMaximaMs
        Primero    = $r.tiempos.primeraRespuestaMs
        Duracion   = $r.tiempos.duracionSegundos
        PorCarpeta = @($porCarpeta)
        Fallos     = @($r.fallos)
    }
}

foreach ($nombre in @("frio", "caliente")) { [void](Invoke-Corrida $nombre) }

$resumenes = @("frio", "caliente") | ForEach-Object { Get-Resumen $_ }
$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# Resumen de la ejecución con Newman")
[void]$md.AppendLine("")
[void]$md.AppendLine("- **Entorno:** $Entorno")
[void]$md.AppendLine("- **URL base:** $BaseUrl")
[void]$md.AppendLine("- **Fecha:** $(Get-Date -Format 'yyyy-MM-dd HH:mm')")
[void]$md.AppendLine("- **Colección:** postman/codice-api.postman_collection.json")
if ($pausa -gt 0) {
    [void]$md.AppendLine("- **Pausa entre requests:** $pausa ms, para no pasar del límite de 30 intentos de login por minuto. No cuenta en los tiempos de respuesta, pero alarga la duración total.")
}
[void]$md.AppendLine("")
[void]$md.AppendLine("| Corrida | Requests | Assertions | Aprobadas | Fallidas | Tiempo promedio de respuesta | Primer request | Respuesta más lenta | Duración total |")
[void]$md.AppendLine("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
foreach ($r in $resumenes) {
    [void]$md.AppendLine("| $($r.Corrida) | $($r.Requests) | $($r.Total) | $($r.Aprobadas) | $($r.Fallidas) | $($r.Promedio) ms | $($r.Primero) ms | $($r.Maximo) ms | $($r.Duracion) s |")
}
foreach ($r in $resumenes) {
    [void]$md.AppendLine("")
    [void]$md.AppendLine("## Resultado por carpeta, corrida $($r.Corrida)")
    [void]$md.AppendLine("")
    [void]$md.AppendLine("| Carpeta | Requests | Assertions | Fallidas |")
    [void]$md.AppendLine("| :--- | ---: | ---: | ---: |")
    foreach ($c in $r.PorCarpeta) { [void]$md.AppendLine("| $($c.Carpeta) | $($c.Requests) | $($c.Aserciones) | $($c.Fallidas) |") }
}
foreach ($r in $resumenes) {
    if ($r.Fallos.Count -gt 0) {
        [void]$md.AppendLine("")
        [void]$md.AppendLine("## Aserciones fallidas en la corrida $($r.Corrida)")
        [void]$md.AppendLine("")
        foreach ($f in $r.Fallos) { [void]$md.AppendLine("- **$($f.request)**: $($f.prueba) ($($f.mensaje))") }
    }
}
$utf8SinBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $carpeta "resumen.md"), $md.ToString(), $utf8SinBom)

Write-Host "`n==> Evidencia guardada en $carpeta" -ForegroundColor Green
$resumenes | Format-Table Corrida, Requests, Total, Aprobadas, Fallidas, Promedio, Primero -AutoSize

$fallo = ($resumenes | Where-Object { $_.Fallidas -gt 0 }).Count -gt 0
if ($fallo) { Write-Host "Hubo aserciones fallidas: revisa resumen.md." -ForegroundColor Red; exit 1 }

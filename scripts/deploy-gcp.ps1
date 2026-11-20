<#
.SYNOPSIS
  Despliega la API de Códice en Google Cloud: PostgreSQL 16 en Cloud SQL + la API en Cloud Run.

.DESCRIPTION
  Se puede correr varias veces: lo que ya existe se reutiliza y solo se vuelve a desplegar la API.
  Requisitos: gcloud instalado, `gcloud auth login` hecho y la facturación (free trial) activa.
  La contraseña de la base y el secreto JWT se generan aquí y van directo a Secret Manager:
  nunca se imprimen ni quedan en el repositorio.
  La llave de la IA (Gemini) la crea cada quien en Google AI Studio: la primera vez (o con -UpdateAiKey) se pide en un
  campo oculto y va directo a Secret Manager, igual que los otros secretos.

.EXAMPLE
  .\scripts\deploy-gcp.ps1 -ProjectId codice-backend-eif411
  .\scripts\deploy-gcp.ps1 -ProjectId codice-511601 -UpdateAiKey
  .\scripts\deploy-gcp.ps1 -ProjectId codice-backend-eif411 -BillingAccount 0123AB-4567CD-89EF01
#>
param(
    [Parameter(Mandatory = $true)] [string] $ProjectId,
    [string] $BillingAccount = "",
    [string] $Region = "us-central1",
    [string] $Instance = "codice-db",
    [string] $Database = "codice",
    [string] $DbUser = "codice",
    [string] $Service = "codice-api",
    [string] $AiProvider = "gemini",
    [string] $AiModel = "gemini-3.5-flash",
    [string] $AiFallbackModel = "gemini-3.5-flash-lite",
    [switch] $UpdateAiKey
)

# Continue (no Stop): en PowerShell 5.1 gcloud escribe el progreso en stderr y Stop lo trataría como error.
# Cada paso revisa $LASTEXITCODE.
$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot

function Step([string] $msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Check([string] $what) {
    if ($LASTEXITCODE -ne 0) { Write-Host "Falló: $what" -ForegroundColor Red; exit 1 }
}
function Test-Gcloud([scriptblock] $cmd) { & $cmd *> $null; return ($LASTEXITCODE -eq 0) }

function New-RandomBase64([int] $bytes) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return [Convert]::ToBase64String($buffer)
}

function Set-Secret([string] $name, [string] $value) {
    # Archivo temporal sin salto de línea final: si se pasara por la tubería, PowerShell agregaría un CRLF.
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($tmp, $value)
        if (Test-Gcloud { gcloud secrets describe $name }) {
            gcloud secrets versions add $name --data-file=$tmp | Out-Null
        } else {
            gcloud secrets create $name --replication-policy=automatic --data-file=$tmp | Out-Null
        }
        Check "guardar el secreto $name"
    } finally {
        Remove-Item $tmp -Force
    }
}

Step "Proyecto $ProjectId"
if (-not (Test-Gcloud { gcloud projects describe $ProjectId })) {
    gcloud projects create $ProjectId --name="Codice Backend"
    Check "crear el proyecto (si el ID ya lo usa otra persona, elija otro)"
}
gcloud config set project $ProjectId *> $null

Step "Facturación"
$billing = gcloud billing projects describe $ProjectId --format="value(billingEnabled)"
if ($billing -ne "True") {
    if ($BillingAccount -eq "") {
        Write-Host "El proyecto no tiene facturación. Cuentas disponibles:" -ForegroundColor Yellow
        gcloud billing accounts list
        Write-Host "Vuelva a correr el script con -BillingAccount <ID de la cuenta>." -ForegroundColor Yellow
        exit 1
    }
    gcloud billing projects link $ProjectId --billing-account=$BillingAccount
    Check "enlazar la cuenta de facturación"
}

Step "Habilitando APIs (la primera vez tarda un par de minutos)"
# compute crea la cuenta de servicio por defecto que usan Cloud Run y Cloud Build.
gcloud services enable compute.googleapis.com iam.googleapis.com run.googleapis.com sqladmin.googleapis.com `
    cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com
Check "habilitar las APIs"

Step "Cloud SQL: instancia $Instance (la primera vez tarda ~10 minutos)"
if (-not (Test-Gcloud { gcloud sql instances describe $Instance })) {
    # db-f1-micro solo existe en la edición ENTERPRISE; HDD y sin respaldos para gastar lo mínimo del crédito.
    gcloud sql instances create $Instance --database-version=POSTGRES_16 --edition=ENTERPRISE `
        --tier=db-f1-micro --region=$Region --storage-type=HDD --storage-size=10GB --no-backup
    Check "crear la instancia de Cloud SQL"
}
if (-not (Test-Gcloud { gcloud sql databases describe $Database --instance=$Instance })) {
    gcloud sql databases create $Database --instance=$Instance
    Check "crear la base $Database"
}

Step "Usuario de la base y secretos"
if (-not (Test-Gcloud { gcloud secrets describe db-url })) {
    $password = (New-RandomBase64 24) -replace '[+/=]', ''
    if (Test-Gcloud { gcloud sql users describe $DbUser --instance=$Instance }) {
        gcloud sql users set-password $DbUser --instance=$Instance --password=$password | Out-Null
    } else {
        gcloud sql users create $DbUser --instance=$Instance --password=$password | Out-Null
    }
    Check "crear el usuario $DbUser"
    # El host lo ignora el conector de Cloud SQL (CLOUD_SQL_INSTANCE); solo importan usuario, clave y base.
    Set-Secret "db-url" "postgres://${DbUser}:$password@localhost:5432/$Database"
    Remove-Variable password
} else {
    Write-Host "db-url ya existe: se conserva la contraseña actual."
}
if (-not (Test-Gcloud { gcloud secrets describe jwt-secret })) {
    # HS512 exige una llave de al menos 64 bytes; JwtService decodifica el secreto como Base64.
    Set-Secret "jwt-secret" (New-RandomBase64 64)
} else {
    Write-Host "jwt-secret ya existe: se conserva."
}
# La llave de la IA no se genera aquí: la crea cada quien en Google AI Studio. Se pide oculta (como una contraseña)
# y va directo a Secret Manager; nunca se imprime ni se escribe en el repositorio.
$aiKeyExists = Test-Gcloud { gcloud secrets describe ai-api-key }
if ($UpdateAiKey -or -not $aiKeyExists) {
    $secureKey = Read-Host "Llave de Gemini (Google AI Studio; no se muestra al escribir)" -AsSecureString
    $aiKey = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)).Trim()
    if ($aiKey -eq "") { Write-Host "No se escribió ninguna llave." -ForegroundColor Red; exit 1 }
    Set-Secret "ai-api-key" $aiKey
    Remove-Variable aiKey, secureKey
} else {
    Write-Host "ai-api-key ya existe: se conserva (use -UpdateAiKey para cambiarla)."
}

Step "Permisos de la cuenta de servicio de Cloud Run"
$projectNumber = gcloud projects describe $ProjectId --format="value(projectNumber)"
$serviceAccount = "serviceAccount:$projectNumber-compute@developer.gserviceaccount.com"
foreach ($role in @("roles/cloudsql.client", "roles/secretmanager.secretAccessor", "roles/cloudbuild.builds.builder")) {
    gcloud projects add-iam-policy-binding $ProjectId --member=$serviceAccount --role=$role --condition=None | Out-Null
    Check "asignar $role"
}
# roles/run.builder es el que la documentación actual pide para `run deploy --source`; si el rol no
# existe en la cuenta, cloudbuild.builds.builder (arriba) ya cubre la construcción.
gcloud projects add-iam-policy-binding $ProjectId --member=$serviceAccount --role=roles/run.builder --condition=None *> $null
if ($LASTEXITCODE -ne 0) { Write-Host "Aviso: no se pudo asignar roles/run.builder; se sigue con cloudbuild.builds.builder." -ForegroundColor Yellow }

Step "Desplegando $Service en Cloud Run (Cloud Build construye el Dockerfile, ~5 minutos)"
$connection = "${ProjectId}:${Region}:$Instance"
gcloud run deploy $Service --source $root --region=$Region --allow-unauthenticated --quiet `
    --add-cloudsql-instances=$connection `
    --set-env-vars="SPRING_PROFILES_ACTIVE=prod,CLOUD_SQL_INSTANCE=$connection,AI_PROVIDER=$AiProvider,AI_MODEL=$AiModel,AI_FALLBACK_MODEL=$AiFallbackModel" `
    --set-secrets="DATABASE_URL=db-url:latest,JWT_SECRET=jwt-secret:latest,AI_API_KEY=ai-api-key:latest" `
    --memory=1Gi --cpu-boost --min-instances=0 --max-instances=2
Check "desplegar en Cloud Run"

$url = gcloud run services describe $Service --region=$Region --format="value(status.url)"
Step "Listo: $url"
try {
    $health = Invoke-RestMethod "$url/actuator/health" -TimeoutSec 120
    Write-Host "Health check: $($health.status)" -ForegroundColor Green
} catch {
    Write-Host "El health check no respondió todavía. Revise los logs:" -ForegroundColor Yellow
    Write-Host "  gcloud run services logs read $Service --region=$Region --limit=100"
}

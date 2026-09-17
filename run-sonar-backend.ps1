# Runs the backend test suite + SonarQube analysis in one command.
#
#   .\run-sonar-backend.ps1          # tests + coverage only (no Sonar push)
#   .\run-sonar-backend.ps1 -Token xxx   # .env override, one-off token
#
# Credentials are loaded from ./.env (see ./.env.example):
#   SONAR_HOST_URL   - SonarQube server (default http://localhost:9000)
#   SONAR_TOKEN      - analysis token generated in the SonarQube UI
# A -Token parameter overrides the .env value for one run.
#
# NOTE: this file is ASCII-only on purpose. Windows PowerShell 5.1 reads .ps1
# files without a BOM as ANSI, so any non-ASCII char breaks parsing.
#
# IMPORTANT - NO `mvn clean` anywhere in this script (by design):
#   `clean` wipes target/ which contains target/allure-results, destroying the
#   accumulated Allure test history. Build is incremental instead; stale-class
#   risk is handled by jacoco append=false (jacoco.exec is overwritten each run,
#   see pom.xml), so coverage sent to Sonar is always from THIS run only.
#
# First-time SonarQube setup:
#   1. Start the server:            docker compose up -d sonarqube
#      (wait ~1 min, then http://localhost:9000 - login admin/admin and change
#       the password; Docker Desktop/WSL2: if the container exits with an
#       Elasticsearch error, run once:
#         wsl -d docker-desktop -u root sysctl -w vm.max_map_count=262144
#       then restart the container)
#   2. Create project: Projects > Create > Manual - key: powerbind-backend
#   3. Generate a token (My Account > Security) and put it in ./.env as SONAR_TOKEN

param(
    [string]$Token,
    [string]$SonarHost,
    [string]$ProjectKey = "powerbind-backend"
)

# --- load ./.env (KEY=VALUE lines, '#' comments ignored) ----------------------
$envMap = @{}
$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and -not $line.TrimStart().StartsWith('#')) {
            $envMap[$Matches[1]] = $Matches[2]
        }
    }
} else {
    Write-Host "No .env found (expected at $envFile) - copy .env.example first." -ForegroundColor Yellow
}

$SonarHost = if ($SonarHost) { $SonarHost } else { if ($envMap['SONAR_HOST_URL']) { $envMap['SONAR_HOST_URL'] } else { 'http://localhost:9000' } }
$Token     = if ($Token)     { $Token }     else { $envMap['SONAR_TOKEN'] }

Write-Host "Running test suite + JaCoCo coverage (mvn verify - NO clean, Allure history preserved)..." -ForegroundColor Cyan
mvn verify
$testExitCode = $LASTEXITCODE

if ($testExitCode -ne 0) {
    Write-Host "Tests FAILED (exit $testExitCode) - SonarQube analysis skipped." -ForegroundColor Red
    Write-Host "Run .\run-allure.ps1 to view the Allure report." -ForegroundColor DarkGray
    exit $testExitCode
}

Write-Host "JaCoCo report: target/site/jacoco/index.html (open in a browser to review locally)" -ForegroundColor DarkGray

if (-not $Token) {
    Write-Host "No SONAR_TOKEN in .env and no -Token given - analysis NOT pushed to SonarQube." -ForegroundColor Yellow
    exit 0
}

Write-Host "Pushing analysis to SonarQube at $SonarHost (project: $ProjectKey)..." -ForegroundColor Cyan
# NOTE: sonar.token - the modern property for SonarQube Server 2025.x+ (Community
# Build 26.9). The old sonar.login property is ignored by these versions.
mvn sonar:sonar "-Dsonar.host.url=$SonarHost" "-Dsonar.token=$Token" "-Dsonar.projectKey=$ProjectKey"
$sonarExitCode = $LASTEXITCODE

if ($sonarExitCode -ne 0) {
    Write-Host "SonarQube analysis FAILED (exit $sonarExitCode)." -ForegroundColor Red
    exit $sonarExitCode
}

Write-Host "Done. View results at $SonarHost/dashboard?id=$ProjectKey" -ForegroundColor Green
exit 0

<#
.SYNOPSIS
    OWASP ZAP dynamic scan (DAST) against the running PowerBind backend.

.DESCRIPTION
    SonarQube Community cannot detect injection-class flaws (SQL injection,
    XSS, broken authorization) - that is dynamic testing territory. ZAP attacks
    the running application and reports what it finds.

    The api mode is driven by the OpenAPI document the backend already exposes
    (springdoc -> /v3/api-docs), so every documented endpoint is exercised,
    including the JWT login and the relay control endpoints.

    Prerequisite: Docker Desktop running AND the backend reachable on 8045.
        docker compose up -d backend        (or)   mvn spring-boot:run

    Modes
      api       (default) zap-api-scan.py against /v3/api-docs (OpenAPI)
      baseline            zap-baseline.py passive scan of the frontend UI
      full                zap-full-scan.py intrusive crawl (slow, local only)

    Reports (gitignored): zap/zap-report.html and zap/zap-report.json
    ZAP exit codes: 0 = no alerts, 1 = warnings only, 2 = alerts found.

.NOTES
    ASCII only - Windows PowerShell 5.1 reads BOM-less scripts as ANSI.
#>
param(
    [ValidateSet('api', 'baseline', 'full')]
    [string] $Mode = 'api',
    [string] $BackendUrl  = 'http://host.docker.internal:8045',
    [string] $FrontendUrl = 'http://host.docker.internal:5173',
    [string] $Image       = 'zaproxy/zap-stable',
    [int]    $Minutes     = 5
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

# 1. the backend must answer before a scan makes sense
$healthUrl = 'http://localhost:8045/actuator/health'
try {
    $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 10
    if ($health.status -ne 'UP') { throw "health status is $($health.status)" }
    Write-Host "Backend health: UP ($healthUrl)" -ForegroundColor Green
} catch {
    Write-Host "Backend not reachable at $healthUrl - start it first:" -ForegroundColor Red
    Write-Host '    docker compose up -d backend        # or: mvn spring-boot:run' -ForegroundColor Yellow
    exit 1
}

# 2. reports land in zap/ next to this script
$reportDir = Join-Path $PSScriptRoot 'zap'
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

# 3. build the command line for the selected mode
$jsonReport = '/zap/wrk/zap-report.json'
$htmlReport = '/zap/wrk/zap-report.html'
switch ($Mode) {
    'api' {
        $target  = "$BackendUrl/v3/api-docs"
        $zapArgs = @('zap-api-scan.py', '-t', $target, '-f', 'openapi',
                     '-J', $jsonReport, '-r', $htmlReport, '-m', "$Minutes")
    }
    'baseline' {
        $target  = $FrontendUrl
        $zapArgs = @('zap-baseline.py', '-t', $target,
                     '-J', $jsonReport, '-r', $htmlReport, '-m', "$Minutes")
    }
    'full' {
        $target  = $FrontendUrl
        $zapArgs = @('zap-full-scan.py', '-t', $target,
                     '-J', $jsonReport, '-r', $htmlReport, '-m', "$Minutes")
    }
}

Write-Host "Running ZAP ($Mode mode) against $target" -ForegroundColor Cyan
Write-Host "Image: $Image" -ForegroundColor DarkGray

# host.docker.internal works out of the box on Docker Desktop (Windows/macOS).
# On Linux add: --add-host=host.docker.internal:host-gateway
#
# ZAP writes its progress to stderr. With $ErrorActionPreference = 'Stop' that
# aborts the script on the very first progress line, so stderr is tolerated here
# and the real outcome is read from ZAP's exit code instead.
$ErrorActionPreference = 'Continue'
& docker run --rm -v "${reportDir}:/zap/wrk:rw" $Image @zapArgs
$zapExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'

Write-Host ''
switch ($zapExit) {
    0       { Write-Host 'ZAP: no alerts raised.' -ForegroundColor Green }
    1       { Write-Host 'ZAP: warnings only.' -ForegroundColor Yellow }
    2       { Write-Host 'ZAP: ALERTS FOUND - review the reports.' -ForegroundColor Red }
    default { Write-Host "ZAP exited with code $zapExit." -ForegroundColor Yellow }
}
Write-Host "Reports: $reportDir\zap-report.html and .json" -ForegroundColor Cyan
exit $zapExit

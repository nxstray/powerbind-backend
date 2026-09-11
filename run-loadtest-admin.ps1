# Runs the k6 load test (perf/k6/dashboard-load.js) against a RUNNING backend.
# Prereqs: k6 installed (`k6 version`), backend + PostgreSQL + InfluxDB + Mosquitto up.
# WARNING: do NOT target a backend connected to real ESP32 devices - test data
# pollutes the dashboard/InfluxDB.
#
# Usage:
#   .\run-loadtest-admin.ps1                            # default: 20 VU / 1m @ localhost:8045
#   .\run-loadtest-admin.ps1 -Vus 50 -Duration 2m       # heavier run
#   .\run-loadtest-admin.ps1 -Baseline                  # quick 10 VU / 30s baseline first
#   .\run-loadtest-admin.ps1 -BaseUrl http://192.168.1.10:8045 -WsUrl ws://192.168.1.10:8045/ws
#   Kredensial: ditanyakan lewat prompt tiap run (password hidden) - KECUALI
#   diberikan eksplisit:
#   .\run-loadtest-admin.ps1 -Username {user} -Password (ConvertTo-SecureString 'pw' -AsPlainText -Force)
#   Untuk akun family (role USER): pakai .\run-loadtest-user.ps1.

param(
    [int]$Vus = 20,
    [string]$Duration = "1m",
    [string]$BaseUrl = "http://localhost:8045",
    [string]$WsUrl = "ws://localhost:8045/ws",
    # Kredensial akun yang diuji. Kalau tidak diberikan, KEDUANYA ditanyakan
    # lewat prompt di blok bawah (input password hidden). Boleh akun admin
    # maupun akun lain - hanya /api/admin/** yang butuh role ADMIN.
    [string]$Username,
    [SecureString]$Password,
    # Quick low-load baseline run before the main run
    [switch]$Baseline,
    # Skip k6/backend availability checks
    [switch]$SkipChecks
)

# Opsi prompt-first: kredensial selalu ditanyakan tiap run, KECUALI sudah
# diberikan lewat parameter. Password TIDAK dibaca dari .env - nilai
# APP_DEFAULT_USER_PASSWORD di .env bisa saja stale, jadi penguji mengetik
# password terkini sendiri (input hidden). HATI-HATI salah ketik: 5 login
# gagal mengunci akun selama 10 menit (login.max-attempts/lockout-minutes).
if (-not $Username) {
    $Username = Read-Host -Prompt "Load test username"
}

if (-not $Password) {
    $Password = Read-Host -Prompt "Password for $Username (hidden)" -AsSecureString
}

$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
$plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$k6Script = Join-Path $scriptDir "perf\k6\dashboard-load.js"
$resultsDir = Join-Path $scriptDir "perf\k6\results"

if (-not $SkipChecks) {
    # 1. k6 must be installed
    $k6Cmd = Get-Command k6 -ErrorAction SilentlyContinue
    if (-not $k6Cmd) {
        Write-Host "k6 not found in PATH. Install it first:" -ForegroundColor Red
        Write-Host "  winget install grafana.k6   (then open a NEW terminal tab)" -ForegroundColor Red
        exit 1
    }
    Write-Host "k6 found: $(& k6 version)" -ForegroundColor Green

    # 2. Backend must be reachable — ANY HTTP answer (401/404 included) proves
    #    the stack is up; only network-level failures mean "not running".
    try {
        $null = Invoke-WebRequest -Uri "$BaseUrl/api/dashboard/summary" `
            -Method Get -TimeoutSec 5 -ErrorAction Stop
        Write-Host "Backend reachable at $BaseUrl" -ForegroundColor Green
    } catch {
        if ($_.Exception.Response) {
            # Server menjawab (401 tanpa token adalah normal) → stack hidup
            Write-Host "Backend reachable at $BaseUrl" -ForegroundColor Green
        } else {
            Write-Host "Backend not reachable at $BaseUrl (error: $($_.Exception.Message))." -ForegroundColor Red
            Write-Host "Start the backend first: mvn spring-boot:run  (plus PostgreSQL/InfluxDB/Mosquitto)." -ForegroundColor Red
            Write-Host "Use -SkipChecks to bypass this check." -ForegroundColor DarkGray
            exit 1
        }
    }
}

# Optional low-load baseline first — useful as the comparison point for the thesis
if ($Baseline) {
    Write-Host "`n=== BASELINE run (10 VU / 30s) ===" -ForegroundColor Cyan
    $baselineArgs = @(
        "-e", "BASE_URL=$BaseUrl",
        "-e", "WS_URL=$WsUrl",
        "-e", "VUS=10",
        "-e", "DURATION=30s",
        "-e", "K6_USERNAME=$Username",
        "-e", "K6_PASSWORD=$plainPassword",
        $k6Script
    )
    k6 run @baselineArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Baseline run FAILED its thresholds - fix before stressing further." -ForegroundColor Yellow
    }
}

# Main run
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$summaryFile = Join-Path $resultsDir "result-$stamp.json"

Write-Host "`n=== MAIN run ($Vus VU / $Duration) ===" -ForegroundColor Cyan
Write-Host "Summary will be exported to: $summaryFile" -ForegroundColor DarkGray

$mainArgs = @(
    "-e", "BASE_URL=$BaseUrl",
    "-e", "WS_URL=$WsUrl",
    "-e", "VUS=$Vus",
    "-e", "DURATION=$Duration",
    "-e", "K6_USERNAME=$Username",
    "-e", "K6_PASSWORD=$plainPassword",
    "--summary-export=$summaryFile",
    $k6Script
)
k6 run @mainArgs
$k6ExitCode = $LASTEXITCODE

if ($k6ExitCode -ne 0) {
    Write-Host "`nLoad test FAILED its thresholds (exit code: $k6ExitCode)." -ForegroundColor Red
    Write-Host "Check the table above: p(95) http_req_duration < 500ms, http_req_failed < 1%." -ForegroundColor Red
} else {
    Write-Host "`nLoad test PASSED all thresholds." -ForegroundColor Green
    Write-Host "For the thesis report: keep the JSON in perf/k6/results/ and the" -ForegroundColor DarkGray
    Write-Host "end-of-run summary table (latency p95/p99, error rate, WS connect time)." -ForegroundColor DarkGray
}

Remove-Variable plainPassword -ErrorAction SilentlyContinue

exit $k6ExitCode
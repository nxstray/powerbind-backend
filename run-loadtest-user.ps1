# Load test khusus akun FAMILY (APP_FAMILY_USERS, role USER) - menjalankan k6
# load test yang sama (perf/k6/dashboard-load.js) terhadap backend yang RUNNING.
# WARNING: do NOT target a backend connected to real ESP32 devices - test data
# pollutes the dashboard/InfluxDB.
#
# Perbedaan dengan run-loadtest-admin.ps1:
#   - TIDAK ada default kredensial sama sekali: username + password family
#     selalu ditanyakan lewat prompt (password hidden), kecuali diberikan
#     eksplisit: -Username {family-user} -Password (ConvertTo-SecureString 'pw' -AsPlainText -Force)
#   - Data dashboard/power-history bersifat per-user: profil beban mengikuti
#     ruangan milik akun yang diuji.
#   - Semua endpoint yang di-load test (login, dashboard, WS) cukup
#     authenticated - hanya /api/admin/** yang butuh role ADMIN.
#
# Usage:
#   .\run-loadtest-user.ps1                             # default: 20 VU / 1m @ localhost:8045
#   .\run-loadtest-user.ps1 -Vus 50 -Duration 2m        # heavier run
#   .\run-loadtest-user.ps1 -Baseline                   # quick 10 VU / 30s baseline first

param(
    [int]$Vus = 20,
    [string]$Duration = "1m",
    [string]$BaseUrl = "http://localhost:8045",
    [string]$WsUrl = "ws://localhost:8045/ws",
    # Username akun family (APP_FAMILY_USERS). Kalau kosong, ditanyakan.
    [string]$Username,
    [SecureString]$Password,
    # Quick low-load baseline run before the main run
    [switch]$Baseline,
    # Skip k6/backend availability checks
    [switch]$SkipChecks
)

$ErrorActionPreference = "Stop"

# Prompt kredensial family - skrip ini sengaja TIDAK punya default kredensial
# (tidak admin, tidak .env). HATI-HATI salah ketik: 5 login gagal mengunci
# akun selama 10 menit (login.max-attempts/lockout-minutes).
if (-not $Username) {
    $Username = Read-Host -Prompt "Family user username"
}

if (-not $Password) {
    $Password = Read-Host -Prompt "Password for $Username (hidden)" -AsSecureString
}

# Delegasi (dot-source) ke skrip inti - satu sumber kebenaran untuk invokasi k6
# (checks, baseline, main run, summary export). Skrip inti tidak prompt lagi
# karena kredensial sudah diberikan eksplisit di sini. Dot-source dipakai agar
# exit code skrip inti (k6 exit code) terpropagasi apa adanya.
# Catatan: splatting PowerShell hanya menerima VARIABLE (@forward), bukan
# hashtable literal - makanya hashtable di-assign dulu ke $forward.
$forward = @{
    Username   = $Username
    Password   = $Password
    Vus        = $Vus
    Duration   = $Duration
    BaseUrl    = $BaseUrl
    WsUrl      = $WsUrl
    Baseline   = $Baseline
    SkipChecks = $SkipChecks
}

. (Join-Path $PSScriptRoot "run-loadtest-admin.ps1") @forward
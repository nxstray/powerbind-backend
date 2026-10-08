<#
.SYNOPSIS
    Generate a self-signed TLS certificate for the Mosquitto MQTT broker (port 8883).

.DESCRIPTION
    Writes mosquitto/certs/server.crt + mosquitto/certs/server.key (gitignored).
    When those files exist, mosquitto/entrypoint.sh enables the TLS listener 8883
    on container start; without them the broker still runs plain-text on 1883
    (inside the private Docker network) and the host-mapped 1884.

    Devices must trust this CA (or disable cert verification for a lab setup).
    For a public deployment replace these with a proper CA-issued certificate.

.NOTES
    ASCII only - Windows PowerShell 5.1 reads BOM-less scripts as ANSI.
#>
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$certsDir = Join-Path $PSScriptRoot 'mosquitto\certs'
$crt = Join-Path $certsDir 'server.crt'
$key = Join-Path $certsDir 'server.key'

New-Item -ItemType Directory -Force -Path $certsDir | Out-Null

if ((Test-Path $crt) -and (Test-Path $key)) {
    Write-Host "TLS cert already exists: $crt (delete mosquitto\certs to regenerate)" -ForegroundColor Yellow
    exit 0
}

Write-Host "Generating self-signed MQTT TLS certificate (valid 365 days) ..." -ForegroundColor Cyan

# openssl runs inside a throwaway container - no local openssl install needed
docker run --rm `
    -v "${certsDir}:/certs" `
    alpine/openssl req -x509 -newkey rsa:2048 -nodes -days 365 `
    -keyout /certs/server.key -out /certs/server.crt `
    -subj "/CN=powerbind-mosquitto" `
    -addext "subjectAltName=DNS:mosquitto,DNS:localhost,IP:127.0.0.1"

if ($LASTEXITCODE -ne 0) {
    Write-Host "Certificate generation FAILED (exit $LASTEXITCODE)." -ForegroundColor Red
    exit $LASTEXITCODE
}

# mosquitto drops privileges and reads the key as a non-root user; openssl
# creates it 0600 which the broker then rejects with "Permission denied".
# The bind mount is read-only in the compose file, so fix it here on the host.
docker run --rm --entrypoint sh -v "${certsDir}:/certs" alpine/openssl -c "chmod 644 /certs/server.key /certs/server.crt"

Write-Host "OK: $crt + $key" -ForegroundColor Green
Write-Host "Restart mosquitto to enable the TLS listener: docker compose up -d mosquitto" -ForegroundColor Green

# Merges results from run-test.ps1, run-performance-test.ps1, and run-selenium-test.ps1
# (all of which write into target/allure-results without cleaning it), then generates
# and opens a single combined Allure report with trend history preserved.
#
# Run this AFTER one or more of: run-test.ps1, run-performance-test.ps1, run-selenium-test.ps1
#
# Optional: pass -Clean to wipe target/ first (starts a completely fresh accumulation).

param(
    [switch]$Clean
)

$allureResults = "target/allure-results"
$allureReport  = "target/allure-report"

if ($Clean) {
    Write-Host "Cleaning target/ before accumulating new results..." -ForegroundColor Yellow
    mvn clean | Out-Null
}

# Preserve trend graph across report regenerations
if (Test-Path "$allureReport/history") {
    Write-Host "Carrying forward previous Allure history for trend graph..." -ForegroundColor Green
    Copy-Item -Path "$allureReport/history" -Destination "$allureResults/history" -Recurse -Force
}

if (!(Test-Path $allureResults)) {
    Write-Host "No allure-results found. Run one of the test scripts first:" -ForegroundColor Red
    Write-Host "  .\run-test.ps1" -ForegroundColor Red
    Write-Host "  .\run-performance-test.ps1" -ForegroundColor Red
    Write-Host "  .\run-selenium-test.ps1" -ForegroundColor Red
    exit 1
}

Write-Host "Generating combined Allure report..." -ForegroundColor Cyan
allure generate $allureResults --clean -o $allureReport
allure open $allureReport
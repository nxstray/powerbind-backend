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

# Preserve trend graph across report regenerations.
# NOTE: results dir intentionally accumulates across run-test.ps1 /
# run-performance-test.ps1 / run-selenium-test.ps1 runs (no clean between them),
# so dedupe to one result per historyId BEFORE generating: Allure renders every
# *-result.json as its own row in Suites/Behaviors, and old Cucumber runs under
# a previous naming-strategy (short names like "A user only sees...") would
# otherwise appear as ghost duplicates next to the current "BDD (...) - ..."
# names — same testCaseId family, different historyId. Trend data itself lives
# in history/history.json and is NOT touched by the purges below.
# Legacy short names from the pre-long-naming-strategy era (e.g. "A user only
# sees...") carry different historyIds than their current "BDD (...) - ..."
# counterparts, so historyId dedupe alone cannot merge them — drop the exact
# legacy names instead. Their points stay visible in history.json (trend), they
# just no longer render as ghost rows in Suites/Behaviors.
$legacyBddNames = @(
    'A user only sees their own conversations in the list',
    'A user can read the messages inside their own conversation',
    "A user cannot open another user's conversation",
    'A user can delete their own conversation',
    'An authenticated user can view the dashboard summary',
    'An authenticated user can view power usage history',
    'Anonymous requests are rejected',
    'Successful login with a pre-seeded account',
    'Login with wrong password',
    'Login lockout after 5 failed attempts',
    'CucumberTestRunner'
)
Get-ChildItem "$allureResults/*-result.json" -ErrorAction SilentlyContinue | ForEach-Object {
    $parsed = Get-Content $_.FullName -Raw | ConvertFrom-Json
    [pscustomobject]@{ File = $_; HistoryId = $parsed.historyId; Stop = $parsed.stop; Name = $parsed.name }
} | ForEach-Object {
    if ($_.Name -in $legacyBddNames) { Remove-Item $_.File.FullName -Force }
    $_
} | Group-Object HistoryId | Where-Object { $_.Count -gt 1 } | ForEach-Object {
    $_.Group | Sort-Object Stop -Descending | Select-Object -Skip 1 | ForEach-Object {
        Remove-Item $_.File.FullName -Force
    }
}
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
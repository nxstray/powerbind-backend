# Runs ONLY @Tag("ui") Selenium tests (Login, Dashboard, AgentPage, ChangePasswordModal, AnomalyToast, ErdPage, LogPage).
# Does NOT generate or open the Allure report - run .\run-allure.ps1 for that.
#
# PREREQUISITES - start these manually FIRST, in separate terminals:
#   1. Backend:  mvn spring-boot:run   (dev DB, not H2 - needs a real seeded account)
#   2. Frontend: npm run dev           (default: http://localhost:5173)
#
# Usage:
#   .\run-selenium-test.ps1
#   You'll be prompted for: Username, Password (hidden), then DB username
#   (hit Enter to skip the DB one - only ChangePasswordModalSeleniumTest needs it,
#   everything else runs fine without it). If you give a DB username, you'll then
#   also be prompted for the DB password (hidden).
#
#   All prompts can still be skipped by passing the matching parameter, e.g.:
#   .\run-selenium-test.ps1 -Username Afwan -DbUsername postgres

param(
    [string]$Username,

    [SecureString]$Password,

    [string]$FrontendUrl = "http://localhost:5173",

    # Optional — only required to run ChangePasswordModalSeleniumTest's DB-toggle tests
    [string]$DbUsername,

    [SecureString]$DbPassword,

    [string]$DbUrl = "jdbc:postgresql://localhost:5432/powerbind"
)

if (-not $Username) {
    $Username = Read-Host -Prompt "Selenium test username"
}

if (-not $Password) {
    $Password = Read-Host -Prompt "Selenium test password" -AsSecureString
}

if (-not $DbUsername) {
    $DbUsername = Read-Host -Prompt "DB username for ChangePasswordModalSeleniumTest (leave blank to skip)"
}

$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
$plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

$mvnArgs = @(
    "-Dtest=com.powerbind.backend.selenium.**"
    "-DexcludedGroups="
    "-Dfrontend.url=$FrontendUrl"
    "-Dselenium.username=$Username"
    "-Dselenium.password=$plainPassword"
)

$plainDbPassword = $null
if ($DbUsername) {
    if (-not $DbPassword) {
        $DbPassword = Read-Host -Prompt "DB password for '$DbUsername'" -AsSecureString
    }
    $dbBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($DbPassword)
    $plainDbPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto($dbBstr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($dbBstr)

    $mvnArgs += "-Ddb.username=$DbUsername"
    $mvnArgs += "-Ddb.password=$plainDbPassword"
    $mvnArgs += "-Ddb.url=$DbUrl"
}

Write-Host "Running UI (Selenium) tests only..." -ForegroundColor Cyan
Write-Host "Make sure backend (mvn spring-boot:run) and frontend (npm run dev) are already running!" -ForegroundColor Yellow
Write-Host "Frontend URL: $FrontendUrl | Test user: $Username" -ForegroundColor DarkGray
if ($DbUsername) {
    Write-Host "DB toggle enabled for user: $DbUsername (ChangePasswordModalSeleniumTest will run in full)" -ForegroundColor DarkGray
} else {
    Write-Host "No DB username provided - ChangePasswordModalSeleniumTest's DB-dependent tests will be skipped." -ForegroundColor DarkGray
}

mvn test @mvnArgs
$testExitCode = $LASTEXITCODE

Remove-Variable plainPassword -ErrorAction SilentlyContinue
Remove-Variable plainDbPassword -ErrorAction SilentlyContinue

if ($testExitCode -ne 0) {
    Write-Host "Some UI tests failed. Exit code: $testExitCode" -ForegroundColor Red
} else {
    Write-Host "All UI tests passed." -ForegroundColor Green
}

Write-Host "Run .\run-allure.ps1 to view the combined Allure report (screenshots included)." -ForegroundColor DarkGray
exit $testExitCode
# Runs ONLY @Tag("ui") Selenium tests (Login, Dashboard, AgentPage, ChangePasswordModal, AnomalyToast).
# Does NOT generate or open the Allure report - run .\run-allure.ps1 for that.
#
# PREREQUISITES - start these manually FIRST, in separate terminals:
#   1. Backend:  mvn spring-boot:run   (dev DB, not H2 - needs a real seeded account)
#   2. Frontend: npm run dev           (default: http://localhost:5173)
#
# Usage:
#   .\run-selenium-test.ps1 -Username {username}
#   (you will be prompted to type the password securely, it will not echo to screen)
#
#   DB credentials are OPTIONAL and only needed by ChangePasswordModalSeleniumTest,
#   which uses them to flip must_change_password directly in Postgres (there is no
#   API to re-arm that flag). Omit -DbUsername entirely to skip that — every other
#   suite runs fine without it, ChangePasswordModalSeleniumTest will just self-skip.
#
#   .\run-selenium-test.ps1 -Username {username} -DbUsername {db user}
#   (you will then also be prompted for the DB password securely)

param(
    [Parameter(Mandatory = $true)]
    [string]$Username,

    [Parameter(Mandatory = $true)]
    [SecureString]$Password,

    [string]$FrontendUrl = "http://localhost:5173",

    # Optional — only required to run ChangePasswordModalSeleniumTest's DB-toggle tests
    [string]$DbUsername,

    [SecureString]$DbPassword,

    [string]$DbUrl = "jdbc:postgresql://localhost:5432/powerbind"
)

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
    Write-Host "No -DbUsername provided - ChangePasswordModalSeleniumTest's DB-dependent tests will be skipped." -ForegroundColor DarkGray
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
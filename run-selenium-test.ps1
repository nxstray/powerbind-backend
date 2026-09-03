# Runs ONLY @Tag("ui") Selenium tests (Login, Dashboard, AgentPage).
# Does NOT generate or open the Allure report - run .\run-allure.ps1 for that.
#
# PREREQUISITES - start these manually FIRST, in separate terminals:
#   1. Backend:  mvn spring-boot:run   (dev DB, not H2 - needs a real seeded account)
#   2. Frontend: npm run dev           (default: http://localhost:5173)
#
# Usage:
#   .\run-selenium-test.ps1 -Username {username}
#   (you will be prompted to type the password securely, it will not echo to screen)

param(
    [Parameter(Mandatory = $true)]
    [string]$Username,

    [Parameter(Mandatory = $true)]
    [SecureString]$Password,

    [string]$FrontendUrl = "http://localhost:5173"
)

$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
$plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

Write-Host "Running UI (Selenium) tests only..." -ForegroundColor Cyan
Write-Host "Make sure backend (mvn spring-boot:run) and frontend (npm run dev) are already running!" -ForegroundColor Yellow
Write-Host "Frontend URL: $FrontendUrl | Test user: $Username" -ForegroundColor DarkGray

mvn test `
    "-Dtest=com.powerbind.backend.selenium.**" `
    "-DexcludedGroups=" `
    "-Dfrontend.url=$FrontendUrl" `
    "-Dselenium.username=$Username" `
    "-Dselenium.password=$plainPassword"
$testExitCode = $LASTEXITCODE

Remove-Variable plainPassword -ErrorAction SilentlyContinue

if ($testExitCode -ne 0) {
    Write-Host "Some UI tests failed. Exit code: $testExitCode" -ForegroundColor Red
} else {
    Write-Host "All UI tests passed." -ForegroundColor Green
}

Write-Host "Run .\run-allure.ps1 to view the combined Allure report (screenshots included)." -ForegroundColor DarkGray
exit $testExitCode
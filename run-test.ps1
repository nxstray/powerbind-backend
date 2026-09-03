# Runs the default test suite (unit, functional, smoke, cucumber).
# Performance and UI tests are excluded by default (see pom.xml excludedGroups).
# Does NOT generate or open the Allure report - run .\run-allure.ps1 for that.

Write-Host "Running default test suite (unit/functional/smoke/cucumber)..." -ForegroundColor Cyan
mvn test
$testExitCode = $LASTEXITCODE

if ($testExitCode -ne 0) {
    Write-Host "Some tests failed. Exit code: $testExitCode" -ForegroundColor Red
} else {
    Write-Host "All tests passed." -ForegroundColor Green
}

Write-Host "Run .\run-allure.ps1 to view the combined Allure report." -ForegroundColor DarkGray
exit $testExitCode
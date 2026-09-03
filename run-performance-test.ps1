# Runs ONLY @Tag("performance") tests (load test, login performance).
# Does NOT generate or open the Allure report - run .\run-allure.ps1 for that.

Write-Host "Running performance-tagged tests only..." -ForegroundColor Cyan
mvn test "-Dtest=com.powerbind.backend.performance.**" "-DexcludedGroups="
$testExitCode = $LASTEXITCODE

if ($testExitCode -ne 0) {
    Write-Host "Some performance tests failed. Exit code: $testExitCode" -ForegroundColor Red
} else {
    Write-Host "All performance tests passed." -ForegroundColor Green
}

Write-Host "Note: 'CucumberTestRunner did not discover any tests' is expected here -" -ForegroundColor DarkGray
Write-Host "no Cucumber scenario is tagged @performance, so it is filtered out on purpose." -ForegroundColor DarkGray
Write-Host "Run .\run-allure.ps1 to view the combined Allure report." -ForegroundColor DarkGray
exit $testExitCode
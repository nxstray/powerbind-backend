<#
.SYNOPSIS
    Generate a CycloneDX SBOM (software bill of materials) for the backend.

.DESCRIPTION
    Writes sbom/bom.json: every dependency the application ships with, resolved
    through the Spring Boot BOM, so the whole transitive tree is covered - not
    just the artifacts listed in pom.xml.

    A CycloneDX SBOM is what SCA tooling consumes (Dependency-Track, Trivy,
    Grype, dep-scan) to track known CVEs without re-resolving the Maven build.

    The cyclonedx-maven-plugin is declared in pom.xml WITHOUT a phase binding,
    so regular builds (mvn verify / run-sonar-backend.ps1) are unaffected.
    Test-scope dependencies are excluded because they are not shipped.

.NOTES
    ASCII only - Windows PowerShell 5.1 reads BOM-less scripts as ANSI.
#>
param(
    [ValidateSet('json', 'xml')]
    [string] $Format = 'json'
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

Write-Host "Generating CycloneDX SBOM (format: $Format) ..." -ForegroundColor Cyan
& mvn -q cyclonedx:makeBom "-DoutputFormat=$Format"
if ($LASTEXITCODE -ne 0) {
    Write-Host "SBOM generation FAILED (exit $LASTEXITCODE)." -ForegroundColor Red
    exit $LASTEXITCODE
}

$bom = Join-Path $PSScriptRoot "sbom/bom.$Format"
if (-not (Test-Path $bom)) {
    Write-Host "Expected $bom but it was not produced." -ForegroundColor Red
    exit 1
}

$sizeKb = [math]::Round((Get-Item $bom).Length / 1KB, 1)
if ($Format -eq 'json') {
    $bomJson = Get-Content $bom -Raw | ConvertFrom-Json
    Write-Host ("SBOM written: {0} ({1} KB, {2} components)" -f $bom, $sizeKb, $bomJson.components.Count) -ForegroundColor Green
} else {
    Write-Host ("SBOM written: {0} ({1} KB)" -f $bom, $sizeKb) -ForegroundColor Green
}

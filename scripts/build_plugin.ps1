$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    if (Test-Path "C:\Program Files\Java\jdk-17") {
        $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
    }
    Write-Host "Compilando y empaquetando moai_tube..." -ForegroundColor Cyan
    gradle :plugin:packagePlugin --no-daemon
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host ""
    Write-Host "Artefactos generados en dist/:" -ForegroundColor Green
    Get-ChildItem -Path "dist" -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($Root.Length + 1)
        "{0,-40} {1,10:N0} bytes" -f $rel, $_.Length
    }
} finally {
    Pop-Location
}

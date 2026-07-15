$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "backup-mysql.ps1"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$externalRoot = "D:\1.hhh\backups\Chtholly-Hub"

function Invoke-ValidationCase {
    param(
        [string]$Name,
        [string]$DestinationRoot,
        [string]$Database,
        [int]$ExpectedExitCode
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -DestinationRoot $DestinationRoot -Database $Database -ValidateOnly 2>&1 | Out-Null
    $actualExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($actualExitCode -ne $ExpectedExitCode) {
        throw "$Name failed: expected exit $ExpectedExitCode, actual $actualExitCode"
    }
}

Invoke-ValidationCase "valid external D drive" $externalRoot "chtholly" 0
Invoke-ValidationCase "relative destination" "backups" "chtholly" 1
Invoke-ValidationCase "C drive destination" "C:\temp\chtholly-backup" "chtholly" 1
Invoke-ValidationCase "repository destination" (Join-Path $repoRoot ".codex-tmp\backup") "chtholly" 1
Invoke-ValidationCase "database injection" $externalRoot "chtholly;DROP_DATABASE" 1

$source = Get-Content $scriptPath -Raw -Encoding UTF8
foreach ($required in @("--single-transaction", "Get-FileHash", "ConvertTo-Json", "finally")) {
    if (-not $source.Contains($required)) {
        throw "backup script is missing required contract token: $required"
    }
}

Write-Host "backup-mysql contract tests passed" -ForegroundColor Green

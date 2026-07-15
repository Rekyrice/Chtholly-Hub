$ErrorActionPreference = "Stop"

$applyPath = Join-Path $PSScriptRoot "apply-migrations.ps1"
$seedPath = Join-Path $PSScriptRoot "run-seed.ps1"
$apply = Get-Content $applyPath -Raw -Encoding UTF8
$seed = Get-Content $seedPath -Raw -Encoding UTF8

foreach ($required in @("[string]`$Database", "Test-SafeDatabaseName", "`$env:MYSQL_DATABASE = `$Database")) {
    if (-not $apply.Contains($required)) {
        throw "apply-migrations database override contract missing: $required"
    }
    if (-not $seed.Contains($required)) {
        throw "run-seed database override contract missing: $required"
    }
}

if (-not $seed.Contains('apply-migrations.ps1") -Database $Database')) {
    throw "run-seed does not pass the database override to apply-migrations"
}
if ($seed.Contains("--default-character-set=utf8mb4 chtholly")) {
    throw "run-seed still hardcodes chtholly in a mysql command"
}
if (-not $seed.Contains(
        '--spring.main.web-application-type=none --spring.main.lazy-initialization=true')) {
    throw "all non-web seed modes must use lazy initialization to avoid servlet-only beans"
}
foreach ($required in @('taskkill.exe', '/T', '$Mode -eq "full"')) {
    if (-not $seed.Contains($required)) {
        throw "run-seed timeout/process-tree safety contract missing: $required"
    }
}

Write-Host "database override contract tests passed" -ForegroundColor Green

# Apply pending SQL migrations from apps/server/db/migration (tracked in schema_migrations)
. (Join-Path $PSScriptRoot "load-env.ps1")

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
}

$hostName = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "localhost" }
$port = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "3306" }
$user = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { "root" }
$password = $env:MYSQL_PASSWORD
$db = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { "chtholly" }

$dockerMysql = $null
if ($hostName -in @("localhost", "127.0.0.1")) {
    $dockerMysql = docker ps --format "{{.Names}}" 2>$null | Where-Object { $_ -eq "mysql" } | Select-Object -First 1
}

$mysql = Get-Command mysql -ErrorAction SilentlyContinue
$useDocker = [bool]$dockerMysql
if (-not $useDocker -and -not $mysql) {
    Write-Host "mysql CLI not found and no docker container 'mysql'. Install client or start docker mysql." -ForegroundColor Red
    exit 1
}

function Invoke-MysqlNative {
    param([string[]]$ExtraArgs, [string]$InputText)
    $args = @("-h", $hostName, "-P", $port, "-u", $user, "--default-character-set=utf8mb4", $db) + $ExtraArgs
    if ($password) { $env:MYSQL_PWD = $password }
    if ($InputText) {
        return $InputText | & mysql @args 2>&1
    }
    return & mysql @args 2>&1
}

function Invoke-MysqlDocker {
    param([string[]]$ExtraArgs, [string]$InputText)
    $args = @("exec", "-i")
    if ($password) { $args += @("-e", "MYSQL_PWD=$password") }
    $args += @($dockerMysql, "mysql", "-u$user", "--default-character-set=utf8mb4", $db) + $ExtraArgs
    if ($InputText) {
        return $InputText | & docker @args 2>&1
    }
    return & docker @args 2>&1
}

function Invoke-MysqlCore {
    param([string[]]$ExtraArgs, [string]$InputText)
    if ($useDocker) {
        return Invoke-MysqlDocker -ExtraArgs $ExtraArgs -InputText $InputText
    }
    return Invoke-MysqlNative -ExtraArgs $ExtraArgs -InputText $InputText
}

function Invoke-MysqlScalar {
    param([string]$Sql)
    $out = Invoke-MysqlCore -ExtraArgs @("-N", "-B", "-e", $Sql)
    if ($LASTEXITCODE -ne 0) { throw "MySQL failed: $out" }
    if ($null -eq $out) { return "" }
    if ($out -is [System.Array]) { return ($out | Select-Object -First 1) }
    return [string]$out
}

function Invoke-MysqlRows {
    param([string]$Sql)
    $out = Invoke-MysqlCore -ExtraArgs @("-N", "-B", "-e", $Sql)
    if ($LASTEXITCODE -ne 0) { throw "MySQL failed: $out" }
    if ($null -eq $out) { return @() }
    if ($out -is [System.Array]) { return @($out | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ }) }
    return @([string]$out)
}

function Invoke-MysqlFile {
    param([string]$Path)
    $content = Get-Content $Path -Raw -Encoding UTF8
    $out = Invoke-MysqlCore -ExtraArgs @() -InputText $content
    if ($LASTEXITCODE -ne 0) { throw "MySQL file failed: $Path -> $out" }
}

function Register-Migration {
    param([string]$Version)
    $escaped = $Version -replace "'", "''"
    Invoke-MysqlScalar "INSERT IGNORE INTO schema_migrations (version) VALUES ('$escaped');" | Out-Null
}

function Test-TableExists {
    param([string]$Table)
    $t = $Table -replace "'", "''"
    $d = $db -replace "'", "''"
    $count = Invoke-MysqlScalar "SELECT COUNT(1) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$d' AND TABLE_NAME = '$t';"
    return [int]$count -gt 0
}

function Test-ColumnExists {
    param([string]$Table, [string]$Column)
    $t = $Table -replace "'", "''"
    $c = $Column -replace "'", "''"
    $d = $db -replace "'", "''"
    $count = Invoke-MysqlScalar "SELECT COUNT(1) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '$d' AND TABLE_NAME = '$t' AND COLUMN_NAME = '$c';"
    return [int]$count -gt 0
}

function Test-IndexExists {
    param([string]$Table, [string]$Index)
    $t = $Table -replace "'", "''"
    $i = $Index -replace "'", "''"
    $d = $db -replace "'", "''"
    $count = Invoke-MysqlScalar "SELECT COUNT(1) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = '$d' AND TABLE_NAME = '$t' AND INDEX_NAME = '$i';"
    return [int]$count -gt 0
}

if ($useDocker) {
    Write-Host ">> Using docker mysql container: $dockerMysql" -ForegroundColor DarkGray
}

Write-Host ">> Ensure schema_migrations table" -ForegroundColor DarkGray
$migrationDir = Join-Path $RepoRoot "apps/server/db/migration"
$bootstrap = Join-Path $migrationDir "V0__schema_migrations.sql"
if (Test-Path $bootstrap) {
    Invoke-MysqlFile $bootstrap
}

$migrationCount = [int](Invoke-MysqlScalar "SELECT COUNT(1) FROM schema_migrations;")
if ($migrationCount -eq 0) {
    Write-Host ">> Auto-baseline existing database" -ForegroundColor Yellow
    if (Test-TableExists "posts") {
        Register-Migration "V3__rename_posts_and_handle"
        Register-Migration "V4__add_slug"
    }
    if (Test-TableExists "tags") {
        Register-Migration "V5__tags"
    }
    if (Test-TableExists "comments") {
        Register-Migration "V6__comments"
    }
    if (Test-TableExists "notifications") {
        Register-Migration "V7__notifications"
    }
    if (Test-TableExists "bangumi_subjects") {
        Register-Migration "V8__bangumi_tables"
    }
    if ((Test-TableExists "comments") -and (Test-ColumnExists "comments" "deleted_at")) {
        Register-Migration "V9__comment_soft_delete"
    }
    if ((Test-TableExists "notifications") -and (Test-IndexExists "notifications" "idx_cleanup")) {
        Register-Migration "V10__notification_cleanup_index"
    }
}

$appliedList = Invoke-MysqlRows "SELECT version FROM schema_migrations;"
$applied = @{}
foreach ($v in $appliedList) {
    if ($v) { $applied[$v] = $true }
}

# 列/表已存在但未登记 migration 时自动补 baseline（常见于手动改库或中断）
if ((Test-TableExists "posts") -and (Test-ColumnExists "posts" "content_analysis") -and -not $applied.ContainsKey("V15__add_content_analysis_field")) {
    Write-Host ">> Baseline V15 (content_analysis already exists)" -ForegroundColor Yellow
    Register-Migration "V15__add_content_analysis_field"
    $applied["V15__add_content_analysis_field"] = $true
}
if ((Test-TableExists "comments") -and (Test-ColumnExists "comments" "is_chtholly") -and -not $applied.ContainsKey("V16__chtholly_comments")) {
    Write-Host ">> Baseline V16 (is_chtholly already exists)" -ForegroundColor Yellow
    Register-Migration "V16__chtholly_comments"
    $applied["V16__chtholly_comments"] = $true
}
if ((Test-TableExists "execution_traces") -and -not $applied.ContainsKey("V17__add_execution_traces")) {
    Write-Host ">> Baseline V17 (execution_traces already exists)" -ForegroundColor Yellow
    Register-Migration "V17__add_execution_traces"
    $applied["V17__add_execution_traces"] = $true
}
if ((Test-TableExists "knowledge_entities") -and -not $applied.ContainsKey("V20__knowledge_graph")) {
    Write-Host ">> Baseline V20 (knowledge_entities already exists)" -ForegroundColor Yellow
    Register-Migration "V20__knowledge_graph"
    $applied["V20__knowledge_graph"] = $true
}
if ((Test-TableExists "users") -and (Invoke-MysqlScalar "SELECT COUNT(1) FROM users WHERE id = 888888888888888888;") -gt 0 -and -not $applied.ContainsKey("V21__chtholly_bot_user")) {
    Write-Host ">> Baseline V21 (chtholly bot user already exists)" -ForegroundColor Yellow
    Register-Migration "V21__chtholly_bot_user"
    $applied["V21__chtholly_bot_user"] = $true
}

$files = Get-ChildItem (Join-Path $migrationDir "V*.sql") | Sort-Object {
    if ($_.BaseName -match '^V(\d+)__') { [int]$Matches[1] } else { 999999 }
}
$ran = 0
foreach ($file in $files) {
    $version = $file.BaseName
    if ($applied.ContainsKey($version)) {
        continue
    }
    Write-Host ">> migration: $($file.Name)" -ForegroundColor Cyan
    try {
        Invoke-MysqlFile $file.FullName
        Register-Migration $version
        $ran++
    } catch {
        Write-Host $_ -ForegroundColor Red
        Write-Host "If already applied manually: INSERT INTO schema_migrations (version) VALUES ('$version');" -ForegroundColor Yellow
        exit 1
    }
}

if ($ran -eq 0) {
    Write-Host ">> No pending migrations" -ForegroundColor Green
} else {
    Write-Host ">> Applied $ran migration(s)" -ForegroundColor Green
}

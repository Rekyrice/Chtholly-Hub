# Chtholly Hub backend - verification codes go to logs/dev-verification.log
. (Join-Path $PSScriptRoot "load-env.ps1")

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
}

$serverPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8888 }

$logDir = Join-Path $RepoRoot "logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$codeLog = Join-Path $logDir "dev-verification.log"

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host " Chtholly Hub backend  http://localhost:$serverPort" -ForegroundColor Green
Write-Host " Verification log: $codeLog" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

# 清理残留进程（含 Cursor Agent 后台调试留下的，避免「只点一次却端口占用」）
& (Join-Path $PSScriptRoot "stop-backend.ps1")

$other = Get-NetTCPConnection -LocalPort $serverPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($other) {
    $pid = $other.OwningProcess
    Write-Host "Port $serverPort still held by PID $pid (not Chtholly). Stop it or set SERVER_PORT." -ForegroundColor Red
    exit 1
}

Write-Host "Applying DB migrations..." -ForegroundColor DarkGray
& (Join-Path $PSScriptRoot "apply-migrations.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# apply-migrations 会再次 load-env 并把 cwd 切回仓库根目录，此处必须重新进入 apps/server
Set-Location (Join-Path $RepoRoot "apps/server")

Write-Host "Compiling..." -ForegroundColor DarkGray
& mvn -q compile "-Dmaven.test.skip=true"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Starting..." -ForegroundColor DarkGray
& mvn spring-boot:run "-Dmaven.test.skip=true"

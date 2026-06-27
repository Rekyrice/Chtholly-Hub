# Chtholly Hub backend - verification codes go to logs/dev-verification.log
. (Join-Path $PSScriptRoot "load-env.ps1")

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

Set-Location (Join-Path $RepoRoot "apps/server")

$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
if (Test-Path $javaExe) {
    $verLine = & $javaExe -version 2>&1 | Select-Object -First 1
    Write-Host "Java: $verLine" -ForegroundColor DarkGray
    if ($verLine -notmatch '"21\.') {
        Write-Host "WARN: 项目需要 JDK 21。请在 .env 设置 JAVA_HOME，或安装 Temurin 21。" -ForegroundColor Yellow
    }
}

Write-Host "Compiling..." -ForegroundColor DarkGray
& mvn -q compile "-Dmaven.test.skip=true"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Starting..." -ForegroundColor DarkGray
& mvn spring-boot:run "-Dmaven.test.skip=true"

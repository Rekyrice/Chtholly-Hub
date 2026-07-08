param(
    [ValidateSet("full", "bangumi", "accounts", "content_only", "content-only")]
    [string]$Mode = "full",
    [switch]$ResetMarker
)

# 一次性冷启动种子：在备用端口启动后端，写入 MySQL + ES 后自动退出
. (Join-Path $PSScriptRoot "load-env.ps1")

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
}

$seedPort = if ($env:SEED_SERVER_PORT) { [int]$env:SEED_SERVER_PORT } else { 8899 }
$serverPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8888 }

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Chtholly Hub seed runner  :$seedPort  mode=$Mode" -ForegroundColor Cyan
Write-Host " (主后端 $serverPort 可保持运行)" -ForegroundColor DarkGray
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

& (Join-Path $PSScriptRoot "apply-migrations.ps1")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

. (Join-Path $PSScriptRoot "load-env.ps1")

if ($ResetMarker) {
    $marker = if ($Mode -eq "content_only" -or $Mode -eq "content-only") { "seed:content_only" } else { $Mode }
    Write-Host "ResetMarker: clearing seed marker '$marker'..." -ForegroundColor Yellow
    docker exec -e "MYSQL_PWD=$env:MYSQL_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 chtholly -e "DELETE FROM seed_data WHERE seed_key='$marker';" 2>$null | Out-Null
}

# content_only 用模板正文即可，关闭 LLM 避免 32 篇生成超时
$savedLlmEnabled = $env:LLM_ENABLED
if ($Mode -eq "content_only" -or $Mode -eq "content-only") {
    $env:LLM_ENABLED = "false"
    $profiles = $env:SPRING_PROFILES_ACTIVE.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -ne "llm" }
    if ($profiles -notcontains "dev") { $profiles = @("dev") + $profiles }
    $env:SPRING_PROFILES_ACTIVE = ($profiles | Select-Object -Unique) -join ","
}

$listener = Get-NetTCPConnection -LocalPort $seedPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    Write-Host "Stopping process on port $seedPort (PID $($listener.OwningProcess))..." -ForegroundColor Yellow
    Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Set-Location (Join-Path $RepoRoot "apps/server")

Write-Host "Compiling..." -ForegroundColor DarkGray
& mvn -q compile "-Dmaven.test.skip=true"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$env:SERVER_PORT = "$seedPort"
Write-Host "Running seed (profile=$env:SPRING_PROFILES_ACTIVE)..." -ForegroundColor DarkGray

$logFile = Join-Path $RepoRoot "logs/seed-run.log"
New-Item -ItemType Directory -Force -Path (Split-Path $logFile) | Out-Null

$proc = Start-Process -FilePath "mvn" `
    -ArgumentList @(
        "spring-boot:run",
        "-Dmaven.test.skip=true",
        "-Dspring-boot.run.arguments=--seed.mode=$Mode"
    ) `
    -WorkingDirectory (Join-Path $RepoRoot "apps/server") `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError (Join-Path $RepoRoot "logs/seed-run.err.log") `
    -PassThru `
    -NoNewWindow

$timeoutMinutes = if ($Mode -eq "content_only" -or $Mode -eq "content-only") { 20 } else { 8 }
$deadline = (Get-Date).AddMinutes($timeoutMinutes)
$finished = $false
while ((Get-Date) -lt $deadline) {
    if ($proc.HasExited -and -not $finished) { break }
    if (Test-Path $logFile) {
        $tail = (Get-Content $logFile -Tail 80 -ErrorAction SilentlyContinue) -join "`n"
        if ($tail -match "Seed run finished") {
            $finished = $true
            break
        }
        if ($tail -match "APPLICATION FAILED TO START") {
            Write-Host "Seed startup failed. See $logFile" -ForegroundColor Red
            if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
            exit 1
        }
    }
    Start-Sleep -Seconds 2
}

if (-not $proc.HasExited) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
}

if (-not $finished) {
    Write-Host "Seed did not finish in time. See $logFile" -ForegroundColor Red
    exit 1
}

Get-Content $logFile -Tail 8 | ForEach-Object { Write-Host $_ }

$postCount = docker exec -e "MYSQL_PWD=$env:MYSQL_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 chtholly -N -B -e "SELECT COUNT(*) FROM posts WHERE status='published' AND visible='public';" 2>$null
Write-Host ""
Write-Host "MySQL published posts: $postCount" -ForegroundColor Green
Write-Host "请重启主后端 (http://localhost:$serverPort) 后刷新 /hub" -ForegroundColor Yellow

if ($null -ne $savedLlmEnabled) {
    $env:LLM_ENABLED = $savedLlmEnabled
}

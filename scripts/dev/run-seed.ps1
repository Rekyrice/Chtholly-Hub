param(
    [ValidateSet("full", "bangumi", "accounts", "content_only", "content-only", "content_pack", "content-pack")]
    [string]$Mode = "full",
    [switch]$DryRun,
    [switch]$ResetMarker
)

# Run one seed job on a spare port while the main backend may remain online.
. (Join-Path $PSScriptRoot "load-env.ps1")

if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
}

$isContentPack = $Mode -eq "content_pack" -or $Mode -eq "content-pack"
$seedPort = if ($env:SEED_SERVER_PORT) { [int]$env:SEED_SERVER_PORT } else { 8899 }
$serverPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8888 }

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Chtholly Hub seed runner  :$seedPort  mode=$Mode" -ForegroundColor Cyan
Write-Host " (main backend :$serverPort may remain online)" -ForegroundColor DarkGray
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# A content-pack dry-run only reads and validates the package. Applying migrations here would
# violate its no-mutation guarantee before the importer even starts.
if (-not ($isContentPack -and $DryRun)) {
    & (Join-Path $PSScriptRoot "apply-migrations.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

. (Join-Path $PSScriptRoot "load-env.ps1")

if ($ResetMarker) {
    if ($DryRun) {
        Write-Host "ResetMarker cannot be combined with DryRun." -ForegroundColor Red
        exit 1
    }
    if ($isContentPack) {
        Write-Host "ResetMarker is not supported for content-pack imports; reruns use stable identities." -ForegroundColor Red
        exit 1
    }
    $marker = if ($Mode -eq "content_only" -or $Mode -eq "content-only") { "seed:content_only" } else { $Mode }
    Write-Host "ResetMarker: clearing seed marker '$marker'..." -ForegroundColor Yellow
    docker exec -e "MYSQL_PWD=$env:MYSQL_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 chtholly -e "DELETE FROM seed_data WHERE seed_key='$marker';" 2>$null | Out-Null
}

# content_only uses template bodies; disabling the LLM avoids unnecessary generation latency.
$savedLlmEnabled = $env:LLM_ENABLED
if ($Mode -eq "content_only" -or $Mode -eq "content-only") {
    $env:LLM_ENABLED = "false"
    $profiles = $env:SPRING_PROFILES_ACTIVE.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -ne "llm" }
    if ($profiles -notcontains "dev") { $profiles = @("dev") + $profiles }
    $env:SPRING_PROFILES_ACTIVE = ($profiles | Select-Object -Unique) -join ","
}

if (-not $isContentPack) {
    $listener = Get-NetTCPConnection -LocalPort $seedPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($listener) {
        Write-Host "Stopping process on port $seedPort (PID $($listener.OwningProcess))..." -ForegroundColor Yellow
        Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}

Set-Location (Join-Path $RepoRoot "apps/server")

Write-Host "Compiling..." -ForegroundColor DarkGray
& mvn -q compile "-Dmaven.test.skip=true"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$env:SERVER_PORT = "$seedPort"
Write-Host "Running seed (profile=$env:SPRING_PROFILES_ACTIVE)..." -ForegroundColor DarkGray

$logFile = Join-Path $RepoRoot "apps/server/target/seed-run.log"
$errorLogFile = Join-Path $RepoRoot "apps/server/target/seed-run.err.log"

$applicationArguments = "--seed.mode=$Mode --spring.main.web-application-type=none"
if ($DryRun) {
    $applicationArguments += " --seed.dry-run=true"
}
if ($isContentPack -and $DryRun) {
    $applicationArguments += " --seed.cli-read-only=true --kafka.enabled=false --canal.enabled=false"
    $applicationArguments += " --bangumi.enabled=false --spring.main.lazy-initialization=true"
}
$bootRunProperty = "-Dspring-boot.run.arguments=`"$applicationArguments`""

$proc = Start-Process -FilePath "mvn" `
    -ArgumentList @("spring-boot:run", "-Dmaven.test.skip=true", $bootRunProperty) `
    -WorkingDirectory (Join-Path $RepoRoot "apps/server") `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError $errorLogFile `
    -PassThru `
    -NoNewWindow

$timeoutMinutes = if ($Mode -eq "content_only" -or $Mode -eq "content-only" -or $isContentPack) { 20 } else { 8 }
$deadline = (Get-Date).AddMinutes($timeoutMinutes)
$finished = $false
$contentPackReport = $null

while ((Get-Date) -lt $deadline) {
    if (Test-Path $logFile) {
        $tail = (Get-Content $logFile -Tail 80 -ErrorAction SilentlyContinue) -join "`n"
        if ($isContentPack -and $tail -match 'SEED_CONTENT_PACK_REPORT=(\{[^\r\n]+\})') {
            try {
                $contentPackReport = $matches[1] | ConvertFrom-Json
                $finished = $true
                break
            } catch {
                Write-Host "Seed returned malformed content-pack report JSON. See $logFile" -ForegroundColor Red
                if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
                exit 1
            }
        }
        if (-not $isContentPack -and $tail -match "Seed run finished") {
            $finished = $true
            break
        }
        if ($tail -match "APPLICATION FAILED TO START") {
            Write-Host "Seed startup failed. See $logFile" -ForegroundColor Red
            if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
            exit 1
        }
    }
    if ($proc.HasExited) { break }
    Start-Sleep -Seconds 2
}

if (-not $proc.HasExited) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
}

if (-not $finished) {
    Write-Host "Seed did not produce a completion report. See $logFile" -ForegroundColor Red
    exit 1
}

Get-Content $logFile -Tail 8 | ForEach-Object { Write-Host $_ }

if ($isContentPack) {
    $status = [string]$contentPackReport.status
    $failedStage = [string]$contentPackReport.failedStage
    $stageSuffix = if ($failedStage) { " (stage=$failedStage)" } else { "" }
    Write-Host ""
    Write-Host "Content-pack status: $status$stageSuffix"
    if ($status -eq "validated" -or $status -eq "completed") { exit 0 }
    if ($status -eq "partial") { exit 2 }
    exit 1
}

$postCount = docker exec -e "MYSQL_PWD=$env:MYSQL_PASSWORD" mysql mysql -uroot --default-character-set=utf8mb4 chtholly -N -B -e "SELECT COUNT(*) FROM posts WHERE status='published' AND visible='public';" 2>$null
Write-Host ""
Write-Host "MySQL published posts: $postCount" -ForegroundColor Green
Write-Host "Restart the main backend (http://localhost:$serverPort), then refresh /hub" -ForegroundColor Yellow

if ($null -ne $savedLlmEnabled) {
    $env:LLM_ENABLED = $savedLlmEnabled
}

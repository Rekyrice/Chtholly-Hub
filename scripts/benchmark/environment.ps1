[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Validate', 'Start', 'Stop')]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$RunId,

    [ValidateSet('smoke', 'standard')]
    [string]$Profile = 'smoke',

    [ValidateSet('stable-hot', 'expiry-spike')]
    [string]$Scenario = 'stable-hot',

    [ValidateSet('db-only', 'full-no-singleflight', 'full')]
    [string]$Variant = 'full',

    [ValidateRange(1024, 65535)]
    [int]$Port = 18888,

    [ValidateRange(1024, 65535)]
    [int]$MysqlPort = 13306,

    [ValidateRange(1024, 65535)]
    [int]$RedisPort = 16379
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$resultsRoot = Join-Path $repoRoot '.benchmark-results'
$runtimeBase = Join-Path $resultsRoot '.runtime'
$runtimeRoot = Join-Path $runtimeBase $RunId
$resultDirectory = Join-Path $resultsRoot $RunId
$sourceCompose = Join-Path $repoRoot 'docker-compose.prod.yml'
$overrideCompose = Join-Path $runtimeRoot 'compose.override.yml'
$environmentFile = Join-Path $runtimeRoot '.env'
$runtimeManifestPath = Join-Path $resultDirectory 'environment-runtime.json'

$safeRunId = ($RunId.ToLowerInvariant() -replace '[^a-z0-9-]', '-').Trim('-')
if ($safeRunId.Length -gt 20) { $safeRunId = $safeRunId.Substring(0, 20).TrimEnd('-') }
$hash = [Security.Cryptography.SHA256]::Create()
try {
    $runIdHash = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($RunId))).Replace('-', '').ToLowerInvariant()
}
finally { $hash.Dispose() }
$projectName = "chtholly-bm-$safeRunId-$($runIdHash.Substring(0, 12))"
$services = @('mysql', 'redis', 'server')
$plan = [ordered]@{
    action = $Action
    runId = $RunId
    projectName = $projectName
    isolated = $true
    removeVolumesOnStop = $true
    profile = $Profile
    scenario = $Scenario
    variant = $Variant
    cacheReadMode = $Variant
    services = $services
    hostBaseUrl = "http://127.0.0.1:$Port"
    k6BaseUrl = 'http://server:8888'
    dependencyPorts = [ordered]@{ mysql = $MysqlPort; redis = $RedisPort }
}
if ($Action -eq 'Validate') {
    $plan | ConvertTo-Json -Depth 5
    exit 0
}

function Assert-RuntimePath {
    param([string]$Path)
    $resolvedBase = (Resolve-Path -LiteralPath $runtimeBase).Path + [IO.Path]::DirectorySeparatorChar
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    if (-not $resolvedPath.StartsWith($resolvedBase, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe benchmark runtime path: $resolvedPath"
    }
    return $resolvedPath
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & docker compose -p $projectName --env-file $environmentFile -f $sourceCompose -f $overrideCompose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed with exit code $LASTEXITCODE" }
}

function Get-OwnedServerContainer {
    $path = Join-Path $runtimeRoot 'server.container-id'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
    $containerId = (Get-Content -LiteralPath $path -Encoding ascii | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) { return $null }
    $inspectText = (& docker inspect $containerId 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($inspectText)) { return $null }
    $inspect = $inspectText | ConvertFrom-Json | Select-Object -First 1
    if ($inspect.Config.Labels.'chtholly.benchmark.owner' -ne $RunId) {
        throw "Refusing to operate on an unowned server container: $containerId"
    }
    return $containerId
}

function Preserve-ServerLog {
    $containerId = Get-OwnedServerContainer
    if ($null -eq $containerId) { return }
    $rawDirectory = Join-Path $resultDirectory 'raw'
    New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null
    & docker logs $containerId 2>&1 | Set-Content -LiteralPath (Join-Path $rawDirectory 'server.log') -Encoding UTF8
}

function Stop-OwnedEnvironment {
    if (-not (Test-Path -LiteralPath $runtimeRoot -PathType Container)) { return }
    Assert-RuntimePath -Path $runtimeRoot | Out-Null
    try { Preserve-ServerLog } catch { }
    $containerId = Get-OwnedServerContainer
    if ($null -ne $containerId) {
        & docker rm -f $containerId | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Cannot remove owned server container: $containerId" }
    }
    Invoke-Compose down --volumes --remove-orphans
    Remove-Item -LiteralPath $runtimeRoot -Recurse -Force
}

if ($Action -eq 'Stop') {
    if (-not (Test-Path -LiteralPath $runtimeRoot -PathType Container)) {
        throw "Benchmark environment is not registered: $RunId"
    }
    Stop-OwnedEnvironment
    Write-Output "Stopped isolated benchmark environment $projectName."
    exit 0
}

if (Test-Path -LiteralPath $runtimeRoot) { throw "Benchmark environment already exists: $RunId" }
$executionCommit = (git -C $repoRoot rev-parse HEAD).Trim()
$executionDirty = -not [string]::IsNullOrWhiteSpace((git -C $repoRoot status --porcelain | Out-String))
if ($Profile -eq 'standard' -and $executionDirty) { throw 'Standard benchmark environments require a clean worktree' }

New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null
Assert-RuntimePath -Path $runtimeRoot | Out-Null
$rootPassword = 'bm-root-' + [Guid]::NewGuid().ToString('N')
$appPassword = 'bm-app-' + [Guid]::NewGuid().ToString('N')
@(
    "MYSQL_ROOT_PASSWORD=$rootPassword",
    'MYSQL_USER=chtholly',
    "MYSQL_PASSWORD=$appPassword",
    'MYSQL_DATABASE=chtholly',
    'NEXT_PUBLIC_SITE_URL=http://localhost',
    'NEXT_PUBLIC_OSS_PUBLIC_URL=http://localhost/uploads',
    'KAFKA_ENABLED=false',
    'LLM_ENABLED=false'
) | Set-Content -LiteralPath $environmentFile -Encoding ascii

@"
services:
  mysql:
    ports:
      - "127.0.0.1:${MysqlPort}:3306"
    volumes:
      - ./apps/server/db/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro
  redis:
    ports:
      - "127.0.0.1:${RedisPort}:6379"
"@ | Set-Content -LiteralPath $overrideCompose -Encoding UTF8

try {
    Invoke-Compose config --quiet
    Invoke-Compose up -d --wait --wait-timeout 240 mysql redis
    $containerIds = [ordered]@{}
    foreach ($service in @('mysql', 'redis')) {
        $id = ((& docker compose -p $projectName --env-file $environmentFile -f $sourceCompose -f $overrideCompose ps -q $service | Out-String).Trim())
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($id)) { throw "Cannot resolve container: $service" }
        $containerIds[$service] = $id
    }

    $previousPassword = $env:BENCHMARK_MYSQL_ROOT_PASSWORD
    try {
        $env:BENCHMARK_MYSQL_ROOT_PASSWORD = $rootPassword
        & (Join-Path $PSScriptRoot 'seed.ps1') -Profile $Profile -MysqlContainer $containerIds.mysql -RedisContainer $containerIds.redis
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark seed failed' }
    }
    finally { $env:BENCHMARK_MYSQL_ROOT_PASSWORD = $previousPassword }

    $serverDirectory = Join-Path $repoRoot 'apps/server'
    Push-Location $serverDirectory
    try {
        & mvn -q -DskipTests clean package
        if ($LASTEXITCODE -ne 0) { throw 'Building the benchmark server failed' }
    }
    finally { Pop-Location }
    $serverJar = Get-ChildItem -LiteralPath (Join-Path $serverDirectory 'target') -Filter 'chtholly-server-*.jar' -File |
        Where-Object Name -NotLike '*.original' | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $serverJar) { throw 'Cannot find the packaged server JAR' }

    $network = ((& docker network ls --filter "label=com.docker.compose.project=$projectName" --format '{{.Name}}' | Out-String).Trim())
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($network)) { throw 'Cannot resolve the benchmark network' }
    $runtimeImage = 'apache/kafka:latest'
    & docker image inspect $runtimeImage *> $null
    if ($LASTEXITCODE -ne 0) { throw "Required local Java runtime image is unavailable: $runtimeImage" }

    $serverEnvironmentFile = Join-Path $runtimeRoot 'server.env'
    @(
        'SERVER_PORT=8888',
        'MYSQL_HOST=mysql',
        'MYSQL_PORT=3306',
        'MYSQL_DATABASE=chtholly',
        'MYSQL_USER=chtholly',
        "MYSQL_PASSWORD=$appPassword",
        'REDIS_HOST=redis',
        'REDIS_PORT=6379',
        'KAFKA_ENABLED=false',
        'ES_URIS=http://127.0.0.1:1',
        'CANAL_ENABLED=false',
        'LLM_ENABLED=false',
        'STORAGE_LOCAL_PATH=/tmp/chtholly-uploads',
        "CACHE_READ_MODE=$Variant",
        "CACHE_BENCHMARK_SCENARIO=$Scenario",
        'LOGGING_LEVEL_COM_CHTHOLLY=WARN',
        'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics,prometheus'
    ) | Set-Content -LiteralPath $serverEnvironmentFile -Encoding ascii

    $serverContainerId = ((& docker run -d --name "$projectName-server" `
        --label "chtholly.benchmark.owner=$RunId" --network $network --network-alias server `
        --memory 1536m -p "127.0.0.1:$Port`:8888" --env-file $serverEnvironmentFile `
        --mount "type=bind,source=$($serverJar.FullName),target=/app/app.jar,readonly" `
        --entrypoint java --pull never $runtimeImage -jar /app/app.jar | Out-String).Trim())
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($serverContainerId)) { throw 'Starting the server container failed' }
    Set-Content -LiteralPath (Join-Path $runtimeRoot 'server.container-id') -Value $serverContainerId -Encoding ascii

    $healthy = $false
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5
            if ($health.status -eq 'UP') { $healthy = $true; break }
        }
        catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $healthy) { throw "Benchmark server failed to become healthy on port $Port" }

    $runtimeManifest = [ordered]@{
        schemaVersion = 1
        capturedAt = [DateTimeOffset]::UtcNow.ToString('o')
        runId = $RunId
        projectName = $projectName
        profile = $Profile
        scenario = $Scenario
        variant = $Variant
        cacheReadMode = $Variant
        hostBaseUrl = "http://127.0.0.1:$Port"
        k6BaseUrl = 'http://server:8888'
        benchmarkNetwork = $network
        services = $services
        containerIds = [ordered]@{ mysql = $containerIds.mysql; redis = $containerIds.redis; server = $serverContainerId }
        executionCommit = $executionCommit
        executionDirty = $executionDirty
        serverJarSha256 = (Get-FileHash -LiteralPath $serverJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        secretsRecorded = $false
    }
    $runtimeManifest | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $runtimeManifestPath -Encoding UTF8
    $runtimeManifest | ConvertTo-Json -Depth 7
}
catch {
    try { Stop-OwnedEnvironment } catch { }
    throw
}

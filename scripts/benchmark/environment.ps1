[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Validate', 'Start', 'Status', 'Stop')]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$RunId,

    [ValidateSet('smoke', 'standard')]
    [string]$Profile = 'smoke',

    [ValidateSet('db-only', 'redis-db', 'full')]
    [string]$Variant = 'full',

    [ValidateRange(1024, 65535)]
    [int]$Port = 18888,

    [ValidateRange(1024, 65535)]
    [int]$MysqlPort = 13306,

    [ValidateRange(1024, 65535)]
    [int]$RedisPort = 16379,

    [ValidateRange(1024, 65535)]
    [int]$KafkaPort = 19092,

    [ValidateRange(1024, 65535)]
    [int]$ElasticsearchPort = 19200
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
$runIdHashAlgorithm = [Security.Cryptography.SHA256]::Create()
try {
    $runIdHash = [BitConverter]::ToString(
        $runIdHashAlgorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($RunId))).Replace('-', '').ToLowerInvariant()
}
finally {
    $runIdHashAlgorithm.Dispose()
}
$projectName = "chtholly-bm-$safeRunId-$($runIdHash.Substring(0, 12))"
$services = @('mysql', 'redis', 'kafka', 'elasticsearch', 'server')
$cacheReadMode = switch ($Variant) {
    'db-only' { 'db-only' }
    'redis-db' { 'redis' }
    default { 'full' }
}
$plan = [ordered]@{
    action = $Action
    runId = $RunId
    projectName = $projectName
    isolated = $true
    removeVolumesOnStop = $true
    profile = $Profile
    variant = $Variant
    cacheReadMode = $cacheReadMode
    port = $Port
    hostBaseUrl = "http://127.0.0.1:$Port"
    k6BaseUrl = 'http://server:8888'
    services = $services
    sourceCompose = 'docker-compose.prod.yml'
    serverRuntime = 'compose-container'
    dependencyPorts = [ordered]@{
        mysql = $MysqlPort
        redis = $RedisPort
        kafka = $KafkaPort
        elasticsearch = $ElasticsearchPort
    }
}

if ($Action -eq 'Validate') {
    $plan | ConvertTo-Json -Depth 5
    exit 0
}

function Assert-RuntimePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $resolvedBase = (Resolve-Path -LiteralPath $runtimeBase).Path + [IO.Path]::DirectorySeparatorChar
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    if (-not $resolvedPath.StartsWith($resolvedBase, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe benchmark runtime path: $resolvedPath"
    }
    return $resolvedPath
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $composeArguments = @('compose', '-p', $projectName, '--env-file', $environmentFile,
        '-f', $sourceCompose, '-f', $overrideCompose) + $Arguments
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & docker @composeArguments
        $composeExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($composeExitCode -ne 0) {
        throw "Docker Compose failed with exit code $composeExitCode."
    }
}

function Get-OwnedServerContainer {
    $containerIdPath = Join-Path $runtimeRoot 'server.container-id'
    if (-not (Test-Path -LiteralPath $containerIdPath -PathType Leaf)) {
        return $null
    }
    $containerId = (Get-Content -LiteralPath $containerIdPath -Encoding ascii | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        return $null
    }
    $inspectJson = (& docker inspect $containerId 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($inspectJson)) {
        return $null
    }
    $inspect = ($inspectJson | ConvertFrom-Json | Select-Object -First 1)
    $name = $inspect.Name.TrimStart('/')
    $owner = $inspect.Config.Labels.'chtholly.benchmark.owner'
    if ($name -ne "$projectName-server" -or $owner -ne $RunId) {
        throw "Refusing to operate on an unverified benchmark server container: $containerId"
    }
    return $containerId
}

function Stop-OwnedServerContainer {
    $containerId = Get-OwnedServerContainer
    if ($null -ne $containerId) {
        & docker rm -f $containerId | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Cannot remove owned benchmark server container: $containerId"
        }
    }
}

function Preserve-ServerLogs {
    $containerId = Get-OwnedServerContainer
    if ($null -ne $containerId) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & docker logs $containerId 2>&1 |
                Set-Content -LiteralPath (Join-Path $runtimeRoot 'server.stdout.log') -Encoding utf8
        }
        finally { $ErrorActionPreference = $previousErrorActionPreference }
    }
    $rawDirectory = Join-Path $resultDirectory 'raw'
    New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null
    foreach ($name in @('server.stdout.log', 'server.stderr.log', 'server-build.log')) {
        $source = Join-Path $runtimeRoot $name
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $rawDirectory $name) -Force
        }
    }
}

if ($Action -in @('Status', 'Stop')) {
    if (-not (Test-Path -LiteralPath $runtimeRoot -PathType Container)) {
        throw "Benchmark environment is not registered: $RunId"
    }
    Assert-RuntimePath -Path $runtimeRoot | Out-Null
    if (-not $projectName.StartsWith('chtholly-bm-', [StringComparison]::Ordinal)) {
        throw "Refusing to operate on an unowned Compose project: $projectName"
    }
    if ($Action -eq 'Status') {
        Invoke-Compose ps
        $serverContainer = Get-OwnedServerContainer
        $serverRunning = $null -ne $serverContainer -and
            ((& docker inspect --format '{{.State.Running}}' $serverContainer | Out-String).Trim() -eq 'true')
        Write-Output "server-container-running=$serverRunning"
        exit 0
    }

    Preserve-ServerLogs
    Stop-OwnedServerContainer
    Invoke-Compose down --volumes --remove-orphans
    Remove-Item -LiteralPath $runtimeRoot -Recurse -Force
    Write-Output "Stopped isolated benchmark environment $projectName."
    exit 0
}

$executionCommit = (git -C $repoRoot rev-parse HEAD).Trim()
$executionDirty = -not [string]::IsNullOrWhiteSpace((git -C $repoRoot status --porcelain | Out-String))
if ($Profile -eq 'standard' -and $executionDirty) {
    throw 'Formal standard benchmark environment requires a clean worktree'
}

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
    'KAFKA_ENABLED=true',
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
  elasticsearch:
    ports:
      - "127.0.0.1:${ElasticsearchPort}:9200"
  kafka:
    ports:
      - "127.0.0.1:${KafkaPort}:29092"
    environment:
      KAFKA_LISTENERS: "PLAINTEXT://0.0.0.0:9092,EXTERNAL://0.0.0.0:29092,CONTROLLER://0.0.0.0:9093"
      KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://kafka:9092,EXTERNAL://127.0.0.1:${KafkaPort}"
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT"
"@ | Set-Content -LiteralPath $overrideCompose -Encoding utf8

try {
    Invoke-Compose config --quiet
    Invoke-Compose up -d --wait --wait-timeout 420 mysql redis elasticsearch kafka

    $containerIds = [ordered]@{}
    $containerNames = [ordered]@{}
    foreach ($service in @('mysql', 'redis', 'kafka', 'elasticsearch')) {
        $idOutput = & docker compose -p $projectName --env-file $environmentFile `
            -f $sourceCompose -f $overrideCompose ps -q $service
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($idOutput | Out-String))) {
            throw "Cannot resolve container for benchmark service: $service"
        }
        $containerId = (($idOutput | Out-String).Trim())
        $containerIds[$service] = $containerId
        $containerNames[$service] = ((& docker inspect --format '{{.Name}}' $containerId | Out-String).Trim()).TrimStart('/')
    }

    $previousRootPassword = $env:BENCHMARK_MYSQL_ROOT_PASSWORD
    $previousDatabase = $env:BENCHMARK_MYSQL_DATABASE
    try {
        $env:BENCHMARK_MYSQL_ROOT_PASSWORD = $rootPassword
        $env:BENCHMARK_MYSQL_DATABASE = 'chtholly'
        & (Join-Path $PSScriptRoot 'seed.ps1') -Profile $Profile `
            -MysqlContainer $containerIds.mysql -RedisContainer $containerIds.redis
        if ($LASTEXITCODE -ne 0) { throw "Benchmark seed failed with exit code $LASTEXITCODE." }
    }
    finally {
        $env:BENCHMARK_MYSQL_ROOT_PASSWORD = $previousRootPassword
        $env:BENCHMARK_MYSQL_DATABASE = $previousDatabase
    }

    $serverDirectory = Join-Path $repoRoot 'apps/server'
    $buildLog = Join-Path $runtimeRoot 'server-build.log'
    Push-Location $serverDirectory
    try {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & mvn -q -DskipTests clean package 2>&1 | Tee-Object -FilePath $buildLog
            $buildExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    }
    finally { Pop-Location }
    if ($buildExitCode -ne 0) {
        throw "Building the benchmark server failed with exit code $buildExitCode."
    }
    $serverJar = Get-ChildItem -LiteralPath (Join-Path $serverDirectory 'target') -Filter 'chtholly-server-*.jar' -File |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $serverJar) {
        throw 'Cannot find the packaged benchmark server JAR.'
    }

    $serverJarSha256 = (Get-FileHash -LiteralPath $serverJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $serverRuntimeImage = 'apache/kafka:latest'
    & docker image inspect $serverRuntimeImage *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Required local Java 21 runtime image is unavailable: $serverRuntimeImage"
    }

    $benchmarkNetwork = ((& docker network ls `
        --filter "label=com.docker.compose.project=$projectName" `
        --filter 'label=com.docker.compose.network=default' `
        --format '{{.Name}}' | Out-String).Trim())
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($benchmarkNetwork)) {
        throw 'Cannot resolve the isolated benchmark network.'
    }
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
        'KAFKA_ENABLED=true',
        'KAFKA_BOOTSTRAP_SERVERS=kafka:9092',
        'ES_URIS=http://elasticsearch:9200',
        'ES_BACKFILL_ENABLED=false',
        'CANAL_ENABLED=false',
        'LLM_ENABLED=false',
        'STORAGE_LOCAL_PATH=/tmp/chtholly-uploads',
        "CACHE_READ_MODE=$cacheReadMode",
        'LOGGING_LEVEL_COM_CHTHOLLY=WARN',
        'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics,prometheus',
        'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true'
    ) | Set-Content -LiteralPath $serverEnvironmentFile -Encoding ascii
    $serverContainerId = ((& docker run -d `
        --name "$projectName-server" `
        --label "chtholly.benchmark.owner=$RunId" `
        --network $benchmarkNetwork `
        --network-alias server `
        --memory 1536m `
        -p "127.0.0.1:$Port`:8888" `
        --env-file $serverEnvironmentFile `
        --mount "type=bind,source=$($serverJar.FullName),target=/app/app.jar,readonly" `
        --entrypoint java `
        --pull never `
        $serverRuntimeImage `
        -jar /app/app.jar | Out-String).Trim())
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($serverContainerId)) {
        throw 'Starting the benchmark server container failed.'
    }
    Set-Content -LiteralPath (Join-Path $runtimeRoot 'server.container-id') `
        -Value $serverContainerId -Encoding ascii
    $containerIds.server = $serverContainerId
    $containerNames.server = "$projectName-server"

    $healthy = $false
    $healthToken = (& (Join-Path $PSScriptRoot 'new-benchmark-token.ps1') `
        -UserId 910000000000000001 -TtlSeconds 1800 | Out-String).Trim()
    $healthHeaders = @{ Authorization = "Bearer $healthToken" }
    $healthDeadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    while ([DateTimeOffset]::UtcNow -lt $healthDeadline) {
        $serverRunning = ((& docker inspect --format '{{.State.Running}}' $serverContainerId 2>$null |
            Out-String).Trim()) -eq 'true'
        if (-not $serverRunning) { break }
        try {
            $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$Port/actuator/health/liveness" `
                -Headers $healthHeaders -TimeoutSec 5
            if ($health.status -eq 'UP') { $healthy = $true; break }
        }
        catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $healthy) {
        throw "Benchmark server failed to become healthy on port $Port."
    }

    $healthToken = $null
    $healthHeaders = $null

    $runtimeManifest = [ordered]@{
        schemaVersion = 1
        capturedAt = [DateTimeOffset]::UtcNow.ToString('o')
        runId = $RunId
        projectName = $projectName
        isolated = $true
        removeVolumesOnStop = $true
        profile = $Profile
        variant = $Variant
        cacheReadMode = $cacheReadMode
        port = $Port
        hostBaseUrl = "http://127.0.0.1:$Port"
        k6BaseUrl = 'http://server:8888'
        benchmarkNetwork = $benchmarkNetwork
        services = $services
        containerIds = $containerIds
        containerNames = $containerNames
        serverRuntime = 'compose-container'
        applicationLogLevel = 'WARN'
        serverContainerId = $serverContainerId
        serverImage = $serverRuntimeImage
        serverImageId = ((& docker image inspect --format '{{.Id}}' $serverRuntimeImage | Out-String).Trim())
        executionCommit = $executionCommit
        executionDirty = $executionDirty
        serverJarSha256 = $serverJarSha256
        dependencyPorts = [ordered]@{
            mysql = $MysqlPort
            redis = $RedisPort
            kafka = $KafkaPort
            elasticsearch = $ElasticsearchPort
        }
        secretsRecorded = $false
    }
    $runtimeManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $runtimeManifestPath -Encoding utf8
    $runtimeManifest | ConvertTo-Json -Depth 8
}
catch {
    try { Preserve-ServerLogs } catch { }
    try { Stop-OwnedServerContainer } catch { }
    try { Invoke-Compose down --volumes --remove-orphans } catch { }
    if (Test-Path -LiteralPath $runtimeRoot -PathType Container) {
        Assert-RuntimePath -Path $runtimeRoot | Out-Null
        Remove-Item -LiteralPath $runtimeRoot -Recurse -Force
    }
    throw
}

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('smoke', 'standard')]
    [string]$Profile,

    [Parameter(Mandatory = $true)]
    [ValidateSet('stable-hot', 'expiry-spike')]
    [string]$Scenario,

    [Parameter(Mandatory = $true)]
    [ValidateSet('db-only', 'full-no-singleflight', 'full')]
    [string]$Variant,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$RunId,

    [ValidateRange(1, 4096)]
    [int]$Concurrency = 0,

    [ValidateRange(1, 3)]
    [int]$Repetition = 1,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$EnvironmentRunId,

    [string]$SubjectCommit,
    [string]$HarnessCommit,
    [string]$DatasetCommit,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$resultsRoot = Join-Path $repoRoot '.benchmark-results'
$runDirectory = Join-Path $resultsRoot $RunId
$rawDirectory = Join-Path $runDirectory 'raw'
$configPath = Join-Path $repoRoot 'benchmarks/config/standard.yml'
$hotPostId = '920000000000000001'

if (Test-Path -LiteralPath $runDirectory) { throw "Benchmark runId already exists: $RunId" }
if (($Scenario -eq 'stable-hot' -and $Variant -notin @('db-only', 'full')) -or
        ($Scenario -eq 'expiry-spike' -and $Variant -notin @('full-no-singleflight', 'full'))) {
    throw "Variant $Variant is not part of the fixed $Scenario comparison"
}

function Resolve-Commit {
    param([string]$Value)
    $candidate = if ([string]::IsNullOrWhiteSpace($Value)) { 'HEAD' } else { $Value }
    $resolved = (git -C $repoRoot rev-parse --verify "$candidate^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) { throw "Cannot resolve commit: $candidate" }
    return $resolved
}

function Read-ProfileValue {
    param([string]$Name, [string]$Key)
    $insideProfile = $false
    foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
        if ($line -match '^  ([A-Za-z0-9_-]+):\s*$') {
            $insideProfile = $Matches[1] -eq $Name
            continue
        }
        if ($insideProfile -and $line -match "^    $([Regex]::Escape($Key)):\s*(\d+)\s*$") {
            return [int]$Matches[1]
        }
    }
    throw "Missing profile value: $Name.$Key"
}

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-ActuatorCount {
    param([string]$BaseUrl, [hashtable]$Headers, [string]$MetricName)
    $response = Invoke-RestMethod -UseBasicParsing -Uri "$BaseUrl/actuator/metrics/$MetricName" -Headers $Headers -TimeoutSec 10
    $measurement = @($response.measurements | Where-Object statistic -eq 'COUNT')
    if ($measurement.Count -ne 1) { throw "Metric $MetricName does not expose exactly one COUNT measurement" }
    return [double]$measurement[0].value
}

function Get-RequiredMetricTag {
    param([object]$Metric, [string]$TagName)
    $tag = @($Metric.availableTags | Where-Object tag -eq $TagName)
    if ($tag.Count -ne 1 -or @($tag[0].values).Count -ne 1) {
        throw "Cache runtime metric does not expose one value for tag $TagName"
    }
    return [string]$tag[0].values[0]
}

function Get-CacheRuntimeContract {
    param([string]$BaseUrl, [hashtable]$Headers)
    $metric = Invoke-RestMethod -UseBasicParsing -Uri "$BaseUrl/actuator/metrics/chtholly.cache.runtime" -Headers $Headers -TimeoutSec 10
    return [ordered]@{
        effectiveReadMode = Get-RequiredMetricTag -Metric $metric -TagName 'read_mode'
        singleFlightEnabled = [bool]::Parse((Get-RequiredMetricTag -Metric $metric -TagName 'single_flight_enabled'))
        cacheMetricsAvailable = [bool]::Parse((Get-RequiredMetricTag -Metric $metric -TagName 'cache_metrics_available'))
    }
}

function Assert-CacheRuntimeContract {
    param([string]$BaseUrl, [hashtable]$Headers, [string]$RequestedMode)
    $contract = Get-CacheRuntimeContract -BaseUrl $BaseUrl -Headers $Headers
    $expectedSingleFlight = $RequestedMode -eq 'full'
    if ($contract.effectiveReadMode -ne $RequestedMode) {
        throw "Requested cache mode $RequestedMode but application reported $($contract.effectiveReadMode)"
    }
    if ($contract.singleFlightEnabled -ne $expectedSingleFlight) {
        throw "Application SingleFlight state does not match cache mode $RequestedMode"
    }
    if (-not $contract.cacheMetricsAvailable) {
        throw 'Application cache metrics are unavailable'
    }
    return $contract
}

function Wait-BenchmarkServer {
    param([string]$BaseUrl)
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        try {
            $health = Invoke-RestMethod -UseBasicParsing -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
            if ($health.status -eq 'UP') { return }
        }
        catch { }
        Start-Sleep -Seconds 1
    }
    throw "Benchmark server did not recover at $BaseUrl"
}

function Reset-ExpirySpikeBoundary {
    param([object]$RuntimeMetadata, [string]$BaseUrl)
    $redisContainer = [string]$RuntimeMetadata.containerIds.redis
    $serverContainer = [string]$RuntimeMetadata.containerIds.server
    if ([string]::IsNullOrWhiteSpace($redisContainer) -or [string]::IsNullOrWhiteSpace($serverContainer)) {
        throw 'Benchmark runtime does not identify the owned Redis and server containers'
    }
    $detailPattern = "post:detail:${hotPostId}:v*"
    $keys = @(& docker exec $redisContainer redis-cli --raw --scan --pattern $detailPattern)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot scan the isolated Redis detail cache' }
    foreach ($key in $keys) {
        if (-not [string]::IsNullOrWhiteSpace($key)) {
            & docker exec $redisContainer redis-cli DEL $key | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "Cannot delete isolated cache key: $key" }
        }
    }
    & docker restart $serverContainer | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot restart the owned benchmark server' }
    Wait-BenchmarkServer -BaseUrl $BaseUrl
    return [DateTimeOffset]::UtcNow.ToString('o')
}

$execution = Resolve-Commit -Value 'HEAD'
$subject = Resolve-Commit -Value $SubjectCommit
$harness = Resolve-Commit -Value $HarnessCommit
$dataset = Resolve-Commit -Value $DatasetCommit
$dirty = -not [string]::IsNullOrWhiteSpace((git -C $repoRoot status --porcelain | Out-String))
if ($Profile -eq 'standard' -and $dirty) { throw 'Standard benchmark runs require a clean worktree' }
if ($Profile -eq 'standard' -and $subject -ne $execution) { throw 'Standard benchmark subject must equal the executed commit' }

$warmupSeconds = Read-ProfileValue -Name $Profile -Key 'warmupSeconds'
$durationSeconds = Read-ProfileValue -Name $Profile -Key 'durationSeconds'
if ($Concurrency -eq 0) { $Concurrency = Read-ProfileValue -Name $Profile -Key 'concurrency' }

$runtimeMetadata = $null
$baseUrl = 'http://host.docker.internal:8888'
$hostBaseUrl = 'http://127.0.0.1:8888'
$benchmarkNetwork = $null
$environmentId = "validation:$Profile"
if (-not [string]::IsNullOrWhiteSpace($EnvironmentRunId)) {
    $runtimePath = Join-Path $resultsRoot "$EnvironmentRunId/environment-runtime.json"
    if (-not (Test-Path -LiteralPath $runtimePath -PathType Leaf)) { throw "Missing benchmark environment: $EnvironmentRunId" }
    $runtimeMetadata = Get-Content -Raw -LiteralPath $runtimePath -Encoding UTF8 | ConvertFrom-Json
    if ($runtimeMetadata.variant -ne $Variant) { throw 'Benchmark environment variant does not match the run variant' }
    if ($runtimeMetadata.executionCommit -ne $execution) { throw 'Benchmark environment commit does not match the executed commit' }
    $baseUrl = [string]$runtimeMetadata.k6BaseUrl
    $hostBaseUrl = [string]$runtimeMetadata.hostBaseUrl
    $benchmarkNetwork = [string]$runtimeMetadata.benchmarkNetwork
    $environmentId = [string]$runtimeMetadata.projectName
}
elseif (-not $ValidateOnly) {
    throw 'EnvironmentRunId is required for an executable benchmark run'
}

$startedAt = [DateTimeOffset]::UtcNow.ToString('o')
$manifest = [ordered]@{
    schemaVersion = 1
    runId = $RunId
    profile = $Profile
    scenario = $Scenario
    variant = $Variant
    repetition = $Repetition
    subjectCommit = $subject
    executionCommit = $execution
    harnessCommit = $harness
    datasetCommit = $dataset
    environmentId = $environmentId
    workload = [ordered]@{
        seed = 20260715
        concurrency = $Concurrency
        warmupSeconds = $warmupSeconds
        durationSeconds = $durationSeconds
    }
    status = if ($ValidateOnly) { 'VALIDATED' } else { 'RUNNING' }
    startedAt = $startedAt
    endedAt = if ($ValidateOnly) { [DateTimeOffset]::UtcNow.ToString('o') } else { $null }
    effectiveReadMode = $null
    singleFlightEnabled = $null
    cacheMetricsAvailable = $null
    cacheInvalidatedAt = $null
    coldStartVerified = $null
}
$environment = [ordered]@{
    capturedAt = [DateTimeOffset]::UtcNow.ToString('o')
    os = [Environment]::OSVersion.ToString()
    architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    processorCount = [Environment]::ProcessorCount
    environmentRunId = $EnvironmentRunId
    hostBaseUrl = $hostBaseUrl
    k6BaseUrl = $baseUrl
    benchmarkNetwork = $benchmarkNetwork
    dirty = $dirty
}

function Fail-IncompleteRun {
    param([string]$Message)
    $script:manifest.status = 'INCOMPLETE'
    $script:manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    Write-JsonFile -Value $script:manifest -Path (Join-Path $script:runDirectory 'manifest.json')
    $Message | Set-Content -LiteralPath (Join-Path $script:rawDirectory 'harness-error.txt') -Encoding UTF8
    & (Join-Path $script:PSScriptRoot 'summarize.ps1') -RunDirectory $script:runDirectory
    throw $Message
}

New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null
Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
Write-JsonFile -Value $environment -Path (Join-Path $runDirectory 'environment.json')
if ($ValidateOnly) { exit 0 }

$k6Directory = (Join-Path $repoRoot 'benchmarks/k6').Replace('\', '/')
$rawMount = $rawDirectory.Replace('\', '/')
$dockerArguments = @(
    'run', '--rm',
    '-v', "${k6Directory}:/scripts:ro",
    '-v', "${rawMount}:/results",
    '-e', "BASE_URL=$baseUrl",
    '-e', "BENCHMARK_PROFILE=$Profile",
    '-e', "BENCHMARK_SCENARIO=$Scenario",
    '-e', "BENCHMARK_VARIANT=$Variant",
    '-e', "BENCHMARK_HOT_POST_ID=$hotPostId",
    '-e', "K6_VUS=$Concurrency"
)
if (-not [string]::IsNullOrWhiteSpace($benchmarkNetwork)) { $dockerArguments += @('--network', $benchmarkNetwork) }

$token = (& (Join-Path $PSScriptRoot 'new-benchmark-token.ps1') -UserId 910000000000000001 -TtlSeconds 900 | Out-String).Trim()
$headers = @{ Authorization = "Bearer $token" }
try {
    $runtimeContract = Assert-CacheRuntimeContract -BaseUrl $hostBaseUrl -Headers $headers -RequestedMode $Variant
    $manifest.effectiveReadMode = $runtimeContract.effectiveReadMode
    $manifest.singleFlightEnabled = $runtimeContract.singleFlightEnabled
    $manifest.cacheMetricsAvailable = $runtimeContract.cacheMetricsAvailable
    Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.mysql.query' | Out-Null
    Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.same.key.load' | Out-Null
    Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
}
catch {
    Fail-IncompleteRun -Message $_.Exception.Message
}

$warmupArguments = $dockerArguments + @('-e', "K6_DURATION=${warmupSeconds}s", 'grafana/k6:0.54.0', 'run', '/scripts/cache-scenarios.js')
& docker @warmupArguments 2>&1 | Tee-Object -FilePath (Join-Path $rawDirectory 'warmup-k6.log')
if ($LASTEXITCODE -ne 0) {
    $manifest.status = 'FAILED'
    $manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
    & (Join-Path $PSScriptRoot 'summarize.ps1') -RunDirectory $runDirectory
    exit 1
}

if ($Scenario -eq 'expiry-spike') {
    try {
        $manifest.cacheInvalidatedAt = Reset-ExpirySpikeBoundary -RuntimeMetadata $runtimeMetadata -BaseUrl $hostBaseUrl
        Assert-CacheRuntimeContract -BaseUrl $hostBaseUrl -Headers $headers -RequestedMode $Variant | Out-Null
        $probeBefore = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.same.key.load'
        $probe = Invoke-WebRequest -UseBasicParsing -Uri "$hostBaseUrl/api/v1/posts/detail/$hotPostId" -Headers $headers -TimeoutSec 15
        $probeAfter = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.same.key.load'
        if ($probe.StatusCode -ne 200 -or $probeAfter -le $probeBefore) {
            throw 'Expiry-spike cold-start probe did not reach the origin loader'
        }
        $manifest.coldStartVerified = $true
        $manifest.cacheInvalidatedAt = Reset-ExpirySpikeBoundary -RuntimeMetadata $runtimeMetadata -BaseUrl $hostBaseUrl
        Assert-CacheRuntimeContract -BaseUrl $hostBaseUrl -Headers $headers -RequestedMode $Variant | Out-Null
        Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
    }
    catch {
        Fail-IncompleteRun -Message $_.Exception.Message
    }
}

try {
    $mysqlBefore = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.mysql.query'
    $loadsBefore = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.same.key.load'
}
catch {
    Fail-IncompleteRun -Message $_.Exception.Message
}

$measurementArguments = $dockerArguments + @(
    '-e', "K6_DURATION=${durationSeconds}s",
    'grafana/k6:0.54.0',
    'run', '--summary-export', '/results/k6.json', '/scripts/cache-scenarios.js'
)
& docker @measurementArguments 2>&1 | Tee-Object -FilePath (Join-Path $rawDirectory 'k6.log')
$k6ExitCode = $LASTEXITCODE

try {
    $mysqlAfter = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.mysql.query'
    $loadsAfter = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.same.key.load'
}
catch {
    Fail-IncompleteRun -Message $_.Exception.Message
}
$token = $null
$headers = $null
$applicationMetrics = [ordered]@{
    mysqlQueryCount = $mysqlAfter - $mysqlBefore
    sameKeyLoadCount = $loadsAfter - $loadsBefore
}
Write-JsonFile -Value $applicationMetrics -Path (Join-Path $rawDirectory 'application-metrics.json')

$manifest.status = if ($k6ExitCode -eq 0) { 'COMPLETED' } else { 'FAILED' }
$manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
& (Join-Path $PSScriptRoot 'summarize.ps1') -RunDirectory $runDirectory
exit $k6ExitCode

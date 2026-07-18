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
    try {
        $response = Invoke-RestMethod -UseBasicParsing -Uri "$BaseUrl/actuator/metrics/$MetricName" -Headers $Headers -TimeoutSec 10
        $measurement = @($response.measurements | Where-Object statistic -eq 'COUNT' | Select-Object -First 1)
        if ($measurement.Count -eq 1) { return [double]$measurement[0].value }
    }
    catch { return $null }
    return $null
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
    '-e', 'BENCHMARK_HOT_POST_ID=920000000000000001',
    '-e', "K6_VUS=$Concurrency"
)
if (-not [string]::IsNullOrWhiteSpace($benchmarkNetwork)) { $dockerArguments += @('--network', $benchmarkNetwork) }

$warmupArguments = $dockerArguments + @('-e', "K6_DURATION=${warmupSeconds}s", 'grafana/k6:0.54.0', 'run', '/scripts/cache-scenarios.js')
& docker @warmupArguments 2>&1 | Tee-Object -FilePath (Join-Path $rawDirectory 'warmup-k6.log')
if ($LASTEXITCODE -ne 0) {
    $manifest.status = 'FAILED'
    $manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
    & (Join-Path $PSScriptRoot 'summarize.ps1') -RunDirectory $runDirectory
    exit 1
}

$token = (& (Join-Path $PSScriptRoot 'new-benchmark-token.ps1') -UserId 910000000000000001 -TtlSeconds 900 | Out-String).Trim()
$headers = @{ Authorization = "Bearer $token" }
$mysqlBefore = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.mysql.query'
$loadsBefore = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.same.key.load'
if ($Scenario -eq 'expiry-spike') { Start-Sleep -Seconds 2 }

$measurementArguments = $dockerArguments + @(
    '-e', "K6_DURATION=${durationSeconds}s",
    'grafana/k6:0.54.0',
    'run', '--summary-export', '/results/k6.json', '/scripts/cache-scenarios.js'
)
& docker @measurementArguments 2>&1 | Tee-Object -FilePath (Join-Path $rawDirectory 'k6.log')
$k6ExitCode = $LASTEXITCODE

$mysqlAfter = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.mysql.query'
$loadsAfter = Get-ActuatorCount -BaseUrl $hostBaseUrl -Headers $headers -MetricName 'chtholly.cache.same.key.load'
$token = $null
$headers = $null
$applicationMetrics = [ordered]@{
    mysqlQueryCount = if ($null -ne $mysqlBefore -and $null -ne $mysqlAfter) { $mysqlAfter - $mysqlBefore } else { $null }
    sameKeyLoadCount = if ($null -ne $loadsBefore -and $null -ne $loadsAfter) { $loadsAfter - $loadsBefore } else { $null }
}
Write-JsonFile -Value $applicationMetrics -Path (Join-Path $rawDirectory 'application-metrics.json')

$manifest.status = if ($k6ExitCode -eq 0) { 'COMPLETED' } else { 'FAILED' }
$manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
& (Join-Path $PSScriptRoot 'summarize.ps1') -RunDirectory $runDirectory
exit $k6ExitCode

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('smoke', 'standard')]
    [string]$Profile,

    [Parameter(Mandatory = $true)]
    [ValidateSet('all', 'cache', 'counter', 'relation', 'fault')]
    [string]$Scenario,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$RunId,

    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$Variant = 'default',

    [ValidateRange(1, 4096)]
    [int]$Concurrency = 0,

    [ValidateRange(1, 100)]
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

if (Test-Path -LiteralPath $runDirectory) {
    throw "Benchmark runId already exists: $RunId"
}

function Resolve-Commit {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return (git -C $repoRoot rev-parse HEAD).Trim()
    }
    $resolved = (git -C $repoRoot rev-parse --verify "$Value^{commit}").Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot resolve commit: $Value"
    }
    return $resolved
}

function Read-ProfileValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Key
    )

    $insideProfile = $false
    foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
        if ($line -match '^  ([A-Za-z0-9_-]+):\s*$') {
            $insideProfile = $Matches[1] -eq $Name
            continue
        }
        if ($insideProfile -and $line -match "^    $([Regex]::Escape($Key)):\s*(.+?)\s*$") {
            return $Matches[1]
        }
    }
    throw "Missing profile value: $Name.$Key"
}

function Write-JsonFile {
    param([object]$Value, [string]$Path)
    $Value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Assert-SubjectExecutionCompatibility {
    param(
        [Parameter(Mandatory = $true)][string]$Subject,
        [Parameter(Mandatory = $true)][string]$Execution
    )

    & git -C $repoRoot merge-base --is-ancestor $Subject $Execution
    if ($LASTEXITCODE -ne 0) {
        throw 'SubjectCommit must be an ancestor of executionCommit'
    }
    $allowedPatterns = @(
        '^\.gitignore$',
        '^benchmarks/',
        '^scripts/benchmark/',
        '^docs/benchmarks/',
        '^docs/development/testing\.md$',
        '^apps/server/src/test/'
    )
    $businessChanges = @(git -C $repoRoot diff --name-only "$Subject..$Execution" | Where-Object {
        $path = $_
        -not ($allowedPatterns | Where-Object { $path -match $_ })
    })
    if ($businessChanges.Count -gt 0) {
        throw "executionCommit contains business changes after SubjectCommit: $($businessChanges -join ', ')"
    }
}

function Assert-CommitTreeMatches {
    param(
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][string[]]$Paths,
        [Parameter(Mandatory = $true)][string]$Message
    )

    & git -C $repoRoot diff --quiet $Commit -- @Paths
    if ($LASTEXITCODE -ne 0) {
        throw $Message
    }
}

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing workload config: $configPath"
}

$subject = Resolve-Commit -Value $SubjectCommit
$harness = Resolve-Commit -Value $HarnessCommit
$dataset = Resolve-Commit -Value $DatasetCommit
$execution = (git -C $repoRoot rev-parse HEAD).Trim()
Assert-SubjectExecutionCompatibility -Subject $subject -Execution $execution
$warmupSeconds = [int](Read-ProfileValue -Name $Profile -Key 'warmupSeconds')
$durationSeconds = [int](Read-ProfileValue -Name $Profile -Key 'durationSeconds')
$configuredConcurrency = (Read-ProfileValue -Name $Profile -Key 'concurrency').Trim('[', ']') -split ',' | ForEach-Object { [int]$_.Trim() }
if ($Concurrency -eq 0) {
    $Concurrency = $configuredConcurrency[0]
}
if ($configuredConcurrency -notcontains $Concurrency) {
    throw "Concurrency $Concurrency is not declared for profile $Profile"
}

$dirty = -not [string]::IsNullOrWhiteSpace((git -C $repoRoot status --porcelain | Out-String))
$runtimeMetadata = $null
$benchmarkNetwork = $null
$baseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://host.docker.internal:8888' }
$hostBaseUrl = if ($env:BENCHMARK_HOST_BASE_URL) { $env:BENCHMARK_HOST_BASE_URL } else { 'http://127.0.0.1:8888' }
if (-not [string]::IsNullOrWhiteSpace($EnvironmentRunId)) {
    $runtimeManifestPath = Join-Path $resultsRoot "$EnvironmentRunId/environment-runtime.json"
    if (-not (Test-Path -LiteralPath $runtimeManifestPath -PathType Leaf)) {
        throw "Missing isolated benchmark environment artifact: $runtimeManifestPath"
    }
    $runtimeMetadata = Get-Content -Raw -LiteralPath $runtimeManifestPath -Encoding UTF8 | ConvertFrom-Json
    if (-not [bool]$runtimeMetadata.isolated -or
            [string]::IsNullOrWhiteSpace([string]$runtimeMetadata.benchmarkNetwork)) {
        throw 'Benchmark environment is not an isolated runtime'
    }
    $benchmarkNetwork = [string]$runtimeMetadata.benchmarkNetwork
    $baseUrl = [string]$runtimeMetadata.k6BaseUrl
    $hostBaseUrl = [string]$runtimeMetadata.hostBaseUrl
}

if ($Profile -eq 'standard' -and -not $ValidateOnly) {
    if ($dirty) {
        throw 'Formal standard benchmark requires a clean worktree'
    }
    if ([string]::IsNullOrWhiteSpace($EnvironmentRunId)) {
        throw 'Formal standard benchmark requires an isolated environment'
    }
    Assert-CommitTreeMatches -Commit $harness -Paths @(
        'scripts/benchmark/run.ps1',
        'scripts/benchmark/environment.ps1',
        'scripts/benchmark/new-benchmark-token.ps1',
        'scripts/benchmark/summarize.ps1',
        'benchmarks/k6/backend-scenarios.js',
        'benchmarks/schema/manifest.schema.json'
    ) -Message 'Harness-controlled files must match HarnessCommit'
    Assert-CommitTreeMatches -Commit $dataset -Paths @(
        'benchmarks/config/standard.yml',
        'benchmarks/seed/standard.sql'
    ) -Message 'Dataset-controlled files must match DatasetCommit'
    if ([string]$runtimeMetadata.executionCommit -ne $execution) {
        throw 'Benchmark environment execution commit does not match the built execution'
    }
    if ([bool]$runtimeMetadata.executionDirty) {
        throw 'Benchmark environment was built from a dirty worktree'
    }
    if ([string]::IsNullOrWhiteSpace([string]$runtimeMetadata.serverJarSha256)) {
        throw 'Benchmark environment JAR binding is missing'
    }
}

New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null
$resolvedResultsRoot = (Resolve-Path -LiteralPath $resultsRoot).Path + [System.IO.Path]::DirectorySeparatorChar
$resolvedRunDirectory = (Resolve-Path -LiteralPath $runDirectory).Path
if (-not $resolvedRunDirectory.StartsWith($resolvedResultsRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe run directory: $resolvedRunDirectory"
}

$startedAt = [DateTimeOffset]::UtcNow.ToString('o')
$environment = [ordered]@{
    capturedAt = $startedAt
    os = [System.Environment]::OSVersion.VersionString
    architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    processorCount = [System.Environment]::ProcessorCount
    memoryBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    java = (Get-Item -LiteralPath (Get-Command java).Source).VersionInfo.ProductVersion
    maven = ((& mvn -version | Select-Object -First 1) | Out-String).Trim()
    docker = ((& docker version --format '{{.Client.Version}}|{{.Server.Version}}' 2>&1) | Out-String).Trim()
    baseUrl = $baseUrl
    hostBaseUrl = $hostBaseUrl
    benchmarkEnvironmentRunId = $EnvironmentRunId
    benchmarkNetwork = $benchmarkNetwork
    searchMode = if ($null -eq $runtimeMetadata) { 'caller-managed' } else { [string]$runtimeMetadata.searchMode }
}
$runtimeFingerprint = if ($null -eq $runtimeMetadata) { 'none' } else {
    '{0}|{1}|{2}|{3}' -f $runtimeMetadata.projectName, $runtimeMetadata.executionCommit,
        $runtimeMetadata.serverJarSha256, $runtimeMetadata.serverImageId
}
$environmentFingerprint = '{0}|{1}|{2}|{3}|{4}|{5}|{6}|{7}' -f $environment.os, $environment.architecture, $environment.processorCount, $environment.memoryBytes, $environment.java, $environment.maven, $environment.docker, $runtimeFingerprint
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $environmentHash = [BitConverter]::ToString($sha256.ComputeHash([Text.Encoding]::UTF8.GetBytes($environmentFingerprint))).Replace('-', '').ToLowerInvariant()
}
finally {
    $sha256.Dispose()
}
$manifest = [ordered]@{
    schemaVersion = 1
    runId = $RunId
    profile = $Profile
    scenario = $Scenario
    variant = $Variant
    subjectCommit = $subject
    executionCommit = $execution
    harnessCommit = $harness
    datasetCommit = $dataset
    environmentId = "sha256:$environmentHash"
    repetition = $Repetition
    experiment = [ordered]@{
        hypothesis = 'The candidate preserves correctness and is compared on the preregistered primary metric.'
        primaryMetric = 'http_req_duration_p95'
        direction = 'LOWER'
        confidenceMethod = 'Median of three independent repetitions with the raw distribution retained.'
        hardFail = @('checks_rate_below_1', 'http_req_failed_above_0', 'manifest_identity_mismatch')
    }
    numberKind = 'CONFIG'
    status = 'RUNNING'
    startedAt = $startedAt
    endedAt = $null
    dirty = $dirty
    authConfigured = -not [string]::IsNullOrWhiteSpace($env:BENCHMARK_TOKEN)
    workload = [ordered]@{
        seed = 20260715
        concurrency = $Concurrency
        warmupSeconds = $warmupSeconds
        durationSeconds = $durationSeconds
        declaredConcurrency = $configuredConcurrency
    }
    artifacts = [ordered]@{
        k6 = 'PLANNED'
        actuator = 'PLANNED'
        redis = 'BLOCKED_EXTERNAL'
        mysql = 'BLOCKED_EXTERNAL'
        search = if ($null -eq $runtimeMetadata) { 'BLOCKED_EXTERNAL' } else { 'NOT_APPLICABLE' }
        agentEvaluation = 'NOT_APPLICABLE'
    }
}

if ($ValidateOnly) {
    $manifest.status = 'VALIDATED'
    $manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $manifest.artifacts.k6 = 'NOT_APPLICABLE'
    $manifest.artifacts.actuator = 'NOT_APPLICABLE'
    Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
    Write-JsonFile -Value $environment -Path (Join-Path $runDirectory 'environment.json')
    exit 0
}

if (($Scenario -in @('all', 'counter', 'relation')) -and [string]::IsNullOrWhiteSpace($env:BENCHMARK_TOKEN)) {
    $manifest.status = 'BLOCKED_EXTERNAL'
    $manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $manifest.artifacts.k6 = 'BLOCKED_EXTERNAL'
    Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
    Write-JsonFile -Value $environment -Path (Join-Path $runDirectory 'environment.json')
    'BENCHMARK_TOKEN is required for write scenarios.' | Set-Content -LiteralPath (Join-Path $runDirectory 'failures.md') -Encoding utf8
    exit 2
}

Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
Write-JsonFile -Value $environment -Path (Join-Path $runDirectory 'environment.json')

$baseUrl = $environment.baseUrl
$k6Directory = (Join-Path $repoRoot 'benchmarks/k6').Replace('\', '/')
$rawMount = $rawDirectory.Replace('\', '/')
$duration = "${durationSeconds}s"
$dockerArguments = @(
    'run', '--rm',
    '-v', "${k6Directory}:/scripts:ro",
    '-v', "${rawMount}:/results",
    '-e', "BASE_URL=$baseUrl",
    '-e', "BENCHMARK_PROFILE=$Profile",
    '-e', "BENCHMARK_SCENARIO=$Scenario",
    '-e', "BENCHMARK_VARIANT=$Variant",
    '-e', 'BENCHMARK_SEED=20260715',
    '-e', 'BENCHMARK_POST_IDS=920000000000000001,920000000000000002,920000000000000003,920000000000000004,920000000000000005',
    '-e', 'BENCHMARK_USER_IDS=910000000000000002,910000000000000003,910000000000000004,910000000000000005,910000000000000006',
    '-e', "K6_VUS=$Concurrency",
    '-e', "K6_DURATION=${warmupSeconds}s"
)
if (-not [string]::IsNullOrWhiteSpace($benchmarkNetwork)) {
    $dockerArguments += @('--network', $benchmarkNetwork)
}
if (-not [string]::IsNullOrWhiteSpace($env:BENCHMARK_TOKEN)) {
    $dockerArguments += @('-e', 'BENCHMARK_TOKEN')
}
$warmupArguments = $dockerArguments + @(
    'grafana/k6:0.54.0',
    'run', '/scripts/backend-scenarios.js'
)

$warmupLogPath = Join-Path $rawDirectory 'warmup-k6.log'
& docker @warmupArguments 2>&1 | Tee-Object -FilePath $warmupLogPath
$warmupExitCode = $LASTEXITCODE
if ($warmupExitCode -ne 0) {
    $manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $manifest.artifacts.k6 = 'FAILED'
    $manifest.status = 'FAILED'
    Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')
    & (Join-Path $PSScriptRoot 'summarize.ps1') -RunDirectory $runDirectory
    exit $warmupExitCode
}

$measurementArguments = [System.Collections.Generic.List[string]]::new()
$measurementArguments.AddRange([string[]]$dockerArguments)
$durationIndex = $measurementArguments.IndexOf("K6_DURATION=${warmupSeconds}s")
$measurementArguments[$durationIndex] = "K6_DURATION=$duration"
$measurementArguments.AddRange([string[]]@(
    'grafana/k6:0.54.0',
    'run', '--summary-export', '/results/k6.json', '/scripts/backend-scenarios.js'
))

$logPath = Join-Path $rawDirectory 'k6.log'
& docker @measurementArguments 2>&1 | Tee-Object -FilePath $logPath
$k6ExitCode = $LASTEXITCODE
$manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
$manifest.artifacts.k6 = if ($k6ExitCode -eq 0) { 'COLLECTED_UNREVIEWED' } else { 'FAILED' }
$manifest.artifacts.actuator = 'BLOCKED_EXTERNAL'
$manifest.status = if ($k6ExitCode -eq 0) { 'COMPLETED' } else { 'FAILED' }
$manifest.numberKind = 'OBSERVED'
Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')

& (Join-Path $PSScriptRoot 'summarize.ps1') -RunDirectory $runDirectory
exit $k6ExitCode

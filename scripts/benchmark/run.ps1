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

if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Missing workload config: $configPath"
}

New-Item -ItemType Directory -Path $rawDirectory -Force | Out-Null
$resolvedResultsRoot = (Resolve-Path -LiteralPath $resultsRoot).Path + [System.IO.Path]::DirectorySeparatorChar
$resolvedRunDirectory = (Resolve-Path -LiteralPath $runDirectory).Path
if (-not $resolvedRunDirectory.StartsWith($resolvedResultsRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe run directory: $resolvedRunDirectory"
}

$subject = Resolve-Commit -Value $SubjectCommit
$harness = Resolve-Commit -Value $HarnessCommit
$dataset = Resolve-Commit -Value $DatasetCommit
$warmupSeconds = [int](Read-ProfileValue -Name $Profile -Key 'warmupSeconds')
$durationSeconds = [int](Read-ProfileValue -Name $Profile -Key 'durationSeconds')
$configuredConcurrency = (Read-ProfileValue -Name $Profile -Key 'concurrency').Trim('[', ']') -split ',' | ForEach-Object { [int]$_.Trim() }
if ($Concurrency -eq 0) {
    $Concurrency = $configuredConcurrency[0]
}
if ($configuredConcurrency -notcontains $Concurrency) {
    throw "Concurrency $Concurrency is not declared for profile $Profile"
}

$dirty = -not [string]::IsNullOrWhiteSpace((git -C $repoRoot status --porcelain --untracked-files=no | Out-String))
$startedAt = [DateTimeOffset]::UtcNow.ToString('o')
$manifest = [ordered]@{
    schemaVersion = 1
    runId = $RunId
    profile = $Profile
    scenario = $Scenario
    variant = $Variant
    subjectCommit = $subject
    harnessCommit = $harness
    datasetCommit = $dataset
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
        agentEvaluation = 'NOT_APPLICABLE'
    }
}

$environment = [ordered]@{
    capturedAt = $startedAt
    os = [System.Environment]::OSVersion.VersionString
    architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    processorCount = [System.Environment]::ProcessorCount
    memoryBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    java = (Get-Item -LiteralPath (Get-Command java).Source).VersionInfo.ProductVersion
    maven = ((& mvn -version | Select-Object -First 1) | Out-String).Trim()
    docker = ((& docker version --format '{{.Client.Version}}|{{.Server.Version}}' 2>&1) | Out-String).Trim()
    baseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://host.docker.internal:8888' }
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
    '-e', "K6_VUS=$Concurrency",
    '-e', "K6_DURATION=$duration"
)
if (-not [string]::IsNullOrWhiteSpace($env:BENCHMARK_TOKEN)) {
    $dockerArguments += @('-e', 'BENCHMARK_TOKEN')
}
$dockerArguments += @(
    'grafana/k6:0.54.0',
    'run', '--summary-export', '/results/k6.json', '/scripts/backend-scenarios.js'
)

$logPath = Join-Path $rawDirectory 'k6.log'
& docker @dockerArguments 2>&1 | Tee-Object -FilePath $logPath
$k6ExitCode = $LASTEXITCODE
$manifest.endedAt = [DateTimeOffset]::UtcNow.ToString('o')
$manifest.artifacts.k6 = if ($k6ExitCode -eq 0) { 'COLLECTED_UNREVIEWED' } else { 'FAILED' }
$manifest.artifacts.actuator = 'BLOCKED_EXTERNAL'
$manifest.status = if ($k6ExitCode -eq 0) { 'COMPLETED' } else { 'FAILED' }
$manifest.numberKind = 'OBSERVED'
Write-JsonFile -Value $manifest -Path (Join-Path $runDirectory 'manifest.json')

& (Join-Path $PSScriptRoot 'summarize.ps1') -RunDirectory $runDirectory
exit $k6ExitCode

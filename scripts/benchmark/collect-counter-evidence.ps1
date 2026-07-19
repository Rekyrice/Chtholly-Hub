[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$RunId,

    [string]$SubjectCommit = 'HEAD',
    [string]$HarnessCommit = 'HEAD',
    [string]$DatasetCommit = 'HEAD'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$resultsRoot = Join-Path $repoRoot '.benchmark-results'
$runDirectory = Join-Path $resultsRoot $RunId
$resultPath = Join-Path $runDirectory 'counter-evidence.json'

function Resolve-Commit {
    param([string]$Value, [string]$Name)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'SilentlyContinue'
        $resolved = & git -C $repoRoot rev-parse --verify "$Value^{commit}" 2>$null
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($resolved)) {
        throw "$Name does not resolve to a commit: $Value"
    }
    return $resolved.Trim()
}

function Assert-CleanWorktree {
    $status = & git -C $repoRoot status --porcelain
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw 'Cannot inspect the benchmark worktree' }
    if (-not [string]::IsNullOrWhiteSpace(($status | Out-String))) {
        throw 'Counter evidence collection requires a clean worktree'
    }
}

& git -C $repoRoot check-ignore --quiet -- '.benchmark-results/probe'
if ($LASTEXITCODE -ne 0) { throw '.benchmark-results/ must be Git-ignored' }
$trackedResults = @(& git -C $repoRoot ls-files -- '.benchmark-results/**')
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect tracked benchmark results' }
if ($trackedResults.Count -ne 0) { throw '.benchmark-results/ must not contain tracked files' }
if (Test-Path -LiteralPath $runDirectory) { throw "Counter evidence runId already exists: $RunId" }
Assert-CleanWorktree

$execution = Resolve-Commit -Value 'HEAD' -Name 'Execution commit'
$subject = Resolve-Commit -Value $SubjectCommit -Name 'Subject commit'
$harness = Resolve-Commit -Value $HarnessCommit -Name 'Harness commit'
$dataset = Resolve-Commit -Value $DatasetCommit -Name 'Dataset commit'
if ($subject -ne $execution -or $harness -ne $execution -or $dataset -ne $execution) {
    throw 'Counter evidence must bind subject, harness and fixed operation sequence to the executed commit'
}

$mavenArguments = @(
    '-q',
    '-Pintegration-test',
    '-Dit.test=CounterInteractionEvidenceCollectorIT',
    '-Dcounter.evidence.enabled=true',
    "-Dcounter.evidence.repo-root=$repoRoot",
    "-Dcounter.evidence.run-id=$RunId",
    "-Dcounter.evidence.subject-commit=$subject",
    "-Dcounter.evidence.harness-commit=$harness",
    "-Dcounter.evidence.dataset-commit=$dataset",
    'verify'
)

Push-Location (Join-Path $repoRoot 'apps/server')
try {
    & mvn @mavenArguments
    $mavenExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($mavenExitCode -ne 0) { throw "Counter evidence integration test failed with exit code $mavenExitCode" }
if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
    throw "Counter evidence result is missing: $resultPath"
}

$result = Get-Content -Raw -LiteralPath $resultPath -Encoding UTF8 | ConvertFrom-Json
if ($result.status -ne 'COMPLETED' -or $result.runId -ne $RunId) {
    throw 'Counter evidence identity or completion status is invalid'
}
foreach ($identity in @(
        @{ name = 'subjectCommit'; expected = $subject },
        @{ name = 'harnessCommit'; expected = $harness },
        @{ name = 'datasetCommit'; expected = $dataset })) {
    if ($result.($identity.name) -ne $identity.expected) {
        throw "Counter evidence $($identity.name) does not match the requested commit"
    }
}

$expectedMetrics = [ordered]@{
    requestTotal = 8L
    stateChangeCount = 4L
    kafkaEventCount = 5L
    dedupHitCount = 1L
    aggregationBatchCount = 2L
    mysqlUpdateCount = 2L
    preCalibrationDiscrepancy = 1L
    postCalibrationDiscrepancy = 0L
}
$actualMetricNames = @($result.metrics.PSObject.Properties.Name)
if ($actualMetricNames.Count -ne $expectedMetrics.Count) {
    throw 'Counter evidence must contain exactly the eight fixed correctness metrics'
}
foreach ($name in $expectedMetrics.Keys) {
    $property = $result.metrics.PSObject.Properties[$name]
    if ($null -eq $property -or $null -eq $property.Value) { throw "Counter evidence metric is missing: $name" }
    $actual = 0L
    if (-not [long]::TryParse([string]$property.Value, [ref]$actual) -or $actual -ne $expectedMetrics[$name]) {
        throw "Counter evidence metric $name must equal $($expectedMetrics[$name])"
    }
}

Assert-CleanWorktree
$relativeResult = ".benchmark-results/$RunId/counter-evidence.json"
& git -C $repoRoot check-ignore --quiet -- $relativeResult
if ($LASTEXITCODE -ne 0) { throw "Counter evidence result must remain ignored: $relativeResult" }

[ordered]@{
    status = $result.status
    runId = $result.runId
    subjectCommit = $result.subjectCommit
    resultPath = $resultPath
    metrics = $result.metrics
} | ConvertTo-Json -Depth 4

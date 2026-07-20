[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$RunId,

    [Parameter(Mandatory = $true)]
    [string]$SubjectCommit,

    [string]$HarnessCommit = 'HEAD',

    [Parameter(Mandatory = $true)]
    [string]$DatasetCommit
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$resultsRoot = Join-Path $repoRoot '.benchmark-results'
$runDirectory = Join-Path $resultsRoot $RunId
$datasetPath = 'benchmarks/datasets/agent-evaluation/retrieval.jsonl'

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
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the retrieval evidence worktree' }
    if (-not [string]::IsNullOrWhiteSpace(($status | Out-String))) {
        throw 'Retrieval evidence collection requires a clean worktree'
    }
}

function Resolve-Blob {
    param([string]$Commit, [string]$Path)
    $blob = & git -C $repoRoot rev-parse "$Commit`:$Path"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($blob)) {
        throw "Cannot resolve $Path at $Commit"
    }
    return $blob.Trim()
}

& git -C $repoRoot check-ignore --quiet -- '.benchmark-results/probe'
if ($LASTEXITCODE -ne 0) { throw '.benchmark-results/ must be Git-ignored' }
if (@(& git -C $repoRoot ls-files -- '.benchmark-results/**').Count -ne 0) {
    throw '.benchmark-results/ must not contain tracked files'
}
if (Test-Path -LiteralPath $runDirectory) { throw "Retrieval evidence runId already exists: $RunId" }
Assert-CleanWorktree

$execution = Resolve-Commit -Value 'HEAD' -Name 'Execution commit'
$subject = Resolve-Commit -Value $SubjectCommit -Name 'Subject commit'
$harness = Resolve-Commit -Value $HarnessCommit -Name 'Harness commit'
$dataset = Resolve-Commit -Value $DatasetCommit -Name 'Dataset commit'
if ($harness -ne $execution) { throw 'Harness commit must equal the clean execution commit' }
& git -C $repoRoot merge-base --is-ancestor $subject $execution
if ($LASTEXITCODE -ne 0) { throw 'Subject commit must be an ancestor of the execution commit' }

& git -C $repoRoot diff --quiet $subject $execution -- 'apps/server/src/main' 'apps/server/pom.xml'
if ($LASTEXITCODE -ne 0) {
    throw 'Executed server production tree or dependencies differ from SubjectCommit'
}
$datasetBlob = Resolve-Blob -Commit $dataset -Path $datasetPath
$currentDatasetBlob = (& git -C $repoRoot hash-object -- $datasetPath).Trim()
if ($LASTEXITCODE -ne 0 -or $datasetBlob -ne $currentDatasetBlob) {
    throw 'Executed retrieval dataset differs from DatasetCommit'
}

$mavenArguments = @(
    '-q',
    '-Pintegration-test',
    '-Dit.test=RetrievalCandidateEvidenceCollectorIT',
    '-Dretrieval.candidate.evidence.enabled=true',
    "-Dretrieval.candidate.evidence.repo-root=$repoRoot",
    "-Dretrieval.candidate.evidence.run-id=$RunId",
    "-Dretrieval.candidate.evidence.subject-commit=$subject",
    "-Dretrieval.candidate.evidence.execution-commit=$execution",
    "-Dretrieval.candidate.evidence.harness-commit=$harness",
    "-Dretrieval.candidate.evidence.dataset-commit=$dataset",
    'verify'
)

Push-Location (Join-Path $repoRoot 'apps/server')
try {
    & mvn @mavenArguments
    $mavenExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($mavenExitCode -ne 0) { throw "Retrieval evidence integration test failed with exit code $mavenExitCode" }

$artifactNames = @('manifest.json', 'environment.json', 'raw/observations.json', 'summary.json',
    'summary.md', 'failures.md', 'checksums.sha256')
foreach ($name in $artifactNames) {
    if (-not (Test-Path -LiteralPath (Join-Path $runDirectory $name) -PathType Leaf)) {
        throw "Retrieval evidence artifact is missing: $name"
    }
}
$manifest = Get-Content -Raw -LiteralPath (Join-Path $runDirectory 'manifest.json') -Encoding UTF8 | ConvertFrom-Json
if ($manifest.status -ne 'COMPLETED' -or $manifest.runId -ne $RunId) {
    throw 'Retrieval evidence identity or completion status is invalid'
}
foreach ($identity in @(
        @{ name = 'subjectCommit'; expected = $subject },
        @{ name = 'executionCommit'; expected = $execution },
        @{ name = 'harnessCommit'; expected = $harness },
        @{ name = 'datasetCommit'; expected = $dataset })) {
    if ($manifest.($identity.name) -ne $identity.expected) {
        throw "Retrieval evidence $($identity.name) does not match the requested commit"
    }
}
if ($manifest.labelStatus -ne 'CANDIDATE_REQUIRES_OWNER_REVIEW' -or
        $manifest.reviewStatus -ne 'COLLECTED_UNREVIEWED' -or
        $manifest.formalGold -ne $false -or $manifest.semanticQualityEvidence -ne $false -or
        $manifest.externalModelCalls -ne 0) {
    throw 'Retrieval evidence must preserve the non-formal review boundary'
}
if ($manifest.candidateRowCount -ne 45 -or $manifest.queryObservationCount -ne 42 -or
        $manifest.deduplicatedQueryGroups -ne 3) {
    throw 'Retrieval evidence must preserve the 45-row to 42-query deduplication contract'
}

$summary = Get-Content -Raw -LiteralPath (Join-Path $runDirectory 'summary.json') -Encoding UTF8 | ConvertFrom-Json
foreach ($mode in @('keyword-only', 'vector-only', 'three-way-document-rrf')) {
    $metrics = $summary.metrics.PSObject.Properties[$mode].Value
    $names = @($metrics.PSObject.Properties.Name)
    if ($names.Count -ne 4) { throw "Retrieval mode $mode must contain exactly four metrics" }
    foreach ($name in @('recallAt5', 'mrr', 'citationValidityRate', 'noAnswerAccuracy')) {
        if ($names -notcontains $name) { throw "Retrieval mode $mode is missing $name" }
    }
    foreach ($name in @('recallAt5', 'mrr', 'noAnswerAccuracy')) {
        $value = [double]$metrics.$name
        if ($value -lt 0.0 -or $value -gt 1.0) { throw "Retrieval metric $mode/$name is out of range" }
    }
    if ($null -ne $metrics.citationValidityRate) {
        throw "Retrieval mode $mode must keep citationValidityRate unobserved without generation"
    }
}
$environment = Get-Content -Raw -LiteralPath (Join-Path $runDirectory 'environment.json') -Encoding UTF8 | ConvertFrom-Json
if ($environment.infrastructureMode -ne 'REAL_TESTCONTAINERS' -or
        $environment.corpusMode -ne 'DETERMINISTIC_CANDIDATE_FIXTURE_V1' -or
        $environment.embeddingMode -ne 'DETERMINISTIC_LOCAL_HASH_V1' -or
        $environment.semanticQualityEvidence -ne $false -or $environment.externalModelCalls -ne 0) {
    throw 'Retrieval evidence environment identity is invalid'
}
$raw = Get-Content -Raw -LiteralPath (Join-Path $runDirectory 'raw/observations.json') -Encoding UTF8 | ConvertFrom-Json
if (@($raw).Count -ne 42) { throw 'Retrieval evidence must contain exactly 42 deduplicated query observations' }

$expectedChecksumPaths = @($artifactNames | Where-Object { $_ -ne 'checksums.sha256' } | Sort-Object)
$checksumPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($line in Get-Content -LiteralPath (Join-Path $runDirectory 'checksums.sha256') -Encoding UTF8) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "Invalid checksum line: $line" }
    $relativePath = $Matches[2].Replace('\', '/')
    if ([System.IO.Path]::IsPathRooted($relativePath) -or
            @($relativePath.Split('/')) -contains '..') {
        throw "Unsafe checksum path: $relativePath"
    }
    if (-not $checksumPaths.Add($relativePath)) { throw "Duplicate checksum path: $relativePath" }
    $artifact = Join-Path $runDirectory $relativePath
    $actual = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Matches[1]) { throw "Checksum mismatch: $($Matches[2])" }
}
$checksumDifference = Compare-Object -ReferenceObject $expectedChecksumPaths -DifferenceObject @($checksumPaths)
if ($null -ne $checksumDifference) {
    throw 'Checksum manifest must cover exactly the six retrieval evidence artifacts'
}

Assert-CleanWorktree
$relativeResult = ".benchmark-results/$RunId/manifest.json"
& git -C $repoRoot check-ignore --quiet -- $relativeResult
if ($LASTEXITCODE -ne 0) { throw "Retrieval evidence result must remain ignored: $relativeResult" }

[ordered]@{
    status = $manifest.status
    runId = $manifest.runId
    subjectCommit = $manifest.subjectCommit
    harnessCommit = $manifest.harnessCommit
    datasetCommit = $manifest.datasetCommit
    resultPath = $runDirectory
    metrics = $summary.metrics
} | ConvertTo-Json -Depth 6

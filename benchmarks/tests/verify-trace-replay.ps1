[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$runnerPath = Join-Path $repoRoot 'scripts/benchmark/trace-replay.ps1'
$probePath = Join-Path $repoRoot 'benchmarks/fixtures/trace-replay/HistoricalTraceRuntimeIT.java'
$datasetPath = Join-Path $repoRoot 'benchmarks/datasets/agent-evaluation/trace-replays.jsonl'
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-Contract {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { $failures.Add($Message) }
}

foreach ($relative in @(
        'scripts/benchmark/trace-replay.ps1',
        'benchmarks/fixtures/trace-replay/HistoricalTraceRuntimeIT.java',
        'benchmarks/datasets/agent-evaluation/trace-replays.jsonl')) {
    Assert-Contract (Test-Path -LiteralPath (Join-Path $repoRoot $relative) -PathType Leaf) `
        "Missing Trace replay file: $relative"
}

foreach ($ignored in @('.benchmark-results/probe', '.codex-tmp/probe')) {
    & git -C $repoRoot check-ignore --quiet -- $ignored
    Assert-Contract ($LASTEXITCODE -eq 0) "$ignored must remain Git-ignored"
}

$dataset = @()
if (Test-Path -LiteralPath $datasetPath -PathType Leaf) {
    $dataset = @(Get-Content -LiteralPath $datasetPath -Encoding UTF8 |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_ | ConvertFrom-Json })
}
Assert-Contract ($dataset.Count -eq 2) 'Trace replay dataset must contain exactly two loops'
if ($dataset.Count -eq 2) {
    Assert-Contract (($dataset.sampleId -join ',') -eq 'trace-replay-001,trace-replay-002') `
        'Trace replay sample IDs must remain stable'
    Assert-Contract (($dataset | Where-Object labelStatus -ne 'CANDIDATE_REQUIRES_OWNER_REVIEW').Count -eq 0) `
        'Trace replay labels must remain owner-review candidates'
    Assert-Contract ($dataset[0].baselineCommit -eq '2d613e81' -and $dataset[0].candidateCommit -eq '6c8e694c') `
        'Retrieval loop must bind the entity-to-article fix commits'
    Assert-Contract ($dataset[1].baselineCommit -eq '6c8e694c' -and $dataset[1].candidateCommit -eq '314700cc') `
        'Citation loop must bind the evidence gate fix commits'
    Assert-Contract ($dataset[1].area -eq 'OUTPUT_VALIDATION') `
        'Citation loop must be classified as observed output validation'
}

$runner = if (Test-Path -LiteralPath $runnerPath) { Get-Content -Raw -LiteralPath $runnerPath -Encoding UTF8 } else { '' }
$probe = if (Test-Path -LiteralPath $probePath) { Get-Content -Raw -LiteralPath $probePath -Encoding UTF8 } else { '' }
$runnerLines = if ($runner) { @(Get-Content -LiteralPath $runnerPath -Encoding UTF8).Count } else { 0 }
$probeSourceLines = if ($probe) { @(Get-Content -LiteralPath $probePath -Encoding UTF8) } else { @() }
$probeClassStart = @($probeSourceLines | Select-String -SimpleMatch 'class HistoricalTraceRuntimeIT').LineNumber
$probeClassLines = if ($probeClassStart.Count -eq 1) {
    @($probeSourceLines[($probeClassStart[0] - 1)..($probeSourceLines.Count - 1)] |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
} else { [int]::MaxValue }
Assert-Contract ($runnerLines -le 370) "Trace runner must stay minimal (actual lines: $runnerLines)"
Assert-Contract ($probeClassLines -le 250) "Historical runtime probe class must stay focused (actual lines: $probeClassLines)"

foreach ($token in @(
        'git archive', '.codex-tmp', 'HistoricalTraceRuntimeIT', 'status --porcelain',
        'subjectTree', 'executionCommit', 'harnessCommit', 'datasetCommit', 'REAL_TRACE',
        'externalModelCalls', 'traceRowCount', 'checksums.sha256', 'regressionTests',
        'unitExitCode', 'integrationExitCode', 'unit.log', 'integration.log', 'failsafe-reports',
        'harnessBlobs', 'datasetBlob', 'deterministicBoundaries', 'Invoke-MavenLogged',
        '-Duser.timezone=UTC', '-Duser.language=zh', '-Duser.country=CN', 'tar -xf',
        'ToUnixTimeSeconds', '[IO.Directory]::Delete', 'ConvertTo-Json -InputObject $Value',
        'Get-JavaVersion')) {
    Assert-Contract ($runner.Contains($token)) "Trace runner must contain $token"
}
foreach ($token in @(
        'ChthollyAgent', 'HybridSearchService', 'TracePersistenceService', 'TraceQueryService',
        'execution_traces', 'traceRowCount', 'externalModelCalls', 'AgentExecutionTrace',
        'SELECT trace_payload FROM execution_traces', 'objectMapper.readTree(rawTracePayload)',
        'groundedSnapshot(retrieval)', 'runtimeTimezone', 'runtimeLocale')) {
    Assert-Contract ($probe.Contains($token)) "Historical runtime probe must contain $token"
}
foreach ($token in @('Invoke-RestMethod', 'CHOLLY_TRACE_ADMIN_TOKEN', 'AllowUncommittedHarness', 'OFFLINE_UNVERIFIED', 'API_UNVERIFIED')) {
    Assert-Contract (-not $runner.Contains($token)) "Minimal Trace runner must not contain legacy token $token"
}
Assert-Contract (-not $runner.Contains('Expand-Archive')) 'Trace runner must avoid Windows long-path Expand-Archive failures'
Assert-Contract (-not $runner.Contains('[IO.Path]::GetRelativePath')) `
    'Trace runner must remain compatible with Windows PowerShell 5.1'
Assert-Contract (-not $runner.Contains('$Value | ConvertTo-Json')) `
    'Trace JSON writer must preserve single-item arrays'
Assert-Contract (-not $runner.Contains('& java -version 2>&1 |')) `
    'Trace environment capture must not treat Java stderr as a terminating error'
Assert-Contract (-not $runner.Contains('Remove-Item -LiteralPath $resolved -Recurse')) `
    'Trace runtime cleanup must support validated Windows long paths'
foreach ($token in @('.expected', 'rootCause', 'primaryChange')) {
    Assert-Contract (-not $probe.Contains($token)) "Runtime probe must not read comparison oracle $token"
}
Assert-Contract (-not $probe.Contains('"trace-replay-001".equals(sampleId)')) `
    'Runtime probe must classify failures from observations rather than sample identity'

foreach ($path in @($runnerPath, $PSScriptRoot + '/verify-trace-replay.ps1')) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $tokens = $null
    $parseErrors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$parseErrors) | Out-Null
    Assert-Contract ($parseErrors.Count -eq 0) "PowerShell parse failed: $path"
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output "FAIL: $_" }
    exit 1
}

Write-Output 'Minimal historical Trace replay contract verified.'

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        $failures.Add($Message)
    }
}

function Read-JsonLines {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Assert-True -Condition $false -Message "Missing dataset: $Path"
        return @()
    }

    return @(Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $_ | ConvertFrom-Json
    })
}

$datasetDirectory = Join-Path $repoRoot 'benchmarks/datasets/agent-evaluation'
$manifestPath = Join-Path $datasetDirectory 'manifest.json'
$skillPath = Join-Path $datasetDirectory 'skills.jsonl'
$retrievalPath = Join-Path $datasetDirectory 'retrieval.jsonl'
$draftPath = Join-Path $datasetDirectory 'draft-flows.jsonl'
$tracePath = Join-Path $datasetDirectory 'trace-replays.jsonl'
$seedPath = Join-Path $repoRoot 'benchmarks/seed/standard.sql'

foreach ($forbiddenPath in @(
    'benchmarks/ledger.json',
    'benchmarks/datasets/catalog.json',
    'benchmarks/datasets/review-template.csv',
    'benchmarks/datasets/agent-reliability-v3',
    'docs/benchmarks/evidence-index.yml'
)) {
    $absolutePath = Join-Path $repoRoot $forbiddenPath
    $artifactExists = if (Test-Path -LiteralPath $absolutePath -PathType Container) {
        @(Get-ChildItem -LiteralPath $absolutePath -File -Recurse).Count -gt 0
    }
    else {
        Test-Path -LiteralPath $absolutePath -PathType Leaf
    }
    Assert-True -Condition (-not $artifactExists) -Message "Forbidden governance artifact remains: $forbiddenPath"
}

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    Assert-True -Condition $false -Message "Missing dataset manifest: $manifestPath"
}
else {
    $manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
    Assert-True -Condition ($manifest.schemaVersion -eq 1) -Message 'Dataset manifest schemaVersion must be 1'
    Assert-True -Condition ($manifest.datasetVersion -eq 'agent-evaluation-v1') -Message 'Dataset version must be agent-evaluation-v1'
    Assert-True -Condition ($manifest.labelStatus -eq 'CANDIDATE_REQUIRES_OWNER_REVIEW') -Message 'Candidate data must require owner review'
    Assert-True -Condition ($manifest.counts.skills -eq 27) -Message 'Manifest must declare 27 Skill candidates'
    Assert-True -Condition ($manifest.counts.retrieval -eq 45) -Message 'Manifest must declare 45 retrieval candidates'
    Assert-True -Condition ($manifest.counts.draftFlows -eq 5) -Message 'Manifest must declare five draft flows'
    Assert-True -Condition ($manifest.counts.traceReplays -eq 2) -Message 'Manifest must declare two Trace replay cases'
    Assert-True -Condition ($manifest.metrics.cache -contains 'sameKeyLoadCount') -Message 'Cache metric name must use sameKeyLoadCount'
    Assert-True -Condition (-not ($manifest.metrics.cache -contains 'originLoadCount')) -Message 'Obsolete originLoadCount metric must not remain'
}

$skills = Read-JsonLines -Path $skillPath
$retrieval = Read-JsonLines -Path $retrievalPath
$draftFlows = Read-JsonLines -Path $draftPath
$traceReplays = Read-JsonLines -Path $tracePath
$allCandidates = @($skills) + @($retrieval) + @($draftFlows) + @($traceReplays)

Assert-True -Condition ($skills.Count -eq 27) -Message 'Skill dataset must contain exactly 27 candidates'
Assert-True -Condition ($retrieval.Count -eq 45) -Message 'Retrieval dataset must contain exactly 45 candidates'
Assert-True -Condition ($draftFlows.Count -eq 5) -Message 'Draft dataset must contain exactly five flows'
Assert-True -Condition ($traceReplays.Count -eq 2) -Message 'Trace dataset must contain exactly two replay cases'

foreach ($collection in @($skills, $retrieval, $draftFlows, $traceReplays)) {
    Assert-True -Condition (@($collection | Where-Object labelStatus -ne 'CANDIDATE_REQUIRES_OWNER_REVIEW').Count -eq 0) -Message 'Every candidate must remain pending owner review'
    Assert-True -Condition (@($collection | Where-Object { $null -ne $_.humanLabels }).Count -eq 0) -Message 'Candidate data must not fabricate human labels'
}
Assert-True -Condition (@($allCandidates.sampleId | Sort-Object -Unique).Count -eq $allCandidates.Count) -Message 'Candidate sample IDs must be unique'

$datasetText = @($skillPath, $retrievalPath, $draftPath, $tracePath) |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    ForEach-Object { Get-Content -Raw -LiteralPath $_ -Encoding UTF8 }
$forbiddenCandidateTokens = @(
    'task-lease',
    'Worker',
    'draft-approval',
    'claimEpoch',
    'stale_worker',
    'secondReviewer',
    'signoff',
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('5pyq6L+H5pyf5a6h5om5')),
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('5a+56LGh6ZSu')),
    [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('5Y+q6KaB5Lu75Yqh55Sx55m75b2V55So5oi35Yib5bu6')),
    [string][char]0x7487,
    [string][char]0x9366,
    [string][char]0xfffd
)
foreach ($token in $forbiddenCandidateTokens) {
    Assert-True -Condition (@($datasetText | Where-Object { $_.Contains($token) }).Count -eq 0) -Message "Candidate data contains forbidden or corrupted token: $token"
}

$skillCounts = @($skills | Group-Object { $_.input.taskType })
foreach ($skillId in @('page-explain', 'evidence-outline', 'draft-fact-check')) {
    $group = @($skillCounts | Where-Object Name -eq $skillId)
    Assert-True -Condition ($group.Count -eq 1 -and $group[0].Count -eq 9) -Message "$skillId must have nine candidates"
}
Assert-True -Condition (@($skills | Where-Object { $_.forbiddenTools -notcontains 'draft_write' }).Count -eq 0) -Message 'Read-only Skill candidates must forbid draft_write'

$expectedRetrievalCounts = @{
    'factual-entity' = 10
    'semantic' = 10
    'cross-document' = 10
    'no-answer' = 8
    'permission-injection' = 7
}
foreach ($bucket in $expectedRetrievalCounts.Keys) {
    $actualCount = @($retrieval | Where-Object quotaBucket -eq $bucket).Count
    Assert-True -Condition ($actualCount -eq $expectedRetrievalCounts[$bucket]) -Message "$bucket retrieval count must be $($expectedRetrievalCounts[$bucket])"
}

$expectedDraftFlows = @('confirm', 'reject', 'duplicate-confirm', 'expired-preview', 'version-conflict')
Assert-True -Condition ((@($draftFlows.flow | Sort-Object) -join ',') -eq (@($expectedDraftFlows | Sort-Object) -join ',')) -Message 'Draft flows must cover the five fixed outcomes'
Assert-True -Condition ((@($traceReplays.failureClass | Sort-Object) -join ',') -eq 'RETRIEVAL_EMPTY,SKILL_VALIDATION_FAILED') -Message 'Trace replays must cover retrieval and Skill/draft failures'

if (Test-Path -LiteralPath $seedPath -PathType Leaf) {
    $seed = Get-Content -Raw -LiteralPath $seedPath -Encoding UTF8
    foreach ($token in @('@benchmark_users', '@benchmark_posts', '@benchmark_relations', 'benchmark_numbers', 'ON DUPLICATE KEY UPDATE')) {
        Assert-True -Condition ($seed.Contains($token)) -Message "SQL seed must contain $token"
    }
}
else {
    Assert-True -Condition $false -Message "Missing deterministic seed: $seedPath"
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output "FAIL: $_" }
    exit 1
}

Write-Output 'Minimal benchmark datasets verified.'

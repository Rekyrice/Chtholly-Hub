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

$ledgerPath = Join-Path $repoRoot 'benchmarks/ledger.json'
$catalogPath = Join-Path $repoRoot 'benchmarks/datasets/catalog.json'
$reviewPath = Join-Path $repoRoot 'benchmarks/datasets/review-template.csv'
$seedPath = Join-Path $repoRoot 'benchmarks/seed/standard.sql'
$v3Directory = Join-Path $repoRoot 'benchmarks/datasets/agent-reliability-v3'
$v3ManifestPath = Join-Path $v3Directory 'manifest.json'
$v3DedupPath = Join-Path $v3Directory 'dedup-report.json'
$v3ReviewPath = Join-Path $v3Directory 'review-queue.csv'
$v3SignoffPath = Join-Path $v3Directory 'signoff-template.json'
$v3DatasetPaths = @(
    (Join-Path $v3Directory 'readonly-skill.jsonl'),
    (Join-Path $v3Directory 'retrieval-generation.jsonl'),
    (Join-Path $v3Directory 'stateful-draft-task.jsonl')
)

foreach ($path in @($ledgerPath, $catalogPath, $reviewPath, $seedPath)) {
    Assert-True -Condition (Test-Path -LiteralPath $path -PathType Leaf) -Message "Missing dataset artifact: $path"
}

foreach ($path in @($v3ManifestPath, $v3DedupPath, $v3ReviewPath, $v3SignoffPath) + $v3DatasetPaths) {
    Assert-True -Condition (Test-Path -LiteralPath $path -PathType Leaf) -Message "Missing v3 candidate artifact: $path"
}

if (Test-Path -LiteralPath $ledgerPath -PathType Leaf) {
    $ledger = Get-Content -Raw -LiteralPath $ledgerPath -Encoding UTF8 | ConvertFrom-Json
    $expectedClaims = @('D-CACHE-01', 'D-COUNTER-01', 'D-RELATION-01', 'D-SKILL-01', 'D-RRF-01', 'D-TRACE-01', 'D-EVAL-SEED')
    Assert-True -Condition ($ledger.claims.Count -eq $expectedClaims.Count) -Message 'Ledger must contain exactly the seven declared baseline claims'
    foreach ($claimId in $expectedClaims) {
        $claim = @($ledger.claims | Where-Object claimId -eq $claimId)
        Assert-True -Condition ($claim.Count -eq 1) -Message "Ledger must contain one $claimId record"
        if ($claim.Count -eq 1) {
            foreach ($property in @('numberKind', 'subjectCommit', 'baselineSubjectCommit', 'candidateSubjectCommit', 'harnessCommit', 'datasetCommit', 'datasetVersion', 'environmentId', 'runIds', 'sampleSize', 'repetitions', 'metricDefinition', 'artifactUri', 'rawArtifactHash', 'retentionUntil', 'summary', 'status', 'reviewer', 'reviewedAt')) {
                Assert-True -Condition ($null -ne $claim[0].PSObject.Properties[$property]) -Message "$claimId must declare $property"
            }
        }
    }
}

if (Test-Path -LiteralPath $catalogPath -PathType Leaf) {
    $catalog = Get-Content -Raw -LiteralPath $catalogPath -Encoding UTF8 | ConvertFrom-Json
    Assert-True -Condition ($catalog.labelStatus -eq 'COLLECTED_UNREVIEWED') -Message 'Seed catalog must remain COLLECTED_UNREVIEWED'
    Assert-True -Condition ($catalog.sources.questions.observedCount -eq 50) -Message 'Catalog must record the observed 50-question seed'
    Assert-True -Condition ($catalog.sources.dimensions.observedCount -eq 9) -Message 'Catalog must record the observed 9-dimension seed'

    $questionPath = Join-Path $repoRoot $catalog.sources.questions.path
    $dimensionPath = Join-Path $repoRoot $catalog.sources.dimensions.path
    $actualQuestions = @(Select-String -LiteralPath $questionPath -Pattern '^\s+- id:' -Encoding UTF8).Count
    $actualDimensions = @(Select-String -LiteralPath $dimensionPath -Pattern '^\s+- key:' -Encoding UTF8).Count
    Assert-True -Condition ($actualQuestions -eq $catalog.sources.questions.observedCount) -Message 'Question count must match the referenced seed file'
    Assert-True -Condition ($actualDimensions -eq $catalog.sources.dimensions.observedCount) -Message 'Dimension count must match the referenced seed file'
}

if ((@($v3ManifestPath, $v3DedupPath, $v3ReviewPath, $v3SignoffPath) + $v3DatasetPaths |
        Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }).Count -eq 0) {
    $v3Manifest = Get-Content -Raw -LiteralPath $v3ManifestPath -Encoding UTF8 | ConvertFrom-Json
    $v3Dedup = Get-Content -Raw -LiteralPath $v3DedupPath -Encoding UTF8 | ConvertFrom-Json
    $v3Signoff = Get-Content -Raw -LiteralPath $v3SignoffPath -Encoding UTF8 | ConvertFrom-Json
    $v3Rows = @($v3DatasetPaths | ForEach-Object {
        Get-Content -LiteralPath $_ -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json }
    })
    $v3ReviewRows = @(Import-Csv -LiteralPath $v3ReviewPath -Encoding UTF8)

    Assert-True -Condition ($v3Manifest.datasetFamily -eq 'agent-reliability-v3') -Message 'v3 manifest must retain the migrated dataset family'
    Assert-True -Condition ($v3Manifest.reviewStatus -eq 'COLLECTED_UNREVIEWED') -Message 'v3 candidates must remain COLLECTED_UNREVIEWED before human signoff'
    Assert-True -Condition ($v3Manifest.subjectCommit -eq '396f7365c129617cd4c3898e58e9a78ef9447898') -Message 'v3 manifest must identify the current business baseline'
    Assert-True -Condition ($v3Manifest.sourceDatasetCommit -eq 'ccb7efbf6c32947a09e57a740c1ee6f6a986ff1f') -Message 'v3 manifest must preserve the committed migration source'
    Assert-True -Condition ($v3Manifest.factRevision -eq 'counter-terminal-v1') -Message 'v3 manifest must identify the terminal counter fact revision'
    Assert-True -Condition ($v3Manifest.counts.readonlySkill -eq 120) -Message 'v3 must contain 120 read-only Skill candidates'
    Assert-True -Condition ($v3Manifest.counts.retrievalAndGeneration -eq 150) -Message 'v3 must contain 150 retrieval and generation candidates'
    Assert-True -Condition ($v3Manifest.counts.statefulDraftTask -eq 100) -Message 'v3 must contain 100 stateful task candidates'
    Assert-True -Condition ($v3Manifest.counts.total -eq 370) -Message 'v3 manifest total must be 370'
    Assert-True -Condition ($v3Rows.Count -eq 370) -Message 'v3 JSONL files must contain exactly 370 candidates'
    Assert-True -Condition (@($v3Rows.sampleId | Sort-Object -Unique).Count -eq 370) -Message 'v3 sample IDs must be unique'
    Assert-True -Condition (@($v3Rows | Where-Object labelStatus -ne 'COLLECTED_UNREVIEWED').Count -eq 0) -Message 'v3 candidate labels must remain COLLECTED_UNREVIEWED'
    Assert-True -Condition (@($v3Rows | Where-Object { $null -ne $_.humanLabels }).Count -eq 0) -Message 'v3 candidates must not fabricate human labels'

    $obsoleteCounterFacts = 'Redis Stream|CounterStreamBridge|\bXADD\b|\bXACK\b|\bPEL\b|\bSDS\b|cutover watermark'
    foreach ($datasetPath in $v3DatasetPaths) {
        $obsoleteMatches = @(Select-String -LiteralPath $datasetPath -Pattern $obsoleteCounterFacts -Encoding UTF8)
        Assert-True -Condition ($obsoleteMatches.Count -eq 0) -Message "v3 candidates must not retain obsolete counter facts: $datasetPath"
    }

    Assert-True -Condition ($v3ReviewRows.Count -eq 370) -Message 'v3 review queue must contain exactly 370 rows'
    Assert-True -Condition (@($v3ReviewRows.sampleId | Sort-Object -Unique).Count -eq 370) -Message 'v3 review queue sample IDs must be unique'
    Assert-True -Condition (@($v3ReviewRows | Where-Object labelStatus -ne 'COLLECTED_UNREVIEWED').Count -eq 0) -Message 'v3 review queue must remain COLLECTED_UNREVIEWED'
    Assert-True -Condition ($v3Dedup.inputCount -eq 370 -and $v3Dedup.outputCount -eq 370 -and $v3Dedup.duplicatesRemoved -eq 0) -Message 'v3 dedup report must account for all 370 unique candidates'
    Assert-True -Condition ($v3Signoff.status -eq 'PENDING_HUMAN_REVIEW') -Message 'v3 signoff must remain pending human review'
    Assert-True -Condition ($null -eq $v3Signoff.primaryReviewer -and $null -eq $v3Signoff.secondReviewer) -Message 'v3 signoff must not invent reviewers'
}

if (Test-Path -LiteralPath $reviewPath -PathType Leaf) {
    $header = Get-Content -LiteralPath $reviewPath -TotalCount 1 -Encoding UTF8
    foreach ($column in @('sampleId', 'sourceType', 'difficulty', 'split', 'snapshotId', 'labelStatus', 'acceptedSkillIds', 'requiredTools', 'forbiddenTools', 'relevantDocumentIds', 'answerExists', 'hardFails', 'reviewer', 'reviewedAt', 'adjudicationReason')) {
        Assert-True -Condition (($header -split ',') -contains $column) -Message "Review template must contain $column"
    }
}

if (Test-Path -LiteralPath $seedPath -PathType Leaf) {
    $seed = Get-Content -Raw -LiteralPath $seedPath -Encoding UTF8
    foreach ($token in @('@benchmark_users', '@benchmark_posts', '@benchmark_relations', 'benchmark_numbers', 'ON DUPLICATE KEY UPDATE')) {
        Assert-True -Condition $seed.Contains($token) -Message "SQL seed must contain $token"
    }
    Assert-True -Condition (-not $seed.Contains('INSERT INTO users VALUES')) -Message 'SQL seed must generate rows instead of committing expanded VALUES lists'
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output "FAIL: $_" }
    exit 1
}

Write-Output 'Benchmark dataset contract verified.'

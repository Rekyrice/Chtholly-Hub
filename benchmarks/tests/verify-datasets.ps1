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

foreach ($path in @($ledgerPath, $catalogPath, $reviewPath, $seedPath)) {
    Assert-True -Condition (Test-Path -LiteralPath $path -PathType Leaf) -Message "Missing dataset artifact: $path"
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

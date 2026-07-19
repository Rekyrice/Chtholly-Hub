[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { $failures.Add($Message) }
}

function Assert-File {
    param([string]$RelativePath)
    Assert-True -Condition (Test-Path -LiteralPath (Join-Path $repoRoot $RelativePath) -PathType Leaf) -Message "Missing file: $RelativePath"
}

Push-Location $repoRoot
try {
    git check-ignore --quiet .benchmark-results/probe
    Assert-True -Condition ($LASTEXITCODE -eq 0) -Message '.benchmark-results/ must be ignored'
    Assert-True -Condition (@(git ls-files '.benchmark-results/**').Count -eq 0) -Message '.benchmark-results/ must not contain tracked files'

    $requiredFiles = @(
        'benchmarks/README.md',
        'benchmarks/config/standard.yml',
        'benchmarks/k6/cache-scenarios.js',
        'benchmarks/schema/manifest.schema.json',
        'benchmarks/seed/standard.sql',
        'benchmarks/templates/experiment-report.md',
        'benchmarks/tests/verify-trace-replay.ps1',
        'apps/server/src/test/java/com/chtholly/integration/CounterInteractionEvidenceCollectorIT.java',
        'apps/server/src/test/java/com/chtholly/integration/CounterEvidenceSqlProbe.java',
        'apps/server/src/test/java/com/chtholly/integration/CounterEvidenceResultWriter.java',
        'scripts/benchmark/collect-counter-evidence.ps1',
        'scripts/benchmark/environment.ps1',
        'scripts/benchmark/new-benchmark-token.ps1',
        'scripts/benchmark/run.ps1',
        'scripts/benchmark/seed.ps1',
        'scripts/benchmark/summarize.ps1',
        'scripts/benchmark/trace-replay.ps1',
        'scripts/benchmark/verify-matrix.ps1'
    )
    foreach ($path in $requiredFiles) { Assert-File -RelativePath $path }

    foreach ($path in @(
        'benchmarks/k6/backend-scenarios.js',
        'docs/benchmarks/evidence-index.yml'
    )) {
        Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $repoRoot $path) -PathType Leaf)) -Message "Obsolete harness file remains: $path"
    }

    $configPath = Join-Path $repoRoot 'benchmarks/config/standard.yml'
    if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        $config = Get-Content -Raw -LiteralPath $configPath -Encoding UTF8
        foreach ($token in @('stable-hot:', 'expiry-spike:', 'db-only', 'full-no-singleflight', 'full', 'formalRuns: 12', 'repetitions: 3')) {
            Assert-True -Condition ($config.Contains($token)) -Message "Benchmark config must contain $token"
        }
        foreach ($token in @('mixed:', 'relationPercent:', 'redis-db')) {
            Assert-True -Condition (-not $config.Contains($token)) -Message "Benchmark config must not contain $token"
        }
    }

    $schemaPath = Join-Path $repoRoot 'benchmarks/schema/manifest.schema.json'
    if (Test-Path -LiteralPath $schemaPath -PathType Leaf) {
        $schema = Get-Content -Raw -LiteralPath $schemaPath -Encoding UTF8 | ConvertFrom-Json
        foreach ($property in @('runId', 'profile', 'scenario', 'variant', 'repetition', 'subjectCommit', 'executionCommit', 'harnessCommit', 'datasetCommit', 'environmentId', 'workload', 'status', 'startedAt', 'effectiveReadMode', 'singleFlightEnabled', 'cacheMetricsAvailable', 'cacheInvalidatedAt', 'coldStartVerified')) {
            Assert-True -Condition ($schema.required -contains $property) -Message "Manifest schema must require $property"
        }
        foreach ($property in @('experiment', 'numberKind', 'artifacts')) {
            Assert-True -Condition ($null -eq $schema.properties.$property) -Message "Manifest schema must not retain $property governance"
        }
    }

    $k6Path = Join-Path $repoRoot 'benchmarks/k6/cache-scenarios.js'
    if (Test-Path -LiteralPath $k6Path -PathType Leaf) {
        & node --check $k6Path
        Assert-True -Condition ($LASTEXITCODE -eq 0) -Message 'Cache k6 scenario must be valid JavaScript'
        $k6Source = Get-Content -Raw -LiteralPath $k6Path -Encoding UTF8
        foreach ($token in @('stable-hot', 'expiry-spike', '920000000000000001', '/api/v1/posts/detail/')) {
            Assert-True -Condition ($k6Source.Contains($token)) -Message "Cache k6 scenario must contain $token"
        }
        foreach ($token in @('/api/v1/action/', '/api/v1/relation/', 'BENCHMARK_TOKEN')) {
            Assert-True -Condition (-not $k6Source.Contains($token)) -Message "Cache k6 scenario must not contain $token"
        }
    }

    $environmentPath = Join-Path $repoRoot 'scripts/benchmark/environment.ps1'
    $counterCollectorPath = Join-Path $repoRoot 'scripts/benchmark/collect-counter-evidence.ps1'
    $counterCollectorTestPath = Join-Path $repoRoot 'apps/server/src/test/java/com/chtholly/integration/CounterInteractionEvidenceCollectorIT.java'
    $counterSqlProbePath = Join-Path $repoRoot 'apps/server/src/test/java/com/chtholly/integration/CounterEvidenceSqlProbe.java'
    $draftPersistenceTestPath = Join-Path $repoRoot 'apps/server/src/test/java/com/chtholly/integration/DraftEditPersistenceIT.java'
    $runPath = Join-Path $repoRoot 'scripts/benchmark/run.ps1'
    $summarizePath = Join-Path $repoRoot 'scripts/benchmark/summarize.ps1'
    $matrixPath = Join-Path $repoRoot 'scripts/benchmark/verify-matrix.ps1'
    $environmentSource = if (Test-Path -LiteralPath $environmentPath -PathType Leaf) { Get-Content -Raw -LiteralPath $environmentPath -Encoding UTF8 } else { '' }
    $counterCollectorSource = if (Test-Path -LiteralPath $counterCollectorPath -PathType Leaf) { Get-Content -Raw -LiteralPath $counterCollectorPath -Encoding UTF8 } else { '' }
    $counterCollectorTestSource = if (Test-Path -LiteralPath $counterCollectorTestPath -PathType Leaf) { Get-Content -Raw -LiteralPath $counterCollectorTestPath -Encoding UTF8 } else { '' }
    $counterSqlProbeSource = if (Test-Path -LiteralPath $counterSqlProbePath -PathType Leaf) { Get-Content -Raw -LiteralPath $counterSqlProbePath -Encoding UTF8 } else { '' }
    $draftPersistenceTestSource = if (Test-Path -LiteralPath $draftPersistenceTestPath -PathType Leaf) { Get-Content -Raw -LiteralPath $draftPersistenceTestPath -Encoding UTF8 } else { '' }
    $runSource = if (Test-Path -LiteralPath $runPath -PathType Leaf) { Get-Content -Raw -LiteralPath $runPath -Encoding UTF8 } else { '' }
    $summarizeSource = if (Test-Path -LiteralPath $summarizePath -PathType Leaf) { Get-Content -Raw -LiteralPath $summarizePath -Encoding UTF8 } else { '' }

    foreach ($token in @('db-only', 'full-no-singleflight', 'full', 'CACHE_READ_MODE', 'KAFKA_ENABLED=false', 'CANAL_ENABLED=false')) {
        Assert-True -Condition ($environmentSource.Contains($token)) -Message "Benchmark environment must contain $token"
    }
    Assert-True -Condition ($environmentSource.Contains('/actuator/info')) -Message 'Cache environment must use the server readiness endpoint instead of aggregate dependency health'
    Assert-True -Condition (-not $environmentSource.Contains('new-benchmark-token.ps1')) -Message 'Cache environment health check must not issue an auth token'

    foreach ($token in @('stable-hot', 'expiry-spike', 'db-only', 'full-no-singleflight', 'full', 'cache-scenarios.js')) {
        Assert-True -Condition ($runSource.Contains($token)) -Message "Runner must contain $token"
    }
    foreach ($token in @('chtholly.cache.runtime', 'effectiveReadMode', 'singleFlightEnabled', 'cacheMetricsAvailable', 'coldStartVerified', 'cacheInvalidatedAt')) {
        Assert-True -Condition ($runSource.Contains($token)) -Message "Runner must verify runtime contract token $token"
    }
    Assert-True -Condition (-not $runSource.Contains('Start-Sleep -Seconds 2')) -Message 'Expiry spike must use explicit cache invalidation instead of a fixed sleep'
    Assert-True -Condition ($runSource.Contains('redis-cli') -and $runSource.Contains('docker restart')) -Message 'Expiry spike must clear Redis detail data and restart the owned server'
    Assert-True -Condition ($runSource.Contains('/actuator/info')) -Message 'Runner recovery must use the server readiness endpoint instead of aggregate dependency health'
    Assert-True -Condition (-not $runSource.Contains('/actuator/health')) -Message 'Runner recovery must not require disabled benchmark dependencies to be healthy'
    foreach ($token in @('imageIds', 'mysql', 'redis', 'server')) {
        Assert-True -Condition ($environmentSource.Contains($token)) -Message "Environment manifest must record image identity token $token"
    }
    foreach ($token in @('CounterInteractionEvidenceCollectorIT', 'counter.evidence.enabled', '.benchmark-results', 'status --porcelain', 'subjectCommit', 'harnessCommit', 'datasetCommit')) {
        Assert-True -Condition ($counterCollectorSource.Contains($token)) -Message "Counter evidence runner must contain $token"
    }
    foreach ($token in @('requestTotal', 'stateChangeCount', 'kafkaEventCount', 'dedupHitCount', 'aggregationBatchCount', 'mysqlUpdateCount', 'preCalibrationDiscrepancy', 'postCalibrationDiscrepancy')) {
        Assert-True -Condition ($counterCollectorSource.Contains($token)) -Message "Counter evidence runner must validate $token"
        Assert-True -Condition ($counterCollectorTestSource.Contains($token)) -Message "Counter evidence collector must record $token"
    }
    $counterCollectorImplementation = $counterCollectorTestSource + $counterSqlProbeSource
    foreach ($token in @('counter-aggregation-events', 'applyBatch', 'reconcileEntity', 'incrementSnapshots', 'replaceReactionSnapshots')) {
        Assert-True -Condition ($counterCollectorImplementation.Contains($token)) -Message "Counter evidence collector must exercise $token"
    }
    foreach ($token in @('rejectPersistsDecisionWithoutMutatingDraft', 'expiredConfirmPersistsExpiryWithoutMutatingDraft', 'versionConflictPreservesNewerDraftAndPendingPreview')) {
        Assert-True -Condition ($draftPersistenceTestSource.Contains($token)) -Message "Draft persistence integration test must contain $token"
    }
    Assert-True -Condition ($runSource.Contains('new-benchmark-token.ps1')) -Message 'Runner must authenticate its Actuator metric reads without persisting the token'
    Assert-True -Condition ($runSource.Contains("if (`$summary.status -ne 'COMPLETED')")) -Message 'Runner must fail when the final summary is incomplete'
    foreach ($token in @("'all'", "'counter'", "'relation'", "'fault'")) {
        Assert-True -Condition (-not $runSource.Contains($token)) -Message "Runner must not retain scenario $token"
    }
    foreach ($token in @('ArchiveDirectory', 'Compress-Archive', 'checksums.sha256', 'EVIDENCE_ARCHIVE_DIR')) {
        Assert-True -Condition (-not $summarizeSource.Contains($token)) -Message "Summarizer must not contain $token"
    }
    foreach ($token in @('p95Ms', 'errorRate', 'mysqlQueryCount', 'sameKeyLoadCount')) {
        Assert-True -Condition ($summarizeSource.Contains($token)) -Message "Summarizer must contain $token"
    }

    if ($environmentSource.Contains('full-no-singleflight')) {
        $plan = & $environmentPath -Action Validate -RunId harness-contract -Profile smoke -Variant full-no-singleflight | Out-String | ConvertFrom-Json
        Assert-True -Condition ($plan.isolated -and $plan.removeVolumesOnStop) -Message 'Environment must declare isolated lifecycle'
        Assert-True -Condition ($plan.services.Count -eq 3) -Message 'Cache environment must contain MySQL, Redis and server only'
        Assert-True -Condition ($plan.cacheReadMode -eq 'full-no-singleflight') -Message 'Environment must preserve the no-SingleFlight mode'
        Assert-True -Condition (-not ($plan.PSObject.Properties.Name -contains 'password')) -Message 'Validation output must not expose credentials'
    }

    $seedPath = Join-Path $repoRoot 'scripts/benchmark/seed.ps1'
    if (Test-Path -LiteralPath $seedPath -PathType Leaf) {
        $seedPlan = & $seedPath -Profile smoke -ValidateOnly | Out-String | ConvertFrom-Json
        Assert-True -Condition ($seedPlan.posts -eq 1000 -and $seedPlan.users -eq 200) -Message 'Smoke seed must remain deterministic'
    }

    if ($runSource.Contains('stable-hot') -and $summarizeSource.Contains('sameKeyLoadCount')) {
        $runId = "harness-contract-$PID"
        $runDirectory = Join-Path $repoRoot ".benchmark-results/$runId"
        if (Test-Path -LiteralPath $runDirectory) { throw "Unexpected test run directory: $runDirectory" }
        $head = (git rev-parse HEAD).Trim()
        try {
            & $runPath -Profile smoke -Scenario stable-hot -Variant full -RunId $runId -Repetition 1 -SubjectCommit $head -HarnessCommit $head -DatasetCommit $head -ValidateOnly
            $manifest = Get-Content -Raw -LiteralPath (Join-Path $runDirectory 'manifest.json') -Encoding UTF8 | ConvertFrom-Json
            Assert-True -Condition ($manifest.status -eq 'VALIDATED') -Message 'ValidateOnly manifest must be VALIDATED'
            Assert-True -Condition ($manifest.scenario -eq 'stable-hot' -and $manifest.variant -eq 'full') -Message 'Manifest must preserve the cache experiment identity'
            Assert-True -Condition ($null -eq $manifest.experiment -and $null -eq $manifest.numberKind) -Message 'Manifest must stay minimal'

            $fixtureK6 = [ordered]@{ metrics = [ordered]@{
                http_req_duration = [ordered]@{ 'p(95)' = 42.5 }
                http_req_failed = [ordered]@{ value = 0.01 }
            } }
            $fixtureApplication = [ordered]@{ mysqlQueryCount = 7; sameKeyLoadCount = 2 }
            $fixtureK6 | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'raw/k6.json') -Encoding UTF8
            $fixtureApplication | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDirectory 'raw/application-metrics.json') -Encoding UTF8
            & $summarizePath -RunDirectory $runDirectory
            foreach ($name in @('summary.json', 'summary.md', 'failures.md')) {
                Assert-True -Condition (Test-Path -LiteralPath (Join-Path $runDirectory $name) -PathType Leaf) -Message "Summarizer must create $name"
            }
            $summary = Get-Content -Raw -LiteralPath (Join-Path $runDirectory 'summary.json') -Encoding UTF8 | ConvertFrom-Json
            Assert-True -Condition ($summary.metrics.p95Ms -eq 42.5 -and $summary.metrics.errorRate -eq 0.01) -Message 'Summarizer must preserve k6 p95 and error rate'
            Assert-True -Condition ($summary.metrics.mysqlQueryCount -eq 7 -and $summary.metrics.sameKeyLoadCount -eq 2) -Message 'Summarizer must preserve application load counts'
            Assert-True -Condition (-not (Test-Path -LiteralPath (Join-Path $runDirectory 'checksums.sha256'))) -Message 'Summarizer must not create checksum governance'
        }
        finally {
            if (Test-Path -LiteralPath $runDirectory -PathType Container) {
                $resolved = (Resolve-Path -LiteralPath $runDirectory).Path
                $allowed = (Resolve-Path -LiteralPath (Join-Path $repoRoot '.benchmark-results')).Path + [IO.Path]::DirectorySeparatorChar
                if (-not $resolved.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe cleanup target: $resolved" }
                Remove-Item -LiteralPath $resolved -Recurse -Force
            }
        }
    }

    if (Test-Path -LiteralPath $matrixPath -PathType Leaf) {
        $resultsRoot = Join-Path $repoRoot '.benchmark-results'
        $runIds = [System.Collections.Generic.List[string]]::new()
        $cleanupPaths = [System.Collections.Generic.List[string]]::new()
        $matrixOutput = Join-Path $resultsRoot "matrix-contract-$PID.json"
        $head = (git rev-parse HEAD).Trim()
        $cells = @(
            @{ scenario = 'stable-hot'; variant = 'db-only' },
            @{ scenario = 'stable-hot'; variant = 'full' },
            @{ scenario = 'expiry-spike'; variant = 'full-no-singleflight' },
            @{ scenario = 'expiry-spike'; variant = 'full' }
        )
        try {
            $index = 0
            foreach ($cell in $cells) {
                foreach ($repetition in 1..3) {
                    $index++
                    $runId = "matrix-contract-$PID-$index"
                    $environmentRunId = "$runId-env"
                    $runDirectory = Join-Path $resultsRoot $runId
                    $environmentDirectory = Join-Path $resultsRoot $environmentRunId
                    New-Item -ItemType Directory -Force -Path $runDirectory, $environmentDirectory | Out-Null
                    $runIds.Add($runId)
                    $cleanupPaths.Add($runDirectory)
                    $cleanupPaths.Add($environmentDirectory)

                    $isExpiry = $cell.scenario -eq 'expiry-spike'
                    $manifest = [ordered]@{
                        runId = $runId; profile = 'standard'; scenario = $cell.scenario; variant = $cell.variant
                        repetition = $repetition; subjectCommit = $head; executionCommit = $head
                        harnessCommit = $head; datasetCommit = $head; environmentId = "project-$index"
                        workload = [ordered]@{ seed = 20260715; concurrency = 64; warmupSeconds = 60; durationSeconds = 300 }
                        status = 'COMPLETED'; effectiveReadMode = $cell.variant
                        singleFlightEnabled = $cell.variant -eq 'full'; cacheMetricsAvailable = $true
                        cacheInvalidatedAt = if ($isExpiry) { '2026-07-19T00:00:00Z' } else { $null }
                        coldStartVerified = $isExpiry
                    }
                    $summary = [ordered]@{
                        runId = $runId; status = 'COMPLETED'; profile = 'standard'; scenario = $cell.scenario
                        variant = $cell.variant; repetition = $repetition; subjectCommit = $head
                        harnessCommit = $head; datasetCommit = $head
                        metrics = [ordered]@{ p95Ms = 10 + $index; errorRate = 0; mysqlQueryCount = 2; sameKeyLoadCount = 1 }
                    }
                    $environment = [ordered]@{ environmentRunId = $environmentRunId }
                    $runtime = [ordered]@{
                        runId = $environmentRunId; profile = 'standard'; scenario = $cell.scenario; variant = $cell.variant
                        projectName = "project-$index"; executionCommit = $head
                        imageIds = [ordered]@{ mysql = 'sha256:mysql'; redis = 'sha256:redis'; server = 'sha256:java' }
                    }
                    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'manifest.json') -Encoding UTF8
                    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runDirectory 'summary.json') -Encoding UTF8
                    $environment | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDirectory 'environment.json') -Encoding UTF8
                    $runtime | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $environmentDirectory 'environment-runtime.json') -Encoding UTF8
                }
            }

            & $matrixPath -ResultsRoot $resultsRoot -RunIds $runIds.ToArray() -OutputPath $matrixOutput
            $matrix = Get-Content -Raw -LiteralPath $matrixOutput -Encoding UTF8 | ConvertFrom-Json
            Assert-True -Condition ($matrix.status -eq 'COMPLETE' -and $matrix.runCount -eq 12) -Message 'Matrix verifier must accept the exact 12-run cache matrix'

            $firstSummaryPath = Join-Path (Join-Path $resultsRoot $runIds[0]) 'summary.json'
            $firstSummary = Get-Content -Raw -LiteralPath $firstSummaryPath -Encoding UTF8 | ConvertFrom-Json
            $firstSummary.status = 'INCOMPLETE'
            $firstSummary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $firstSummaryPath -Encoding UTF8
            $rejected = $false
            try { & $matrixPath -ResultsRoot $resultsRoot -RunIds $runIds.ToArray() -OutputPath $matrixOutput }
            catch { $rejected = $true }
            Assert-True -Condition $rejected -Message 'Matrix verifier must reject an incomplete run'
        }
        finally {
            foreach ($path in $cleanupPaths) {
                if (Test-Path -LiteralPath $path -PathType Container) { Remove-Item -LiteralPath $path -Recurse -Force }
            }
            if (Test-Path -LiteralPath $matrixOutput -PathType Leaf) { Remove-Item -LiteralPath $matrixOutput -Force }
        }
    }
}
finally {
    Pop-Location
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output "FAIL: $_" }
    exit 1
}

Write-Output 'Minimal benchmark harness verified.'

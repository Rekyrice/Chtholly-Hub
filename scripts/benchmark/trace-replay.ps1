[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')]
    [string]$RunId,

    [string]$HarnessCommit = 'HEAD',

    [Parameter(Mandatory = $true)]
    [string]$DatasetCommit
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$resultDirectory = Join-Path $repoRoot ".benchmark-results/$RunId"
$tempDirectory = Join-Path $repoRoot ".codex-tmp/tr-$PID-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$datasetRelative = 'benchmarks/datasets/agent-evaluation/trace-replays.jsonl'
$runnerRelative = 'scripts/benchmark/trace-replay.ps1'
$probeRelative = 'benchmarks/fixtures/trace-replay/HistoricalTraceRuntimeIT.java'
$datasetPath = Join-Path $repoRoot $datasetRelative
$probePath = Join-Path $repoRoot $probeRelative
$startedAt = [DateTimeOffset]::UtcNow.ToString('o')

function Resolve-Commit {
    param([string]$Value, [string]$Name)
    $resolved = & git -C $repoRoot rev-parse --verify "$Value^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($resolved)) {
        throw "$Name does not resolve to a commit: $Value"
    }
    return $resolved.Trim()
}

function Get-GitObject {
    param([string]$Commit, [string]$Path, [bool]$Required = $true)
    $value = & git -C $repoRoot rev-parse --verify "$Commit`:$Path" 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($value)) {
        if ($Required) { throw "Cannot resolve $Path at $Commit" }
        return $null
    }
    return $value.Trim()
}

function Get-Sha256Text {
    param([AllowEmptyString()][string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $digest = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([BitConverter]::ToString($digest) -replace '-', '').ToLowerInvariant()
}

function Write-Json {
    param([string]$Path, [object]$Value)
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Invoke-MavenLogged {
    param([string[]]$MavenArguments, [string]$LogPath)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & mvn @MavenArguments *> $LogPath
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
}

function Get-ProductionDigest {
    param([string]$ServerRoot)
    $paths = @(Get-ChildItem -LiteralPath (Join-Path $ServerRoot 'src/main') -Recurse -File)
    $paths += Get-Item -LiteralPath (Join-Path $ServerRoot 'pom.xml')
    $lines = $paths | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($ServerRoot.Length).TrimStart('\', '/').Replace('\', '/')
        "$relative $((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
    }
    return Get-Sha256Text ($lines -join "`n")
}

function Remove-ValidatedTemp {
    if (-not (Test-Path -LiteralPath $tempDirectory -PathType Container)) { return }
    $tempRoot = (Resolve-Path -LiteralPath (Join-Path $repoRoot '.codex-tmp')).Path
    $resolved = (Resolve-Path -LiteralPath $tempDirectory).Path
    $prefix = $tempRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe Trace runtime cleanup target: $resolved"
    }
    if (((Get-Item -LiteralPath $resolved -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Trace runtime cleanup refuses a reparse point: $resolved"
    }
    [IO.Directory]::Delete("\\?\$resolved", $true)
}

function Add-Case {
    param([Collections.IDictionary]$Groups, [string]$Commit, [object]$Sample, [string]$Role)
    if (-not $Groups.Contains($Commit)) { $Groups[$Commit] = [Collections.Generic.List[object]]::new() }
    $Groups[$Commit].Add([ordered]@{
        sample = $Sample
        sampleId = [string]$Sample.sampleId
        subjectRole = $Role
        question = [string]$Sample.fixture.question
        pageContext = [string]$Sample.fixture.pageContext
    })
}

function Assert-Projection {
    param([object]$Actual, [object]$Sample, [string]$Role, [string]$Subject)
    $issues = [Collections.Generic.List[string]]::new()
    $expected = $Sample.expected.$Role
    $checks = [ordered]@{
        sampleId = $Sample.sampleId; subjectRole = $Role; subjectCommit = $Subject
        runtimeInfrastructure = 'HISTORICAL_ARCHIVE_TESTCONTAINERS'
        tracePersistence = 'MYSQL_EXECUTION_TRACES'; traceReadback = 'TRACE_QUERY_SERVICE'
        observationOverlay = 'TEST_ONLY_OBSERVATION_OVERLAY'; productionSourceModified = $false
        runtimeTimezone = $Sample.environment.timezone; runtimeLocale = $Sample.environment.locale
        rawTracePayloadSource = 'MYSQL_EXECUTION_TRACES.TRACE_PAYLOAD'; queryReadbackMatched = $true
        traceStatus = $expected.traceStatus; traceRowCount = 1; externalModelCalls = 0
        deterministicInvokerCalls = 1; retrievalComponent = $expected.retrievalComponent
        citationValidator = $expected.citationValidator; evidenceCount = $expected.evidenceCount
        failureType = $expected.failureType; citationValidationStatus = $expected.citationValidationStatus
        semanticCalls = 1; keywordCalls = 1; entityCalls = 1
        entityMappingCalls = if ($expected.retrievalComponent -eq 'document-rrf-v1') { 1 } else { 0 }
    }
    foreach ($name in $checks.Keys) {
        if ($Actual.$name -ne $checks[$name]) { $issues.Add("$($Sample.sampleId).$Role.$name") }
    }
    foreach ($route in @('semantic', 'keyword', 'entity')) {
        $expectedStatus = if ($route -eq 'entity') { $expected.entityStatus } else { 'SUCCESS_EMPTY' }
        if ($Actual.retrievalStatuses.$route -ne $expectedStatus) {
            $issues.Add("$($Sample.sampleId).$Role.retrievalStatuses.$route")
        }
    }
    if ($Actual.persistedTracePayloadFieldCount -le 0) { $issues.Add("$($Sample.sampleId).$Role.persistedTracePayloadFieldCount") }
    foreach ($name in @('inputFingerprint', 'questionFingerprint', 'pageContextFingerprint',
            'rawTracePayloadSha256', 'traceCorrelationFingerprint', 'evidenceSnapshotHash')) {
        if ([string]$Actual.$name -notmatch '^[0-9a-f]{64}$') { $issues.Add("$($Sample.sampleId).$Role.$name") }
    }
    return $issues.ToArray()
}

& git -C $repoRoot check-ignore --quiet -- '.benchmark-results/probe'
if ($LASTEXITCODE -ne 0) { throw '.benchmark-results/ must be Git-ignored' }
& git -C $repoRoot check-ignore --quiet -- '.codex-tmp/probe'
if ($LASTEXITCODE -ne 0) { throw '.codex-tmp/ must be Git-ignored' }
foreach ($storageRoot in @((Join-Path $repoRoot '.benchmark-results'), (Join-Path $repoRoot '.codex-tmp'))) {
    if (-not (Test-Path -LiteralPath $storageRoot)) { New-Item -ItemType Directory -Path $storageRoot | Out-Null }
    if (((Get-Item -LiteralPath $storageRoot -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Trace replay storage root must not be a reparse point: $storageRoot"
    }
}
if ((Test-Path -LiteralPath $resultDirectory) -or (Test-Path -LiteralPath $tempDirectory)) {
    throw "Trace replay runId already exists: $RunId"
}
$status = & git -C $repoRoot status --porcelain
if (-not [string]::IsNullOrWhiteSpace(($status | Out-String))) {
    throw 'Trace replay requires a clean worktree'
}

$executionCommit = Resolve-Commit 'HEAD' 'ExecutionCommit'
$harnessCommitFull = Resolve-Commit $HarnessCommit 'HarnessCommit'
$datasetCommitFull = Resolve-Commit $DatasetCommit 'DatasetCommit'
if ($executionCommit -ne $harnessCommitFull) { throw 'HarnessCommit must equal the clean executionCommit' }
$harnessBlobs = [ordered]@{}
foreach ($relative in @($runnerRelative, $probeRelative)) {
    $commitBlob = Get-GitObject $harnessCommitFull $relative
    $workingBlob = (& git -C $repoRoot hash-object -- $relative).Trim()
    if ($LASTEXITCODE -ne 0 -or $commitBlob -ne $workingBlob) { throw "HarnessCommit does not own $relative" }
    $harnessBlobs[$relative] = $commitBlob
}
$datasetBlob = Get-GitObject $datasetCommitFull $datasetRelative
$workingDatasetBlob = (& git -C $repoRoot hash-object -- $datasetRelative).Trim()
if ($LASTEXITCODE -ne 0 -or $datasetBlob -ne $workingDatasetBlob) {
    throw 'DatasetCommit does not own the Trace replay dataset'
}

$samples = @(Get-Content -LiteralPath $datasetPath -Encoding UTF8 |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_ | ConvertFrom-Json })
if ($samples.Count -ne 2) { throw 'Trace replay dataset must contain exactly two samples' }
$casesBySubject = [ordered]@{}
foreach ($sample in $samples) {
    if ($sample.labelStatus -ne 'CANDIDATE_REQUIRES_OWNER_REVIEW') { throw 'Trace labels are not review candidates' }
    Add-Case $casesBySubject (Resolve-Commit $sample.baselineCommit 'BaselineCommit') $sample 'baseline'
    Add-Case $casesBySubject (Resolve-Commit $sample.candidateCommit 'CandidateCommit') $sample 'candidate'
}

New-Item -ItemType Directory -Path (Join-Path $resultDirectory 'raw') -Force | Out-Null
New-Item -ItemType Directory -Path $tempDirectory -Force | Out-Null
$runtimeRecords = [Collections.Generic.List[object]]::new()
$executedRegressionTests = [Collections.Generic.List[string]]::new()
try {
    foreach ($subject in $casesBySubject.Keys) {
        $cases = @($casesBySubject[$subject])
        $stage = Join-Path $tempDirectory $subject.Substring(0, 8)
        $archive = Join-Path $stage 'subject.tar'
        $source = Join-Path $stage 'source'
        New-Item -ItemType Directory -Path $stage -Force | Out-Null
        # git archive guarantees the production source is read from the exact subject object.
        git -C $repoRoot archive --format=tar -o $archive $subject apps/server
        if ($LASTEXITCODE -ne 0) { throw "git archive failed for $subject" }
        New-Item -ItemType Directory -Path $source | Out-Null
        & tar -xf $archive -C $source
        if ($LASTEXITCODE -ne 0) { throw "tar extraction failed for $subject" }
        $serverRoot = Join-Path $source 'apps/server'
        $productionDigest = Get-ProductionDigest $serverRoot
        $probeDestination = Join-Path $serverRoot 'src/test/java/com/chtholly/integration/HistoricalTraceRuntimeIT.java'
        Copy-Item -LiteralPath $probePath -Destination $probeDestination -Force
        $safeCases = @($cases | ForEach-Object { [ordered]@{
            sampleId = $_.sampleId; subjectRole = $_.subjectRole
            question = $_.question; pageContext = $_.pageContext
        } })
        $casesPath = Join-Path $stage 'cases.json'
        Write-Json $casesPath $safeCases

        $regressionTests = @($cases | Where-Object subjectRole -eq 'candidate' |
            ForEach-Object { @($_.sample.regressionTests) } | Sort-Object -Unique)
        $methodSelectors = [Collections.Generic.List[string]]::new()
        foreach ($identifier in $regressionTests) {
            $separator = $identifier.IndexOf('.')
            $className = $identifier.Substring(0, $separator)
            $methodName = $identifier.Substring($separator + 1)
            $testFile = Get-ChildItem (Join-Path $serverRoot 'src/test/java') -Recurse -Filter "$className.java" |
                Select-Object -First 1
            if ($null -eq $testFile -or -not (Select-String -LiteralPath $testFile.FullName -SimpleMatch $methodName -Quiet)) {
                throw "Declared regression test is absent at ${subject}: $identifier"
            }
            $methodSelectors.Add("$className#$methodName")
        }
        $subjectPrefix = $subject.Substring(0, 8)
        $unitLog = Join-Path $resultDirectory "raw/$subjectPrefix-unit.log"
        $integrationLog = Join-Path $resultDirectory "raw/$subjectPrefix-integration.log"
        $unitExitCode = $null
        $jvmLocaleArguments = @('-Duser.timezone=UTC', '-Duser.language=zh', '-Duser.country=CN')
        $integrationArguments = @(
            '-q') + $jvmLocaleArguments + @('-Pintegration-test',
            '-Dit.test=HistoricalTraceRuntimeIT', '-Dtrace.runtime.enabled=true',
            "-Dtrace.runtime.cases-path=$casesPath", "-Dtrace.runtime.output-dir=$(Join-Path $resultDirectory 'raw')",
            "-Dtrace.runtime.subject-commit=$subject", 'verify')
        Push-Location $serverRoot
        try {
            if ($methodSelectors.Count -gt 0) {
                $unitArguments = @('-q') + $jvmLocaleArguments + @("-Dtest=$($methodSelectors -join ',')", 'test')
                $unitExitCode = Invoke-MavenLogged $unitArguments $unitLog
                if ($unitExitCode -ne 0) { throw "Historical regression tests failed for $subject" }
                $regressionTests | ForEach-Object { $executedRegressionTests.Add($_) }
            }
            $integrationExitCode = Invoke-MavenLogged $integrationArguments $integrationLog
        } finally { Pop-Location }
        if ($integrationExitCode -ne 0) { throw "Historical Trace runtime failed for $subject" }
        Get-ChildItem -LiteralPath @((Join-Path $serverRoot 'target/surefire-reports'),
                (Join-Path $serverRoot 'target/failsafe-reports')) -Filter '*.xml' -File -ErrorAction SilentlyContinue |
            ForEach-Object { Copy-Item $_.FullName (Join-Path $resultDirectory "raw/$subjectPrefix-$($_.Name)") }
        if ((Get-ProductionDigest $serverRoot) -ne $productionDigest) {
            throw "Historical Trace probe modified production source for $subject"
        }
        $criticalBlobs = [ordered]@{}
        foreach ($path in @(
                'apps/server/src/main/java/com/chtholly/agent/ChthollyAgent.java',
                'apps/server/src/main/java/com/chtholly/agent/search/HybridSearchService.java',
                'apps/server/src/main/java/com/chtholly/agent/evidence/EvidenceSet.java',
                'apps/server/src/main/java/com/chtholly/agent/trace/TracePersistenceService.java',
                'apps/server/src/main/java/com/chtholly/agent/trace/TraceMapper.java',
                'apps/server/src/main/java/com/chtholly/agent/trace/TraceQueryService.java')) {
            $blob = Get-GitObject $subject $path $false
            if ($null -ne $blob) { $criticalBlobs[$path] = $blob }
        }
        $runtimeRecords.Add([ordered]@{
            subjectCommit = $subject; subjectTree = Get-GitObject $subject 'apps/server'
            archiveSha256 = (Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant()
            productionDigest = $productionDigest; criticalProductionBlobs = $criticalBlobs
            regressionTests = $regressionTests; unitExitCode = $unitExitCode; integrationExitCode = $integrationExitCode
        })
    }
} finally {
    Remove-ValidatedTemp
}

$projectionFailures = [Collections.Generic.List[string]]::new()
$comparisons = [Collections.Generic.List[object]]::new()
$diffRows = [Collections.Generic.List[object]]::new()
foreach ($sample in $samples) {
    $baselineSubject = Resolve-Commit $sample.baselineCommit 'BaselineCommit'
    $candidateSubject = Resolve-Commit $sample.candidateCommit 'CandidateCommit'
    $baseline = Get-Content -Raw -LiteralPath (Join-Path $resultDirectory "raw/$($sample.sampleId)-baseline.json") -Encoding UTF8 | ConvertFrom-Json
    $candidate = Get-Content -Raw -LiteralPath (Join-Path $resultDirectory "raw/$($sample.sampleId)-candidate.json") -Encoding UTF8 | ConvertFrom-Json
    @(Assert-Projection $baseline $sample 'baseline' $baselineSubject) | ForEach-Object { $projectionFailures.Add($_) }
    @(Assert-Projection $candidate $sample 'candidate' $candidateSubject) | ForEach-Object { $projectionFailures.Add($_) }
    foreach ($name in @('inputFingerprint', 'questionFingerprint', 'pageContextFingerprint')) {
        if ($baseline.$name -ne $candidate.$name) { $projectionFailures.Add("$($sample.sampleId).invariant.$name") }
    }
    if ($sample.sampleId -eq 'trace-replay-002' -and
            (-not $baseline.unsafeCitationReachedClient -or -not $baseline.unsafeCitationReachedMemory -or
             $candidate.unsafeCitationReachedClient -or $candidate.unsafeCitationReachedMemory)) {
        $projectionFailures.Add('trace-replay-002.unsafe-citation-boundary')
    }
    $differences = [Collections.Generic.List[object]]::new()
    foreach ($name in @('failureType', 'retrievalComponent', 'citationValidator', 'evidenceCount',
            'citationValidationStatus', 'unsafeCitationReachedClient', 'unsafeCitationReachedMemory')) {
        if ($baseline.$name -ne $candidate.$name) {
            $row = [ordered]@{ sampleId = $sample.sampleId; path = $name
                baseline = [string]$baseline.$name; candidate = [string]$candidate.$name }
            $differences.Add($row); $diffRows.Add($row)
        }
    }
    $comparisons.Add([ordered]@{
        sampleId = $sample.sampleId; rootCause = $sample.rootCause; primaryChange = $sample.primaryChange
        baseline = $baseline; candidate = $candidate; differences = $differences
    })
}

$statusText = if ($projectionFailures.Count -eq 0) { 'COMPLETED' } else { 'FAILED_EXPECTATION' }
$environment = [ordered]@{
    runtimeMode = 'HISTORICAL_ARCHIVE_TESTCONTAINERS'; modelAdapter = 'DETERMINISTIC_FAKE'
    timezone = 'UTC'; locale = 'zh-CN'
    realBoundaries = @('historical-production-policy', 'mysql-authority', 'trace-persistence', 'trace-query')
    deterministicBoundaries = @('llm', 'semantic-upstream', 'keyword-upstream', 'entity-upstream', 'observation')
    externalModelCalls = 0; javaVersion = (& java -version 2>&1 | Select-Object -First 1)
    mavenVersion = (& mvn -version | Select-Object -First 1); os = "$([Environment]::OSVersion) $env:PROCESSOR_ARCHITECTURE"
    subjectRuntimes = $runtimeRecords
}
$manifest = [ordered]@{
    schemaVersion = 1; kind = 'historical-agent-trace-replay'; runId = $RunId; status = $statusText
    evidenceLevel = 'REAL_TRACE'; labelStatus = 'CANDIDATE_REQUIRES_OWNER_REVIEW'
    reviewStatus = 'COLLECTED_UNREVIEWED'; formalGold = $false; runtimeEvidence = $true
    executionCommit = $executionCommit; harnessCommit = $harnessCommitFull; datasetCommit = $datasetCommitFull
    harnessBlobs = $harnessBlobs; datasetBlob = $datasetBlob; subjectCommits = @($runtimeRecords.subjectCommit)
    datasetVersion = 'trace-replay-candidates-v2'; sampleCount = 2; traceCount = 4
    externalModelCalls = 0; regressionTests = @($executedRegressionTests | Sort-Object -Unique)
    startedAt = $startedAt; endedAt = [DateTimeOffset]::UtcNow.ToString('o')
}
Write-Json (Join-Path $resultDirectory 'manifest.json') $manifest
Write-Json (Join-Path $resultDirectory 'environment.json') $environment
Write-Json (Join-Path $resultDirectory 'comparisons.json') $comparisons
$diffRows | Export-Csv -LiteralPath (Join-Path $resultDirectory 'diff.csv') -NoTypeInformation -Encoding UTF8

$summary = [Collections.Generic.List[string]]::new()
$summary.Add('# Historical Agent Trace before/after loops'); $summary.Add('')
$summary.Add('- Evidence level: `REAL_TRACE`'); $summary.Add('- Model: deterministic local adapter; external calls: 0')
$summary.Add('- Path: subject archive -> production policy -> ChthollyAgent -> MySQL Trace -> TraceQueryService'); $summary.Add('')
foreach ($comparison in $comparisons) {
    $summary.Add("## $($comparison.sampleId)"); $summary.Add('')
    $summary.Add("Root cause: $($comparison.rootCause)"); $summary.Add('')
    $summary.Add("Single primary change: $($comparison.primaryChange)"); $summary.Add('')
    foreach ($row in $comparison.differences) {
        $summary.Add(('- `{0}`: `{1}` -> `{2}`' -f $row.path, $row.baseline, $row.candidate))
    }
    $summary.Add('')
}
$summary | Set-Content -LiteralPath (Join-Path $resultDirectory 'summary.md') -Encoding UTF8
if ($projectionFailures.Count -eq 0) { 'NONE' | Set-Content (Join-Path $resultDirectory 'failures.md') -Encoding UTF8 }
else { $projectionFailures | Set-Content (Join-Path $resultDirectory 'failures.md') -Encoding UTF8 }

$checksumPath = Join-Path $resultDirectory 'checksums.sha256'
$checksumLines = Get-ChildItem -LiteralPath $resultDirectory -Recurse -File |
    Where-Object FullName -ne $checksumPath | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($resultDirectory.Length).TrimStart('\', '/').Replace('\', '/')
        "$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())  $relative"
    }
$checksumLines | Set-Content -LiteralPath $checksumPath -Encoding UTF8

$finalStatus = & git -C $repoRoot status --porcelain
if (-not [string]::IsNullOrWhiteSpace(($finalStatus | Out-String))) { throw 'Trace replay changed the worktree' }
& git -C $repoRoot check-ignore --quiet -- ".benchmark-results/$RunId/manifest.json"
if ($LASTEXITCODE -ne 0) { throw 'Trace replay results must remain ignored' }
if ($projectionFailures.Count -gt 0) { throw "Trace replay expectations failed: $($projectionFailures.Count)" }
[ordered]@{ status = $statusText; evidenceLevel = 'REAL_TRACE'; runId = $RunId
    resultPath = $resultDirectory; traceCount = 4; externalModelCalls = 0 } | ConvertTo-Json

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$runnerPath = Join-Path $repoRoot 'scripts/benchmark/trace-replay.ps1'
$datasetPath = Join-Path $repoRoot 'benchmarks/datasets/agent-evaluation/trace-replays.jsonl'
$resultsRoot = Join-Path $repoRoot '.benchmark-results'
$prefix = "trace-replay-contract-$PID"
$failures = [System.Collections.Generic.List[string]]::new()
$cleanupPaths = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { $failures.Add($Message) }
}

function Get-Sha256 {
    param([AllowEmptyString()][string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
}

function Get-TraceSamples {
    return @(Get-Content -LiteralPath $datasetPath -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
}

function Register-CleanupPath {
    param([string]$Path)
    if (-not $cleanupPaths.Contains($Path)) { $cleanupPaths.Add($Path) }
}

function New-TraceResponse {
    param([object]$Sample, [ValidateSet('baseline', 'candidate')][string]$Role, [string]$CorrelationId)

    $question = [string]$Sample.fixture.question
    $pageContext = [string]$Sample.fixture.pageContext
    $inputFingerprint = Get-Sha256 -Value ($question.Trim() + "`n--page--`n" + $pageContext.Trim())
    $isBaseline = $Role -eq 'baseline'
    $expected = if ($isBaseline) { $Sample.expected.baseline } else { $Sample.expected.candidate }
    $component = [string]$expected.retrievalComponent
    $evidence = @()
    if ([int]$expected.evidenceCount -gt 0) {
        $evidence = @([ordered]@{
            citationId = 'E1'; documentId = 'post:920000000000000001'; source = 'entity'
            sourceVersion = 'content-v3'; sourceHash = ('a' * 64); title = 'must-not-export'
        })
    }

    return [ordered]@{
        correlationId = $CorrelationId
        userId = 42
        sessionId = 'private-session'
        status = [string]$expected.traceStatus
        durationMs = if ($isBaseline) { 140 } else { 120 }
        stepsCount = 1
        errorMessage = 'must-not-export-error'
        secret = 'must-not-export-secret'
        tracePayload = [ordered]@{
            correlationId = $CorrelationId
            userId = 42
            sessionId = 'private-session'
            errorMessage = 'must-not-export-error'
            prompt = 'must-not-export-prompt'
            answer = 'must-not-export-answer'
            components = [ordered]@{
                prompt = 'agent-prompt-v1'; skillSelector = 'skill-selector-v1'; model = 'deterministic-fake'
                retrieval = $component
                citationValidator = [string]$expected.citationValidator
                tools = 'agent-tool-v1'; traceSchema = 'agent-trace-v1'; unknown = 'must-not-export-component'
            }
            skill = [ordered]@{
                selectionStatus = if ($Sample.sampleId -eq 'trace-replay-002') { 'SELECTED' } else { 'NOT_EVALUATED' }
                id = if ($Sample.sampleId -eq 'trace-replay-002') { 'page-explain' } else { '' }
                version = if ($Sample.sampleId -eq 'trace-replay-002') { 'v1' } else { '' }
                validationStatus = if ($Sample.sampleId -eq 'trace-replay-002' -and -not $isBaseline) { 'INSUFFICIENT_EVIDENCE' } else { 'NOT_RUN' }
                instruction = 'must-not-export-instruction'
            }
            retrieval = [ordered]@{
                strategy = $component
                statuses = [ordered]@{ semantic = 'SUCCESS_EMPTY'; keyword = 'SUCCESS_EMPTY'; entity = [string]$expected.entityStatus; private = 'must-not-export-route' }
                evidenceCount = $evidence.Count
                evidenceSnapshotHash = if ($evidence.Count -gt 0) { 'b' * 64 } else { 'c' * 64 }
                evidence = $evidence
                degraded = $false
                citationValidationStatus = if ($null -ne $expected.citationValidationStatus) { $expected.citationValidationStatus } else { 'NOT_RUN' }
                excerpt = 'must-not-export-excerpt'
            }
            toolVersions = [ordered]@{ fulltext_search = 'agent-tool-v1' }
            failureType = [string]$expected.failureType
            runMode = $Role
            input = [ordered]@{
                fingerprint = $inputFingerprint
                questionFingerprint = Get-Sha256 -Value $question.Trim()
                pageContextFingerprint = Get-Sha256 -Value $pageContext.Trim()
                question = $question
            }
            totalDurationMs = if ($isBaseline) { 140 } else { 120 }
            llmDurationMs = 80
            toolDurationMs = 20
            inputTokens = 30
            outputTokens = 12
            llmCallCount = 1
            finalAnswerLength = 24
            steps = @([ordered]@{ stepIndex = 0; action = 'fulltext_search'; llmMs = 80; toolMs = 20; private = 'must-not-export-step' })
            llmCalls = @([ordered]@{ sequence = 1; step_index = 0; duration_ms = 80; input_chars = 120; output_chars = 24; first_token_ms = 15; prompt = 'must-not-export-llm' })
            toolCalls = @([ordered]@{
                sequence = 2; step_index = 0; tool = 'fulltext_search'; duration_ms = 20; success = $true
                input_summary = ('sha256=' + ('d' * 64) + ';chars=16')
                observation_summary = ('sha256=' + ('e' * 64) + ';chars=20')
                observation = 'must-not-export-observation'
            })
            unknown = 'must-not-export-unknown'
        }
        steps = @()
        unassignedEvents = @()
    }
}

function Write-Json {
    param([string]$Path, [object]$Value)
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Export-Role {
    param([object]$Sample, [ValidateSet('baseline', 'candidate')][string]$Role)

    $runId = "$prefix-$($Sample.sampleId)-$Role"
    $sourceDirectory = Join-Path $resultsRoot "$prefix-input"
    New-Item -ItemType Directory -Force -Path $sourceDirectory | Out-Null
    Register-CleanupPath -Path $sourceDirectory
    $sourcePath = Join-Path $sourceDirectory "$($Sample.sampleId)-$Role.json"
    $correlationId = "$($Sample.sampleId)-$Role-private-correlation"
    Write-Json -Path $sourcePath -Value (New-TraceResponse -Sample $Sample -Role $Role -CorrelationId $correlationId)
    $subjectCommit = if ($Role -eq 'baseline') { $Sample.baselineCommit } else { $Sample.candidateCommit }
    $head = (git -C $repoRoot rev-parse HEAD).Trim()

    & $runnerPath -Action Export -RunId $runId -SampleId $Sample.sampleId -SubjectRole $Role `
        -SubjectCommit $subjectCommit -HarnessCommit $head -DatasetCommit $head -TraceResponsePath $sourcePath `
        -AllowUncommittedHarness | Out-Null
    Register-CleanupPath -Path (Join-Path $resultsRoot $runId)
    return [ordered]@{
        runId = $runId
        directory = Join-Path $resultsRoot $runId
        sourcePath = $sourcePath
        correlationId = $correlationId
    }
}

function Compare-Pair {
    param([object]$Sample, [object]$Baseline, [object]$Candidate)
    $runId = "$prefix-$($Sample.sampleId)-compare"
    & $runnerPath -Action Compare -RunId $runId `
        -BaselineRunDirectory $Baseline.directory -CandidateRunDirectory $Candidate.directory | Out-Null
    $directory = Join-Path $resultsRoot $runId
    Register-CleanupPath -Path $directory
    return $directory
}

if (-not (Test-Path -LiteralPath $resultsRoot -PathType Container)) {
    New-Item -ItemType Directory -Path $resultsRoot | Out-Null
}

try {
    Assert-True -Condition (Test-Path -LiteralPath $runnerPath -PathType Leaf) -Message 'Missing Trace replay runner'
    if (-not (Test-Path -LiteralPath $runnerPath -PathType Leaf)) { throw 'Trace replay runner is missing' }
    $runnerSource = Get-Content -Raw -LiteralPath $runnerPath -Encoding UTF8
    Assert-True -Condition ($runnerSource.Contains('[Uri]::IsLoopback')) -Message 'Trace API must only send its admin token to a loopback host'
    Assert-True -Condition ($runnerSource.Contains('-MaximumRedirection 0')) -Message 'Trace API must reject redirects'
    Assert-True -Condition (-not $runnerSource.Contains('$AdminTokenEnvironment')) -Message 'Trace API token environment name must not be caller-controlled'
    Assert-True -Condition ($runnerSource.Contains("return 'API_UNVERIFIED'")) -Message 'API capture without bound provenance must remain unverified'
    Assert-True -Condition (-not $runnerSource.Contains("return 'REAL_TRACE'")) -Message 'Replay harness must not self-attest a REAL_TRACE without an external runtime manifest'
    Assert-True -Condition ($runnerSource.Contains("`$evidenceLevel -eq 'OFFLINE_UNVERIFIED'")) `
        -Message 'Offline replay summary must have its own evidence notice'
    Assert-True -Condition ($runnerSource.Contains("`$evidenceLevel -eq 'API_UNVERIFIED'")) `
        -Message 'API replay summary must not be described as an offline fixture'

    $samples = Get-TraceSamples
    foreach ($sample in $samples) {
        $baseline = Export-Role -Sample $sample -Role baseline
        $candidate = Export-Role -Sample $sample -Role candidate
        $baselineReplayPath = Join-Path $baseline.directory 'replay.json'
        $baselineManifestPath = Join-Path $baseline.directory 'manifest.json'
        $baselineText = Get-Content -Raw -LiteralPath $baselineReplayPath -Encoding UTF8
        $manifest = Get-Content -Raw -LiteralPath $baselineManifestPath -Encoding UTF8 | ConvertFrom-Json

        foreach ($forbidden in @($baseline.correlationId, 'private-session', 'must-not-export', [string]$sample.fixture.question)) {
            Assert-True -Condition (-not $baselineText.Contains($forbidden)) -Message "Export leaked forbidden content for $($sample.sampleId): $forbidden"
        }
        foreach ($omitted in @('"provenance"', '"evidence"', '"steps"', '"llmCalls"', '"toolCalls"')) {
            Assert-True -Condition (-not $baselineText.Contains($omitted)) -Message "Fixed replay retained unnecessary detail for $($sample.sampleId): $omitted"
        }
        Assert-True -Condition ($manifest.sourceTraceFingerprint -match '^[a-f0-9]{64}$') -Message 'Correlation ID must be fingerprinted'
        Assert-True -Condition ($manifest.PSObject.Properties.Name -notcontains 'executionCommit') `
            -Message 'Export manifest must not label the exporter HEAD as the runtime execution commit'
        Assert-True -Condition ($manifest.exporterCommit -eq (git -C $repoRoot rev-parse HEAD).Trim()) `
            -Message 'Export manifest must identify the exporter commit explicitly'
        Assert-True -Condition ($manifest.evidenceLevel -eq 'OFFLINE_UNVERIFIED') -Message 'Offline input must not be promoted to real Trace evidence'
        Assert-True -Condition ($manifest.identityStatus -in @('VERIFIED_COMMIT_BLOBS', 'WORKTREE_UNCOMMITTED')) -Message 'Export must disclose its Git blob identity status'
        Assert-True -Condition ($manifest.replayFileSha256 -match '^[a-f0-9]{64}$') -Message 'Export manifest must bind the entire replay file'

        $compareDirectory = Compare-Pair -Sample $sample -Baseline $baseline -Candidate $candidate
        foreach ($name in @('manifest.json', 'replay.json', 'diff.csv', 'summary.md', 'failures.md')) {
            Assert-True -Condition (Test-Path -LiteralPath (Join-Path $compareDirectory $name) -PathType Leaf) -Message "Compare output is missing $name for $($sample.sampleId)"
        }
        $summary = Get-Content -Raw -LiteralPath (Join-Path $compareDirectory 'summary.md') -Encoding UTF8
        $comparisonManifest = Get-Content -Raw -LiteralPath (Join-Path $compareDirectory 'manifest.json') -Encoding UTF8 | ConvertFrom-Json
        $failureReport = Get-Content -Raw -LiteralPath (Join-Path $compareDirectory 'failures.md') -Encoding UTF8
        Assert-True -Condition ($comparisonManifest.status -eq 'COMPARED') -Message "Expected Trace pair must compare successfully: $($sample.sampleId)"
        Assert-True -Condition ($failureReport.Contains('NONE')) -Message "Successful Trace pair must report no expectation failures: $($sample.sampleId)"
        Assert-True -Condition ($summary.Contains([string]$sample.rootCause)) -Message "Summary must include the real root cause for $($sample.sampleId)"
        Assert-True -Condition ($summary.Contains([string]$sample.primaryChange)) -Message "Summary must include the single primary change for $($sample.sampleId)"
        Assert-True -Condition (-not $summary.Contains('must-not-export')) -Message "Summary leaked source fields for $($sample.sampleId)"
        if ($sample.sampleId -eq 'trace-replay-002') {
            $diffPaths = @(Import-Csv -LiteralPath (Join-Path $compareDirectory 'diff.csv') | ForEach-Object path)
            Assert-True -Condition ($diffPaths -contains 'components.citationValidator') `
                -Message 'Citation validator version change must appear in the Trace comparison diff'
        }

        $reversedRejected = $false
        try {
            & $runnerPath -Action Compare -RunId "$prefix-$($sample.sampleId)-reversed" `
                -BaselineRunDirectory $candidate.directory -CandidateRunDirectory $baseline.directory | Out-Null
        }
        catch { $reversedRejected = $true }
        Assert-True -Condition $reversedRejected -Message "Compare must reject reversed roles for $($sample.sampleId)"
    }

    $firstSample = $samples | Where-Object sampleId -eq 'trace-replay-001' | Select-Object -First 1
    $badSource = Join-Path (Join-Path $resultsRoot "$prefix-input") 'bad-input.json'
    $badResponse = New-TraceResponse -Sample $firstSample -Role baseline -CorrelationId 'bad-input-correlation'
    $badResponse.tracePayload.input.fingerprint = '0' * 64
    Write-Json -Path $badSource -Value $badResponse
    $inputRejected = $false
    try {
        $head = (git -C $repoRoot rev-parse HEAD).Trim()
        & $runnerPath -Action Export -RunId "$prefix-bad-input" -SampleId $firstSample.sampleId -SubjectRole baseline `
            -SubjectCommit $firstSample.baselineCommit -HarnessCommit $head -DatasetCommit $head `
            -TraceResponsePath $badSource -AllowUncommittedHarness | Out-Null
    }
    catch { $inputRejected = $true }
    Assert-True -Condition $inputRejected -Message 'Export must reject a mismatched input fingerprint'

    $badModeSource = Join-Path (Join-Path $resultsRoot "$prefix-input") 'bad-run-mode.json'
    $badModeResponse = New-TraceResponse -Sample $firstSample -Role baseline -CorrelationId 'bad-mode-correlation'
    $badModeResponse.tracePayload.runMode = 'candidate'
    Write-Json -Path $badModeSource -Value $badModeResponse
    $runModeRejected = $false
    try {
        $head = (git -C $repoRoot rev-parse HEAD).Trim()
        & $runnerPath -Action Export -RunId "$prefix-bad-run-mode" -SampleId $firstSample.sampleId -SubjectRole baseline `
            -SubjectCommit $firstSample.baselineCommit -HarnessCommit $head -DatasetCommit $head `
            -TraceResponsePath $badModeSource -AllowUncommittedHarness | Out-Null
    }
    catch { $runModeRejected = $true }
    Assert-True -Condition $runModeRejected -Message 'Export must bind runMode to the subject role'

    $badBooleanSource = Join-Path (Join-Path $resultsRoot "$prefix-input") 'bad-degraded-boolean.json'
    $badBooleanResponse = New-TraceResponse -Sample $firstSample -Role baseline -CorrelationId 'bad-degraded-boolean-correlation'
    $badBooleanResponse.tracePayload.retrieval.degraded = 'false'
    Write-Json -Path $badBooleanSource -Value $badBooleanResponse
    $booleanRejected = $false
    try {
        $head = (git -C $repoRoot rev-parse HEAD).Trim()
        & $runnerPath -Action Export -RunId "$prefix-bad-tool-boolean" -SampleId $firstSample.sampleId -SubjectRole baseline `
            -SubjectCommit $firstSample.baselineCommit -HarnessCommit $head -DatasetCommit $head `
            -TraceResponsePath $badBooleanSource -AllowUncommittedHarness | Out-Null
    }
    catch { $booleanRejected = $true }
    Assert-True -Condition $booleanRejected -Message 'Export must reject a string masquerading as a JSON boolean'

    $parent = (git -C $repoRoot rev-parse HEAD^).Trim()
    $blobBindingRejected = $false
    try {
        & $runnerPath -Action Export -RunId "$prefix-bad-blob-binding" -SampleId $firstSample.sampleId -SubjectRole baseline `
            -SubjectCommit $firstSample.baselineCommit -HarnessCommit $parent -DatasetCommit $parent `
            -TraceResponsePath (Join-Path (Join-Path $resultsRoot "$prefix-input") 'trace-replay-001-baseline.json') | Out-Null
    }
    catch { $blobBindingRejected = $true }
    Assert-True -Condition $blobBindingRejected -Message 'Export must reject harness and dataset files not owned by their declared commits'

    $baselineManifestPath = Join-Path (Join-Path $resultsRoot "$prefix-trace-replay-001-baseline") 'manifest.json'
    $candidateDirectory = Join-Path $resultsRoot "$prefix-trace-replay-001-candidate"
    $candidateManifestPath = Join-Path $candidateDirectory 'manifest.json'
    $candidateReplayPath = Join-Path $candidateDirectory 'replay.json'
    $candidateManifest = Get-Content -Raw -LiteralPath $candidateManifestPath -Encoding UTF8 | ConvertFrom-Json
    $candidateReplay = Get-Content -Raw -LiteralPath $candidateReplayPath -Encoding UTF8 | ConvertFrom-Json
    $originalEnvironmentFingerprint = $candidateManifest.environmentFingerprint
    $originalInvariantFingerprint = $candidateManifest.invariantFingerprint
    $mismatchedEnvironmentFingerprint = 'f' * 64
    $candidateReplay.environmentFingerprint = $mismatchedEnvironmentFingerprint
    $candidateReplay.invariantFingerprint = Get-Sha256 -Value (
        $candidateReplay.inputFingerprint + "`n" + $candidateReplay.dataFingerprint + "`n" + $mismatchedEnvironmentFingerprint
    )
    Write-Json -Path $candidateReplayPath -Value $candidateReplay
    $candidateManifest.environmentFingerprint = $candidateReplay.environmentFingerprint
    $candidateManifest.invariantFingerprint = $candidateReplay.invariantFingerprint
    $candidateManifest.replayFileSha256 = (Get-FileHash -LiteralPath $candidateReplayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $environmentRejected = $false
    $environmentError = $null
    try {
        & $runnerPath -Action Compare -RunId "$prefix-environment-mismatch" `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch {
        $environmentRejected = $true
        $environmentError = $_.Exception.Message
    }
    Assert-True -Condition $environmentRejected -Message 'Compare must reject an environment fingerprint mismatch'
    Assert-True -Condition ($environmentError -like '*invariant mismatch: environmentFingerprint*') `
        -Message 'Environment mismatch test must reach the cross-run invariant comparison'
    $candidateReplay.environmentFingerprint = $originalEnvironmentFingerprint
    $candidateReplay.invariantFingerprint = $originalInvariantFingerprint
    Write-Json -Path $candidateReplayPath -Value $candidateReplay
    $candidateManifest.environmentFingerprint = $originalEnvironmentFingerprint
    $candidateManifest.invariantFingerprint = $originalInvariantFingerprint
    $candidateManifest.replayFileSha256 = (Get-FileHash -LiteralPath $candidateReplayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Json -Path $candidateManifestPath -Value $candidateManifest

    $baselineDirectory = Split-Path $baselineManifestPath
    $baselineReplayPath = Join-Path $baselineDirectory 'replay.json'
    $baselineManifest = Get-Content -Raw -LiteralPath $baselineManifestPath -Encoding UTF8 | ConvertFrom-Json
    $candidateManifest = Get-Content -Raw -LiteralPath $candidateManifestPath -Encoding UTF8 | ConvertFrom-Json
    $baselineReplay = Get-Content -Raw -LiteralPath $baselineReplayPath -Encoding UTF8 | ConvertFrom-Json
    $candidateReplay = Get-Content -Raw -LiteralPath $candidateReplayPath -Encoding UTF8 | ConvertFrom-Json
    $identityBundles = @(
        [ordered]@{ manifest = $baselineManifest; manifestPath = $baselineManifestPath; replay = $baselineReplay; replayPath = $baselineReplayPath },
        [ordered]@{ manifest = $candidateManifest; manifestPath = $candidateManifestPath; replay = $candidateReplay; replayPath = $candidateReplayPath }
    )
    foreach ($bundle in $identityBundles) {
        $bundle['original'] = [ordered]@{
            inputFingerprint = $bundle.replay.inputFingerprint
            dataFingerprint = $bundle.replay.dataFingerprint
            environmentFingerprint = $bundle.replay.environmentFingerprint
            invariantFingerprint = $bundle.replay.invariantFingerprint
        }
        $bundle.replay.inputFingerprint = '1' * 64
        $bundle.replay.dataFingerprint = '2' * 64
        $bundle.replay.environmentFingerprint = '3' * 64
        $bundle.replay.invariantFingerprint = '4' * 64
        $bundle.manifest.inputFingerprint = $bundle.replay.inputFingerprint
        $bundle.manifest.dataFingerprint = $bundle.replay.dataFingerprint
        $bundle.manifest.environmentFingerprint = $bundle.replay.environmentFingerprint
        $bundle.manifest.invariantFingerprint = $bundle.replay.invariantFingerprint
        Write-Json -Path $bundle.replayPath -Value $bundle.replay
        $bundle.manifest.replayFileSha256 = (Get-FileHash -LiteralPath $bundle.replayPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Json -Path $bundle.manifestPath -Value $bundle.manifest
    }
    $coordinatedIdentityRunId = "$prefix-coordinated-identity-tamper"
    $coordinatedIdentityRejected = $false
    try {
        & $runnerPath -Action Compare -RunId $coordinatedIdentityRunId `
            -BaselineRunDirectory $baselineDirectory -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch { $coordinatedIdentityRejected = $true }
    Register-CleanupPath -Path (Join-Path $resultsRoot $coordinatedIdentityRunId)
    Assert-True -Condition $coordinatedIdentityRejected `
        -Message 'Compare must recompute canonical input, data, environment, and invariant fingerprints'
    foreach ($bundle in $identityBundles) {
        $bundle.replay.inputFingerprint = $bundle.original.inputFingerprint
        $bundle.replay.dataFingerprint = $bundle.original.dataFingerprint
        $bundle.replay.environmentFingerprint = $bundle.original.environmentFingerprint
        $bundle.replay.invariantFingerprint = $bundle.original.invariantFingerprint
        $bundle.manifest.inputFingerprint = $bundle.original.inputFingerprint
        $bundle.manifest.dataFingerprint = $bundle.original.dataFingerprint
        $bundle.manifest.environmentFingerprint = $bundle.original.environmentFingerprint
        $bundle.manifest.invariantFingerprint = $bundle.original.invariantFingerprint
        Write-Json -Path $bundle.replayPath -Value $bundle.replay
        $bundle.manifest.replayFileSha256 = (Get-FileHash -LiteralPath $bundle.replayPath -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Json -Path $bundle.manifestPath -Value $bundle.manifest
    }

    $originalNestedInputFingerprint = $candidateReplay.input.fingerprint
    $candidateReplay.input.fingerprint = 'f' * 64
    Write-Json -Path $candidateReplayPath -Value $candidateReplay
    $candidateManifest.replayFileSha256 = (Get-FileHash -LiteralPath $candidateReplayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $nestedInputRunId = "$prefix-nested-input-tamper"
    $nestedInputRejected = $false
    try {
        & $runnerPath -Action Compare -RunId $nestedInputRunId `
            -BaselineRunDirectory $baselineDirectory -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch { $nestedInputRejected = $true }
    Register-CleanupPath -Path (Join-Path $resultsRoot $nestedInputRunId)
    Assert-True -Condition $nestedInputRejected `
        -Message 'Compare must bind nested replay input fingerprints to the reviewed fixture'
    $candidateReplay.input.fingerprint = $originalNestedInputFingerprint
    Write-Json -Path $candidateReplayPath -Value $candidateReplay
    $candidateManifest.replayFileSha256 = (Get-FileHash -LiteralPath $candidateReplayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Json -Path $candidateManifestPath -Value $candidateManifest

    $candidateManifest | Add-Member -NotePropertyName executionCommit -NotePropertyValue ('f' * 40)
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $unknownManifestRejected = $false
    try {
        & $runnerPath -Action Compare -RunId "$prefix-unknown-manifest-field" `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch { $unknownManifestRejected = $true }
    Assert-True -Condition $unknownManifestRejected -Message 'Compare must reject unknown manifest provenance fields'
    $candidateManifest.PSObject.Properties.Remove('executionCommit')
    Write-Json -Path $candidateManifestPath -Value $candidateManifest

    $baselineManifest = Get-Content -Raw -LiteralPath $baselineManifestPath -Encoding UTF8 | ConvertFrom-Json
    $candidateManifest = Get-Content -Raw -LiteralPath $candidateManifestPath -Encoding UTF8 | ConvertFrom-Json
    $originalGitIdentities = @(
        [ordered]@{ manifest = $baselineManifest; exporterCommit = $baselineManifest.exporterCommit; harnessCommit = $baselineManifest.harnessCommit; datasetCommit = $baselineManifest.datasetCommit },
        [ordered]@{ manifest = $candidateManifest; exporterCommit = $candidateManifest.exporterCommit; harnessCommit = $candidateManifest.harnessCommit; datasetCommit = $candidateManifest.datasetCommit }
    )
    foreach ($identity in $originalGitIdentities) {
        $identity.manifest.exporterCommit = 'not-a-commit'
        $identity.manifest.harnessCommit = 'not-a-commit'
        $identity.manifest.datasetCommit = 'not-a-commit'
    }
    Write-Json -Path $baselineManifestPath -Value $baselineManifest
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $invalidCommitRunId = "$prefix-invalid-manifest-commit"
    $invalidCommitRejected = $false
    $invalidCommitError = $null
    try {
        & $runnerPath -Action Compare -RunId $invalidCommitRunId `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch {
        $invalidCommitRejected = $true
        $invalidCommitError = $_.Exception.Message
    }
    Register-CleanupPath -Path (Join-Path $resultsRoot $invalidCommitRunId)
    Assert-True -Condition $invalidCommitRejected -Message 'Compare must reject nonexistent manifest commits'
    Assert-True -Condition ($invalidCommitError -like '*does not resolve to a commit*') `
        -Message "Invalid manifest commit test must reach Git identity validation: $invalidCommitError"
    foreach ($identity in $originalGitIdentities) {
        $identity.manifest.exporterCommit = $identity.exporterCommit
        $identity.manifest.harnessCommit = $identity.harnessCommit
        $identity.manifest.datasetCommit = $identity.datasetCommit
    }
    Write-Json -Path $baselineManifestPath -Value $baselineManifest
    Write-Json -Path $candidateManifestPath -Value $candidateManifest

    $originalBaselineHarnessBlob = $baselineManifest.harnessBlob
    $originalBaselineDatasetBlob = $baselineManifest.datasetBlob
    $originalCandidateHarnessBlob = $candidateManifest.harnessBlob
    $originalCandidateDatasetBlob = $candidateManifest.datasetBlob
    foreach ($manifest in @($baselineManifest, $candidateManifest)) {
        $manifest.harnessBlob = '0' * 40
        $manifest.datasetBlob = '0' * 40
    }
    Write-Json -Path $baselineManifestPath -Value $baselineManifest
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $invalidBlobRunId = "$prefix-invalid-manifest-blob"
    $invalidBlobRejected = $false
    try {
        & $runnerPath -Action Compare -RunId $invalidBlobRunId `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch { $invalidBlobRejected = $true }
    Register-CleanupPath -Path (Join-Path $resultsRoot $invalidBlobRunId)
    Assert-True -Condition $invalidBlobRejected -Message 'Compare must reject manifest blobs that do not identify the current replay files'
    $baselineManifest.harnessBlob = $originalBaselineHarnessBlob
    $baselineManifest.datasetBlob = $originalBaselineDatasetBlob
    $candidateManifest.harnessBlob = $originalCandidateHarnessBlob
    $candidateManifest.datasetBlob = $originalCandidateDatasetBlob
    Write-Json -Path $baselineManifestPath -Value $baselineManifest
    Write-Json -Path $candidateManifestPath -Value $candidateManifest

    $originalBaselineIdentityStatus = $baselineManifest.identityStatus
    $originalCandidateIdentityStatus = $candidateManifest.identityStatus
    foreach ($manifest in @($baselineManifest, $candidateManifest)) {
        $manifest.sourceMode = 'api'
        $manifest.evidenceLevel = 'REAL_TRACE'
        $manifest.identityStatus = 'VERIFIED_COMMIT_BLOBS'
    }
    Write-Json -Path $baselineManifestPath -Value $baselineManifest
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $promotionRunId = "$prefix-false-real-promotion"
    $promotionRejected = $false
    $promotionError = $null
    try {
        & $runnerPath -Action Compare -RunId $promotionRunId `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch {
        $promotionRejected = $true
        $promotionError = $_.Exception.Message
    }
    Register-CleanupPath -Path (Join-Path $resultsRoot $promotionRunId)
    Assert-True -Condition $promotionRejected -Message 'Compare must recompute evidence eligibility instead of trusting a promoted manifest'
    Assert-True -Condition ($promotionError -like '*evidence level is not eligible*') `
        -Message 'False REAL promotion must reach the evidence eligibility recomputation check'
    foreach ($manifest in @($baselineManifest, $candidateManifest)) {
        $manifest.sourceMode = 'ignored-file'
        $manifest.evidenceLevel = 'OFFLINE_UNVERIFIED'
    }
    $baselineManifest.identityStatus = $originalBaselineIdentityStatus
    $candidateManifest.identityStatus = $originalCandidateIdentityStatus
    Write-Json -Path $baselineManifestPath -Value $baselineManifest
    Write-Json -Path $candidateManifestPath -Value $candidateManifest

    $candidateReplayPath = Join-Path $candidateDirectory 'replay.json'
    $candidateReplay = Get-Content -Raw -LiteralPath $candidateReplayPath -Encoding UTF8 | ConvertFrom-Json
    $originalInputFingerprint = $candidateReplay.inputFingerprint
    $candidateReplay.inputFingerprint = 'f' * 64
    Write-Json -Path $candidateReplayPath -Value $candidateReplay
    $replayBindingRejected = $false
    try {
        & $runnerPath -Action Compare -RunId "$prefix-replay-binding-mismatch" `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch { $replayBindingRejected = $true }
    Assert-True -Condition $replayBindingRejected -Message 'Compare must bind replay content to its manifest fingerprints'
    $candidateReplay.inputFingerprint = $originalInputFingerprint
    Write-Json -Path $candidateReplayPath -Value $candidateReplay

    $candidateManifest = Get-Content -Raw -LiteralPath $candidateManifestPath -Encoding UTF8 | ConvertFrom-Json
    $candidateReplay = Get-Content -Raw -LiteralPath $candidateReplayPath -Encoding UTF8 | ConvertFrom-Json
    $candidateReplay | Add-Member -NotePropertyName secret -NotePropertyValue 'must-not-export-replay-tamper'
    Write-Json -Path $candidateReplayPath -Value $candidateReplay
    $candidateManifest.replayFileSha256 = (Get-FileHash -LiteralPath $candidateReplayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $tamperRejected = $false
    try {
        & $runnerPath -Action Compare -RunId "$prefix-replay-safe-projection" `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch { $tamperRejected = $true }
    Assert-True -Condition $tamperRejected -Message 'Compare must reject an unknown replay field even when its manifest hash is rewritten'

    $candidateReplay.PSObject.Properties.Remove('secret')
    $candidateReplay.runMode = 'replay'
    Write-Json -Path $candidateReplayPath -Value $candidateReplay
    $candidateManifest.replayFileSha256 = (Get-FileHash -LiteralPath $candidateReplayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $runModeTamperRunId = "$prefix-replay-role-mismatch"
    $runModeTamperRejected = $false
    try {
        & $runnerPath -Action Compare -RunId $runModeTamperRunId `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch { $runModeTamperRejected = $true }
    Register-CleanupPath -Path (Join-Path $resultsRoot $runModeTamperRunId)
    Assert-True -Condition $runModeTamperRejected -Message 'Compare must bind replay runMode to its baseline or candidate role'

    $candidateReplay.runMode = 'candidate'
    $candidateReplay.failureType = 'INTERNAL_ERROR'
    Write-Json -Path $candidateReplayPath -Value $candidateReplay
    $candidateManifest.replayFileSha256 = (Get-FileHash -LiteralPath $candidateReplayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Json -Path $candidateManifestPath -Value $candidateManifest
    $expectationRunId = "$prefix-expectation-mismatch"
    $expectationRejected = $false
    try {
        & $runnerPath -Action Compare -RunId $expectationRunId `
            -BaselineRunDirectory (Split-Path $baselineManifestPath) -CandidateRunDirectory $candidateDirectory | Out-Null
    }
    catch { $expectationRejected = $true }
    $expectationDirectory = Join-Path $resultsRoot $expectationRunId
    Register-CleanupPath -Path $expectationDirectory
    $expectationManifest = Get-Content -Raw -LiteralPath (Join-Path $expectationDirectory 'manifest.json') -Encoding UTF8 | ConvertFrom-Json
    $expectationFailures = Get-Content -Raw -LiteralPath (Join-Path $expectationDirectory 'failures.md') -Encoding UTF8
    Assert-True -Condition $expectationRejected -Message 'Compare must return a failing exit for an unexpected Trace outcome'
    Assert-True -Condition ($expectationManifest.status -eq 'FAILED_EXPECTATION') -Message 'Unexpected Trace outcome must not be marked COMPARED'
    Assert-True -Condition ($expectationFailures.Contains('failureType')) -Message 'Expectation report must name the mismatched observable field'
}
finally {
    if (Test-Path -LiteralPath $resultsRoot -PathType Container) {
        $allowedRoot = (Resolve-Path -LiteralPath $resultsRoot).Path + [IO.Path]::DirectorySeparatorChar
        foreach ($path in $cleanupPaths) {
            if (-not (Test-Path -LiteralPath $path -PathType Container)) { continue }
            $directory = Get-Item -LiteralPath $path -Force
            Assert-True -Condition (($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) `
                -Message "Trace replay cleanup refuses a reparse point: $path"
            if (($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { continue }
            $resolved = (Resolve-Path -LiteralPath $directory.FullName).Path
            if (-not $resolved.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Unsafe Trace replay test cleanup target: $resolved"
            }
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Output "FAIL: $_" }
    exit 1
}

Write-Output 'Trace replay harness verified.'

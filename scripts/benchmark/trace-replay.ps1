[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Export', 'Compare')]
    [string]$Action,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,95}$')]
    [string]$RunId,

    [string]$SampleId,
    [ValidateSet('baseline', 'candidate')]
    [string]$SubjectRole,
    [string]$SubjectCommit,
    [string]$HarnessCommit,
    [string]$DatasetCommit,
    [Alias('CorrelationId')]
    [string]$TraceId,
    [string]$TraceResponsePath,
    [string]$BaseUrl = 'http://127.0.0.1:8888',
    [switch]$AllowUncommittedHarness,
    [string]$BaselineRunDirectory,
    [string]$CandidateRunDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$resultsRoot = Join-Path $repoRoot '.benchmark-results'
$datasetPath = Join-Path $repoRoot 'benchmarks/datasets/agent-evaluation/trace-replays.jsonl'
$runnerPath = $MyInvocation.MyCommand.Path
$adminTokenEnvironment = 'CHOLLY_TRACE_ADMIN_TOKEN'
$allowedFailureTypes = @(
    'NONE', 'INVALID_INPUT', 'RETRIEVAL_EMPTY', 'RETRIEVAL_TIMEOUT', 'SKILL_NO_MATCH',
    'SKILL_VALIDATION_FAILED', 'TOOL_FAILED', 'LLM_TIMEOUT', 'CITATION_INVALID',
    'DRAFT_VERSION_CONFLICT', 'PERMISSION_DENIED', 'INTERNAL_ERROR'
)
$allowedTools = @('article_rag', 'bangumi_characters', 'bangumi_person_works', 'bangumi_search', 'fulltext_search')
$allowedRetrievalStatuses = @('SUCCESS_RESULTS', 'SUCCESS_EMPTY', 'TIMEOUT', 'FAILED')
$allowedCitationStatuses = @('NOT_RUN', 'VALID', 'NO_ANSWER', 'NO_EVIDENCE', 'MISSING_CITATION', 'UNKNOWN_CITATION')
$allowedSkillSelectionStatuses = @('NOT_EVALUATED', 'DISABLED', 'SELECTED', 'CLARIFICATION_REQUIRED', 'NO_MATCH', 'ERROR')
$allowedSkillValidationStatuses = @(
    'NOT_RUN', 'NOT_APPLICABLE', 'VALID', 'INSUFFICIENT_EVIDENCE', 'SCHEMA_INVALID',
    'CITATION_INVALID', 'CONSTRAINT_INVALID'
)
$allowedSkillIds = @('draft-edit', 'draft-fact-check', 'evidence-outline', 'page-explain')
$allowedModels = @('deepseek-chat', 'deepseek-reasoner', 'deterministic-fake', 'unknown')
$allowedRetrievalVersions = @('chunk-hybrid', 'document-rrf-v1')
$allowedCitationValidators = @('stream-before-validation', 'evidence-citation-gate-v1')

function Get-Sha256 {
    param([AllowEmptyString()][string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
}

function Get-Utf8Text {
    param([string]$Base64)
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Base64))
}

function Resolve-Commit {
    param([string]$Value, [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name is required" }
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

function Get-PropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    if ($Object -is [Collections.IDictionary] -and $Object.Contains($Name)) { return $Object[$Name] }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-Token {
    param([object]$Value, [string]$Name, [bool]$Required = $false)
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        if ($Required) { throw "$Name is required" }
        return $null
    }
    $text = [string]$Value
    if ($text -notmatch '^[A-Za-z0-9._:-]{1,128}$') { throw "$Name contains an unsafe value" }
    return $text
}

function Get-AllowlistedValue {
    param(
        [object]$Value,
        [string]$Name,
        [string[]]$Allowed,
        [bool]$Required = $true
    )
    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace([string]$Value)) {
        if ($Required) { throw "$Name is required" }
        return $null
    }
    $text = [string]$Value
    if ($Allowed -notcontains $text) { throw "$Name contains an unsupported value: $text" }
    return $text
}

function Get-StrictBoolean {
    param([object]$Value, [string]$Name)
    if ($Value -isnot [bool]) { throw "$Name must be a JSON boolean" }
    return [bool]$Value
}

function Assert-NoReparsePoint {
    param([string]$Path, [string]$Name)
    $item = Get-Item -LiteralPath $Path -Force
    while ($null -ne $item) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Name contains a reparse point: $($item.FullName)"
        }
        if ($item.FullName -eq $repoRoot) { break }
        $parent = Split-Path -Parent $item.FullName
        if ([string]::IsNullOrWhiteSpace($parent) -or -not (Test-Path -LiteralPath $parent)) { break }
        $item = Get-Item -LiteralPath $parent -Force
    }
}

function Get-Hash {
    param([object]$Value, [string]$Name)
    $text = [string]$Value
    if ($text -notmatch '^[a-fA-F0-9]{64}$') { throw "$Name must be a SHA-256 fingerprint" }
    return $text.ToLowerInvariant()
}

function Get-NonNegativeNumber {
    param([object]$Value, [string]$Name, [bool]$Required = $false)
    if ($null -eq $Value) {
        if ($Required) { throw "$Name is required" }
        return $null
    }
    $number = 0L
    if (-not [long]::TryParse([string]$Value, [ref]$number) -or $number -lt 0) {
        throw "$Name must be a non-negative integer"
    }
    return $number
}

function Get-IgnoredInputPath {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Trace response does not exist: $Path" }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $repoPrefix = $repoRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Trace response must remain inside the repository ignored workspace'
    }
    $relative = $resolved.Substring($repoPrefix.Length).Replace('\', '/')
    & git -C $repoRoot check-ignore --quiet -- $relative
    if ($LASTEXITCODE -ne 0) { throw "Trace response must be Git-ignored: $relative" }
    if (-not ($relative.StartsWith('.benchmark-results/') -or $relative.StartsWith('.codex-tmp/'))) {
        throw 'Trace response must be under .benchmark-results/ or .codex-tmp/'
    }
    Assert-NoReparsePoint -Path $resolved -Name 'Trace response'
    return $resolved
}

function Get-NewRunDirectory {
    if (-not (Test-Path -LiteralPath $resultsRoot -PathType Container)) {
        New-Item -ItemType Directory -Path $resultsRoot | Out-Null
    }
    Assert-NoReparsePoint -Path $resultsRoot -Name 'Results root'
    & git -C $repoRoot check-ignore --quiet -- '.benchmark-results/probe'
    if ($LASTEXITCODE -ne 0) { throw '.benchmark-results/ must be Git-ignored' }
    $directory = Join-Path $resultsRoot $RunId
    if (Test-Path -LiteralPath $directory) { throw "Run directory already exists: $directory" }
    New-Item -ItemType Directory -Path $directory | Out-Null
    Assert-NoReparsePoint -Path $directory -Name 'Run directory'
    return $directory
}

function Get-ExistingRunDirectory {
    param([string]$Path, [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Name does not exist"
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $prefix = (Resolve-Path -LiteralPath $resultsRoot).Path.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name must be under .benchmark-results/"
    }
    Assert-NoReparsePoint -Path $resolved -Name $Name
    return $resolved
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    $Value | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Get-Sample {
    param([string]$Id)
    if ([string]::IsNullOrWhiteSpace($Id)) { throw 'SampleId is required' }
    $matches = @(Get-Content -LiteralPath $datasetPath -Encoding UTF8 |
        ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object sampleId -eq $Id)
    if ($matches.Count -ne 1) { throw "Unknown or duplicate Trace replay sample: $Id" }
    return $matches[0]
}

function Get-CanonicalReplayIdentity {
    param([object]$Sample, [string]$DatasetCommit)
    $dataset = Resolve-Commit -Value $DatasetCommit -Name 'DatasetCommit'
    $datasetFileSha256 = (Get-FileHash -LiteralPath $datasetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $question = ([string]$Sample.fixture.question).Trim()
    $pageContext = ([string]$Sample.fixture.pageContext).Trim()
    $inputFingerprint = Get-Sha256 -Value ($question + "`n--page--`n" + $pageContext)
    $dataFingerprint = Get-Sha256 -Value ($dataset + "`n" + $datasetFileSha256 + "`n" + [string]$Sample.dataSnapshotId)
    $environment = $Sample.environment
    $permissions = @($environment.permissions | ForEach-Object { [string]$_ } | Sort-Object -Unique)
    $environmentText = @(
        [string]$environment.profile,
        [string]$environment.modelAdapter,
        [string]$environment.locale,
        [string]$environment.timezone,
        ($permissions -join ',')
    ) -join "`n"
    $environmentFingerprint = Get-Sha256 -Value $environmentText
    return [ordered]@{
        datasetCommit = $dataset
        datasetFileSha256 = $datasetFileSha256
        inputFingerprint = $inputFingerprint
        questionFingerprint = Get-Sha256 -Value $question
        pageContextFingerprint = Get-Sha256 -Value $pageContext
        dataFingerprint = $dataFingerprint
        environmentFingerprint = $environmentFingerprint
        invariantFingerprint = Get-Sha256 -Value ($inputFingerprint + "`n" + $dataFingerprint + "`n" + $environmentFingerprint)
    }
}

function Select-ToolVersions {
    param([object]$Source)
    $selected = [ordered]@{}
    if ($null -eq $Source) { return $selected }
    foreach ($property in @($Source.PSObject.Properties | Sort-Object Name)) {
        $name = Get-AllowlistedValue -Value $property.Name -Name 'toolVersions.name' -Allowed $allowedTools
        $value = Get-AllowlistedValue -Value $property.Value -Name "toolVersions.$name" -Allowed @('agent-tool-v1')
        $selected[$name] = $value
    }
    return $selected
}

function Get-TraceResponse {
    $hasPath = -not [string]::IsNullOrWhiteSpace($TraceResponsePath)
    $hasId = -not [string]::IsNullOrWhiteSpace($TraceId)
    if ($hasPath -eq $hasId) { throw 'Provide exactly one of TraceResponsePath or TraceId' }
    if ($hasPath) {
        $path = Get-IgnoredInputPath -Path $TraceResponsePath
        return [ordered]@{
            sourceMode = 'ignored-file'
            response = Get-Content -Raw -LiteralPath $path -Encoding UTF8 | ConvertFrom-Json
        }
    }

    $token = [Environment]::GetEnvironmentVariable($adminTokenEnvironment)
    if ([string]::IsNullOrWhiteSpace($token)) { throw "Admin token environment variable is empty: $adminTokenEnvironment" }
    $baseUri = $null
    if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$baseUri)) {
        throw 'BaseUrl must be an absolute loopback HTTP(S) URL'
    }
    if ($baseUri.Scheme -notin @('http', 'https') `
            -or -not [Uri]::IsLoopback($baseUri) `
            -or -not [string]::IsNullOrEmpty($baseUri.UserInfo) `
            -or -not [string]::IsNullOrEmpty($baseUri.Query) `
            -or -not [string]::IsNullOrEmpty($baseUri.Fragment)) {
        throw 'BaseUrl must be a loopback HTTP(S) URL without userinfo, query, or fragment'
    }
    $safeTraceId = Get-Token -Value $TraceId -Name 'TraceId' -Required $true
    $uri = $baseUri.AbsoluteUri.TrimEnd('/') + '/api/v1/traces/' + [Uri]::EscapeDataString($safeTraceId)
    $response = Invoke-RestMethod -Method Get -Uri $uri -Headers @{ Authorization = "Bearer $token" } -MaximumRedirection 0
    return [ordered]@{ sourceMode = 'api'; response = $response }
}

function Get-GitBlobId {
    param([string]$Commit, [string]$RelativePath)
    $spec = $Commit + ':' + $RelativePath
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'SilentlyContinue'
        $blob = & git -C $repoRoot rev-parse --verify $spec 2>$null
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($blob)) { return $null }
    return $blob.Trim()
}

function Get-GitBlobToken {
    param([object]$Value, [string]$Name)
    $text = [string]$Value
    if ($text -notmatch '^[a-fA-F0-9]{40}$') { throw "$Name must be a full Git blob ID" }
    return $text.ToLowerInvariant()
}

function Get-WorkingTreeBlobId {
    param([string]$RelativePath)
    $blob = & git -C $repoRoot hash-object "--path=$RelativePath" -- $RelativePath 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($blob)) {
        throw "Cannot hash working tree file: $RelativePath"
    }
    return $blob.Trim()
}

function Get-ExportIdentity {
    param([object]$Sample)
    $subject = Resolve-Commit -Value $SubjectCommit -Name 'SubjectCommit'
    $harness = Resolve-Commit -Value $HarnessCommit -Name 'HarnessCommit'
    $canonical = Get-CanonicalReplayIdentity -Sample $Sample -DatasetCommit $DatasetCommit
    $dataset = $canonical.datasetCommit
    $expectedRef = if ($SubjectRole -eq 'baseline') { $Sample.baselineCommit } else { $Sample.candidateCommit }
    $expected = Resolve-Commit -Value $expectedRef -Name 'dataset subject commit'
    if ($subject -ne $expected) { throw "SubjectCommit does not match $SubjectRole commit for $($Sample.sampleId)" }

    $harnessRelativePath = 'scripts/benchmark/trace-replay.ps1'
    $datasetRelativePath = 'benchmarks/datasets/agent-evaluation/trace-replays.jsonl'
    $harnessCommitBlob = Get-GitBlobId -Commit $harness -RelativePath $harnessRelativePath
    $datasetCommitBlob = Get-GitBlobId -Commit $dataset -RelativePath $datasetRelativePath
    $harnessWorkingBlob = Get-WorkingTreeBlobId -RelativePath $harnessRelativePath
    $datasetWorkingBlob = Get-WorkingTreeBlobId -RelativePath $datasetRelativePath
    $blobIdentityMatches = $harnessCommitBlob -eq $harnessWorkingBlob -and $datasetCommitBlob -eq $datasetWorkingBlob
    if (-not $blobIdentityMatches -and (-not $AllowUncommittedHarness -or [string]::IsNullOrWhiteSpace($TraceResponsePath))) {
        throw 'HarnessCommit or DatasetCommit does not own the current replay files'
    }
    $identityStatus = if ($blobIdentityMatches) { 'VERIFIED_COMMIT_BLOBS' } else { 'WORKTREE_UNCOMMITTED' }

    $harnessFileSha256 = (Get-FileHash -LiteralPath $runnerPath -Algorithm SHA256).Hash.ToLowerInvariant()
    return [ordered]@{
        subjectCommit = $subject
        exporterCommit = Resolve-Commit -Value 'HEAD' -Name 'exporter commit'
        harnessCommit = $harness
        datasetCommit = $dataset
        harnessFileSha256 = $harnessFileSha256
        datasetFileSha256 = $canonical.datasetFileSha256
        inputFingerprint = $canonical.inputFingerprint
        questionFingerprint = $canonical.questionFingerprint
        pageContextFingerprint = $canonical.pageContextFingerprint
        dataFingerprint = $canonical.dataFingerprint
        environmentFingerprint = $canonical.environmentFingerprint
        invariantFingerprint = $canonical.invariantFingerprint
        identityStatus = $identityStatus
        harnessBlob = $harnessWorkingBlob
        datasetBlob = $datasetWorkingBlob
    }
}

function Get-EvidenceLevel {
    param([string]$SourceMode, [object]$Replay, [object]$Identity)
    if ($SourceMode -eq 'ignored-file') { return 'OFFLINE_UNVERIFIED' }
    return 'API_UNVERIFIED'
}

function Convert-TraceResponse {
    param([object]$Response, [object]$Sample, [object]$Identity, [string]$SourceMode)
    $payload = Get-PropertyValue -Object $Response -Name 'tracePayload'
    if ($null -eq $payload) { throw 'Trace response does not contain tracePayload' }
    $input = Get-PropertyValue -Object $payload -Name 'input'
    if ($null -eq $input) { throw 'Trace payload does not contain replay input fingerprints' }

    $expectedInput = $Identity.inputFingerprint
    $expectedQuestion = $Identity.questionFingerprint
    $expectedPage = $Identity.pageContextFingerprint
    $actualInput = Get-Hash -Value (Get-PropertyValue $input 'fingerprint') -Name 'input.fingerprint'
    $actualQuestion = Get-Hash -Value (Get-PropertyValue $input 'questionFingerprint') -Name 'input.questionFingerprint'
    $actualPage = Get-Hash -Value (Get-PropertyValue $input 'pageContextFingerprint') -Name 'input.pageContextFingerprint'
    if ($actualInput -ne $expectedInput -or $actualQuestion -ne $expectedQuestion -or $actualPage -ne $expectedPage) {
        throw 'Trace input fingerprints do not match the reviewed replay fixture'
    }

    $failureType = Get-Token -Value (Get-PropertyValue $payload 'failureType') -Name 'failureType' -Required $true
    if ($allowedFailureTypes -notcontains $failureType) { throw "Unknown failureType: $failureType" }
    $runMode = Get-Token -Value (Get-PropertyValue $payload 'runMode') -Name 'runMode' -Required $true
    if ($runMode -notin @('baseline', 'candidate', 'replay')) { throw "Unknown runMode: $runMode" }
    if ($runMode -ne $SubjectRole) { throw 'Trace runMode does not match SubjectRole' }
    $status = Get-AllowlistedValue -Value (Get-PropertyValue $Response 'status') -Name 'status' `
        -Allowed @('SUCCESS', 'FAILURE', 'TIMEOUT', 'ABORTED')

    $componentSource = Get-PropertyValue $payload 'components'
    $components = [ordered]@{
        prompt = Get-AllowlistedValue -Value (Get-PropertyValue $componentSource 'prompt') -Name 'components.prompt' -Allowed @('agent-prompt-v1')
        skillSelector = Get-AllowlistedValue -Value (Get-PropertyValue $componentSource 'skillSelector') -Name 'components.skillSelector' -Allowed @('skill-selector-v1')
        model = Get-AllowlistedValue -Value (Get-PropertyValue $componentSource 'model') -Name 'components.model' -Allowed $allowedModels
        retrieval = Get-AllowlistedValue -Value (Get-PropertyValue $componentSource 'retrieval') -Name 'components.retrieval' -Allowed $allowedRetrievalVersions
        citationValidator = Get-AllowlistedValue -Value (Get-PropertyValue $componentSource 'citationValidator') -Name 'components.citationValidator' -Allowed $allowedCitationValidators
        tools = Get-AllowlistedValue -Value (Get-PropertyValue $componentSource 'tools') -Name 'components.tools' -Allowed @('agent-tool-v1')
        traceSchema = Get-AllowlistedValue -Value (Get-PropertyValue $componentSource 'traceSchema') -Name 'components.traceSchema' -Allowed @('agent-trace-v1')
    }
    $skillSource = Get-PropertyValue $payload 'skill'
    $skill = [ordered]@{
        selectionStatus = Get-AllowlistedValue -Value (Get-PropertyValue $skillSource 'selectionStatus') -Name 'skill.selectionStatus' -Allowed $allowedSkillSelectionStatuses
        id = Get-AllowlistedValue -Value (Get-PropertyValue $skillSource 'id') -Name 'skill.id' -Allowed $allowedSkillIds -Required $false
        version = Get-AllowlistedValue -Value (Get-PropertyValue $skillSource 'version') -Name 'skill.version' -Allowed @('v1') -Required $false
        validationStatus = Get-AllowlistedValue -Value (Get-PropertyValue $skillSource 'validationStatus') -Name 'skill.validationStatus' -Allowed $allowedSkillValidationStatuses
    }
    $retrievalSource = Get-PropertyValue $payload 'retrieval'
    if ($null -eq $retrievalSource) { throw 'Trace retrieval metadata is missing' }
    $statusSource = Get-PropertyValue $retrievalSource 'statuses'
    $statuses = [ordered]@{
        semantic = Get-AllowlistedValue -Value (Get-PropertyValue $statusSource 'semantic') -Name 'retrieval.statuses.semantic' -Allowed $allowedRetrievalStatuses
        keyword = Get-AllowlistedValue -Value (Get-PropertyValue $statusSource 'keyword') -Name 'retrieval.statuses.keyword' -Allowed $allowedRetrievalStatuses
        entity = Get-AllowlistedValue -Value (Get-PropertyValue $statusSource 'entity') -Name 'retrieval.statuses.entity' -Allowed $allowedRetrievalStatuses
    }
    $evidenceHash = Get-Hash -Value (Get-PropertyValue $retrievalSource 'evidenceSnapshotHash') -Name 'retrieval.evidenceSnapshotHash'
    $citationStatus = Get-AllowlistedValue -Value (Get-PropertyValue $retrievalSource 'citationValidationStatus') `
        -Name 'retrieval.citationValidationStatus' -Allowed $allowedCitationStatuses
    $degradedValue = Get-StrictBoolean -Value (Get-PropertyValue $retrievalSource 'degraded') -Name 'retrieval.degraded'
    $correlationId = [string](Get-PropertyValue $Response 'correlationId')
    if ([string]::IsNullOrWhiteSpace($correlationId)) { throw 'Trace response correlationId is missing' }
    $payloadCorrelationId = [string](Get-PropertyValue $payload 'correlationId')
    if ($payloadCorrelationId -ne $correlationId) { throw 'Trace response and payload correlationId do not match' }
    if ($SourceMode -eq 'api' -and $correlationId -ne $TraceId) { throw 'API Trace correlationId does not match TraceId' }
    $evidenceCount = Get-NonNegativeNumber -Value (Get-PropertyValue $retrievalSource 'evidenceCount') -Name 'retrieval.evidenceCount' -Required $true

    return [ordered]@{
        schemaVersion = 1
        kind = 'agent-trace-replay'
        sampleId = [string]$Sample.sampleId
        subjectRole = $SubjectRole
        subjectCommit = $Identity.subjectCommit
        sourceTraceFingerprint = Get-Sha256 -Value $correlationId
        status = $status
        durationMs = Get-NonNegativeNumber -Value (Get-PropertyValue $Response 'durationMs') -Name 'durationMs'
        stepsCount = Get-NonNegativeNumber -Value (Get-PropertyValue $Response 'stepsCount') -Name 'stepsCount'
        input = [ordered]@{ fingerprint = $actualInput; questionFingerprint = $actualQuestion; pageContextFingerprint = $actualPage }
        components = $components
        skill = $skill
        retrieval = [ordered]@{
            strategy = Get-AllowlistedValue -Value (Get-PropertyValue $retrievalSource 'strategy') -Name 'retrieval.strategy' -Allowed $allowedRetrievalVersions
            statuses = $statuses
            evidenceCount = $evidenceCount
            evidenceSnapshotHash = $evidenceHash
            degraded = $degradedValue
            citationValidationStatus = $citationStatus
        }
        toolVersions = Select-ToolVersions -Source (Get-PropertyValue $payload 'toolVersions')
        failureType = $failureType
        runMode = $runMode
        totals = [ordered]@{
            totalDurationMs = Get-NonNegativeNumber -Value (Get-PropertyValue $payload 'totalDurationMs') -Name 'totals.totalDurationMs'
            llmDurationMs = Get-NonNegativeNumber -Value (Get-PropertyValue $payload 'llmDurationMs') -Name 'totals.llmDurationMs'
            toolDurationMs = Get-NonNegativeNumber -Value (Get-PropertyValue $payload 'toolDurationMs') -Name 'totals.toolDurationMs'
            inputTokens = Get-NonNegativeNumber -Value (Get-PropertyValue $payload 'inputTokens') -Name 'totals.inputTokens'
            outputTokens = Get-NonNegativeNumber -Value (Get-PropertyValue $payload 'outputTokens') -Name 'totals.outputTokens'
            llmCallCount = Get-NonNegativeNumber -Value (Get-PropertyValue $payload 'llmCallCount') -Name 'totals.llmCallCount'
            finalAnswerLength = Get-NonNegativeNumber -Value (Get-PropertyValue $payload 'finalAnswerLength') -Name 'totals.finalAnswerLength'
        }
        inputFingerprint = $expectedInput
        dataFingerprint = $Identity.dataFingerprint
        environmentFingerprint = $Identity.environmentFingerprint
        invariantFingerprint = $Identity.invariantFingerprint
    }
}

function Invoke-Export {
    foreach ($required in @('SampleId', 'SubjectRole', 'SubjectCommit', 'HarnessCommit', 'DatasetCommit')) {
        if ([string]::IsNullOrWhiteSpace((Get-Variable -Name $required -ValueOnly))) { throw "$required is required for Export" }
    }
    if ($AllowUncommittedHarness -and [string]::IsNullOrWhiteSpace($TraceResponsePath)) {
        throw 'AllowUncommittedHarness is limited to ignored offline fixtures'
    }
    $startedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $sample = Get-Sample -Id $SampleId
    $identity = Get-ExportIdentity -Sample $sample
    $source = Get-TraceResponse
    $replay = Convert-TraceResponse -Response $source.response -Sample $sample -Identity $identity -SourceMode $source.sourceMode
    $evidenceLevel = Get-EvidenceLevel -SourceMode $source.sourceMode -Replay $replay -Identity $identity
    $runDirectory = Get-NewRunDirectory
    $replayPath = Join-Path $runDirectory 'replay.json'
    Write-JsonFile -Path $replayPath -Value $replay
    $replayFileSha256 = (Get-FileHash -LiteralPath $replayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        schemaVersion = 1; kind = 'trace-replay-export'; runId = $RunId; sampleId = $SampleId
        subjectRole = $SubjectRole; subjectCommit = $identity.subjectCommit; exporterCommit = $identity.exporterCommit
        harnessCommit = $identity.harnessCommit; datasetCommit = $identity.datasetCommit
        harnessFileSha256 = $identity.harnessFileSha256; datasetFileSha256 = $identity.datasetFileSha256
        harnessBlob = $identity.harnessBlob; datasetBlob = $identity.datasetBlob
        identityStatus = $identity.identityStatus; sourceMode = $source.sourceMode; evidenceLevel = $evidenceLevel
        sourceTraceFingerprint = $replay.sourceTraceFingerprint; inputFingerprint = $replay.inputFingerprint
        dataFingerprint = $identity.dataFingerprint; environmentFingerprint = $identity.environmentFingerprint
        invariantFingerprint = $replay.invariantFingerprint; replayFileSha256 = $replayFileSha256; status = 'EXPORTED'
        startedAt = $startedAt; endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    Write-JsonFile -Path (Join-Path $runDirectory 'manifest.json') -Value $manifest
    Write-Output ([ordered]@{ runId = $RunId; runDirectory = $runDirectory; status = 'EXPORTED' } | ConvertTo-Json)
}

function Assert-EqualField {
    param([object]$Baseline, [object]$Candidate, [string]$Name)
    if ([string](Get-PropertyValue $Baseline $Name) -ne [string](Get-PropertyValue $Candidate $Name)) {
        throw "Trace replay invariant mismatch: $Name"
    }
}

function Assert-ReplayManifestBinding {
    param([object]$Replay, [object]$Manifest, [string]$Name)
    $bindings = @{
        sampleId = 'sampleId'
        subjectRole = 'subjectRole'
        subjectCommit = 'subjectCommit'
        sourceTraceFingerprint = 'sourceTraceFingerprint'
        inputFingerprint = 'inputFingerprint'
        dataFingerprint = 'dataFingerprint'
        environmentFingerprint = 'environmentFingerprint'
        invariantFingerprint = 'invariantFingerprint'
    }
    foreach ($replayField in $bindings.Keys) {
        $manifestField = $bindings[$replayField]
        $replayValue = [string](Get-PropertyValue $Replay $replayField)
        $manifestValue = [string](Get-PropertyValue $Manifest $manifestField)
        if ($replayValue -ne $manifestValue) {
            throw "$Name replay does not match its manifest: $replayField replay=$replayValue manifest=$manifestValue"
        }
    }
}

function Assert-ExportManifest {
    param([object]$Manifest, [string]$Name)
    Assert-FixedProperties $Manifest @(
        'schemaVersion', 'kind', 'runId', 'sampleId', 'subjectRole', 'subjectCommit',
        'exporterCommit', 'harnessCommit', 'datasetCommit', 'harnessFileSha256',
        'datasetFileSha256', 'harnessBlob', 'datasetBlob', 'identityStatus', 'sourceMode',
        'evidenceLevel', 'sourceTraceFingerprint', 'inputFingerprint', 'dataFingerprint',
        'environmentFingerprint', 'invariantFingerprint', 'replayFileSha256', 'status',
        'startedAt', 'endedAt'
    ) "$Name manifest"
    if ([int](Get-PropertyValue $Manifest 'schemaVersion') -ne 1 `
            -or (Get-PropertyValue $Manifest 'kind') -ne 'trace-replay-export' `
            -or (Get-PropertyValue $Manifest 'status') -ne 'EXPORTED') {
        throw "$Name manifest has an unsupported schema, kind, or status"
    }
    foreach ($field in @('subjectCommit', 'exporterCommit', 'harnessCommit', 'datasetCommit')) {
        $declared = [string](Get-PropertyValue $Manifest $field)
        $resolved = Resolve-Commit -Value $declared -Name "$Name.$field"
        if ($declared -ne $resolved) { throw "$Name.$field must be a full commit ID" }
    }
    foreach ($field in @('harnessBlob', 'datasetBlob')) {
        [void](Get-GitBlobToken -Value (Get-PropertyValue $Manifest $field) -Name "$Name.$field")
    }
    $sourceMode = Get-AllowlistedValue -Value (Get-PropertyValue $Manifest 'sourceMode') `
        -Name "$Name.sourceMode" -Allowed @('ignored-file', 'api')
    $evidenceLevel = Get-AllowlistedValue -Value (Get-PropertyValue $Manifest 'evidenceLevel') `
        -Name "$Name.evidenceLevel" -Allowed @('OFFLINE_UNVERIFIED', 'API_UNVERIFIED', 'REAL_TRACE')
    $identityStatus = Get-AllowlistedValue -Value (Get-PropertyValue $Manifest 'identityStatus') `
        -Name "$Name.identityStatus" -Allowed @('WORKTREE_UNCOMMITTED', 'VERIFIED_COMMIT_BLOBS')
    if ($sourceMode -eq 'ignored-file' -and $evidenceLevel -ne 'OFFLINE_UNVERIFIED') {
        throw "$Name offline manifest promoted its evidence level"
    }
    if ($identityStatus -eq 'WORKTREE_UNCOMMITTED' -and $evidenceLevel -ne 'OFFLINE_UNVERIFIED') {
        throw "$Name uncommitted manifest promoted its evidence level"
    }
    foreach ($field in @(
        'harnessFileSha256', 'datasetFileSha256', 'replayFileSha256', 'sourceTraceFingerprint',
        'inputFingerprint', 'dataFingerprint', 'environmentFingerprint', 'invariantFingerprint'
    )) {
        [void](Get-Hash -Value (Get-PropertyValue $Manifest $field) -Name "$Name.$field")
    }
}

function Assert-FixedProperties {
    param([object]$Object, [string[]]$Allowed, [string]$Name)
    if ($null -eq $Object) { throw "$Name is required" }
    foreach ($property in $Object.PSObject.Properties) {
        if ($Allowed -notcontains $property.Name) { throw "$Name contains an unknown field: $($property.Name)" }
    }
}

function Convert-ReplayProjection {
    param([object]$Replay)
    if ([int](Get-PropertyValue $Replay 'schemaVersion') -ne 1 `
            -or (Get-PropertyValue $Replay 'kind') -ne 'agent-trace-replay') {
        throw 'Replay has an unsupported schema or kind'
    }
    Assert-FixedProperties $Replay @(
        'schemaVersion', 'kind', 'sampleId', 'subjectRole', 'subjectCommit', 'sourceTraceFingerprint',
        'status', 'durationMs', 'stepsCount', 'input', 'components', 'skill', 'retrieval',
        'toolVersions', 'failureType', 'runMode', 'totals', 'inputFingerprint', 'dataFingerprint',
        'environmentFingerprint', 'invariantFingerprint'
    ) 'replay'
    $componentsSource = Get-PropertyValue $Replay 'components'
    Assert-FixedProperties $componentsSource @(
        'prompt', 'skillSelector', 'model', 'retrieval', 'citationValidator', 'tools', 'traceSchema'
    ) 'replay.components'
    $components = [ordered]@{
        prompt = Get-AllowlistedValue -Value (Get-PropertyValue $componentsSource 'prompt') -Name 'replay.components.prompt' -Allowed @('agent-prompt-v1')
        skillSelector = Get-AllowlistedValue -Value (Get-PropertyValue $componentsSource 'skillSelector') -Name 'replay.components.skillSelector' -Allowed @('skill-selector-v1')
        model = Get-AllowlistedValue -Value (Get-PropertyValue $componentsSource 'model') -Name 'replay.components.model' -Allowed $allowedModels
        retrieval = Get-AllowlistedValue -Value (Get-PropertyValue $componentsSource 'retrieval') -Name 'replay.components.retrieval' -Allowed $allowedRetrievalVersions
        citationValidator = Get-AllowlistedValue -Value (Get-PropertyValue $componentsSource 'citationValidator') -Name 'replay.components.citationValidator' -Allowed $allowedCitationValidators
        tools = Get-AllowlistedValue -Value (Get-PropertyValue $componentsSource 'tools') -Name 'replay.components.tools' -Allowed @('agent-tool-v1')
        traceSchema = Get-AllowlistedValue -Value (Get-PropertyValue $componentsSource 'traceSchema') -Name 'replay.components.traceSchema' -Allowed @('agent-trace-v1')
    }
    $skillSource = Get-PropertyValue $Replay 'skill'
    Assert-FixedProperties $skillSource @('selectionStatus', 'id', 'version', 'validationStatus') 'replay.skill'
    $skill = [ordered]@{
        selectionStatus = Get-AllowlistedValue -Value (Get-PropertyValue $skillSource 'selectionStatus') -Name 'replay.skill.selectionStatus' -Allowed $allowedSkillSelectionStatuses
        id = Get-AllowlistedValue -Value (Get-PropertyValue $skillSource 'id') -Name 'replay.skill.id' -Allowed $allowedSkillIds -Required $false
        version = Get-AllowlistedValue -Value (Get-PropertyValue $skillSource 'version') -Name 'replay.skill.version' -Allowed @('v1') -Required $false
        validationStatus = Get-AllowlistedValue -Value (Get-PropertyValue $skillSource 'validationStatus') -Name 'replay.skill.validationStatus' -Allowed $allowedSkillValidationStatuses
    }
    $retrievalSource = Get-PropertyValue $Replay 'retrieval'
    Assert-FixedProperties $retrievalSource @(
        'strategy', 'statuses', 'evidenceCount', 'evidenceSnapshotHash', 'degraded', 'citationValidationStatus'
    ) 'replay.retrieval'
    $statusSource = Get-PropertyValue $retrievalSource 'statuses'
    Assert-FixedProperties $statusSource @('semantic', 'keyword', 'entity') 'replay.retrieval.statuses'
    $statuses = [ordered]@{
        semantic = Get-AllowlistedValue -Value (Get-PropertyValue $statusSource 'semantic') -Name 'replay.retrieval.statuses.semantic' -Allowed $allowedRetrievalStatuses
        keyword = Get-AllowlistedValue -Value (Get-PropertyValue $statusSource 'keyword') -Name 'replay.retrieval.statuses.keyword' -Allowed $allowedRetrievalStatuses
        entity = Get-AllowlistedValue -Value (Get-PropertyValue $statusSource 'entity') -Name 'replay.retrieval.statuses.entity' -Allowed $allowedRetrievalStatuses
    }
    $evidenceCount = Get-NonNegativeNumber -Value (Get-PropertyValue $retrievalSource 'evidenceCount') -Name 'replay.retrieval.evidenceCount' -Required $true
    $totalsSource = Get-PropertyValue $Replay 'totals'
    Assert-FixedProperties $totalsSource @(
        'totalDurationMs', 'llmDurationMs', 'toolDurationMs', 'inputTokens', 'outputTokens',
        'llmCallCount', 'finalAnswerLength'
    ) 'replay.totals'
    Assert-FixedProperties (Get-PropertyValue $Replay 'input') @(
        'fingerprint', 'questionFingerprint', 'pageContextFingerprint'
    ) 'replay.input'
    $subjectCommit = [string](Get-PropertyValue $Replay 'subjectCommit')
    if ($subjectCommit -notmatch '^[a-fA-F0-9]{40}$') { throw 'replay.subjectCommit must be a full Git commit' }
    return [ordered]@{
        schemaVersion = 1
        kind = 'agent-trace-replay'
        sampleId = Get-Token -Value (Get-PropertyValue $Replay 'sampleId') -Name 'replay.sampleId' -Required $true
        subjectRole = Get-AllowlistedValue -Value (Get-PropertyValue $Replay 'subjectRole') -Name 'replay.subjectRole' -Allowed @('baseline', 'candidate')
        subjectCommit = $subjectCommit.ToLowerInvariant()
        sourceTraceFingerprint = Get-Hash -Value (Get-PropertyValue $Replay 'sourceTraceFingerprint') -Name 'replay.sourceTraceFingerprint'
        status = Get-AllowlistedValue -Value (Get-PropertyValue $Replay 'status') -Name 'replay.status' -Allowed @('SUCCESS', 'FAILURE', 'TIMEOUT', 'ABORTED')
        durationMs = Get-NonNegativeNumber -Value (Get-PropertyValue $Replay 'durationMs') -Name 'replay.durationMs'
        stepsCount = Get-NonNegativeNumber -Value (Get-PropertyValue $Replay 'stepsCount') -Name 'replay.stepsCount'
        input = [ordered]@{
            fingerprint = Get-Hash -Value (Get-PropertyValue $Replay.input 'fingerprint') -Name 'replay.input.fingerprint'
            questionFingerprint = Get-Hash -Value (Get-PropertyValue $Replay.input 'questionFingerprint') -Name 'replay.input.questionFingerprint'
            pageContextFingerprint = Get-Hash -Value (Get-PropertyValue $Replay.input 'pageContextFingerprint') -Name 'replay.input.pageContextFingerprint'
        }
        components = $components
        skill = $skill
        retrieval = [ordered]@{
            strategy = Get-AllowlistedValue -Value (Get-PropertyValue $retrievalSource 'strategy') -Name 'replay.retrieval.strategy' -Allowed $allowedRetrievalVersions
            statuses = $statuses
            evidenceCount = $evidenceCount
            evidenceSnapshotHash = Get-Hash -Value (Get-PropertyValue $retrievalSource 'evidenceSnapshotHash') -Name 'replay.retrieval.evidenceSnapshotHash'
            degraded = Get-StrictBoolean -Value (Get-PropertyValue $retrievalSource 'degraded') -Name 'replay.retrieval.degraded'
            citationValidationStatus = Get-AllowlistedValue -Value (Get-PropertyValue $retrievalSource 'citationValidationStatus') -Name 'replay.retrieval.citationValidationStatus' -Allowed $allowedCitationStatuses
        }
        toolVersions = Select-ToolVersions -Source (Get-PropertyValue $Replay 'toolVersions')
        failureType = Get-AllowlistedValue -Value (Get-PropertyValue $Replay 'failureType') -Name 'replay.failureType' -Allowed $allowedFailureTypes
        runMode = Get-AllowlistedValue -Value (Get-PropertyValue $Replay 'runMode') -Name 'replay.runMode' -Allowed @('baseline', 'candidate', 'replay')
        totals = [ordered]@{
            totalDurationMs = Get-NonNegativeNumber -Value (Get-PropertyValue $totalsSource 'totalDurationMs') -Name 'replay.totals.totalDurationMs'
            llmDurationMs = Get-NonNegativeNumber -Value (Get-PropertyValue $totalsSource 'llmDurationMs') -Name 'replay.totals.llmDurationMs'
            toolDurationMs = Get-NonNegativeNumber -Value (Get-PropertyValue $totalsSource 'toolDurationMs') -Name 'replay.totals.toolDurationMs'
            inputTokens = Get-NonNegativeNumber -Value (Get-PropertyValue $totalsSource 'inputTokens') -Name 'replay.totals.inputTokens'
            outputTokens = Get-NonNegativeNumber -Value (Get-PropertyValue $totalsSource 'outputTokens') -Name 'replay.totals.outputTokens'
            llmCallCount = Get-NonNegativeNumber -Value (Get-PropertyValue $totalsSource 'llmCallCount') -Name 'replay.totals.llmCallCount'
            finalAnswerLength = Get-NonNegativeNumber -Value (Get-PropertyValue $totalsSource 'finalAnswerLength') -Name 'replay.totals.finalAnswerLength'
        }
        inputFingerprint = Get-Hash -Value (Get-PropertyValue $Replay 'inputFingerprint') -Name 'replay.inputFingerprint'
        dataFingerprint = Get-Hash -Value (Get-PropertyValue $Replay 'dataFingerprint') -Name 'replay.dataFingerprint'
        environmentFingerprint = Get-Hash -Value (Get-PropertyValue $Replay 'environmentFingerprint') -Name 'replay.environmentFingerprint'
        invariantFingerprint = Get-Hash -Value (Get-PropertyValue $Replay 'invariantFingerprint') -Name 'replay.invariantFingerprint'
    }
}

function Read-BoundReplay {
    param([string]$Directory, [object]$Manifest, [string]$Name)
    $path = Join-Path $Directory 'replay.json'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "$Name replay file is missing" }
    Assert-NoReparsePoint -Path $path -Name "$Name replay"
    $expectedHash = Get-Hash -Value (Get-PropertyValue $Manifest 'replayFileSha256') -Name "$Name.replayFileSha256"
    $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) { throw "$Name replay file does not match its manifest hash" }
    $raw = Get-Content -Raw -LiteralPath $path -Encoding UTF8 | ConvertFrom-Json
    return Convert-ReplayProjection -Replay $raw
}

function Add-DiffRow {
    param([System.Collections.Generic.List[object]]$Rows, [string]$Path, [object]$Baseline, [object]$Candidate)
    $baselineText = if ($null -eq $Baseline) { '' } else { [string]$Baseline }
    $candidateText = if ($null -eq $Candidate) { '' } else { [string]$Candidate }
    $Rows.Add([pscustomobject][ordered]@{
        path = $Path; baseline = $baselineText; candidate = $candidateText
        changed = ($baselineText -ne $candidateText).ToString().ToLowerInvariant()
    })
}

function Get-ReplayDiff {
    param([object]$Baseline, [object]$Candidate)
    $rows = [System.Collections.Generic.List[object]]::new()
    foreach ($name in @('status', 'failureType', 'runMode', 'durationMs', 'stepsCount')) {
        Add-DiffRow $rows $name (Get-PropertyValue $Baseline $name) (Get-PropertyValue $Candidate $name)
    }
    foreach ($name in @('prompt', 'skillSelector', 'model', 'retrieval', 'citationValidator', 'tools', 'traceSchema')) {
        Add-DiffRow $rows "components.$name" (Get-PropertyValue $Baseline.components $name) (Get-PropertyValue $Candidate.components $name)
    }
    foreach ($name in @('selectionStatus', 'id', 'version', 'validationStatus')) {
        Add-DiffRow $rows "skill.$name" (Get-PropertyValue $Baseline.skill $name) (Get-PropertyValue $Candidate.skill $name)
    }
    foreach ($name in @('strategy', 'evidenceCount', 'evidenceSnapshotHash', 'degraded', 'citationValidationStatus')) {
        Add-DiffRow $rows "retrieval.$name" (Get-PropertyValue $Baseline.retrieval $name) (Get-PropertyValue $Candidate.retrieval $name)
    }
    foreach ($name in @('semantic', 'keyword', 'entity')) {
        Add-DiffRow $rows "retrieval.statuses.$name" (Get-PropertyValue $Baseline.retrieval.statuses $name) (Get-PropertyValue $Candidate.retrieval.statuses $name)
    }
    foreach ($name in @('totalDurationMs', 'llmDurationMs', 'toolDurationMs', 'inputTokens', 'outputTokens', 'llmCallCount', 'finalAnswerLength')) {
        Add-DiffRow $rows "totals.$name" (Get-PropertyValue $Baseline.totals $name) (Get-PropertyValue $Candidate.totals $name)
    }
    return $rows.ToArray()
}

function Add-ExpectationMismatch {
    param(
        [System.Collections.Generic.List[string]]$Failures,
        [string]$Role,
        [string]$Path,
        [object]$Expected,
        [object]$Actual
    )
    if ([string]$Expected -ne [string]$Actual) {
        $Failures.Add("$Role.$Path expected=$Expected actual=$Actual")
    }
}

function Get-ExpectationFailures {
    param([object]$Sample, [object]$Baseline, [object]$Candidate, [int]$ChangedCount)
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($role in @('baseline', 'candidate')) {
        $expected = Get-PropertyValue (Get-PropertyValue $Sample 'expected') $role
        $actual = if ($role -eq 'baseline') { $Baseline } else { $Candidate }
        Add-ExpectationMismatch $failures $role 'failureType' $expected.failureType $actual.failureType
        Add-ExpectationMismatch $failures $role 'status' $expected.traceStatus $actual.status
        Add-ExpectationMismatch $failures $role 'components.retrieval' $expected.retrievalComponent $actual.components.retrieval
        Add-ExpectationMismatch $failures $role 'components.citationValidator' $expected.citationValidator $actual.components.citationValidator
        Add-ExpectationMismatch $failures $role 'retrieval.statuses.entity' $expected.entityStatus $actual.retrieval.statuses.entity
        Add-ExpectationMismatch $failures $role 'retrieval.evidenceCount' $expected.evidenceCount $actual.retrieval.evidenceCount
        Add-ExpectationMismatch $failures $role 'retrieval.citationValidationStatus' $expected.citationValidationStatus $actual.retrieval.citationValidationStatus
    }
    if ($ChangedCount -eq 0) { $failures.Add('comparison.changedFieldCount expected=>0 actual=0') }
    return $failures.ToArray()
}

function Invoke-Compare {
    $baselineDirectory = Get-ExistingRunDirectory -Path $BaselineRunDirectory -Name 'BaselineRunDirectory'
    $candidateDirectory = Get-ExistingRunDirectory -Path $CandidateRunDirectory -Name 'CandidateRunDirectory'
    $baselineManifest = Get-Content -Raw -LiteralPath (Join-Path $baselineDirectory 'manifest.json') -Encoding UTF8 | ConvertFrom-Json
    $candidateManifest = Get-Content -Raw -LiteralPath (Join-Path $candidateDirectory 'manifest.json') -Encoding UTF8 | ConvertFrom-Json
    Assert-ExportManifest -Manifest $baselineManifest -Name 'Baseline'
    Assert-ExportManifest -Manifest $candidateManifest -Name 'Candidate'
    $baselineReplay = Read-BoundReplay -Directory $baselineDirectory -Manifest $baselineManifest -Name 'Baseline'
    $candidateReplay = Read-BoundReplay -Directory $candidateDirectory -Manifest $candidateManifest -Name 'Candidate'

    if ($baselineManifest.subjectRole -ne 'baseline' -or $candidateManifest.subjectRole -ne 'candidate') {
        throw 'Trace replay roles must be baseline then candidate'
    }
    Assert-ReplayManifestBinding -Replay $baselineReplay -Manifest $baselineManifest -Name 'Baseline'
    Assert-ReplayManifestBinding -Replay $candidateReplay -Manifest $candidateManifest -Name 'Candidate'
    if ($baselineReplay.runMode -ne 'baseline' -or $candidateReplay.runMode -ne 'candidate') {
        throw 'Trace replay runMode does not match its baseline or candidate role'
    }
    if ($baselineManifest.evidenceLevel -ne (Get-EvidenceLevel $baselineManifest.sourceMode $baselineReplay $baselineManifest) `
            -or $candidateManifest.evidenceLevel -ne (Get-EvidenceLevel $candidateManifest.sourceMode $candidateReplay $candidateManifest)) {
        throw 'Trace replay manifest evidence level is not eligible for its source'
    }
    foreach ($name in @('sampleId', 'harnessCommit', 'datasetCommit', 'harnessFileSha256', 'datasetFileSha256', 'harnessBlob', 'datasetBlob', 'identityStatus', 'inputFingerprint', 'dataFingerprint', 'environmentFingerprint', 'invariantFingerprint')) {
        Assert-EqualField -Baseline $baselineManifest -Candidate $candidateManifest -Name $name
    }
    $currentHarnessSha256 = (Get-FileHash -LiteralPath $runnerPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $currentDatasetSha256 = (Get-FileHash -LiteralPath $datasetPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($baselineManifest.harnessFileSha256 -ne $currentHarnessSha256) { throw 'Current harness differs from the exported runs' }
    if ($baselineManifest.datasetFileSha256 -ne $currentDatasetSha256) { throw 'Current dataset differs from the exported runs' }
    $currentHarnessBlob = Get-WorkingTreeBlobId -RelativePath 'scripts/benchmark/trace-replay.ps1'
    $currentDatasetBlob = Get-WorkingTreeBlobId -RelativePath 'benchmarks/datasets/agent-evaluation/trace-replays.jsonl'
    if ($baselineManifest.harnessBlob -ne $currentHarnessBlob -or $baselineManifest.datasetBlob -ne $currentDatasetBlob) {
        throw 'Trace replay manifest Git blob identity does not match the current files'
    }
    $declaredCommitsOwnFiles = `
        (Get-GitBlobId $baselineManifest.harnessCommit 'scripts/benchmark/trace-replay.ps1') -eq $currentHarnessBlob `
        -and (Get-GitBlobId $baselineManifest.datasetCommit 'benchmarks/datasets/agent-evaluation/trace-replays.jsonl') -eq $currentDatasetBlob
    $derivedIdentityStatus = if ($declaredCommitsOwnFiles) { 'VERIFIED_COMMIT_BLOBS' } else { 'WORKTREE_UNCOMMITTED' }
    if ($baselineManifest.identityStatus -ne $derivedIdentityStatus) {
        throw 'Trace replay manifest identityStatus does not match its commit and blob ownership'
    }
    $sample = Get-Sample -Id $baselineManifest.sampleId
    $expectedBaseline = Resolve-Commit -Value $sample.baselineCommit -Name 'dataset baseline commit'
    $expectedCandidate = Resolve-Commit -Value $sample.candidateCommit -Name 'dataset candidate commit'
    if ($baselineManifest.subjectCommit -ne $expectedBaseline -or $candidateManifest.subjectCommit -ne $expectedCandidate) {
        throw 'Trace replay subject pair does not match the dataset'
    }
    $canonical = Get-CanonicalReplayIdentity -Sample $sample -DatasetCommit $baselineManifest.datasetCommit
    $canonicalFields = [ordered]@{
        inputFingerprint = $canonical.inputFingerprint
        dataFingerprint = $canonical.dataFingerprint
        environmentFingerprint = $canonical.environmentFingerprint
        invariantFingerprint = $canonical.invariantFingerprint
    }
    foreach ($bundle in @(
        [ordered]@{ name = 'Baseline'; manifest = $baselineManifest; replay = $baselineReplay },
        [ordered]@{ name = 'Candidate'; manifest = $candidateManifest; replay = $candidateReplay }
    )) {
        foreach ($field in $canonicalFields.Keys) {
            $expectedValue = $canonicalFields[$field]
            if ((Get-PropertyValue $bundle.manifest $field) -ne $expectedValue `
                    -or (Get-PropertyValue $bundle.replay $field) -ne $expectedValue) {
                throw "$($bundle.name) Trace replay canonical identity mismatch: $field"
            }
        }
        $replayInput = Get-PropertyValue $bundle.replay 'input'
        foreach ($field in @('fingerprint', 'questionFingerprint', 'pageContextFingerprint')) {
            $canonicalField = if ($field -eq 'fingerprint') { 'inputFingerprint' } else { $field }
            if ((Get-PropertyValue $replayInput $field) -ne (Get-PropertyValue $canonical $canonicalField)) {
                throw "$($bundle.name) Trace replay canonical input mismatch: $field"
            }
        }
    }
    foreach ($requiredPath in @('components', 'skill', 'retrieval', 'input', 'failureType', 'runMode')) {
        if ($null -eq (Get-PropertyValue $baselineReplay $requiredPath) -or $null -eq (Get-PropertyValue $candidateReplay $requiredPath)) {
            throw "Trace replay is missing required field: $requiredPath"
        }
    }

    $startedAt = [DateTimeOffset]::UtcNow.ToString('o')
    $diff = Get-ReplayDiff -Baseline $baselineReplay -Candidate $candidateReplay
    $changed = @($diff | Where-Object changed -eq 'true')
    $expectationFailures = @(Get-ExpectationFailures -Sample $sample -Baseline $baselineReplay -Candidate $candidateReplay -ChangedCount $changed.Count)
    $comparisonStatus = if ($expectationFailures.Count -eq 0) { 'COMPARED' } else { 'FAILED_EXPECTATION' }
    $evidenceLevel = if ($baselineManifest.evidenceLevel -eq 'REAL_TRACE' -and $candidateManifest.evidenceLevel -eq 'REAL_TRACE') {
        'REAL_TRACE'
    } elseif ($baselineManifest.sourceMode -eq 'api' -or $candidateManifest.sourceMode -eq 'api') {
        'API_UNVERIFIED'
    } else {
        'OFFLINE_UNVERIFIED'
    }
    $runDirectory = Get-NewRunDirectory
    $comparison = [ordered]@{
        schemaVersion = 1; kind = 'agent-trace-replay-comparison'; sampleId = $sample.sampleId
        invariantFingerprint = $baselineManifest.invariantFingerprint
        baseline = $baselineReplay; candidate = $candidateReplay; differences = $changed
    }
    $manifest = [ordered]@{
        schemaVersion = 1; kind = 'trace-replay-compare'; runId = $RunId; sampleId = $sample.sampleId
        baselineCommit = $baselineManifest.subjectCommit; candidateCommit = $candidateManifest.subjectCommit
        harnessCommit = $baselineManifest.harnessCommit; datasetCommit = $baselineManifest.datasetCommit
        inputFingerprint = $baselineManifest.inputFingerprint; dataFingerprint = $baselineManifest.dataFingerprint
        environmentFingerprint = $baselineManifest.environmentFingerprint; invariantFingerprint = $baselineManifest.invariantFingerprint
        evidenceLevel = $evidenceLevel; changedFieldCount = $changed.Count; status = $comparisonStatus
        startedAt = $startedAt; endedAt = [DateTimeOffset]::UtcNow.ToString('o')
    }
    $comparisonReplayPath = Join-Path $runDirectory 'replay.json'
    Write-JsonFile -Path $comparisonReplayPath -Value $comparison
    $manifest['replayFileSha256'] = (Get-FileHash -LiteralPath $comparisonReplayPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-JsonFile -Path (Join-Path $runDirectory 'manifest.json') -Value $manifest
    $diff | Export-Csv -LiteralPath (Join-Path $runDirectory 'diff.csv') -NoTypeInformation -Encoding UTF8

    $summaryLines = [System.Collections.Generic.List[string]]::new()
    $summaryLines.Add(((Get-Utf8Text 'IyBUcmFjZSDliY3lkI7lm57mlL7vvJp7MH0=') -f $sample.sampleId))
    $summaryLines.Add('')
    $summaryLines.Add(((Get-Utf8Text 'LSBCYXNlbGluZe+8mmB7MH1g') -f $baselineManifest.subjectCommit))
    $summaryLines.Add(((Get-Utf8Text 'LSBDYW5kaWRhdGXvvJpgezB9YA==') -f $candidateManifest.subjectCommit))
    $summaryLines.Add(((Get-Utf8Text 'LSDnu5PmnpzmgKfotKjvvJpgezB9YA==') -f $evidenceLevel))
    $summaryLines.Add(((Get-Utf8Text 'LSDkuI3lj5jph4/vvJrnm7jlkIzovpPlhaXjgIHmlbDmja7lv6vnhafkuI7njq/looPvvIhgezB9YO+8iQ==') -f $baselineManifest.invariantFingerprint))
    $summaryLines.Add('')
    $summaryLines.Add((Get-Utf8Text 'IyMg5qC55Zug'))
    $summaryLines.Add('')
    $summaryLines.Add([string]$sample.rootCause)
    $summaryLines.Add('')
    $summaryLines.Add((Get-Utf8Text 'IyMg5Y2V5LiA5Li76KaB5pS55Yqo'))
    $summaryLines.Add('')
    $summaryLines.Add([string]$sample.primaryChange)
    $summaryLines.Add('')
    $summaryLines.Add((Get-Utf8Text 'IyMg6KeC5a+f5Yiw55qE5Zu65a6a5a2X5q615Y+Y5YyW'))
    $summaryLines.Add('')
    if ($changed.Count -eq 0) { $summaryLines.Add((Get-Utf8Text 'LSDml6DjgII=')) }
    foreach ($row in $changed | Select-Object -First 20) {
        $summaryLines.Add(('- `{0}`: `{1}` => `{2}`' -f $row.path, $row.baseline, $row.candidate))
    }
    $summaryLines.Add('')
    $summaryLines.Add((Get-Utf8Text 'IyMg5Zue5b2S5rWL6K+V5byV55So'))
    $summaryLines.Add('')
    foreach ($test in @($sample.regressionTests)) { $summaryLines.Add(('- `{0}`' -f $test)) }
    if ($evidenceLevel -eq 'OFFLINE_UNVERIFIED') {
        $summaryLines.Add('')
        $summaryLines.Add((Get-Utf8Text 'PiDlvZPliY3ovpPlhaXmnaXoh6rooqvlv73nlaXnmoTnprvnur/mlofku7bvvIzlj6rog73pqozor4HohLHmlY/jgIHlm7rlrprlm57mlL7lkIjlkIzkuI7noa7lrprmgKflm57lvZLvvIzkuI3kvZzkuLrnnJ/lrp4gVHJhY2Ug6L+Q6KGM6K+B5o2u44CC'))
    } elseif ($evidenceLevel -eq 'API_UNVERIFIED') {
        $summaryLines.Add('')
        $summaryLines.Add((Get-Utf8Text 'PiDlvZPliY3ovpPlhaXmnaXoh6rmnKzlnLAgVHJhY2UgQVBJ77yM5L2G57y65bCR54us56uLIHJ1bnRpbWUgbWFuaWZlc3Qg5a+55omn6KGM5Yi25ZOB44CB5pWw5o2u5ZKM546v5aKD55qE57uR5a6a77yM5Y+q6IO95L2c5Li6IEFQSV9VTlZFUklGSUVEIOe7k+aenO+8jOS4jeS9nOS4uuecn+WunuWJjeWQjiBUcmFjZSDor4Hmja7jgII='))
    }
    $summaryLines | Set-Content -LiteralPath (Join-Path $runDirectory 'summary.md') -Encoding UTF8
    $failureLines = [System.Collections.Generic.List[string]]::new()
    $failureLines.Add((Get-Utf8Text 'IyDlm57mlL7lrozmlbTmgKfpl67popg='))
    $failureLines.Add('')
    if ($expectationFailures.Count -eq 0) {
        $failureLines.Add('NONE')
    } else {
        foreach ($failure in $expectationFailures) { $failureLines.Add("- $failure") }
    }
    $failureLines | Set-Content -LiteralPath (Join-Path $runDirectory 'failures.md') -Encoding UTF8
    Write-Output ([ordered]@{ runId = $RunId; runDirectory = $runDirectory; status = $comparisonStatus; evidenceLevel = $evidenceLevel } | ConvertTo-Json)
    if ($expectationFailures.Count -gt 0) { throw "Trace replay expectations failed: $($expectationFailures.Count)" }
}

if ($Action -eq 'Export') { Invoke-Export } else { Invoke-Compare }

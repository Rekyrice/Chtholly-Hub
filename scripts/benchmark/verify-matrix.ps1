[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ResultsRoot,

    [Parameter(Mandatory = $true)]
    [string[]]$RunIds,

    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$resolvedResultsRoot = (Resolve-Path -LiteralPath $ResultsRoot).Path
$allowedPrefix = $resolvedResultsRoot + [IO.Path]::DirectorySeparatorChar
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $resolvedResultsRoot 'cache-standard-matrix.json'
}
$resolvedOutputPath = [IO.Path]::GetFullPath($OutputPath)
if (-not $resolvedOutputPath.StartsWith($allowedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Matrix output must stay under the benchmark results root: $resolvedOutputPath"
}

function Read-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing matrix input: $Path"
    }
    try {
        return Get-Content -Raw -LiteralPath $Path -Encoding UTF8 | ConvertFrom-Json
    }
    catch {
        throw "Invalid JSON matrix input: $Path"
    }
}

function Assert-Value {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Get-RequiredText {
    param([object]$Object, [string]$Property, [string]$Source)
    $member = $Object.PSObject.Properties[$Property]
    if ($null -eq $member -or [string]::IsNullOrWhiteSpace([string]$member.Value)) {
        throw "$Source is missing $Property"
    }
    return [string]$member.Value
}

function Get-WorkloadIdentity {
    param([object]$Workload, [string]$Source)
    if ($null -eq $Workload) { throw "$Source is missing workload" }
    $values = foreach ($property in @('seed', 'concurrency', 'warmupSeconds', 'durationSeconds')) {
        $member = $Workload.PSObject.Properties[$property]
        if ($null -eq $member -or $null -eq $member.Value) { throw "$Source workload is missing $property" }
        [string]$member.Value
    }
    return $values -join '|'
}

function Assert-Metric {
    param([object]$Metrics, [string]$Property, [string]$RunId)
    $member = if ($null -ne $Metrics) { $Metrics.PSObject.Properties[$Property] } else { $null }
    if ($null -eq $member -or $null -eq $member.Value -or $member.Value -is [bool]) {
        throw "Run $RunId is missing numeric metric $Property"
    }
    $parsed = 0.0
    if (-not [double]::TryParse([string]$member.Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
        throw "Run $RunId has non-numeric metric $Property"
    }
}

$requestedRunIds = @($RunIds | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
Assert-Value -Condition ($requestedRunIds.Count -eq 12) -Message 'The formal cache matrix requires exactly 12 run IDs'
Assert-Value -Condition (@($requestedRunIds | Sort-Object -Unique).Count -eq 12) -Message 'The formal cache matrix cannot contain duplicate run IDs'

$expectedCells = @{}
foreach ($repetition in 1..3) {
    foreach ($cell in @(
        @{ scenario = 'stable-hot'; variant = 'db-only' },
        @{ scenario = 'stable-hot'; variant = 'full' },
        @{ scenario = 'expiry-spike'; variant = 'full-no-singleflight' },
        @{ scenario = 'expiry-spike'; variant = 'full' }
    )) {
        $expectedCells["$($cell.scenario)|$($cell.variant)|$repetition"] = $true
    }
}

$seenCells = @{}
$seenEnvironmentRuns = @{}
$seenProjects = @{}
$baseline = $null
$rows = [System.Collections.Generic.List[object]]::new()

foreach ($runId in $requestedRunIds) {
    Assert-Value -Condition ($runId -match '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$') -Message "Unsafe matrix run ID: $runId"
    $runDirectory = Join-Path $resolvedResultsRoot $runId
    $manifest = Read-JsonFile -Path (Join-Path $runDirectory 'manifest.json')
    $summary = Read-JsonFile -Path (Join-Path $runDirectory 'summary.json')
    $environment = Read-JsonFile -Path (Join-Path $runDirectory 'environment.json')

    Assert-Value -Condition ($manifest.runId -eq $runId -and $summary.runId -eq $runId) -Message "Run identity mismatch for $runId"
    Assert-Value -Condition ($manifest.profile -eq 'standard' -and $summary.profile -eq 'standard') -Message "Run $runId is not a standard profile"
    Assert-Value -Condition ($manifest.status -eq 'COMPLETED' -and $summary.status -eq 'COMPLETED') -Message "Run $runId is not complete"
    foreach ($property in @('scenario', 'variant', 'repetition', 'subjectCommit', 'harnessCommit', 'datasetCommit')) {
        Assert-Value -Condition ($manifest.$property -eq $summary.$property) -Message "Run $runId has inconsistent $property"
    }

    $cellKey = "$($manifest.scenario)|$($manifest.variant)|$($manifest.repetition)"
    Assert-Value -Condition $expectedCells.ContainsKey($cellKey) -Message "Unexpected formal cache cell: $cellKey"
    Assert-Value -Condition (-not $seenCells.ContainsKey($cellKey)) -Message "Duplicate formal cache cell: $cellKey"
    $seenCells[$cellKey] = $true

    Assert-Value -Condition ($manifest.effectiveReadMode -eq $manifest.variant) -Message "Run $runId did not execute the requested cache mode"
    Assert-Value -Condition ($manifest.cacheMetricsAvailable -eq $true) -Message "Run $runId did not expose required cache metrics"
    $expectedSingleFlight = $manifest.variant -eq 'full'
    Assert-Value -Condition ($manifest.singleFlightEnabled -eq $expectedSingleFlight) -Message "Run $runId has an invalid SingleFlight state"
    if ($manifest.scenario -eq 'expiry-spike') {
        Assert-Value -Condition ($manifest.coldStartVerified -eq $true) -Message "Run $runId did not verify a cold start"
        Assert-Value -Condition (-not [string]::IsNullOrWhiteSpace([string]$manifest.cacheInvalidatedAt)) -Message "Run $runId did not record cache invalidation"
    }

    foreach ($metric in @('p95Ms', 'errorRate', 'mysqlQueryCount', 'sameKeyLoadCount')) {
        Assert-Metric -Metrics $summary.metrics -Property $metric -RunId $runId
    }

    $environmentRunId = Get-RequiredText -Object $environment -Property 'environmentRunId' -Source "Run $runId environment"
    Assert-Value -Condition ($environmentRunId -match '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$') -Message "Run $runId has an unsafe environment run ID"
    Assert-Value -Condition (-not $seenEnvironmentRuns.ContainsKey($environmentRunId)) -Message "Environment $environmentRunId is reused by multiple formal runs"
    $seenEnvironmentRuns[$environmentRunId] = $true
    $runtime = Read-JsonFile -Path (Join-Path (Join-Path $resolvedResultsRoot $environmentRunId) 'environment-runtime.json')
    Assert-Value -Condition ($runtime.runId -eq $environmentRunId) -Message "Runtime identity mismatch for $runId"
    foreach ($property in @('profile', 'scenario', 'variant', 'executionCommit')) {
        Assert-Value -Condition ($runtime.$property -eq $manifest.$property) -Message "Run $runId runtime has inconsistent $property"
    }
    $projectName = Get-RequiredText -Object $runtime -Property 'projectName' -Source "Run $runId runtime"
    Assert-Value -Condition ($projectName -eq $manifest.environmentId) -Message "Run $runId project identity does not match its manifest"
    Assert-Value -Condition (-not $seenProjects.ContainsKey($projectName)) -Message "Project $projectName is reused by multiple formal runs"
    $seenProjects[$projectName] = $true

    $imageIds = [ordered]@{}
    foreach ($service in @('mysql', 'redis', 'server')) {
        $imageIds[$service] = Get-RequiredText -Object $runtime.imageIds -Property $service -Source "Run $runId image identity"
    }
    $workloadIdentity = Get-WorkloadIdentity -Workload $manifest.workload -Source "Run $runId manifest"
    if ($null -eq $baseline) {
        $baseline = [ordered]@{
            subjectCommit = Get-RequiredText -Object $manifest -Property 'subjectCommit' -Source "Run $runId manifest"
            executionCommit = Get-RequiredText -Object $manifest -Property 'executionCommit' -Source "Run $runId manifest"
            harnessCommit = Get-RequiredText -Object $manifest -Property 'harnessCommit' -Source "Run $runId manifest"
            datasetCommit = Get-RequiredText -Object $manifest -Property 'datasetCommit' -Source "Run $runId manifest"
            workloadIdentity = $workloadIdentity
            workload = $manifest.workload
            imageIds = $imageIds
        }
    }
    else {
        foreach ($property in @('subjectCommit', 'executionCommit', 'harnessCommit', 'datasetCommit')) {
            Assert-Value -Condition ($manifest.$property -eq $baseline[$property]) -Message "Run $runId uses a different $property"
        }
        Assert-Value -Condition ($workloadIdentity -eq $baseline.workloadIdentity) -Message "Run $runId uses a different workload"
        foreach ($service in @('mysql', 'redis', 'server')) {
            Assert-Value -Condition ($imageIds[$service] -eq $baseline.imageIds[$service]) -Message "Run $runId uses a different $service image"
        }
    }

    $rows.Add([ordered]@{
        runId = $runId
        scenario = [string]$manifest.scenario
        variant = [string]$manifest.variant
        repetition = [int]$manifest.repetition
        environmentRunId = $environmentRunId
        environmentId = [string]$manifest.environmentId
        metrics = $summary.metrics
    })
}

foreach ($cellKey in $expectedCells.Keys) {
    Assert-Value -Condition $seenCells.ContainsKey($cellKey) -Message "Missing formal cache cell: $cellKey"
}

$matrix = [ordered]@{
    schemaVersion = 1
    status = 'COMPLETE'
    verifiedAt = [DateTimeOffset]::UtcNow.ToString('o')
    runCount = $rows.Count
    subjectCommit = $baseline.subjectCommit
    executionCommit = $baseline.executionCommit
    harnessCommit = $baseline.harnessCommit
    datasetCommit = $baseline.datasetCommit
    workload = $baseline.workload
    imageIds = $baseline.imageIds
    runs = @($rows | Sort-Object scenario, variant, repetition)
}
$matrix | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedOutputPath -Encoding UTF8
Write-Output "Verified formal cache matrix: $resolvedOutputPath"

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RunDirectory,

    [string]$ArchiveDirectory
)

$ErrorActionPreference = 'Stop'
$resolvedRunDirectory = (Resolve-Path -LiteralPath $RunDirectory).Path
$manifestPath = Join-Path $resolvedRunDirectory 'manifest.json'
$environmentPath = Join-Path $resolvedRunDirectory 'environment.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Missing manifest: $manifestPath"
}
if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf)) {
    throw "Missing environment snapshot: $environmentPath"
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
$k6Path = Join-Path $resolvedRunDirectory 'raw/k6.json'
$metrics = [ordered]@{}
if (Test-Path -LiteralPath $k6Path -PathType Leaf) {
    $k6 = Get-Content -Raw -LiteralPath $k6Path -Encoding UTF8 | ConvertFrom-Json
    $durationValues = $k6.metrics.http_req_duration.values
    $metrics = [ordered]@{
        requests = $k6.metrics.http_reqs.values.count
        requestRate = $k6.metrics.http_reqs.values.rate
        errorRate = $k6.metrics.http_req_failed.values.rate
        checkRate = $k6.metrics.checks.values.rate
        p50Ms = $durationValues.med
        p95Ms = $durationValues.'p(95)'
        p99Ms = $durationValues.'p(99)'
    }
}

$summary = [ordered]@{
    schemaVersion = 1
    runId = $manifest.runId
    status = $manifest.status
    numberKind = $manifest.numberKind
    profile = $manifest.profile
    scenario = $manifest.scenario
    variant = $manifest.variant
    subjectCommit = $manifest.subjectCommit
    harnessCommit = $manifest.harnessCommit
    datasetCommit = $manifest.datasetCommit
    dirty = $manifest.dirty
    metrics = $metrics
    conclusion = if ($manifest.status -eq 'COMPLETED') { 'COLLECTED_UNREVIEWED' } else { $manifest.status }
}
$summaryPath = Join-Path $resolvedRunDirectory 'summary.json'
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8

$summaryMarkdown = @(
    "# Benchmark run $($manifest.runId)",
    '',
    "- Status: $($manifest.status)",
    "- Profile: $($manifest.profile)",
    "- Scenario: $($manifest.scenario)",
    "- Variant: $($manifest.variant)",
    "- Subject commit: $($manifest.subjectCommit)",
    "- Harness commit: $($manifest.harnessCommit)",
    "- Dataset commit: $($manifest.datasetCommit)",
    "- Dirty: $($manifest.dirty)",
    "- Evidence state: $($summary.conclusion)",
    '',
    'This summary is deterministic output from the recorded manifest and raw files. It does not upgrade the run to REPRODUCED.'
)
$summaryMarkdown | Set-Content -LiteralPath (Join-Path $resolvedRunDirectory 'summary.md') -Encoding utf8

$failureLines = [System.Collections.Generic.List[string]]::new()
if ($manifest.status -ne 'COMPLETED') {
    $failureLines.Add("Run status is $($manifest.status).")
}
if ($manifest.dirty) {
    $failureLines.Add('The subject worktree was dirty; this run is diagnostic only.')
}
if ($failureLines.Count -eq 0) {
    $failureLines.Add('No harness-level failure was recorded. Application-level failures remain subject to raw result review.')
}
$failureLines | Set-Content -LiteralPath (Join-Path $resolvedRunDirectory 'failures.md') -Encoding utf8

$checksumPath = Join-Path $resolvedRunDirectory 'checksums.sha256'
$checksumLines = Get-ChildItem -LiteralPath $resolvedRunDirectory -Recurse -File |
    Where-Object { $_.FullName -ne $checksumPath } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($resolvedRunDirectory.Length).TrimStart('\', '/').Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
$checksumLines | Set-Content -LiteralPath $checksumPath -Encoding ascii

if (-not [string]::IsNullOrWhiteSpace($ArchiveDirectory)) {
    New-Item -ItemType Directory -Path $ArchiveDirectory -Force | Out-Null
    $archivePath = Join-Path (Resolve-Path -LiteralPath $ArchiveDirectory).Path "$($manifest.runId).zip"
    if (Test-Path -LiteralPath $archivePath) {
        throw "Archive already exists: $archivePath"
    }
    Compress-Archive -Path (Join-Path $resolvedRunDirectory '*') -DestinationPath $archivePath -CompressionLevel Optimal
    $archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "Archive: $archivePath"
    Write-Output "SHA-256: $archiveHash"
}

Write-Output "Summary: $summaryPath"

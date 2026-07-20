[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RunDirectory
)

$ErrorActionPreference = 'Stop'
$resolvedRunDirectory = (Resolve-Path -LiteralPath $RunDirectory).Path
$manifestPath = Join-Path $resolvedRunDirectory 'manifest.json'
$environmentPath = Join-Path $resolvedRunDirectory 'environment.json'
foreach ($path in @($manifestPath, $environmentPath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing benchmark input: $path"
    }
}

function Get-MetricValues {
    param([object]$Metric)
    if ($null -eq $Metric) { return $null }
    if ($null -ne $Metric.PSObject.Properties['values']) { return $Metric.values }
    return $Metric
}

$manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
$k6Path = Join-Path $resolvedRunDirectory 'raw/k6.json'
$applicationMetricsPath = Join-Path $resolvedRunDirectory 'raw/application-metrics.json'
$p95Ms = $null
$errorRate = $null
$mysqlQueryCount = $null
$sameKeyLoadCount = $null

if (Test-Path -LiteralPath $k6Path -PathType Leaf) {
    $k6 = Get-Content -Raw -LiteralPath $k6Path -Encoding UTF8 | ConvertFrom-Json
    $duration = Get-MetricValues -Metric $k6.metrics.http_req_duration
    $failures = Get-MetricValues -Metric $k6.metrics.http_req_failed
    if ($null -ne $duration) { $p95Ms = $duration.'p(95)' }
    if ($null -ne $failures) {
        if ($null -ne $failures.PSObject.Properties['rate']) { $errorRate = $failures.rate }
        elseif ($null -ne $failures.PSObject.Properties['value']) { $errorRate = $failures.value }
    }
}

if (Test-Path -LiteralPath $applicationMetricsPath -PathType Leaf) {
    $applicationMetrics = Get-Content -Raw -LiteralPath $applicationMetricsPath -Encoding UTF8 | ConvertFrom-Json
    $mysqlQueryCount = $applicationMetrics.mysqlQueryCount
    $sameKeyLoadCount = $applicationMetrics.sameKeyLoadCount
}

$summaryStatus = $manifest.status
if ($manifest.status -eq 'COMPLETED' -and
        ($null -eq $p95Ms -or $null -eq $errorRate -or $null -eq $mysqlQueryCount -or $null -eq $sameKeyLoadCount)) {
    $summaryStatus = 'INCOMPLETE'
}

$summary = [ordered]@{
    schemaVersion = 1
    runId = $manifest.runId
    status = $summaryStatus
    profile = $manifest.profile
    scenario = $manifest.scenario
    variant = $manifest.variant
    repetition = $manifest.repetition
    subjectCommit = $manifest.subjectCommit
    executionCommit = $manifest.executionCommit
    harnessCommit = $manifest.harnessCommit
    datasetCommit = $manifest.datasetCommit
    environmentId = $manifest.environmentId
    metrics = [ordered]@{
        p95Ms = $p95Ms
        errorRate = $errorRate
        mysqlQueryCount = $mysqlQueryCount
        sameKeyLoadCount = $sameKeyLoadCount
    }
}
$summaryPath = Join-Path $resolvedRunDirectory 'summary.json'
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

@(
    "# Cache benchmark $($manifest.runId)",
    '',
    "- Status: $summaryStatus",
    "- Scenario: $($manifest.scenario)",
    "- Variant: $($manifest.variant)",
    "- Repetition: $($manifest.repetition)",
    "- Subject commit: $($manifest.subjectCommit)",
    "- Harness commit: $($manifest.harnessCommit)",
    "- Dataset commit: $($manifest.datasetCommit)",
    '',
    '| Metric | Value |',
    '|---|---:|',
    "| p95Ms | $p95Ms |",
    "| errorRate | $errorRate |",
    "| mysqlQueryCount | $mysqlQueryCount |",
    "| sameKeyLoadCount | $sameKeyLoadCount |"
) | Set-Content -LiteralPath (Join-Path $resolvedRunDirectory 'summary.md') -Encoding UTF8

$failureLines = [System.Collections.Generic.List[string]]::new()
if ($summaryStatus -eq 'INCOMPLETE') {
    $failureLines.Add('The run is missing one or more required raw metrics; no comparison is allowed.')
}
elseif ($summaryStatus -notin @('COMPLETED', 'VALIDATED')) {
    $failureLines.Add("The run status is $summaryStatus.")
}
else {
    $failureLines.Add('No harness-level failure was recorded.')
}
$failureLines | Set-Content -LiteralPath (Join-Path $resolvedRunDirectory 'failures.md') -Encoding UTF8

Write-Output "Summary: $summaryPath"

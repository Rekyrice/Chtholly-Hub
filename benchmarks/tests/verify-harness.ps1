[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Condition) {
        $failures.Add($Message)
    }
}

function Assert-File {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $path = Join-Path $repoRoot $RelativePath
    Assert-True -Condition (Test-Path -LiteralPath $path -PathType Leaf) -Message "Missing file: $RelativePath"
}

Push-Location $repoRoot
try {
    git check-ignore --quiet .benchmark-results/probe
    Assert-True -Condition ($LASTEXITCODE -eq 0) -Message '.benchmark-results/ must be ignored by Git'

    $trackedResults = @(git ls-files '.benchmark-results/**')
    Assert-True -Condition ($trackedResults.Count -eq 0) -Message '.benchmark-results/ must not contain tracked files'

    $requiredFiles = @(
        'benchmarks/README.md',
        'benchmarks/config/standard.yml',
        'benchmarks/k6/backend-scenarios.js',
        'benchmarks/schema/manifest.schema.json',
        'scripts/benchmark/run.ps1',
        'scripts/benchmark/summarize.ps1',
        'docs/benchmarks/evidence-index.yml'
    )
    foreach ($relativePath in $requiredFiles) {
        Assert-File -RelativePath $relativePath
    }

    $schemaPath = Join-Path $repoRoot 'benchmarks/schema/manifest.schema.json'
    if (Test-Path -LiteralPath $schemaPath -PathType Leaf) {
        $schema = Get-Content -Raw -LiteralPath $schemaPath -Encoding UTF8 | ConvertFrom-Json
        foreach ($requiredProperty in @('subjectCommit', 'harnessCommit', 'datasetCommit', 'profile', 'scenario', 'runId', 'status')) {
            Assert-True -Condition ($schema.required -contains $requiredProperty) -Message "Manifest schema must require $requiredProperty"
        }
    }

    $configPath = Join-Path $repoRoot 'benchmarks/config/standard.yml'
    if (Test-Path -LiteralPath $configPath -PathType Leaf) {
        $config = Get-Content -Raw -LiteralPath $configPath -Encoding UTF8
        foreach ($token in @('smoke:', 'standard:', 'concurrency:', 'warmupSeconds:', 'durationSeconds:', 'seed:')) {
            Assert-True -Condition $config.Contains($token) -Message "Workload config must contain $token"
        }
    }

    $k6Path = Join-Path $repoRoot 'benchmarks/k6/backend-scenarios.js'
    if (Test-Path -LiteralPath $k6Path -PathType Leaf) {
        & node --check $k6Path
        Assert-True -Condition ($LASTEXITCODE -eq 0) -Message 'k6 scenario must be valid JavaScript syntax'
    }

    $runScript = Join-Path $repoRoot 'scripts/benchmark/run.ps1'
    $summarizeScript = Join-Path $repoRoot 'scripts/benchmark/summarize.ps1'
    if ((Test-Path -LiteralPath $runScript -PathType Leaf) -and (Test-Path -LiteralPath $summarizeScript -PathType Leaf)) {
        $head = (git rev-parse HEAD).Trim()
        $runId = 'harness-contract-test'
        $runDirectory = Join-Path $repoRoot ".benchmark-results/$runId"

        & $runScript -Profile smoke -Scenario all -RunId $runId -SubjectCommit $head -HarnessCommit $head -DatasetCommit $head -ValidateOnly
        Assert-True -Condition ($LASTEXITCODE -eq 0) -Message 'run.ps1 validation mode must succeed without starting services'

        $manifestPath = Join-Path $runDirectory 'manifest.json'
        $environmentPath = Join-Path $runDirectory 'environment.json'
        Assert-True -Condition (Test-Path -LiteralPath $manifestPath -PathType Leaf) -Message 'Validation run must write manifest.json'
        Assert-True -Condition (Test-Path -LiteralPath $environmentPath -PathType Leaf) -Message 'Validation run must write environment.json'

        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            $manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
            Assert-True -Condition ($manifest.status -eq 'VALIDATED') -Message 'Validation manifest status must be VALIDATED'
            Assert-True -Condition ($manifest.numberKind -eq 'CONFIG') -Message 'Validation manifest numberKind must be CONFIG'
        }

        & $summarizeScript -RunDirectory $runDirectory
        Assert-True -Condition ($LASTEXITCODE -eq 0) -Message 'summarize.ps1 must accept a validation run'
        foreach ($name in @('summary.json', 'summary.md', 'failures.md', 'checksums.sha256')) {
            Assert-True -Condition (Test-Path -LiteralPath (Join-Path $runDirectory $name) -PathType Leaf) -Message "Summarizer must create $name"
        }

        $archiveDirectory = Join-Path $repoRoot '.benchmark-results/harness-contract-archive'
        & $summarizeScript -RunDirectory $runDirectory -ArchiveDirectory $archiveDirectory
        $archivePath = Join-Path $archiveDirectory "$runId.zip"
        Assert-True -Condition (Test-Path -LiteralPath $archivePath -PathType Leaf) -Message 'Summarizer must create an external evidence archive'
        if (Test-Path -LiteralPath $archivePath -PathType Leaf) {
            Assert-True -Condition ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.Length -eq 64) -Message 'Evidence archive must have a SHA-256 hash'
        }

        if (Test-Path -LiteralPath $runDirectory -PathType Container) {
            $resolvedRunDirectory = (Resolve-Path -LiteralPath $runDirectory).Path
            $resultsRoot = (Resolve-Path -LiteralPath (Join-Path $repoRoot '.benchmark-results')).Path + [System.IO.Path]::DirectorySeparatorChar
            if (-not $resolvedRunDirectory.StartsWith($resultsRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "Unsafe cleanup target: $resolvedRunDirectory"
            }
            Remove-Item -LiteralPath $resolvedRunDirectory -Recurse -Force
        }
        if (Test-Path -LiteralPath $archiveDirectory -PathType Container) {
            $resolvedArchiveDirectory = (Resolve-Path -LiteralPath $archiveDirectory).Path
            $resultsRoot = (Resolve-Path -LiteralPath (Join-Path $repoRoot '.benchmark-results')).Path + [System.IO.Path]::DirectorySeparatorChar
            if (-not $resolvedArchiveDirectory.StartsWith($resultsRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
                throw "Unsafe cleanup target: $resolvedArchiveDirectory"
            }
            Remove-Item -LiteralPath $resolvedArchiveDirectory -Recurse -Force
        }
    }
}
finally {
    Pop-Location
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output 'Benchmark harness contract verified.'

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
        'scripts/benchmark/seed.ps1',
        'scripts/benchmark/summarize.ps1',
        'docs/benchmarks/evidence-index.yml'
    )
    foreach ($relativePath in $requiredFiles) {
        Assert-File -RelativePath $relativePath
    }

    $schemaPath = Join-Path $repoRoot 'benchmarks/schema/manifest.schema.json'
    if (Test-Path -LiteralPath $schemaPath -PathType Leaf) {
        $schema = Get-Content -Raw -LiteralPath $schemaPath -Encoding UTF8 | ConvertFrom-Json
        foreach ($requiredProperty in @('subjectCommit', 'harnessCommit', 'datasetCommit', 'environmentId', 'repetition', 'experiment', 'profile', 'scenario', 'runId', 'status')) {
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
        $k6Source = Get-Content -Raw -LiteralPath $k6Path -Encoding UTF8
        Assert-True -Condition $k6Source.Contains('920000000000000001') -Message 'k6 default post IDs must match the deterministic seed range'
        Assert-True -Condition $k6Source.Contains('910000000000000002') -Message 'k6 default user IDs must match the deterministic seed range'
    }

    $runScript = Join-Path $repoRoot 'scripts/benchmark/run.ps1'
    $seedScript = Join-Path $repoRoot 'scripts/benchmark/seed.ps1'
    $summarizeScript = Join-Path $repoRoot 'scripts/benchmark/summarize.ps1'
    if ((Test-Path -LiteralPath $runScript -PathType Leaf) -and (Test-Path -LiteralPath $seedScript -PathType Leaf) -and (Test-Path -LiteralPath $summarizeScript -PathType Leaf)) {
        $seedPlan = & $seedScript -Profile smoke -ValidateOnly | Out-String | ConvertFrom-Json
        Assert-True -Condition ($seedPlan.users -eq 200) -Message 'Smoke seed must declare 200 users'
        Assert-True -Condition ($seedPlan.posts -eq 1000) -Message 'Smoke seed must declare 1000 posts'
        Assert-True -Condition ($seedPlan.interactions -eq 10000) -Message 'Smoke seed must declare 10000 authoritative interaction states'
        Assert-True -Condition ($seedPlan.relations -eq 10000) -Message 'Smoke seed must declare 10000 relations'

        $head = (git rev-parse HEAD).Trim()
        $runId = 'harness-contract-test'
        $runDirectory = Join-Path $repoRoot ".benchmark-results/$runId"

        & $runScript -Profile smoke -Scenario all -RunId $runId -SubjectCommit $head -HarnessCommit $head -DatasetCommit $head -Repetition 1 -ValidateOnly
        Assert-True -Condition ($LASTEXITCODE -eq 0) -Message 'run.ps1 validation mode must succeed without starting services'

        $manifestPath = Join-Path $runDirectory 'manifest.json'
        $environmentPath = Join-Path $runDirectory 'environment.json'
        Assert-True -Condition (Test-Path -LiteralPath $manifestPath -PathType Leaf) -Message 'Validation run must write manifest.json'
        Assert-True -Condition (Test-Path -LiteralPath $environmentPath -PathType Leaf) -Message 'Validation run must write environment.json'

        if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
            $manifest = Get-Content -Raw -LiteralPath $manifestPath -Encoding UTF8 | ConvertFrom-Json
            Assert-True -Condition ($manifest.status -eq 'VALIDATED') -Message 'Validation manifest status must be VALIDATED'
            Assert-True -Condition ($manifest.numberKind -eq 'CONFIG') -Message 'Validation manifest numberKind must be CONFIG'
            Assert-True -Condition ($manifest.repetition -eq 1) -Message 'Validation manifest must record the repetition'
            Assert-True -Condition (-not [string]::IsNullOrWhiteSpace($manifest.environmentId)) -Message 'Validation manifest must record an environmentId'
            Assert-True -Condition ($null -ne $manifest.experiment.hardFail) -Message 'Validation manifest must preregister hard failures'
        }

        $runSource = Get-Content -Raw -LiteralPath $runScript -Encoding UTF8
        Assert-True -Condition (-not $runSource.Contains('--untracked-files=no')) -Message 'Dirty detection must include untracked files'
        Assert-True -Condition $runSource.Contains('BENCHMARK_POST_IDS') -Message 'Runner must pass deterministic post IDs to k6'
        Assert-True -Condition $runSource.Contains('BENCHMARK_USER_IDS') -Message 'Runner must pass deterministic user IDs to k6'
        Assert-True -Condition $runSource.Contains('warmup-k6.log') -Message 'Runner must execute and retain an independent warmup phase'

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

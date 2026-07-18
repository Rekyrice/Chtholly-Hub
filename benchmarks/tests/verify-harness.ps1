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
        'scripts/benchmark/environment.ps1',
        'scripts/benchmark/new-benchmark-token.ps1',
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
        foreach ($requiredProperty in @('subjectCommit', 'executionCommit', 'harnessCommit', 'datasetCommit', 'environmentId', 'repetition', 'experiment', 'profile', 'scenario', 'runId', 'status')) {
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
    $environmentScript = Join-Path $repoRoot 'scripts/benchmark/environment.ps1'
    $tokenScript = Join-Path $repoRoot 'scripts/benchmark/new-benchmark-token.ps1'
    $seedScript = Join-Path $repoRoot 'scripts/benchmark/seed.ps1'
    $summarizeScript = Join-Path $repoRoot 'scripts/benchmark/summarize.ps1'
    if (Test-Path -LiteralPath $environmentScript -PathType Leaf) {
        $environmentSource = Get-Content -Raw -LiteralPath $environmentScript -Encoding UTF8
        foreach ($token in @('executionCommit', 'executionDirty', 'clean package', "applicationLogLevel = 'WARN'", '/actuator/health/liveness', 'new-benchmark-token.ps1')) {
            Assert-True -Condition $environmentSource.Contains($token) -Message "Benchmark environment must contain $token"
        }
        foreach ($obsoleteToken in @(
                ('Invoke-Counter' + 'RecoveryWarmup'),
                ('Counter' + 'StreamBridge'),
                ('X' + 'ADD'),
                ('X' + 'ACK'),
                ('P' + 'EL'))) {
            Assert-True -Condition (-not $environmentSource.Contains($obsoleteToken)) -Message "Benchmark environment must not retain $obsoleteToken"
        }
        $environmentPlan = & $environmentScript `
            -Action Validate `
            -RunId harness-environment-contract `
            -Profile smoke `
            -Variant full `
            -Port 18888 | Out-String | ConvertFrom-Json
        Assert-True -Condition $environmentPlan.isolated -Message 'Benchmark environment must declare isolated ownership'
        Assert-True -Condition $environmentPlan.removeVolumesOnStop -Message 'Benchmark environment must remove only its owned volumes'
        Assert-True -Condition ($environmentPlan.projectName -match '^chtholly-bm-[a-z0-9-]+-[0-9a-f]{12}$') -Message 'Benchmark project must combine a readable owned prefix with a RunId hash'
        Assert-True -Condition ($environmentPlan.services.Count -eq 5) -Message 'Benchmark environment must reuse exactly five service types'
        Assert-True -Condition ($environmentPlan.services -contains 'server') -Message 'Benchmark environment must include the application server'
        Assert-True -Condition ($environmentPlan.serverRuntime -eq 'compose-container') -Message 'Benchmark server must run in the isolated Compose network'
        Assert-True -Condition ($environmentPlan.k6BaseUrl -eq 'http://server:8888') -Message 'k6 must address the server through the isolated network'
        Assert-True -Condition (-not ($environmentPlan.PSObject.Properties.Name -contains 'password')) -Message 'Validation output must not expose generated credentials'
        $collisionPlan = & $environmentScript `
            -Action Validate `
            -RunId 'harness-environment-contract-shared-prefix-alpha' `
            -Profile smoke `
            -Variant full `
            -Port 18889 | Out-String | ConvertFrom-Json
        $collisionPeerPlan = & $environmentScript `
            -Action Validate `
            -RunId 'harness-environment-contract-shared-prefix-beta' `
            -Profile smoke `
            -Variant full `
            -Port 18890 | Out-String | ConvertFrom-Json
        Assert-True -Condition ($collisionPlan.projectName -ne $collisionPeerPlan.projectName) -Message 'Distinct RunIds must not collide after Compose name truncation'
    }
    if (Test-Path -LiteralPath $tokenScript -PathType Leaf) {
        $tokenPlan = & $tokenScript -UserId 910000000000000001 -ValidateOnly | Out-String | ConvertFrom-Json
        Assert-True -Condition ($tokenPlan.algorithm -eq 'RS256') -Message 'Benchmark token must use the application RS256 contract'
        Assert-True -Condition ($tokenPlan.issuer -eq 'chtholly') -Message 'Benchmark token must use the application issuer'
        Assert-True -Condition ($tokenPlan.userId -eq 910000000000000001) -Message 'Benchmark token must bind the seeded caller'
        Assert-True -Condition (-not ($tokenPlan.PSObject.Properties.Name -contains 'token')) -Message 'Token validation output must not contain a token'
    }
    if ((Test-Path -LiteralPath $runScript -PathType Leaf) -and (Test-Path -LiteralPath $seedScript -PathType Leaf) -and (Test-Path -LiteralPath $summarizeScript -PathType Leaf)) {
        $seedPlan = & $seedScript -Profile smoke -ValidateOnly | Out-String | ConvertFrom-Json
        Assert-True -Condition ($seedPlan.users -eq 200) -Message 'Smoke seed must declare 200 users'
        Assert-True -Condition ($seedPlan.posts -eq 1000) -Message 'Smoke seed must declare 1000 posts'
        Assert-True -Condition ($seedPlan.interactions -eq 10000) -Message 'Smoke seed must declare 10000 authoritative interaction states'
        Assert-True -Condition ($seedPlan.relations -eq 10000) -Message 'Smoke seed must declare 10000 relations'

        $head = (git rev-parse HEAD).Trim()
        $subject = (git rev-parse origin/main).Trim()
        $candidateManifest = Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'benchmarks/datasets/agent-reliability-v3/manifest.json') -Encoding UTF8 | ConvertFrom-Json
        $dataset = [string]$candidateManifest.datasetCommit
        $runId = 'harness-contract-test'
        $runDirectory = Join-Path $repoRoot ".benchmark-results/$runId"

        & $runScript -Profile smoke -Scenario all -RunId $runId -SubjectCommit $subject -HarnessCommit $head -DatasetCommit $dataset -Repetition 1 -ValidateOnly
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
            Assert-True -Condition ($manifest.subjectCommit -eq $subject) -Message 'Manifest must preserve the business subject commit'
            Assert-True -Condition ($manifest.executionCommit -eq $head) -Message 'Manifest must distinguish the checked-out execution commit'
            Assert-True -Condition ($manifest.datasetCommit -eq $dataset) -Message 'Manifest must bind the frozen dataset commit'
            Assert-True -Condition (-not [string]::IsNullOrWhiteSpace($manifest.environmentId)) -Message 'Validation manifest must record an environmentId'
            Assert-True -Condition ($null -ne $manifest.experiment.hardFail) -Message 'Validation manifest must preregister hard failures'
        }

        $immutableRunIdRejected = $false
        try {
            & $runScript -Profile smoke -Scenario all -RunId $runId -SubjectCommit $subject -HarnessCommit $head -DatasetCommit $dataset -Repetition 1 -ValidateOnly
        }
        catch {
            $immutableRunIdRejected = $_.Exception.Message.Contains('already exists')
        }
        Assert-True -Condition $immutableRunIdRejected -Message 'Runner must reject reuse of an existing runId'

        $latestBusinessCommit = (git log -1 --format='%H' $head -- apps/server/src/main).Trim()
        $businessAncestor = (git rev-parse "$latestBusinessCommit^").Trim()
        $businessRunId = 'harness-business-identity-rejection'
        $businessRunDirectory = Join-Path $repoRoot ".benchmark-results/$businessRunId"
        $businessSubjectRejected = $false
        try {
            & $runScript -Profile smoke -Scenario all -RunId $businessRunId -SubjectCommit $businessAncestor -HarnessCommit $head -DatasetCommit $dataset -Repetition 1 -ValidateOnly
        }
        catch {
            $businessSubjectRejected = $_.Exception.Message.Contains('business changes')
        }
        Assert-True -Condition $businessSubjectRejected -Message 'Harness-only subject allowance must reject intervening business changes'
        Assert-True -Condition (-not (Test-Path -LiteralPath $businessRunDirectory)) -Message 'Rejected identity must not create a run directory'

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

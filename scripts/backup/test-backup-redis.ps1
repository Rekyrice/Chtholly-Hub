$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "backup-redis.ps1"
$modulePath = Join-Path $PSScriptRoot "backup-redis-core.psm1"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$externalRoot = "D:\1.hhh\backups\Chtholly-Hub"
$gitCommonDirectory = (& git -C $repoRoot rev-parse --path-format=absolute --git-common-dir).Trim()
if ($LASTEXITCODE -ne 0 -or -not $gitCommonDirectory) {
    throw "cannot resolve the shared Git directory for backup path tests"
}
$sharedRepositoryRoot = [System.IO.Directory]::GetParent(
    [System.IO.Path]::GetFullPath($gitCommonDirectory)
).FullName
$registeredWorktrees = @(
    & git -C $repoRoot worktree list --porcelain |
        Where-Object { $_.StartsWith("worktree ") } |
        ForEach-Object { [System.IO.Path]::GetFullPath($_.Substring(9)) }
)

function Invoke-ValidationCase {
    param(
        [string]$Name,
        [string]$DestinationRoot,
        [string]$Container,
        [int]$ExpectedExitCode,
        [string]$ExpectedMessage
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -DestinationRoot $DestinationRoot -Container $Container -ValidateOnly 2>&1 | Out-String
    $actualExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousPreference
    if ($actualExitCode -ne $ExpectedExitCode) {
        throw "$Name failed: expected exit $ExpectedExitCode, actual $actualExitCode`n$output"
    }
    if ($ExpectedMessage -and $output -notmatch [regex]::Escape($ExpectedMessage)) {
        throw "$Name failed: output did not contain '$ExpectedMessage'`n$output"
    }
    return $output
}

$validOutput = Invoke-ValidationCase "valid external D drive" $externalRoot "redis-local" 0
$validationJson = $validOutput -split "`r?`n" | Where-Object { $_.Trim().StartsWith("{") } | Select-Object -Last 1 | ConvertFrom-Json
if ($validationJson.status -ne "validated" -or $validationJson.container -ne "redis-local") {
    throw "valid case did not return the expected validation metadata: $validOutput"
}

Invoke-ValidationCase "relative destination" "backups" "redis" 1 | Out-Null
Invoke-ValidationCase "C drive destination" "C:\temp\chtholly-backup" "redis" 1 | Out-Null
Invoke-ValidationCase "repository destination" (Join-Path $repoRoot ".codex-tmp\backup") "redis" 1 | Out-Null
Invoke-ValidationCase "shared repository destination" (Join-Path $sharedRepositoryRoot ".codex-tmp\backup") `
    "redis" 1 "repository or registered worktree" | Out-Null
$siblingWorktree = $registeredWorktrees |
    Where-Object { -not $_.Equals($repoRoot, [System.StringComparison]::OrdinalIgnoreCase) } |
    Select-Object -First 1
if ($siblingWorktree) {
    Invoke-ValidationCase "sibling worktree destination" (Join-Path $siblingWorktree ".codex-tmp\backup") `
        "redis" 1 "repository or registered worktree" | Out-Null
}
Invoke-ValidationCase "container injection" $externalRoot "redis;whoami" 1 | Out-Null

$junctionTestRoot = Join-Path $repoRoot ".codex-tmp\backup-redis-junction-$PID-$([Guid]::NewGuid().ToString('N'))"
$junctionTarget = Join-Path $junctionTestRoot "target"
$junctionPath = Join-Path $junctionTestRoot "link"
try {
    New-Item -ItemType Directory -Path $junctionTarget -Force | Out-Null
    New-Item -ItemType Junction -Path $junctionPath -Target $junctionTarget | Out-Null
    Invoke-ValidationCase "junction destination" (Join-Path $junctionPath "backup") "redis" 1 "reparse" | Out-Null
} finally {
    if (Test-Path -LiteralPath $junctionPath) {
        Remove-Item -LiteralPath $junctionPath -Force
    }
    if (Test-Path -LiteralPath $junctionTarget) {
        Remove-Item -LiteralPath $junctionTarget -Force
    }
    if (Test-Path -LiteralPath $junctionTestRoot) {
        Remove-Item -LiteralPath $junctionTestRoot -Force
    }
}

$source = (Get-Content $scriptPath -Raw -Encoding UTF8) + "`n" + (Get-Content $modulePath -Raw -Encoding UTF8)
foreach ($required in @(
        "redis-cli", "--rdb", "docker cp", ".partial", "Get-FileHash", "Move-Item",
        "ConvertTo-Json", "finally", "REDISCLI_AUTH", "ReparsePoint", "Length",
        "git-common-dir", "worktree list --porcelain"
    )) {
    if (-not $source.Contains($required)) {
        throw "backup script is missing required contract token: $required"
    }
}
if ($source.Contains('REDISCLI_AUTH=$env:REDIS_PASSWORD')) {
    throw "Redis password must not be embedded in docker command arguments."
}

Import-Module $modulePath -Force
$coreTestRoot = Join-Path $repoRoot ".codex-tmp\backup-redis-core-$PID-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $coreTestRoot -Force | Out-Null
$hadOriginalRedisCliAuth = Test-Path Env:REDISCLI_AUTH
$originalRedisCliAuth = $env:REDISCLI_AUTH
try {
    $freshFailureRoot = Join-Path $coreTestRoot "created-only-for-failed-run"
    $missingContainerInvoker = {
        param([string[]]$Arguments)
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @("unexpected docker command") }
    }
    $missingContainerFailed = $false
    try {
        Invoke-RedisRdbBackup `
            -SafeDestinationRoot $freshFailureRoot `
            -Container "redis" `
            -RedisPassword "" `
            -DockerInvoker $missingContainerInvoker | Out-Null
    } catch {
        $missingContainerFailed = $true
    }
    if (-not $missingContainerFailed) {
        throw "missing-container case unexpectedly succeeded"
    }
    if (Test-Path -LiteralPath $freshFailureRoot) {
        throw "failed backup left the destination root that it created"
    }

    $collisionNow = [DateTimeOffset]::Parse("2026-07-18T12:32:56.789Z")
    $collisionRunId = [Guid]::Parse("90134567-89ab-cdef-0123-456789abcdef")
    $collisionDirectory = Join-Path $coreTestRoot "20260718T123256789Z-90134567"
    New-Item -ItemType Directory -Path $collisionDirectory | Out-Null
    $runningContainerInvoker = {
        param([string[]]$Arguments)
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("redis") }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @("unexpected docker command") }
    }
    $collisionFailed = $false
    try {
        Invoke-RedisRdbBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "redis" `
            -RedisPassword "" `
            -Now $collisionNow `
            -RunId $collisionRunId `
            -DockerInvoker $runningContainerInvoker | Out-Null
    } catch {
        $collisionFailed = $true
    }
    if (-not $collisionFailed) {
        throw "existing target directory was reused"
    }
    if (-not (Test-Path -LiteralPath $collisionDirectory -PathType Container)) {
        throw "collision handling removed the pre-existing target directory"
    }

    $fakeDockerBin = Join-Path $coreTestRoot "fake-docker-bin"
    $fakeDockerCommand = Join-Path $fakeDockerBin "docker.cmd"
    New-Item -ItemType Directory -Path $fakeDockerBin -Force | Out-Null
    @'
@echo off
if "%~1"=="ps" (
  echo redis
  exit /b 0
)
if "%~1"=="cp" (
  >"%~3" echo RDB
  exit /b 0
)
if "%~1"=="exec" (
  if "%~2"=="--env" (
    if not "%~3"=="REDISCLI_AUTH" exit /b 41
    if not "%REDISCLI_AUTH%"=="contract-secret" exit /b 42
  )
  if "%~3"=="redis-cli" (
    >&2 echo sending REPLCONF capa eof
  )
  if "%~5"=="redis-cli" (
    >&2 echo sending REPLCONF capa eof
  )
  exit /b 0
)
exit /b 99
'@ | Set-Content -LiteralPath $fakeDockerCommand -Encoding ASCII

    $previousPath = $env:PATH
    try {
        $env:PATH = "$fakeDockerBin;$previousPath"
        $env:REDISCLI_AUTH = "preexisting-redis-auth"
        $nativeResult = Invoke-RedisRdbBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "redis" `
            -RedisPassword "contract-secret" `
            -Now ([DateTimeOffset]::Parse("2026-07-18T12:33:56.789Z")) `
            -RunId ([Guid]::Parse("00134567-89ab-cdef-0123-456789abcdef"))
        if (-not (Test-Path -LiteralPath $nativeResult.RdbFile -PathType Leaf)) {
            throw "native Docker invocation did not tolerate successful stderr progress output"
        }
        if ($env:REDISCLI_AUTH -ne "preexisting-redis-auth") {
            throw "native Docker invocation did not restore REDISCLI_AUTH"
        }
    } finally {
        $env:PATH = $previousPath
    }

    $successState = @{
        ContainerFile = $false
        FailCopy = $false
        Calls = [System.Collections.Generic.List[string]]::new()
    }
    $successInvoker = {
        param([string[]]$Arguments)
        $successState.Calls.Add(($Arguments -join ' '))
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("redis") }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "--rdb") {
            $successState.ContainerFile = $true
            return [pscustomobject]@{ ExitCode = 0; Output = @("SYNC sent to master") }
        }
        if ($Arguments[0] -eq "cp") {
            if ($successState.FailCopy) {
                return [pscustomobject]@{ ExitCode = 42; Output = @("copy failed") }
            }
            [System.IO.File]::WriteAllBytes($Arguments[-1], [byte[]](1, 2, 3, 4, 5, 6))
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "rm") {
            $successState.ContainerFile = $false
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @("unexpected docker command") }
    }.GetNewClosure()

    $env:REDISCLI_AUTH = "preexisting-redis-auth"
    $result = Invoke-RedisRdbBackup `
        -SafeDestinationRoot $coreTestRoot `
        -Container "redis" `
        -RedisPassword "contract-secret" `
        -Now ([DateTimeOffset]::Parse("2026-07-18T12:34:56.789Z")) `
        -RunId ([Guid]::Parse("01234567-89ab-cdef-0123-456789abcdef")) `
        -DockerInvoker $successInvoker

    if ($env:REDISCLI_AUTH -ne "preexisting-redis-auth") {
        throw "successful backup did not restore the previous REDISCLI_AUTH value"
    }
    if (-not (Test-Path -LiteralPath $result.RdbFile -PathType Leaf) -or
            -not (Test-Path -LiteralPath $result.MetadataFile -PathType Leaf)) {
        throw "successful core backup did not create the RDB and metadata files"
    }
    if ((Get-Item -LiteralPath $result.RdbFile).Length -ne 6) {
        throw "successful core backup did not preserve the fake RDB bytes"
    }
    $metadata = Get-Content -LiteralPath $result.MetadataFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $actualHash = (Get-FileHash -LiteralPath $result.RdbFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($metadata.sha256 -ne $actualHash -or $metadata.bytes -ne 6 -or $metadata.dataStore -ne "redis" -or
            $metadata.rdb -ne (Get-Item -LiteralPath $result.RdbFile).Name) {
        throw "successful core backup metadata is inconsistent"
    }
    $metadataText = Get-Content -LiteralPath $result.MetadataFile -Raw -Encoding UTF8
    if ($metadataText.Contains("contract-secret")) {
        throw "successful core backup metadata exposed the Redis password"
    }
    $expectedDirectory = Join-Path $coreTestRoot "20260718T123456789Z-01234567"
    $expectedPrefix = [System.IO.Path]::GetFullPath($expectedDirectory) +
        [System.IO.Path]::DirectorySeparatorChar
    if (-not [System.IO.Path]::GetFullPath($result.RdbFile).StartsWith(
            $expectedPrefix,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "backup directory does not include milliseconds and a run identifier"
    }
    if (Get-ChildItem -LiteralPath $expectedDirectory -Filter "*.partial*" -Force) {
        throw "successful backup left host-side partial files"
    }
    if ($successState.ContainerFile) {
        throw "successful core backup did not clean the container temporary file"
    }
    $allCalls = $successState.Calls -join "`n"
    foreach ($expectedCall in @("ps --filter", "redis-cli --rdb", "cp redis:", "rm -f --")) {
        if (-not $allCalls.Contains($expectedCall)) {
            throw "successful core backup did not invoke expected Docker command: $expectedCall`n$allCalls"
        }
    }
    if (-not $allCalls.Contains("--env REDISCLI_AUTH") -or $allCalls.Contains("contract-secret")) {
        throw "Redis authentication must use an inherited environment name without exposing its value"
    }

    $failureState = @{
        ContainerFile = $false
        FailCopy = $true
        Calls = [System.Collections.Generic.List[string]]::new()
    }
    $failureInvoker = {
        param([string[]]$Arguments)
        $failureState.Calls.Add(($Arguments -join ' '))
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("redis") }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "--rdb") {
            $failureState.ContainerFile = $true
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "cp") {
            return [pscustomobject]@{ ExitCode = 42; Output = @("copy failed") }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "rm") {
            $failureState.ContainerFile = $false
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @() }
    }.GetNewClosure()

    Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue
    $failed = $false
    try {
        Invoke-RedisRdbBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "redis" `
            -RedisPassword "contract-secret" `
            -Now ([DateTimeOffset]::Parse("2026-07-18T12:35:56.789Z")) `
            -RunId ([Guid]::Parse("11234567-89ab-cdef-0123-456789abcdef")) `
            -DockerInvoker $failureInvoker | Out-Null
    } catch {
        $failed = $true
    }
    if (-not $failed) {
        throw "copy failure case unexpectedly succeeded"
    }
    if ($failureState.ContainerFile) {
        throw "copy failure did not clean the container temporary file"
    }
    if (Test-Path Env:REDISCLI_AUTH) {
        throw "copy failure did not restore the originally absent REDISCLI_AUTH"
    }
    $failureDirectory = Join-Path $coreTestRoot "20260718T123556789Z-11234567"
    if (Test-Path -LiteralPath $failureDirectory) {
        throw "copy failure left a host-side partial backup directory"
    }

    $emptyInvoker = {
        param([string[]]$Arguments)
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("redis") }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "--rdb") {
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "cp") {
            [System.IO.File]::WriteAllBytes($Arguments[-1], [byte[]]@())
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "rm") {
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @() }
    }
    $emptyFailed = $false
    try {
        Invoke-RedisRdbBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "redis" `
            -RedisPassword "" `
            -Now ([DateTimeOffset]::Parse("2026-07-18T12:36:56.789Z")) `
            -RunId ([Guid]::Parse("21234567-89ab-cdef-0123-456789abcdef")) `
            -DockerInvoker $emptyInvoker | Out-Null
    } catch {
        $emptyFailed = $true
    }
    if (-not $emptyFailed -or
            (Test-Path -LiteralPath (Join-Path $coreTestRoot "20260718T123656789Z-21234567"))) {
        throw "empty Redis RDB was accepted or left host-side task files"
    }

    $cleanupFailureState = @{ ContainerFile = $false }
    $cleanupFailureInvoker = {
        param([string[]]$Arguments)
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("redis") }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "--rdb") {
            $cleanupFailureState.ContainerFile = $true
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "cp") {
            [System.IO.File]::WriteAllBytes($Arguments[-1], [byte[]](7, 8, 9))
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "rm") {
            return [pscustomobject]@{ ExitCode = 43; Output = @("cleanup failed") }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @() }
    }.GetNewClosure()
    $cleanupFailureDirectory = Join-Path $coreTestRoot "20260718T123756789Z-31234567"
    $cleanupFailed = $false
    try {
        Invoke-RedisRdbBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "redis" `
            -RedisPassword "" `
            -Now ([DateTimeOffset]::Parse("2026-07-18T12:37:56.789Z")) `
            -RunId ([Guid]::Parse("31234567-89ab-cdef-0123-456789abcdef")) `
            -DockerInvoker $cleanupFailureInvoker | Out-Null
    } catch {
        $cleanupFailed = $_.Exception.Message -match "clean the Redis container"
        if (-not $_.Exception.Message.Contains($cleanupFailureDirectory)) {
            throw "container cleanup failure did not report the preserved host backup path"
        }
    }
    if (-not $cleanupFailed) {
        throw "container cleanup failure was not reported"
    }
    if (-not (Test-Path -LiteralPath $cleanupFailureDirectory -PathType Container)) {
        throw "container cleanup failure removed the already verified host-side backup"
    }
    $preservedRdb = @(Get-ChildItem -LiteralPath $cleanupFailureDirectory -Filter "*.rdb")
    $preservedMetadata = @(Get-ChildItem -LiteralPath $cleanupFailureDirectory -Filter "*.json")
    if ($preservedRdb.Count -ne 1 -or $preservedMetadata.Count -ne 1 -or
            $preservedRdb[0].Length -le 0) {
        throw "container cleanup failure did not preserve one complete RDB and metadata pair"
    }
    $preservedMetadataValue = Get-Content -LiteralPath $preservedMetadata[0].FullName -Raw -Encoding UTF8 |
        ConvertFrom-Json
    $preservedHash = (Get-FileHash -LiteralPath $preservedRdb[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($preservedMetadataValue.sha256 -ne $preservedHash -or
            $preservedMetadataValue.bytes -ne $preservedRdb[0].Length) {
        throw "container cleanup failure preserved an unverifiable host-side backup"
    }
    if (Get-ChildItem -LiteralPath $cleanupFailureDirectory -Filter "*.partial*" -Force) {
        throw "container cleanup failure left host-side partial files"
    }
} finally {
    if ($hadOriginalRedisCliAuth) {
        $env:REDISCLI_AUTH = $originalRedisCliAuth
    } else {
        Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue
    }
    Get-ChildItem -LiteralPath $coreTestRoot -File -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-ChildItem -LiteralPath $coreTestRoot -Directory -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Remove-Item -Force
    if (Test-Path -LiteralPath $coreTestRoot) {
        Remove-Item -LiteralPath $coreTestRoot -Force
    }
}

Write-Host "backup-redis contract tests passed" -ForegroundColor Green

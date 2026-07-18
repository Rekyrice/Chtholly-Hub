$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "backup-mysql.ps1"
$modulePath = Join-Path $PSScriptRoot "backup-mysql-core.psm1"
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
        [string]$Database,
        [int]$ExpectedExitCode,
        [string]$ExpectedMessage
    )

    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -DestinationRoot $DestinationRoot -Database $Database -ValidateOnly 2>&1 | Out-String
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

Invoke-ValidationCase "valid external D drive" $externalRoot "chtholly" 0 | Out-Null
Invoke-ValidationCase "relative destination" "backups" "chtholly" 1 | Out-Null
Invoke-ValidationCase "C drive destination" "C:\temp\chtholly-backup" "chtholly" 1 | Out-Null
Invoke-ValidationCase "repository destination" (Join-Path $repoRoot ".codex-tmp\backup") "chtholly" 1 | Out-Null
Invoke-ValidationCase "shared repository destination" (Join-Path $sharedRepositoryRoot ".codex-tmp\backup") `
    "chtholly" 1 "repository or registered worktree" | Out-Null
$siblingWorktree = $registeredWorktrees |
    Where-Object { -not $_.Equals($repoRoot, [System.StringComparison]::OrdinalIgnoreCase) } |
    Select-Object -First 1
if ($siblingWorktree) {
    Invoke-ValidationCase "sibling worktree destination" (Join-Path $siblingWorktree ".codex-tmp\backup") `
        "chtholly" 1 "repository or registered worktree" | Out-Null
}
Invoke-ValidationCase "database injection" $externalRoot "chtholly;DROP_DATABASE" 1 | Out-Null

$junctionTestRoot = Join-Path $repoRoot ".codex-tmp\backup-mysql-junction-$PID-$([Guid]::NewGuid().ToString('N'))"
$junctionTarget = Join-Path $junctionTestRoot "target"
$junctionPath = Join-Path $junctionTestRoot "link"
try {
    New-Item -ItemType Directory -Path $junctionTarget -Force | Out-Null
    New-Item -ItemType Junction -Path $junctionPath -Target $junctionTarget | Out-Null
    Invoke-ValidationCase "junction destination" (Join-Path $junctionPath "backup") "chtholly" 1 "reparse" | Out-Null
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

$source = (Get-Content $scriptPath -Raw -Encoding UTF8)
if (Test-Path -LiteralPath $modulePath -PathType Leaf) {
    $source += "`n" + (Get-Content $modulePath -Raw -Encoding UTF8)
}
foreach ($required in @(
        "--single-transaction", "Get-FileHash", "ConvertTo-Json", "finally", "ReparsePoint",
        "HHmmssfffZ", ".partial", "Move-Item", "Length", "MYSQL_PWD", "git-common-dir",
        "worktree list --porcelain"
    )) {
    if (-not $source.Contains($required)) {
        throw "backup script is missing required contract token: $required"
    }
}
if ($source.Contains('MYSQL_PWD=$env:MYSQL_PASSWORD')) {
    throw "MySQL password must not be embedded in docker command arguments."
}

if (-not (Test-Path -LiteralPath $modulePath -PathType Leaf)) {
    throw "MySQL backup core module is required for responsibility-level tests."
}
Import-Module $modulePath -Force

$coreTestRoot = Join-Path $repoRoot ".codex-tmp\backup-mysql-core-$PID-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $coreTestRoot -Force | Out-Null
$hadOriginalMysqlPwd = Test-Path Env:MYSQL_PWD
$originalMysqlPwd = $env:MYSQL_PWD
try {
    $successState = @{
        ContainerFile = $false
        Calls = [System.Collections.Generic.List[string]]::new()
        ObservedMysqlPwd = $null
    }
    $successInvoker = {
        param([string[]]$Arguments)
        $successState.Calls.Add(($Arguments -join ' '))
        $joined = $Arguments -join ' '
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("mysql") }
        }
        if ($Arguments[0] -eq "exec" -and $joined.Contains("mysqldump")) {
            $successState.ContainerFile = $true
            $successState.ObservedMysqlPwd = $env:MYSQL_PWD
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "cp") {
            [System.IO.File]::WriteAllBytes(
                $Arguments[-1],
                [System.Text.Encoding]::UTF8.GetBytes("CREATE TABLE contract_test (id BIGINT);`n")
            )
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "rm") {
            $successState.ContainerFile = $false
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @("unexpected docker command") }
    }.GetNewClosure()

    $env:MYSQL_PWD = "preexisting-mysql-pwd"
    $fixedNow = [DateTimeOffset]::Parse("2026-07-18T12:34:56.789Z")
    $fixedRunId = [Guid]::Parse("01234567-89ab-cdef-0123-456789abcdef")
    $result = Invoke-MySqlBackup `
        -SafeDestinationRoot $coreTestRoot `
        -Container "mysql" `
        -Database "chtholly" `
        -MySqlUser "root" `
        -MySqlPassword "contract-secret" `
        -Now $fixedNow `
        -RunId $fixedRunId `
        -DockerInvoker $successInvoker

    if ($env:MYSQL_PWD -ne "preexisting-mysql-pwd") {
        throw "successful backup did not restore the previous MYSQL_PWD value"
    }
    if ($successState.ObservedMysqlPwd -ne "contract-secret") {
        throw "mysqldump did not receive MYSQL_PWD through the inherited environment"
    }
    if ($successState.ContainerFile) {
        throw "successful backup did not clean the container temporary file"
    }
    if (-not (Test-Path -LiteralPath $result.ArchiveFile -PathType Leaf) -or
            -not (Test-Path -LiteralPath $result.MetadataFile -PathType Leaf)) {
        throw "successful backup did not create archive and metadata files"
    }
    if ((Get-Item -LiteralPath $result.ArchiveFile).Length -le 0) {
        throw "successful backup archive is empty"
    }
    $expectedDirectory = Join-Path $coreTestRoot "20260718T123456789Z-01234567"
    if (-not [System.IO.Path]::GetFullPath($result.ArchiveFile).StartsWith(
            [System.IO.Path]::GetFullPath($expectedDirectory) + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
        throw "backup directory does not include milliseconds and a run identifier"
    }

    $metadata = Get-Content -LiteralPath $result.MetadataFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $archiveItem = Get-Item -LiteralPath $result.ArchiveFile
    $actualHash = (Get-FileHash -LiteralPath $result.ArchiveFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($metadata.sha256 -ne $actualHash -or $metadata.bytes -ne $archiveItem.Length -or
            $metadata.database -ne "chtholly" -or $metadata.archive -ne $archiveItem.Name) {
        throw "successful backup metadata SHA-256 or byte count is inconsistent"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($result.ArchiveFile)
    try {
        if ($archive.Entries.Count -ne 1 -or $archive.Entries[0].Length -le 0) {
            throw "successful backup archive does not contain one non-empty SQL dump"
        }
    } finally {
        $archive.Dispose()
    }
    $allCalls = $successState.Calls -join "`n"
    foreach ($expectedCall in @("ps --filter", "mysqldump", "cp mysql:", "rm -f --")) {
        if (-not $allCalls.Contains($expectedCall)) {
            throw "successful backup did not invoke expected Docker command: $expectedCall`n$allCalls"
        }
    }
    if (-not $allCalls.Contains("--env MYSQL_PWD") -or $allCalls.Contains("contract-secret")) {
        throw "MySQL authentication must use an inherited environment name without exposing its value"
    }
    if (Get-ChildItem -LiteralPath $expectedDirectory -Filter "*.partial*" -Force) {
        throw "successful backup left host-side partial files"
    }

    $collisionFailed = $false
    try {
        Invoke-MySqlBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "mysql" `
            -Database "chtholly" `
            -MySqlUser "root" `
            -MySqlPassword "contract-secret" `
            -Now $fixedNow `
            -RunId $fixedRunId `
            -DockerInvoker $successInvoker | Out-Null
    } catch {
        $collisionFailed = $true
    }
    if (-not $collisionFailed) {
        throw "an existing backup target was reused"
    }
    if (-not (Test-Path -LiteralPath $result.ArchiveFile -PathType Leaf)) {
        throw "collision handling damaged the previous successful backup"
    }

    $failureState = @{
        ContainerFile = $false
        Calls = [System.Collections.Generic.List[string]]::new()
    }
    $copyFailureInvoker = {
        param([string[]]$Arguments)
        $failureState.Calls.Add(($Arguments -join ' '))
        $joined = $Arguments -join ' '
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("mysql") }
        }
        if ($Arguments[0] -eq "exec" -and $joined.Contains("mysqldump")) {
            $failureState.ContainerFile = $true
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "cp") {
            [System.IO.File]::WriteAllBytes($Arguments[-1], [byte[]](1, 2, 3))
            return [pscustomobject]@{ ExitCode = 42; Output = @("copy failed") }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "rm") {
            $failureState.ContainerFile = $false
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @() }
    }.GetNewClosure()

    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    $failureNow = [DateTimeOffset]::Parse("2026-07-18T12:35:56.789Z")
    $failureRunId = [Guid]::Parse("11234567-89ab-cdef-0123-456789abcdef")
    $copyFailed = $false
    try {
        Invoke-MySqlBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "mysql" `
            -Database "chtholly" `
            -MySqlUser "root" `
            -MySqlPassword "contract-secret" `
            -Now $failureNow `
            -RunId $failureRunId `
            -DockerInvoker $copyFailureInvoker | Out-Null
    } catch {
        $copyFailed = $true
    }
    if (-not $copyFailed) {
        throw "copy failure case unexpectedly succeeded"
    }
    if ($failureState.ContainerFile) {
        throw "copy failure did not clean the container temporary file"
    }
    if (Test-Path Env:MYSQL_PWD) {
        throw "copy failure did not restore the originally absent MYSQL_PWD"
    }
    $failureDirectory = Join-Path $coreTestRoot "20260718T123556789Z-11234567"
    if (Test-Path -LiteralPath $failureDirectory) {
        throw "copy failure left task files or an empty backup directory"
    }

    $emptyInvoker = {
        param([string[]]$Arguments)
        $joined = $Arguments -join ' '
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("mysql") }
        }
        if ($Arguments[0] -eq "exec" -and $joined.Contains("mysqldump")) {
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
        Invoke-MySqlBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "mysql" `
            -Database "chtholly" `
            -MySqlUser "root" `
            -MySqlPassword "contract-secret" `
            -Now ([DateTimeOffset]::Parse("2026-07-18T12:36:56.789Z")) `
            -RunId ([Guid]::Parse("21234567-89ab-cdef-0123-456789abcdef")) `
            -DockerInvoker $emptyInvoker | Out-Null
    } catch {
        $emptyFailed = $true
    }
    if (-not $emptyFailed -or
            (Test-Path -LiteralPath (Join-Path $coreTestRoot "20260718T123656789Z-21234567"))) {
        throw "empty SQL dump was accepted or left host-side task files"
    }

    $cleanupFailureInvoker = {
        param([string[]]$Arguments)
        $joined = $Arguments -join ' '
        if ($Arguments[0] -eq "ps") {
            return [pscustomobject]@{ ExitCode = 0; Output = @("mysql") }
        }
        if ($Arguments[0] -eq "exec" -and $joined.Contains("mysqldump")) {
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "cp") {
            [System.IO.File]::WriteAllBytes(
                $Arguments[-1],
                [System.Text.Encoding]::UTF8.GetBytes("CREATE TABLE cleanup_contract (id BIGINT);`n")
            )
            return [pscustomobject]@{ ExitCode = 0; Output = @() }
        }
        if ($Arguments[0] -eq "exec" -and $Arguments -contains "rm") {
            return [pscustomobject]@{ ExitCode = 52; Output = @("container unavailable") }
        }
        return [pscustomobject]@{ ExitCode = 99; Output = @() }
    }
    $cleanupFailureMessage = $null
    try {
        Invoke-MySqlBackup `
            -SafeDestinationRoot $coreTestRoot `
            -Container "mysql" `
            -Database "chtholly" `
            -MySqlUser "root" `
            -MySqlPassword "contract-secret" `
            -Now ([DateTimeOffset]::Parse("2026-07-18T12:37:56.789Z")) `
            -RunId ([Guid]::Parse("31234567-89ab-cdef-0123-456789abcdef")) `
            -DockerInvoker $cleanupFailureInvoker | Out-Null
    } catch {
        $cleanupFailureMessage = $_.Exception.Message
    }
    $cleanupFailureDirectory = Join-Path $coreTestRoot "20260718T123756789Z-31234567"
    $cleanupFailureArchive = Join-Path $cleanupFailureDirectory "chtholly-20260718T123756789Z.zip"
    $cleanupFailureMetadata = Join-Path $cleanupFailureDirectory "chtholly-20260718T123756789Z.json"
    if ($cleanupFailureMessage -notmatch "container temporary SQL dump" -or
            -not $cleanupFailureMessage.Contains($cleanupFailureDirectory) -or
            -not $cleanupFailureMessage.Contains("/tmp/chtholly-mysql-backup-3123456789abcdef0123456789abcdef.sql") -or
            -not (Test-Path -LiteralPath $cleanupFailureArchive -PathType Leaf) -or
            -not (Test-Path -LiteralPath $cleanupFailureMetadata -PathType Leaf)) {
        throw "container cleanup failure was not reported or discarded a completed host backup"
    }
    if (Get-ChildItem -LiteralPath $cleanupFailureDirectory -Filter "*.partial*" -Force) {
        throw "container cleanup failure left host-side partial files"
    }
} finally {
    if ($hadOriginalMysqlPwd) {
        $env:MYSQL_PWD = $originalMysqlPwd
    } else {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }
    Get-ChildItem -LiteralPath $coreTestRoot -File -Recurse -ErrorAction SilentlyContinue |
        Remove-Item -Force
    Get-ChildItem -LiteralPath $coreTestRoot -Directory -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Remove-Item -Force
    if (Test-Path -LiteralPath $coreTestRoot) {
        Remove-Item -LiteralPath $coreTestRoot -Force
    }
}

Write-Host "backup-mysql contract tests passed" -ForegroundColor Green

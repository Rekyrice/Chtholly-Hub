param(
    [Parameter(Mandatory = $true)]
    [string]$DestinationRoot,
    [string]$Container = "redis",
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "../dev/load-env.ps1")
Import-Module (Join-Path $PSScriptRoot "backup-redis-core.psm1") -Force

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path

function Test-SafeContainerName {
    param([string]$Name)
    return -not [string]::IsNullOrWhiteSpace($Name) -and
        $Name.Length -le 128 -and
        $Name -match '^[A-Za-z0-9][A-Za-z0-9_.-]*$'
}

function Assert-NoReparsePointInPath {
    param([string]$Path)

    $current = [System.IO.Path]::GetFullPath($Path)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Backup destination must not contain reparse points or junctions: $current"
            }
        }
        $parent = [System.IO.Directory]::GetParent($current)
        if ($null -eq $parent -or $parent.FullName.Equals($current, [System.StringComparison]::OrdinalIgnoreCase)) {
            break
        }
        $current = $parent.FullName
    }
}

function Get-ProtectedGitRoots {
    $commonDirectoryOutput = & git -C $repoRoot rev-parse --path-format=absolute --git-common-dir 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $commonDirectoryOutput) {
        throw "Unable to resolve the shared Git directory."
    }
    $commonDirectory = [System.IO.Path]::GetFullPath(($commonDirectoryOutput | Select-Object -First 1).Trim())
    $sharedRepositoryRoot = [System.IO.Directory]::GetParent($commonDirectory)
    if ($null -eq $sharedRepositoryRoot) {
        throw "Unable to resolve the shared repository root."
    }

    $worktreeOutput = & git -C $repoRoot worktree list --porcelain 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to enumerate registered Git worktrees."
    }

    $roots = [System.Collections.Generic.List[string]]::new()
    $roots.Add([System.IO.Path]::GetFullPath($repoRoot))
    $roots.Add([System.IO.Path]::GetFullPath($sharedRepositoryRoot.FullName))
    foreach ($line in @($worktreeOutput)) {
        if ($line.StartsWith("worktree ")) {
            $roots.Add([System.IO.Path]::GetFullPath($line.Substring(9)))
        }
    }
    return @($roots | Select-Object -Unique)
}

function Test-PathInsideRoot {
    param(
        [string]$Path,
        [string]$Root
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd('\')
    $rootPrefix = $fullRoot + [System.IO.Path]::DirectorySeparatorChar
    return $fullPath.Equals($fullRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
        $fullPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)
}

function Resolve-SafeDestinationRoot {
    param([string]$Path)

    if (-not [System.IO.Path]::IsPathRooted($Path)) {
        throw "Backup destination must be an absolute path."
    }
    $fullPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\')
    $drive = [System.IO.Path]::GetPathRoot($fullPath).TrimEnd('\')
    if (-not $drive.Equals("D:", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Backup destination must be on the D: drive."
    }
    if ($fullPath.Equals("D:", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Backup destination must not be the drive root."
    }
    Assert-NoReparsePointInPath $fullPath
    if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
        throw "Backup destination must be a directory."
    }
    foreach ($protectedRoot in Get-ProtectedGitRoots) {
        if (Test-PathInsideRoot $fullPath $protectedRoot) {
            throw "Backup destination must be outside the shared repository or registered worktree: $protectedRoot"
        }
    }
    return $fullPath
}

try {
    if (-not (Test-SafeContainerName $Container)) {
        throw "Container name contains unsupported characters."
    }
    $safeDestinationRoot = Resolve-SafeDestinationRoot $DestinationRoot

    if ($ValidateOnly) {
        [ordered]@{
            status = "validated"
            dataStore = "redis"
            destinationRoot = $safeDestinationRoot
            container = $Container
        } | ConvertTo-Json -Compress
        exit 0
    }

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker CLI is required for the Redis backup."
    }

    $result = Invoke-RedisRdbBackup `
        -SafeDestinationRoot $safeDestinationRoot `
        -Container $Container `
        -RedisPassword $env:REDIS_PASSWORD
    $result.Metadata | ConvertTo-Json -Compress
} catch {
    Write-Error $_.Exception.Message
    exit 1
}

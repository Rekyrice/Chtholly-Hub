function Invoke-RedisRdbBackup {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$SafeDestinationRoot,
        [Parameter(Mandatory = $true)]
        [string]$Container,
        [AllowEmptyString()]
        [string]$RedisPassword,
        [DateTimeOffset]$Now = [DateTimeOffset]::UtcNow,
        [Guid]$RunId = [Guid]::NewGuid(),
        [scriptblock]$DockerInvoker
    )

    $ErrorActionPreference = "Stop"
    if (-not $DockerInvoker) {
        $DockerInvoker = {
            param([string[]]$Arguments)
            $previousErrorActionPreference = $ErrorActionPreference
            try {
                # redis-cli --rdb writes successful replication progress to stderr. PowerShell 5
                # promotes redirected native stderr to a terminating NativeCommandError under Stop.
                $ErrorActionPreference = "Continue"
                $output = & docker @Arguments 2>&1
                $exitCode = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $previousErrorActionPreference
            }
            return [pscustomobject]@{
                ExitCode = $exitCode
                Output = @($output)
            }
        }
    }

    $containerTempFile = $null
    $partialRdbFile = $null
    $rdbFile = $null
    $metadataPartialFile = $null
    $metadataFile = $null
    $targetDirectory = $null
    $destinationRootCreated = $false
    $targetDirectoryCreated = $false
    $backupCompleted = $false
    $primaryFailure = $false
    $containerCleanupFailure = $false
    $hadRedisCliAuth = Test-Path Env:REDISCLI_AUTH
    $previousRedisCliAuth = $env:REDISCLI_AUTH

    try {
        if (-not (Test-Path -LiteralPath $SafeDestinationRoot)) {
            New-Item -ItemType Directory -Path $SafeDestinationRoot | Out-Null
            $destinationRootCreated = $true
        }
        Assert-CorePathHasNoReparsePoint $SafeDestinationRoot

        $runningResult = Invoke-DockerCommand $DockerInvoker @(
            "ps", "--filter", "name=^/$Container$", "--format", "{{.Names}}"
        )
        if ($runningResult.ExitCode -ne 0 -or @($runningResult.Output) -notcontains $Container) {
            throw "Redis container is not running: $Container"
        }

        $timestamp = $Now.UtcDateTime.ToString("yyyyMMddTHHmmssfffZ")
        $runIdText = $RunId.ToString('N')
        $targetDirectory = Join-Path $SafeDestinationRoot "$timestamp-$($runIdText.Substring(0, 8))"
        if (Test-Path -LiteralPath $targetDirectory) {
            throw "Backup target already exists: $targetDirectory"
        }
        New-Item -ItemType Directory -Path $targetDirectory | Out-Null
        $targetDirectoryCreated = $true
        Assert-CorePathHasNoReparsePoint $targetDirectory

        $baseName = "redis-$timestamp"
        $partialRdbFile = Join-Path $targetDirectory "$baseName.rdb.partial"
        $rdbFile = Join-Path $targetDirectory "$baseName.rdb"
        $metadataPartialFile = Join-Path $targetDirectory "$baseName.json.partial"
        $metadataFile = Join-Path $targetDirectory "$baseName.json"
        $containerTempFile = "/tmp/chtholly-redis-backup-$runIdText.rdb"

        $dockerExecArgs = @("exec")
        if (-not [string]::IsNullOrEmpty($RedisPassword)) {
            # 只把环境变量名交给 Docker，密码值不进入命令参数或输出。
            $env:REDISCLI_AUTH = $RedisPassword
            $dockerExecArgs += @("--env", "REDISCLI_AUTH")
        } else {
            Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue
        }
        $dockerExecArgs += @($Container, "redis-cli", "--rdb", $containerTempFile)
        $redisResult = Invoke-DockerCommand $DockerInvoker $dockerExecArgs
        if ($redisResult.ExitCode -ne 0) {
            throw "redis-cli RDB export failed."
        }

        # docker cp writes to a same-directory .partial file before the atomic rename.
        $copyResult = Invoke-DockerCommand $DockerInvoker @(
            "cp", "${Container}:$containerTempFile", $partialRdbFile
        )
        if ($copyResult.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $partialRdbFile -PathType Leaf)) {
            throw "Failed to copy the Redis RDB from the container."
        }
        if ((Get-Item -LiteralPath $partialRdbFile).Length -le 0) {
            throw "Redis RDB backup is empty."
        }

        Move-Item -LiteralPath $partialRdbFile -Destination $rdbFile
        $rdbItem = Get-Item -LiteralPath $rdbFile
        $hash = (Get-FileHash -LiteralPath $rdbFile -Algorithm SHA256).Hash.ToLowerInvariant()
        $metadata = [ordered]@{
            formatVersion = 1
            dataStore = "redis"
            createdAt = $Now.UtcDateTime.ToString("o")
            container = $Container
            rdb = [System.IO.Path]::GetFileName($rdbFile)
            sha256 = $hash
            bytes = $rdbItem.Length
        }
        $metadata | ConvertTo-Json | Set-Content -LiteralPath $metadataPartialFile -Encoding UTF8
        Move-Item -LiteralPath $metadataPartialFile -Destination $metadataFile
        $backupCompleted = $true

        return [pscustomobject]@{
            Metadata = $metadata
            RdbFile = $rdbFile
            MetadataFile = $metadataFile
        }
    } catch {
        $primaryFailure = $true
        throw
    } finally {
        if ($containerTempFile -and $containerTempFile.StartsWith("/tmp/chtholly-redis-backup-")) {
            try {
                $cleanupResult = Invoke-DockerCommand $DockerInvoker @(
                    "exec", $Container, "rm", "-f", "--", $containerTempFile
                )
                $containerCleanupFailure = $cleanupResult.ExitCode -ne 0
            } catch {
                $containerCleanupFailure = $true
            }
        }

        if ($hadRedisCliAuth) {
            $env:REDISCLI_AUTH = $previousRedisCliAuth
        } else {
            Remove-Item Env:REDISCLI_AUTH -ErrorAction SilentlyContinue
        }

        if (-not $backupCompleted) {
            foreach ($taskFile in @($partialRdbFile, $rdbFile, $metadataPartialFile, $metadataFile)) {
                if ($taskFile -and (Test-Path -LiteralPath $taskFile -PathType Leaf)) {
                    Remove-Item -LiteralPath $taskFile -Force
                }
            }
            if ($targetDirectoryCreated -and
                    (Test-Path -LiteralPath $targetDirectory -PathType Container) -and
                    @(Get-ChildItem -LiteralPath $targetDirectory -Force).Count -eq 0) {
                Remove-Item -LiteralPath $targetDirectory -Force
            }
            if ($destinationRootCreated -and
                    (Test-Path -LiteralPath $SafeDestinationRoot -PathType Container) -and
                    @(Get-ChildItem -LiteralPath $SafeDestinationRoot -Force).Count -eq 0) {
                Remove-Item -LiteralPath $SafeDestinationRoot -Force
            }
        }
        if ($containerCleanupFailure -and -not $primaryFailure) {
            throw "Failed to clean the Redis container temporary RDB '$containerTempFile'. Verified host backup was preserved at: $targetDirectory"
        }
    }
}

function Invoke-DockerCommand {
    param(
        [scriptblock]$Invoker,
        [string[]]$Arguments
    )

    $result = & $Invoker -Arguments $Arguments
    if ($null -eq $result -or $null -eq $result.ExitCode) {
        throw "Docker command invoker returned an invalid result."
    }
    return $result
}

function Assert-CorePathHasNoReparsePoint {
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

Export-ModuleMember -Function Invoke-RedisRdbBackup

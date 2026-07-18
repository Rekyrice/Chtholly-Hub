function Invoke-MySqlBackup {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$SafeDestinationRoot,
        [Parameter(Mandatory = $true)]
        [string]$Container,
        [Parameter(Mandatory = $true)]
        [string]$Database,
        [Parameter(Mandatory = $true)]
        [string]$MySqlUser,
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string]$MySqlPassword,
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
    $partialSqlFile = $null
    $partialArchiveFile = $null
    $archiveFile = $null
    $partialMetadataFile = $null
    $metadataFile = $null
    $targetDirectory = $null
    $destinationRootCreated = $false
    $targetDirectoryCreated = $false
    $backupCompleted = $false
    $primaryFailure = $false
    $containerCleanupFailure = $false
    $hadMysqlPwd = Test-Path Env:MYSQL_PWD
    $previousMysqlPwd = $env:MYSQL_PWD

    try {
        if ([string]::IsNullOrEmpty($MySqlPassword)) {
            throw "MYSQL_PASSWORD is required."
        }

        if (-not (Test-Path -LiteralPath $SafeDestinationRoot)) {
            New-Item -ItemType Directory -Path $SafeDestinationRoot | Out-Null
            $destinationRootCreated = $true
        }
        Assert-CorePathHasNoReparsePoint $SafeDestinationRoot

        $runningResult = Invoke-DockerCommand $DockerInvoker @(
            "ps", "--filter", "name=^/$Container$", "--format", "{{.Names}}"
        )
        if ($runningResult.ExitCode -ne 0 -or @($runningResult.Output) -notcontains $Container) {
            throw "MySQL container is not running: $Container"
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

        $baseName = "$Database-$timestamp"
        $partialSqlFile = Join-Path $targetDirectory "$baseName.sql.partial"
        $partialArchiveFile = Join-Path $targetDirectory "$baseName.zip.partial"
        $archiveFile = Join-Path $targetDirectory "$baseName.zip"
        $partialMetadataFile = Join-Path $targetDirectory "$baseName.json.partial"
        $metadataFile = Join-Path $targetDirectory "$baseName.json"
        $containerTempFile = "/tmp/chtholly-mysql-backup-$runIdText.sql"

        $env:MYSQL_PWD = $MySqlPassword
        $dumpCommand = 'exec mysqldump -u"$1" --default-character-set=utf8mb4 --single-transaction --routines --triggers --events --hex-blob "$2" > "$3"'
        $dumpResult = Invoke-DockerCommand $DockerInvoker @(
            "exec", "--env", "MYSQL_PWD", $Container, "sh", "-c", $dumpCommand,
            "chtholly-backup", $MySqlUser, $Database, $containerTempFile
        )
        if ($dumpResult.ExitCode -ne 0) {
            throw "mysqldump failed."
        }

        $copyResult = Invoke-DockerCommand $DockerInvoker @(
            "cp", "${Container}:$containerTempFile", $partialSqlFile
        )
        if ($copyResult.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $partialSqlFile -PathType Leaf)) {
            throw "Failed to copy the MySQL dump from the container."
        }
        if ((Get-Item -LiteralPath $partialSqlFile).Length -le 0) {
            throw "MySQL SQL dump is empty."
        }

        New-SingleFileZipArchive `
            -InputFile $partialSqlFile `
            -ArchiveFile $partialArchiveFile `
            -EntryName "$baseName.sql"
        if (-not (Test-Path -LiteralPath $partialArchiveFile -PathType Leaf) -or
                (Get-Item -LiteralPath $partialArchiveFile).Length -le 0) {
            throw "MySQL backup archive is empty."
        }
        Move-Item -LiteralPath $partialArchiveFile -Destination $archiveFile
        Remove-Item -LiteralPath $partialSqlFile -Force

        $archiveItem = Get-Item -LiteralPath $archiveFile
        $hash = (Get-FileHash -LiteralPath $archiveFile -Algorithm SHA256).Hash.ToLowerInvariant()
        $metadata = [ordered]@{
            formatVersion = 1
            dataStore = "mysql"
            database = $Database
            createdAt = $Now.UtcDateTime.ToString("o")
            container = $Container
            archive = $archiveItem.Name
            sha256 = $hash
            bytes = $archiveItem.Length
        }
        $metadata | ConvertTo-Json | Set-Content -LiteralPath $partialMetadataFile -Encoding UTF8
        if ((Get-Item -LiteralPath $partialMetadataFile).Length -le 0) {
            throw "MySQL backup metadata is empty."
        }
        Move-Item -LiteralPath $partialMetadataFile -Destination $metadataFile
        $backupCompleted = $true

        return [pscustomobject]@{
            Metadata = $metadata
            ArchiveFile = $archiveFile
            MetadataFile = $metadataFile
        }
    } catch {
        $primaryFailure = $true
        throw
    } finally {
        if ($containerTempFile -and $containerTempFile.StartsWith("/tmp/chtholly-mysql-backup-")) {
            try {
                $cleanupResult = Invoke-DockerCommand $DockerInvoker @(
                    "exec", $Container, "rm", "-f", "--", $containerTempFile
                )
                $containerCleanupFailure = $cleanupResult.ExitCode -ne 0
            } catch {
                $containerCleanupFailure = $true
            }
        }

        if ($hadMysqlPwd) {
            $env:MYSQL_PWD = $previousMysqlPwd
        } else {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }

        if (-not $backupCompleted) {
            foreach ($taskFile in @(
                    $partialSqlFile,
                    $partialArchiveFile,
                    $archiveFile,
                    $partialMetadataFile,
                    $metadataFile
                )) {
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
            throw "Failed to clean the MySQL container temporary SQL dump '$containerTempFile'. " +
                "The verified host backup was retained at '$targetDirectory'."
        }
    }
}

function New-SingleFileZipArchive {
    param(
        [string]$InputFile,
        [string]$ArchiveFile,
        [string]$EntryName
    )

    Add-Type -AssemblyName System.IO.Compression
    $archiveStream = [System.IO.File]::Open(
        $ArchiveFile,
        [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $archiveStream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            $entry = $archive.CreateEntry($EntryName, [System.IO.Compression.CompressionLevel]::Optimal)
            $entryStream = $entry.Open()
            try {
                $inputStream = [System.IO.File]::OpenRead($InputFile)
                try {
                    $inputStream.CopyTo($entryStream)
                } finally {
                    $inputStream.Dispose()
                }
            } finally {
                $entryStream.Dispose()
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $archiveStream.Dispose()
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

Export-ModuleMember -Function Invoke-MySqlBackup

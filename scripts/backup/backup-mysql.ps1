param(
    [Parameter(Mandatory = $true)]
    [string]$DestinationRoot,
    [string]$Database = $env:MYSQL_DATABASE,
    [string]$Container = "mysql",
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "../dev/load-env.ps1")
if (-not $Database) {
    $Database = $env:MYSQL_DATABASE
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$containerTempFile = $null
$localSqlFile = $null

function Test-SafeDatabaseName {
    param([string]$Name)
    return -not [string]::IsNullOrWhiteSpace($Name) -and $Name -match '^[A-Za-z0-9_]+$'
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
    $repoPrefix = $repoRoot.TrimEnd('\') + '\'
    if ($fullPath.Equals($repoRoot, [System.StringComparison]::OrdinalIgnoreCase) -or
            $fullPath.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Backup destination must be outside the repository."
    }
    return $fullPath
}

try {
    if (-not (Test-SafeDatabaseName $Database)) {
        throw "Database name contains unsupported characters."
    }
    if (-not (Test-SafeDatabaseName $Container)) {
        throw "Container name contains unsupported characters."
    }
    $safeDestinationRoot = Resolve-SafeDestinationRoot $DestinationRoot

    if ($ValidateOnly) {
        [ordered]@{
            status = "validated"
            database = $Database
            destinationRoot = $safeDestinationRoot
            container = $Container
        } | ConvertTo-Json -Compress
        exit 0
    }

    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker CLI is required for the MySQL backup."
    }
    $running = docker ps --filter "name=^/$Container$" --format "{{.Names}}" 2>$null
    if ($LASTEXITCODE -ne 0 -or $running -notcontains $Container) {
        throw "MySQL container is not running: $Container"
    }
    if ([string]::IsNullOrEmpty($env:MYSQL_PASSWORD)) {
        throw "MYSQL_PASSWORD is required."
    }

    $timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
    $targetDirectory = Join-Path $safeDestinationRoot $timestamp
    New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
    $baseName = "$Database-$timestamp"
    $localSqlFile = Join-Path $targetDirectory "$baseName.sql"
    $archiveFile = Join-Path $targetDirectory "$baseName.zip"
    $metadataFile = Join-Path $targetDirectory "$baseName.json"
    $containerTempFile = "/tmp/chtholly-backup-$([Guid]::NewGuid().ToString('N')).sql"
    $mysqlUser = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { "root" }
    if (-not (Test-SafeDatabaseName $mysqlUser)) {
        throw "MYSQL_USER contains unsupported characters."
    }

    docker exec `
        -e "MYSQL_PWD=$env:MYSQL_PASSWORD" `
        -e "BACKUP_DB=$Database" `
        -e "BACKUP_FILE=$containerTempFile" `
        -e "BACKUP_USER=$mysqlUser" `
        $Container sh -c 'mysqldump -u"$BACKUP_USER" --default-character-set=utf8mb4 --single-transaction --routines --triggers --events --hex-blob "$BACKUP_DB" > "$BACKUP_FILE"'
    if ($LASTEXITCODE -ne 0) {
        throw "mysqldump failed."
    }

    docker cp "${Container}:$containerTempFile" $localSqlFile | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $localSqlFile)) {
        throw "Failed to copy the MySQL dump from the container."
    }
    Compress-Archive -LiteralPath $localSqlFile -DestinationPath $archiveFile -CompressionLevel Optimal -Force
    $hash = (Get-FileHash -LiteralPath $archiveFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata = [ordered]@{
        formatVersion = 1
        database = $Database
        createdAt = (Get-Date).ToUniversalTime().ToString("o")
        container = $Container
        archive = [System.IO.Path]::GetFileName($archiveFile)
        sha256 = $hash
        bytes = (Get-Item -LiteralPath $archiveFile).Length
    }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath $metadataFile -Encoding UTF8
    $metadata | ConvertTo-Json -Compress
} catch {
    Write-Error $_.Exception.Message
    exit 1
} finally {
    if ($containerTempFile -and $containerTempFile.StartsWith("/tmp/chtholly-backup-")) {
        docker exec $Container rm -f -- $containerTempFile 2>$null | Out-Null
    }
    if ($localSqlFile -and (Test-Path -LiteralPath $localSqlFile)) {
        Remove-Item -LiteralPath $localSqlFile -Force
    }
}

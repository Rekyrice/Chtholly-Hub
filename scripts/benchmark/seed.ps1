[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('smoke', 'standard')]
    [string]$Profile,

    [string]$MysqlContainer = 'mysql',
    [string]$RedisContainer = 'redis',
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$configPath = Join-Path $repoRoot 'benchmarks/config/standard.yml'
$seedSqlPath = Join-Path $repoRoot 'benchmarks/seed/standard.sql'

function Read-ProfileValue {
    param([string]$Name, [string]$Key)
    $insideProfile = $false
    foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
        if ($line -match '^  ([A-Za-z0-9_-]+):\s*$') {
            $insideProfile = $Matches[1] -eq $Name
            continue
        }
        if ($insideProfile -and $line -match "^    $([Regex]::Escape($Key)):\s*(.+?)\s*$") {
            return [int64]$Matches[1]
        }
    }
    throw "Missing profile value: $Name.$Key"
}

$users = Read-ProfileValue -Name $Profile -Key 'users'
$posts = Read-ProfileValue -Name $Profile -Key 'posts'
$interactions = Read-ProfileValue -Name $Profile -Key 'interactions'
$relations = Read-ProfileValue -Name $Profile -Key 'relations'

if ($ValidateOnly) {
    [ordered]@{ profile = $Profile; users = $users; posts = $posts; interactions = $interactions; relations = $relations } | ConvertTo-Json
    exit 0
}

foreach ($container in @($MysqlContainer, $RedisContainer)) {
    & docker inspect $container *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Required benchmark container is unavailable: $container"
    }
}

$mysqlRootPassword = if ($env:BENCHMARK_MYSQL_ROOT_PASSWORD) { $env:BENCHMARK_MYSQL_ROOT_PASSWORD } else { 'root' }
$mysqlDatabase = if ($env:BENCHMARK_MYSQL_DATABASE) { $env:BENCHMARK_MYSQL_DATABASE } else { 'chtholly' }
$seedVariables = "SET @benchmark_users=$users; SET @benchmark_posts=$posts; SET @benchmark_interactions=$interactions; SET @benchmark_relations=$relations;`n"
$sql = $seedVariables + (Get-Content -Raw -LiteralPath $seedSqlPath -Encoding UTF8)
$sql | & docker exec -i -e "MYSQL_PWD=$mysqlRootPassword" $MysqlContainer mysql -uroot $mysqlDatabase
if ($LASTEXITCODE -ne 0) {
    throw 'MySQL benchmark seed failed.'
}

Write-Output "Seeded $users users, $posts posts, $relations relations and $interactions authoritative MySQL interaction states."

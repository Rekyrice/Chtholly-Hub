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
$seedVariables = "SET @benchmark_users=$users; SET @benchmark_posts=$posts; SET @benchmark_relations=$relations;`n"
$sql = $seedVariables + (Get-Content -Raw -LiteralPath $seedSqlPath -Encoding UTF8)
$sql | & docker exec -i -e "MYSQL_PWD=$mysqlRootPassword" $MysqlContainer mysql -uroot $mysqlDatabase
if ($LASTEXITCODE -ne 0) {
    throw 'MySQL benchmark seed failed.'
}

$seedDirectory = Join-Path $repoRoot ".benchmark-results/seed-$Profile"
New-Item -ItemType Directory -Path $seedDirectory -Force | Out-Null
$redisPipe = Join-Path $seedDirectory 'redis-authoritative.pipe'
$writer = [System.IO.StreamWriter]::new($redisPipe, $false, [Text.UTF8Encoding]::new($false))
try {
    for ([int64]$i = 0; $i -lt $interactions; $i++) {
        [int64]$pairIndex = [Math]::Floor($i / 2)
        [int64]$userId = 910000000000000001 + ($pairIndex % $users)
        [int64]$postIndex = [Math]::Floor($pairIndex / $users) % $posts
        [int64]$postId = 920000000000000001 + $postIndex
        [int64]$chunk = ($userId - ($userId % 32768)) / 32768
        [int64]$bit = $userId % 32768
        $metric = if (($i % 2) -eq 0) { 'like' } else { 'fav' }
        $key = "bm:$metric`:post:$postId`:$chunk"
        $command = @('SETBIT', $key, [string]$bit, '1')
        $writer.Write("*$($command.Count)`r`n")
        foreach ($part in $command) {
            $bytes = [Text.Encoding]::UTF8.GetByteCount($part)
            $writer.Write("`$$bytes`r`n$part`r`n")
        }
    }
}
finally {
    $writer.Dispose()
}

try {
    & docker cp $redisPipe "${RedisContainer}:/tmp/benchmark-seed.pipe"
    if ($LASTEXITCODE -ne 0) {
        throw 'Copying the Redis benchmark seed failed.'
    }
    & docker exec $RedisContainer sh -c 'redis-cli --pipe < /tmp/benchmark-seed.pipe'
    if ($LASTEXITCODE -ne 0) {
        throw 'Redis authoritative bitmap seed failed.'
    }
}
finally {
    & docker exec $RedisContainer rm -f /tmp/benchmark-seed.pipe *> $null
    if (Test-Path -LiteralPath $redisPipe -PathType Leaf) {
        Remove-Item -LiteralPath $redisPipe -Force
    }
}
Write-Output "Seeded $users users, $posts posts, $relations relations and $interactions authoritative interaction states."

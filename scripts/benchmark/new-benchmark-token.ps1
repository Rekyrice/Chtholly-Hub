[CmdletBinding()]
param(
    [ValidateRange(1, [long]::MaxValue)]
    [long]$UserId = 910000000000000001,

    [ValidateRange(60, 86400)]
    [int]$TtlSeconds = 7200,

    [string]$PrivateKeyPath,

    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if ([string]::IsNullOrWhiteSpace($PrivateKeyPath)) {
    $PrivateKeyPath = Join-Path $repoRoot 'apps/server/src/main/resources/keys/private.pem'
}

$plan = [ordered]@{
    algorithm = 'RS256'
    issuer = 'chtholly'
    keyId = 'chtholly-key'
    userId = $UserId
    tokenType = 'access'
    ttlSeconds = $TtlSeconds
}
if ($ValidateOnly) {
    $plan | ConvertTo-Json
    exit 0
}

if (-not (Test-Path -LiteralPath $PrivateKeyPath -PathType Leaf)) {
    throw "Missing benchmark signing key: $PrivateKeyPath"
}
$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if ($null -eq $openssl) {
    throw 'OpenSSL is required to issue the local benchmark token.'
}

function ConvertTo-Base64Url {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$issuedAt = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$header = [ordered]@{ alg = 'RS256'; typ = 'JWT'; kid = 'chtholly-key' } |
    ConvertTo-Json -Compress
$payload = [ordered]@{
    iss = 'chtholly'
    iat = $issuedAt
    exp = $issuedAt + $TtlSeconds
    sub = [string]$UserId
    jti = [Guid]::NewGuid().ToString()
    token_type = 'access'
    uid = $UserId
    nickname = 'Benchmark User 1'
} | ConvertTo-Json -Compress
$unsigned = '{0}.{1}' -f `
    (ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($header))), `
    (ConvertTo-Base64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($payload)))

$runtimeBase = Join-Path $repoRoot '.benchmark-results/.runtime'
$tokenDirectory = Join-Path $runtimeBase ("token-{0}-{1}" -f $PID, [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tokenDirectory -Force | Out-Null
$inputPath = Join-Path $tokenDirectory 'unsigned.jwt'
$signaturePath = Join-Path $tokenDirectory 'signature.bin'
[IO.File]::WriteAllBytes($inputPath, [Text.Encoding]::ASCII.GetBytes($unsigned))

try {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $openssl.Source dgst -sha256 -sign $PrivateKeyPath -out $signaturePath $inputPath 2>$null
        $signExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($signExitCode -ne 0 -or -not (Test-Path -LiteralPath $signaturePath -PathType Leaf)) {
        throw "OpenSSL failed to sign the benchmark token (exit $signExitCode)."
    }
    $signature = ConvertTo-Base64Url -Bytes ([IO.File]::ReadAllBytes($signaturePath))
    Write-Output "$unsigned.$signature"
}
finally {
    if (Test-Path -LiteralPath $tokenDirectory -PathType Container) {
        $resolvedTokenDirectory = (Resolve-Path -LiteralPath $tokenDirectory).Path
        $allowedRuntimeRoot = (Resolve-Path -LiteralPath $runtimeBase).Path + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedTokenDirectory.StartsWith($allowedRuntimeRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Unsafe token cleanup target: $resolvedTokenDirectory"
        }
        Remove-Item -LiteralPath $resolvedTokenDirectory -Recurse -Force
    }
}

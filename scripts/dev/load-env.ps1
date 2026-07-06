# Load repo root .env into current PowerShell session
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path

$envFile = Join-Path $RepoRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Host ".env not found - copy from .env.example" -ForegroundColor Yellow
} else {
    Get-Content $envFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        if ($line -match '^([^=]+)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim()
            Set-Item -Path "env:$name" -Value $value
        }
    }
}

if (-not $env:CANAL_ENABLED) { $env:CANAL_ENABLED = "false" }
if (-not $env:LLM_ENABLED) { $env:LLM_ENABLED = "false" }

# 本地开发始终启用 dev profile（Swagger + 冷启动 seed）；与 llm 等 profile 并存
$profiles = @()
if ($env:SPRING_PROFILES_ACTIVE) {
    $profiles = $env:SPRING_PROFILES_ACTIVE.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
}
if ($env:LLM_ENABLED -eq "true" -and $profiles -notcontains "llm") {
    $profiles += "llm"
}
if ($profiles -notcontains "dev") {
    $profiles = @("dev") + $profiles
}
$env:SPRING_PROFILES_ACTIVE = ($profiles | Select-Object -Unique) -join ","

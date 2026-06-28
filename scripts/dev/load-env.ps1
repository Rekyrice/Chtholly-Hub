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

# When LLM is on, activate Spring profile "llm" (application-llm.yml)
if ($env:LLM_ENABLED -eq "true") {
    $profile = ${env:SPRING_PROFILES_ACTIVE}
    if ([string]::IsNullOrWhiteSpace($profile)) {
        $env:SPRING_PROFILES_ACTIVE = "llm"
    }
}

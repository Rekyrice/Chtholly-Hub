# 从仓库根目录 .env 注入当前 PowerShell 进程环境变量
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path

$envFile = Join-Path $RepoRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Host "未找到 .env，请复制 .env.example" -ForegroundColor Yellow
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

# 若 .env 指定 JAVA_HOME，优先用于 Maven / Spring Boot（项目目标 JDK 21）
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
}

Set-Location $RepoRoot

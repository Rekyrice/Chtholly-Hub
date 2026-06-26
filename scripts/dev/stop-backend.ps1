# 结束所有 Chtholly 后端 Java 进程（含 AI 后台调试留下的孤儿进程）
$procs = Get-CimInstance Win32_Process -Filter "name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'ChthollyApplication|chtholly-server\\target\\classes' }

if (-not $procs) {
    Write-Host "No Chtholly backend process found." -ForegroundColor DarkGray
    exit 0
}

foreach ($p in $procs) {
    Write-Host "Stopping PID $($p.ProcessId)..." -ForegroundColor Yellow
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

Start-Sleep -Seconds 1
Write-Host "Done." -ForegroundColor Green

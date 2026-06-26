# Chtholly Hub 前端
. (Join-Path $PSScriptRoot "load-env.ps1")

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Chtholly Hub 前端  http://localhost:3000" -ForegroundColor Cyan
Write-Host " 登录页: http://localhost:3000/login" -ForegroundColor DarkCyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

Set-Location (Join-Path $RepoRoot "apps/web")
npm run dev

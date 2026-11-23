# Reads server/deploy.env into $cfg. Dot-source it: . "$PSScriptRoot\env.ps1"
$cfgFile = Join-Path $PSScriptRoot 'deploy.env'
if (-not (Test-Path $cfgFile)) { throw "Нет файла $cfgFile — скопируйте deploy.env.example в deploy.env и заполните." }
$cfg = @{}
Get-Content $cfgFile | Where-Object { $_ -match '^\s*([A-Z_]+)=(.*)$' } | ForEach-Object { $cfg[$Matches[1]] = $Matches[2].Trim() }
$sshKey = $cfg.PARENTLOCK_SSH_KEY -replace '^~', $env:USERPROFILE
$sshHost = $cfg.PARENTLOCK_HOST

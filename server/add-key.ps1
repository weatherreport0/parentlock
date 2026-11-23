# Adds the public half of PARENTLOCK_SSH_KEY to the server's authorized_keys.
# Asks for the server password once (or uses a key you already have).
. "$PSScriptRoot\env.ps1"
$pub = (Get-Content "$sshKey.pub" -Raw).Trim()
Write-Host "Введите пароль от сервера, если он спросит (символы не отображаются):"
$pub | ssh -o StrictHostKeyChecking=accept-new $sshHost "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys && echo KEY-ADDED"

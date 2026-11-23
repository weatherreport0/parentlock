# Sets a new password for the site. The password is typed here and never shown.
#   .\server\reset-password.ps1        main parent
#   .\server\reset-password.ps1 2      second parent
param([ValidateSet(1, 2)][int]$Slot = 1)
. "$PSScriptRoot\env.ps1"
$who = if ($Slot -eq 2) { 'второго родителя' } else { 'для сайта' }
$remote = @'
while true; do
  read -r -s -p "Новый пароль WHO (от 8 символов): " P; echo
  read -r -s -p "Повторите пароль: " P2; echo
  if [ "$P" != "$P2" ]; then echo "Пароли не совпали, ещё раз."; continue; fi
  if [ ${#P} -lt 8 ]; then echo "Короче 8 символов, ещё раз."; continue; fi
  break
done
printf '%s\n' "$P" | sudo -u parentlock PARENTLOCK_DB=/var/lib/parentlock/db.sqlite python3 /opt/parentlock/parentlock_server.py set-password --slot SLOT \
  && systemctl restart parentlock && echo "Готово. Войдите на сайт с новым паролем."
'@
$remote = $remote.Replace('WHO', $who).Replace('SLOT', "$Slot").Replace("`r", "")
ssh -t -i $sshKey -o IdentitiesOnly=yes -o LogLevel=ERROR $sshHost $remote

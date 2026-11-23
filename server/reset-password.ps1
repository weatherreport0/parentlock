# Sets a new parent password for the site. The password is typed here and never shown.
. "$PSScriptRoot\env.ps1"
$remote = @'
while true; do
  read -r -s -p "Новый пароль для сайта (от 8 символов): " P; echo
  read -r -s -p "Повторите пароль: " P2; echo
  if [ "$P" != "$P2" ]; then echo "Пароли не совпали, ещё раз."; continue; fi
  if [ ${#P} -lt 8 ]; then echo "Короче 8 символов, ещё раз."; continue; fi
  break
done
printf '%s\n' "$P" | sudo -u parentlock PARENTLOCK_DB=/var/lib/parentlock/db.sqlite python3 /opt/parentlock/parentlock_server.py set-password \
  && systemctl restart parentlock && echo "Готово. Войдите на сайт с новым паролем."
'@
ssh -t -i $sshKey -o IdentitiesOnly=yes -o LogLevel=ERROR $sshHost $remote.Replace("`r", "")

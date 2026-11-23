#!/usr/bin/env bash
# Installs or updates the parent site on your server. Safe to run again for updates.
# Needs server/deploy.env (see deploy.env.example). Run from Git Bash, Linux or macOS.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/deploy.env"
KEY="${PARENTLOCK_SSH_KEY/#\~/$HOME}"
O=(-i "$KEY" -o IdentitiesOnly=yes -o LogLevel=ERROR)
H="$PARENTLOCK_HOST"

TMP="$(mktemp -d)"
sed "s/parent\.example\.com/$PARENTLOCK_DOMAIN/" "$HERE/nginx-parentlock.conf" > "$TMP/nginx-parentlock.conf"
scp "${O[@]}" "$HERE/parentlock_server.py" "$HERE/index.html" "$HERE/parentlock.service" \
    "$TMP/nginx-parentlock.conf" "$H:/tmp/"
rm -rf "$TMP"

ssh "${O[@]}" "$H" "DOMAIN='$PARENTLOCK_DOMAIN' bash -s" <<'REMOTE'
set -e
id parentlock >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin parentlock
mkdir -p /opt/parentlock /var/lib/parentlock
chown parentlock:parentlock /var/lib/parentlock && chmod 700 /var/lib/parentlock
install -m 644 /tmp/parentlock_server.py /tmp/index.html /opt/parentlock/
install -m 644 /tmp/parentlock.service /etc/systemd/system/parentlock.service
rm -f /tmp/parentlock_server.py /tmp/index.html /tmp/parentlock.service
systemctl daemon-reload
systemctl enable --quiet parentlock
systemctl restart parentlock

if [ ! -e /etc/nginx/sites-available/parentlock ]; then
  install -m 644 /tmp/nginx-parentlock.conf /etc/nginx/sites-available/parentlock
  ln -sf /etc/nginx/sites-available/parentlock /etc/nginx/sites-enabled/parentlock
  nginx -t && systemctl reload nginx
  if certbot --nginx -d "$DOMAIN" --non-interactive --redirect; then
    echo "HTTPS ready"
  else
    echo "!! certbot needs a one-time setup on this server. Run on the server: certbot --nginx -d $DOMAIN"
  fi
fi
rm -f /tmp/nginx-parentlock.conf
sleep 1
systemctl is-active parentlock
REMOTE
echo "Site: https://$PARENTLOCK_DOMAIN/  (first time: set the password with reset-password.ps1)"

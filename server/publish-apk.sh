#!/usr/bin/env bash
# Uploads the freshly built parentlock.apk to the site, so the parent can press
# "Обновить приложение на телефоне". Run from Git Bash after build.ps1.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/deploy.env"
KEY="${PARENTLOCK_SSH_KEY/#\~/$HOME}"
O=(-i "$KEY" -o IdentitiesOnly=yes -o LogLevel=ERROR)
APK="$HERE/../android/parentlock.apk"
AAPT="$LOCALAPPDATA/Android/Sdk/build-tools/36.1.0/aapt2.exe"
VER=$("$AAPT" dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
echo "$VER" > "$HERE/app.version"
scp "${O[@]}" "$APK" "$PARENTLOCK_HOST:/opt/parentlock/app.apk"
scp "${O[@]}" "$HERE/app.version" "$PARENTLOCK_HOST:/opt/parentlock/app.version"
ssh "${O[@]}" "$PARENTLOCK_HOST" 'chmod 644 /opt/parentlock/app.*'
echo "published $VER"

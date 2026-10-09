#!/usr/bin/env bash
# Erlina's web app, on the same server as the bot. Run after bot/deploy/setup.sh:
#   sudo bash web/deploy/setup.sh 'the family passcode'
# The first run sets the passcode and web address; later runs (and every bot
# update) just reinstall. To change the passcode, run it again with a new one.
set -euo pipefail

# School emails for the parents (see web/README.md):
#   sudo bash web/deploy/setup.sh --gmail-token gmail_token.json
#   sudo bash web/deploy/setup.sh --parents 'parents passcode'
ENV=/etc/familyhub-web.env
APP=/opt/family-hub/web
STATE=/var/lib/familyhub-web
[ -d "$APP" ] || { echo "Run bot/deploy/setup.sh first (it copies the repo to /opt/family-hub)."; exit 1; }

case "${1:-}" in
  --gmail-token)
    [ -f "${2:-}" ] || { echo "Usage: sudo bash web/deploy/setup.sh --gmail-token gmail_token.json"; exit 1; }
    install -d -m 700 -o familyhub -g familyhub "$STATE"
    install -m 600 -o familyhub -g familyhub "$2" "$STATE/gmail_token.json"
    systemctl restart familyhub-web
    echo "Gmail signed in. School emails are on."
    exit 0 ;;
  --parents)
    [ -n "${2:-}" ] && [ -f "$ENV" ] || { echo "Usage: sudo bash web/deploy/setup.sh --parents 'parents passcode' (after the first setup)"; exit 1; }
    sed -i '/^PARENT_PASSCODE=/d' "$ENV"
    printf 'PARENT_PASSCODE=%s\n' "$2" >> "$ENV"
    systemctl restart familyhub-web
    echo "Parents passcode set."
    exit 0 ;;
esac

if [ -n "${1:-}" ] || [ ! -f "$ENV" ]; then
  PASSCODE="${1:-}"
  [ -n "$PASSCODE" ] || { echo "Usage: sudo bash web/deploy/setup.sh 'family passcode'"; exit 1; }
  HOST="${WEB_HOST:-}"
  if [ -z "$HOST" ] && [ -f "$ENV" ]; then HOST="$(sed -n 's/^WEB_HOST=//p' "$ENV")"; fi
  if [ -z "$HOST" ]; then
    # A free address that points at this server's IP, e.g. 34-69-120-131.sslip.io.
    IP="$(curl -fsS -H 'Metadata-Flavor: Google' \
      http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip \
      || curl -fsS https://api.ipify.org)"
    HOST="${IP//./-}.sslip.io"
  fi
  PARENTS=""
  if [ -f "$ENV" ]; then PARENTS="$(sed -n 's/^PARENT_PASSCODE=//p' "$ENV")"; fi
  umask 027
  printf 'WEB_HOST=%s\nWEB_PASSCODE=%s\n' "$HOST" "$PASSCODE" > "$ENV"
  [ -z "$PARENTS" ] || printf 'PARENT_PASSCODE=%s\n' "$PARENTS" >> "$ENV"
  chown root:familyhub "$ENV"
  chmod 640 "$ENV"
fi
HOST="$(sed -n 's/^WEB_HOST=//p' "$ENV")"

# Caddy gets and renews the HTTPS certificate by itself. Ubuntu's own package
# is enough (Caddy's apt repo failed its signing-key check on 2026-10-07).
command -v caddy >/dev/null || apt-get install -y caddy
cat > /etc/caddy/Caddyfile <<CADDY
$HOST {
	encode gzip
	reverse_proxy 127.0.0.1:8080
}
CADDY
systemctl reload-or-restart caddy

python3 -m venv "$APP/.venv"
"$APP/.venv/bin/pip" install -q -r "$APP/requirements.txt"
chown -R familyhub:familyhub "$APP"
cp "$APP/deploy/familyhub-web.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable familyhub-web
systemctl restart familyhub-web
echo "Web app running at https://$HOST  Logs: sudo journalctl -u familyhub-web -f"

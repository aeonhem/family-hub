#!/usr/bin/env bash
# Run on the cloud server (Ubuntu) from inside the cloned repo:
#   sudo bash bot/deploy/setup.sh
# Then put .env and token.json in /opt/family-hub/bot and run:
#   sudo systemctl restart familyhub-bot
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
apt-get update -y
apt-get install -y python3 python3-venv git

id familyhub >/dev/null 2>&1 || useradd --system --home /opt/family-hub --shell /usr/sbin/nologin familyhub
if [ "$REPO_DIR" != /opt/family-hub ]; then
  # Keep secrets and state across updates.
  KEEP="$(mktemp -d)"
  for f in .env token.json state.json; do
    [ -f "/opt/family-hub/bot/$f" ] && cp "/opt/family-hub/bot/$f" "$KEEP/"
  done
  rm -rf /opt/family-hub
  cp -r "$REPO_DIR" /opt/family-hub
  cp -a "$KEEP"/. /opt/family-hub/bot/ 2>/dev/null || true
  rm -rf "$KEEP"
fi
python3 -m venv /opt/family-hub/bot/.venv
/opt/family-hub/bot/.venv/bin/pip install -q -r /opt/family-hub/bot/requirements.txt
chown -R familyhub:familyhub /opt/family-hub

cp /opt/family-hub/bot/deploy/familyhub-bot.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable familyhub-bot
if [ -f /opt/family-hub/bot/.env ] && [ -f /opt/family-hub/bot/token.json ]; then
  systemctl restart familyhub-bot
  echo "Bot started. Logs: sudo journalctl -u familyhub-bot -f"
else
  echo "Installed. Add .env and token.json to /opt/family-hub/bot, then: sudo systemctl restart familyhub-bot"
fi

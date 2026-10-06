# Erlina's Discord bot

Family Hub for Erlina's iPhone, through Discord:

- **7:00am DM**: today's events, dinner, and her chores as tick buttons.
- **Memos**: anything addressed to her or everyone arrives as a DM within a minute.
- **Commands**: `/today`, `/dinner`, `/chores`, `/memo`. She can also just
  type "what's for dinner?" or "chores" in the DM.
- Ticking a chore updates the Family calendar, so it shows as done in the
  Android app too, and she gets a "Nice! 2 of 3 done" message.

It reads and writes the Family Google Calendar using
[../docs/calendar-conventions.md](../docs/calendar-conventions.md).

## One-time setup

### 1. Discord bot account (about 5 minutes)

1. Go to https://discord.com/developers/applications, then **New Application**
   and name it "Family Hub".
2. **Bot** tab: **Reset Token**, copy it (this is `DISCORD_TOKEN`). Further
   down, switch on **Message Content Intent**.
3. **OAuth2 > URL Generator**: tick `bot` and `applications.commands`, open
   the generated link and add the bot to a small private server that Erlina
   is in (make one called "Family" if needed). Sharing a server is what lets
   the bot DM her.
4. In Discord, Settings > Advanced > **Developer Mode** on. Long-press
   Erlina's name and choose **Copy User ID** (`ERLINA_DISCORD_USER_ID`).

### 2. Let the bot use the Family calendar (about 10 minutes)

The Family calendar Google makes for a family group can't be shared with
outside accounts, so the bot signs in as you once and keeps a token.

1. Go to https://console.cloud.google.com, create a project called
   "Family Hub", and enable the **Google Calendar API**.
2. **APIs & Services > OAuth consent screen**: External, app name
   "Family Hub", your email. Add yourself as a test user, then press
   **Publish app** (otherwise Google expires the sign-in every 7 days).
3. **Credentials > Create credentials > OAuth client ID**, type **Desktop
   app**. Download the JSON as `client_secret.json`.
4. On your PC, in this `bot` folder:
   `pip install google-auth-oauthlib` then
   `python authorize.py client_secret.json`. Sign in with the account that
   owns the Family calendar and accept the "unverified app" warning (it's
   your own app). This writes `token.json`.
5. The Family calendar id is
   `family04174376375408900708@group.calendar.google.com`
   (`FAMILY_CALENDAR_ID`).

### 3. Free cloud server

Oracle Cloud's Always Free tier runs this at no cost.

1. Sign up at https://www.oracle.com/cloud/free/ (it asks for a card to
   check you're real; Always Free resources aren't charged).
2. **Create a VM instance**: image **Ubuntu 24.04**, shape
   **VM.Standard.E2.1.Micro** (Always Free). Download the SSH key it offers.
3. SSH in, then:

```
git clone https://<your-github-token>@github.com/aeonhem/family-hub.git
sudo bash family-hub/bot/deploy/setup.sh
```

The token is the read-only one you make for Obtainium (see
../android/README.md).

4. Copy `token.json` to `/opt/family-hub/bot/`, then create
   `/opt/family-hub/bot/.env` from `.env.example` with the values above, and:

```
sudo chown familyhub /opt/family-hub/bot/.env /opt/family-hub/bot/token.json
sudo systemctl restart familyhub-bot
sudo journalctl -u familyhub-bot -f
```

## Updating

```
cd ~/family-hub && git pull && sudo bash bot/deploy/setup.sh
```

`setup.sh` keeps `.env`, `token.json` and the bot's state across updates.

## Running locally

```
cd bot
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env   # fill it in
python bot.py
```

Tests: `pip install pytest && pytest tests`.

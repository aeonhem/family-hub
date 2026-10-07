# Family Hub web app

The Android app's four tabs (Today / Dinner / Chores / Memos) as a web page
Erlina can add to her iPhone home screen. It's an alternative to her Discord
bot; both keep running, so she can use either.

- Same screens, colours and rules as the Android app, reading and writing the
  Family Google Calendar per [../docs/calendar-conventions.md](../docs/calendar-conventions.md).
- **Memo alerts** through Web Push (iPhone with iOS 16.4 or newer, once it's
  on the home screen), plus a 7:00am summary for Erlina.
- A family **passcode** keeps the public address private. Changing it signs
  everyone out.
- Runs on the bot's free Google Cloud server and uses the bot's Google
  sign-in (`bot/token.json`), so there's nothing new to sign up for. Caddy
  gets a free HTTPS certificate for an address like
  `34-69-120-131.sslip.io` (sslip.io turns the server's IP into a name).

## One-time setup

1. **Open the web ports.** Google Cloud console > Compute Engine > VM
   instances > `familyhub-bot` > **Edit**. Under Networking > Firewalls tick
   **Allow HTTP traffic** and **Allow HTTPS traffic**, then **Save**.
2. **Install it** on the server, from the copied repo (the same way the bot
   is updated):

   ```
   sudo bash bot/deploy/setup.sh
   sudo bash web/deploy/setup.sh 'pick a family passcode'
   ```

   It prints the address, e.g. `https://34-69-120-131.sslip.io`.
3. **On Erlina's iPhone**: open the address in Safari, tap **Share**, then
   **Add to Home Screen**. Open Family Hub from the home screen, type the
   passcode, pick her name, and tap **Turn on alerts**.

After that, every `bot/deploy/setup.sh` run updates the web app too.

If the server's IP ever changes (only if the VM is stopped and started), run
`sudo rm /etc/familyhub-web.env` and then `web/deploy/setup.sh` with the
passcode again to get the new address, and re-add it to the home screen.

## Running locally

```
cd web
python -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
WEB_PASSCODE=test python server.py   # reads ../bot/.env and ../bot/token.json
```

Then open http://localhost:8080. Tests: `pip install pytest && pytest tests`.

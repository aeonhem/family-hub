# Family Hub web app

The Android app's four tabs (Today / Dinner / Chores / Memos) as a web page
Erlina can add to her iPhone home screen. It's an alternative to her Discord
bot; both keep running, so she can use either.

- Same screens, colours and rules as the Android app, reading and writing the
  Family Google Calendar per [../docs/calendar-conventions.md](../docs/calendar-conventions.md).
- **Memo alerts** through Web Push (iPhone with iOS 16.4 or newer, once it's
  on the home screen), plus a 7:00am summary for Erlina.
- **Weather** for Molendinar on the Today tab, from Open-Meteo (free, no
  key), fetched by the server at most once an hour. Set `WEATHER_LAT`,
  `WEATHER_LON` and `WEATHER_PLACE` to move it.
- **Themes** under the settings cog (Teal, Ocean, Berry, Forest, Night),
  remembered per phone.
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

## School emails for the parents

The Android app's Today screen shows Julian and Sally that week's emails from
@arcadia.qld.edu.au addresses, with a short summary. Swipe one away to move it
to Seen; that's saved here, so it's gone on both phones. Erlina never sees
them: they sit behind a second, parents-only passcode.

The server reads Julian's Gmail with its own read-only sign-in, separate from
the calendar one. To set it up (once):

1. In Google Cloud console, Family Hub project, turn on the **Gmail API**
   (APIs & Services > Library > Gmail API > Enable).
2. On the PC with `client_secret.json`:
   `python bot/authorize.py client_secret.json --gmail`, sign in as Julian and
   allow "Read your email". It writes `gmail_token.json`.
3. Copy it to the server and run
   `sudo bash web/deploy/setup.sh --gmail-token gmail_token.json`, then delete
   the copy on the server.
4. Pick a parents' passcode:
   `sudo bash web/deploy/setup.sh --parents 'parents passcode'`.
5. On each parent's phone, open Family Hub and type that passcode into the
   Arcadia emails card once.

## Memo alerts on the Android app (Firebase)

The Android app's own check reads the phone's calendar, and Android pauses it
while the phone is idle, so a memo could wait until the app was opened. The
server now also sends each new memo to Julian's and Sally's phones through
Firebase Cloud Messaging (free), which wakes an idle phone. It skips the
person who wrote the memo; Erlina keeps getting hers from the bot and Web Push.
A memo still only buzzes once if both get there.

Set up once:

1. At https://console.firebase.google.com (signed in as Julian), **Create a
   project**, choose the existing **Family Hub** Google Cloud project, and
   skip Google Analytics. The free Spark plan is all it needs.
2. Add an **Android** app with package name `com.aeonhem.familyhub` and
   download `google-services.json`. Commit it as
   `android/app/google-services.json` (it isn't secret); the next APK build
   has push in it.
3. Project settings > **Service accounts** > **Generate new private key**.
   Copy that file to the server and run
   `sudo bash web/deploy/setup.sh --fcm-key fcm_service_account.json`, then
   delete the copy.
4. Each phone registers itself once its person is picked and the parents'
   passcode has been entered in the school emails card.

## Smart Life lights (parents only)

The Android app's **Lights** tab, on Julian's and Sally's phones, switches and
dims the lights added in the Smart Life app. The server talks to Tuya's cloud
(the platform behind Smart Life) with a free Tuya IoT project linked to the
Smart Life account, and the phones only talk to the server. It uses the
parents' passcode, like the school emails.

Set up once:

1. At https://platform.tuya.com, sign up (free) and go to **Cloud >
   Development > Create Cloud Project**. Industry *Smart Home*, development
   method *Smart Home*, and the **data centre where the Smart Life account
   lives**. If you're not sure, add more than one; the link in step 3 only
   finds devices in the right one.
2. On the API services page, keep **IoT Core** and **Authorization Token
   Management** (both free).
3. In the project, **Devices > Link App Account > Add App Account**, and scan
   the QR code with the Smart Life app (Me > the scan icon, top right). The
   lights appear under **All Devices**. Copy the **UID** shown for the linked
   account.
4. From **Overview**, copy the **Access ID** and **Access Secret**, and make
   `tuya.json` (keep it private, it's in .gitignore):

   ```json
   {
     "endpoint": "https://openapi.tuyaus.com",
     "access_id": "...",
     "access_secret": "...",
     "uid": "..."
   }
   ```

   `endpoint` goes with the data centre: Western America
   `https://openapi.tuyaus.com`, Eastern America `https://openapi-ueaz.tuyaus.com`,
   Central Europe `https://openapi.tuyaeu.com`, Western Europe
   `https://openapi-weaz.tuyaeu.com`, India `https://openapi.tuyain.com`,
   China `https://openapi.tuyacn.com`.
5. Try it from the PC first: `python web/lights.py tuya.json` lists the
   lights, and `python web/lights.py tuya.json "bedroom lamp" off` switches
   one.
6. Put it on the server: `bash deploy_server.sh tuya.json` from the PC (or
   copy it there and run `sudo bash web/deploy/setup.sh --tuya tuya.json`),
   then delete any copy on the server.

New lights added in Smart Life show up in the tab by themselves. Bulbs and
strips get a brightness slider; plugs and switches get on/off.

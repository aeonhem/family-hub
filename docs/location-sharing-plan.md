# Family location sharing: plan

Status: plan only. No code, no server change. Written 2026-11-25.

Goal: a free, Life360-style "where is everyone" view inside Family Hub. The
location data stays on our own server.

## 1. Recommendation in short

Build a small location feature into the parts we already have:

- **Android app** (Julian, Sally): a new opt-in "Share my location" switch.
  When it is on, a foreground service sends the phone's position to the
  family server. A new **Map** tab shows everyone.
- **Family server** (`web/server.py`): three new endpoints and one small
  SQLite file. It keeps the last position of each person, plus a short
  history if Julian wants one.
- **Erlina (iPhone)**: the free OwnTracks app sends her position to our
  server in the background and shows the family on its own map (see 6).
  The web app gets a read-only **Map** tab too.
- **Place alerts** ("Sally got home"): the server checks each new position
  against saved places and sends an alert. This is a later step.
- **Discord bot**: no location features (Julian's decision).

Do not install OpenBeacon or openfamily on the server. Use both only as a
reference for the Android background location code and the battery rules.

## 2. The two projects

| | OpenBeacon (LSP-Software) | openfamily (wheresfrank) |
|---|---|---|
| What it is | Family location app, Android only, end-to-end encrypted | Self-hosted Life360 clone: live map, places, SOS, history |
| Licence | AGPL-3.0 **plus Commons Clause** (no selling; family self-host is allowed) | AGPL-3.0 |
| App stack | React Native (Expo) + a native Kotlin module for tracking | Flutter (Dart) + `background_locator_2` plugin |
| Server stack | TypeScript (Bun) API, scheduled-jobs, map-tiles: 3 containers + PostgreSQL + Redis + S3 storage | Go API + PostgreSQL/PostGIS/TimescaleDB + ntfy + Caddy, in Docker Compose |
| Collection | Foreground service (`foregroundServiceType="location"`), Fused Location Provider. Every 10 s moving / 30 s still; uploads every 60 s; slower when battery saver or battery <= 15 %. Skips points if still and < 25 m moved, max 5 min skip. | Foreground service via plugin, fix every 60 s (`distanceFilter` 0) in background; 50 m stream in foreground. Asks for battery-optimisation exemption. |
| Transport | Encrypted on phone, queued offline, uploaded in batches. FCM data push to "locate now". | REST + WebSocket. UnifiedPush/ntfy (no Google). |
| Storage | Encrypted blobs in Postgres; history up to 14 days, each person chooses | Postgres; 90-day history, last-known position kept |
| iPhone | Not yet (roadmap) | Flutter iOS app, needs App Store or a paid dev account to install |

### Can we reuse them?

- **Self-host beside our server: no.** Both need PostgreSQL and Docker with
  several containers. OpenBeacon also needs Redis and S3. The e2-micro has
  1 GB RAM and about 2.8 GB disk free, and it already runs the bot and the
  web server. This would not fit, and it would be a second app with its own
  accounts, not part of Family Hub.
- **Embed the code: no.** The stacks do not match our Kotlin/Compose app and
  our Python server. Both are AGPL. If we copied code into Family Hub, the
  whole Family Hub repo would come under AGPL (and Commons Clause for
  OpenBeacon). That is legal for a family, but it is a change Julian must
  agree to. We do not need to copy code.
- **Use as a reference: yes.** We write our own Kotlin from the ideas, not
  the code:
  - OpenBeacon's `CaptureSamplingPolicy.kt`: moving vs. still intervals,
    battery-saver slow-down, skip points when still. A clear model for
    battery use.
  - OpenBeacon's FCM "locate now" wake with a 30 s cool-down. We already
    have FCM, so this is cheap to add.
  - OpenBeacon's offline queue: keep points on the phone when there is no
    signal, send them later.
  - openfamily's onboarding order: ask "While using", then "Allow all the
    time", then the battery-optimisation exemption.
  - openfamily's OpenStreetMap map and its notes on tile hosting.
- **Use one of them as-is instead of building**: possible as a fallback.
  OpenBeacon's hosted server is free but it is their server, not ours, and
  it is a separate app. This fails the "data stays on our server" goal.

## 3. Where it fits in Family Hub

Today all data is on Google Calendar and the server keeps small JSON files
in `STATE_DIR` (`Store` in `web/server.py`). Location does not belong on the
calendar: it changes every minute and must not show in Google Calendar.

```
 Julian's / Sally's phone                 family server (e2-micro)
 ┌──────────────────────┐   HTTPS POST   ┌───────────────────────────┐
 │ LocationService (FGS)│ ─────────────▶ │ /api/location  (aiohttp)  │
 │ Fused Location, queue│                │ location.db (SQLite)      │
 │ Map tab (OSM)        │ ◀───────────── │ /api/locations            │
 └──────────▲───────────┘   GET          └──────┬──────────▲─────────┘
            │ FCM "locate now"                  │          │ GET
            └───────────────────────────────────┘   Erlina's web app Map tab
```

### Server (`web/server.py`, aiohttp, not Flask)

- `POST /api/location` — a phone sends one or more points:
  `{person, points: [{lat, lon, acc, t, battery}]}`. Parents' token only.
- `GET /api/locations` — last point of each person who shares, plus age.
  Family and parents' tokens (Erlina sees the parents).
- `POST /api/location/locate` — ask a phone for a fresh fix through FCM
  (rate-limited, one per 30 s per person).
- Optional `GET /api/location/history?person=&day=` if Julian wants history.
- Storage: one SQLite file `STATE_DIR/location.db` (Python's built-in
  `sqlite3`, no new package). Table `last` (one row per person) and table
  `points` (history, only if on). A daily job deletes points older than the
  retention limit. Do not use the JSON `Store` for points: rewriting a JSON
  file each minute is wasteful.

### Identity (a gap today)

The server only knows two shared passcodes (family, parents). It does not
know *who* sends a request. `phones.json` already stores `{person, token}`
for FCM. For location, the phone sends its `person` name, the same as for
`/api/phone/register`. Anyone with the parents' passcode could send a
position "as Sally". For a family of three this is acceptable, but it is a
decision (see 9). A stronger option is a per-phone secret made at register
time.

### Android app

- New permissions: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`,
  `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_LOCATION`. Already present: `POST_NOTIFICATIONS`,
  `RECEIVE_BOOT_COMPLETED`, `INTERNET`.
- New dependency: `com.google.android.gms:play-services-location`
  (Fused Location Provider). The app already uses Firebase, so Google Play
  services are already on the phones.
- `LocationService`: a foreground service with type `location` and a
  permanent notification "Sharing your location with the family". Android
  requires this notification; it cannot be hidden.
- Sampling, after OpenBeacon: 30 s still / 10 s moving is more than we
  need. Start with **one fix every 2 min when still, every 30 s when
  moving, upload at most once a minute**, balanced power
  (`PRIORITY_BALANCED_POWER_ACCURACY`, about 100 m) when still, high
  accuracy only when moving. Slower again in battery saver.
- Offline queue: keep unsent points in a small file or Room table; send
  them when the network returns.
- Restart after reboot through the existing `BootReceiver`.
- FCM "locate now": extend `MemoPushService` to handle a `locate` message.
- **Map** tab: OpenStreetMap through osmdroid (Apache-2.0) or a simple
  WebView with Leaflet. No Google Maps key needed.

### Web app (`web/static/`)

- **Map** tab with Leaflet (BSD-2) and OSM tiles. The CSP today is
  `img-src 'self' data:`, so it must allow the tile host.

## 4. Privacy

- **Opt-in per person.** Off by default. Each person turns on their own
  sharing on their own phone. Nobody can turn it on for someone else.
- **Pause** switch: stop sharing for 1 h / until tomorrow / until I turn it
  back on. The server then shows "paused", not the old position.
- **Who sees whom.** Everyone in the family sees everyone who shares.
- **History.** None. Each new position replaces the old one.
- **Data stays on our server.** No third-party location service. The phone
  talks only to `34-69-120-131.sslip.io`. Google sees only the FCM wake
  message (no position inside it). OSM tile servers see which map area is
  viewed, not who is where; self-hosted tiles can remove that later.
- **Encryption.** HTTPS in transit (Caddy). On the server, positions are in
  plain SQLite readable by anyone with server access (Julian). End-to-end
  encryption like OpenBeacon is possible but is a big extra job; not
  proposed for step 1.
- **Logs.** Do not log positions. Request logs must not print the body.
  (The disk filled with logs on 2026-11-01.)
- Delete: a "Forget my location" button deletes that person's rows.

## 5. Battery

The foreground service is the main cost. With the sampling in 3 and
balanced power when still, expect a few percent per day, like Life360. Two
things matter more than the interval:

- Some phone makers (Samsung, Xiaomi, OnePlus) kill background apps. The
  app must ask the user to exclude it from battery optimisation, and show
  a warning when the last upload is old.
- "Allow all the time" location is needed. Without it Android 14/15 stops
  the service when the app is closed.

## 6. Sideload and iPhone limits

- **Sideload is fine.** Google Play's background-location review does not
  apply to a sideloaded APK. The user still must pick "Allow all the time"
  in system settings by hand; Android 11+ does not show that choice in the
  normal dialog. The app must send the user to the settings page.
- Play Protect may warn on install of an app with background location. Tap
  "Install anyway".
- Android 8 (our `minSdk` 26) and up are fine. Android 10+ needs the
  separate background permission; Android 14+ needs the
  `FOREGROUND_SERVICE_LOCATION` permission and service type.
- **Erlina (iPhone, no app).** A home-screen web app cannot share location
  in the background on iOS. It can send her position only while the page
  is open. Julian finds that too annoying, so the plan uses OwnTracks.

### OwnTracks for Erlina

OwnTracks is a free, open-source location app on the App Store
(github.com/owntracks/ios, active in 2026). It has no company server of its
own. You type in the address of *your* server, and it sends positions there.

- **HTTP mode.** In Settings → Connection, mode "HTTP", URL
  `https://34-69-120-131.sslip.io/api/owntracks`, user `erlina`, password =
  the family passcode. It POSTs a small JSON message
  (`{"_type":"location","lat":..,"lon":..,"acc":..,"batt":..,"tst":..}`)
  with HTTP Basic auth over HTTPS. If there is no signal, it queues the
  message and sends it later.
- **Background on iOS.** Apple allows this for real apps. OwnTracks'
  "significant changes" mode wakes on about 500 m of movement and is very
  light on battery. "Move" mode is more exact but uses more battery. Erlina
  must pick "Always" location for OwnTracks in iPhone settings.
- **She sees the family too.** Our server's reply to each POST can contain
  the parents' last positions. OwnTracks then shows them on its own map.
  So OwnTracks is both her "share" and her "map", with no web app open.
- **Place alerts.** OwnTracks also has regions with enter/leave events,
  but we do the place check on the server for everyone, so it is the same
  for all phones.
- **Server work.** One extra endpoint, `POST /api/owntracks`, that accepts
  Basic auth with the family passcode, maps user `erlina` to "Erlina",
  stores the point like any other, and returns the others' positions as
  OwnTracks `location` objects. Small: about 50 lines plus tests.
- **Limits.** It is a second app on her phone, not Family Hub. It shows
  updates only when it sends one (no live polling in HTTP mode). The
  passcode is stored in the OwnTracks settings on her phone.

### Why not a Family Hub iPhone app (yet)

A native iPhone app can share location in the background, but:

- Installing your own app on an iPhone needs a **Mac with Xcode**.
- With a free Apple ID, the app **stops working after 7 days** and must be
  installed again from the Mac each week.
- Without that limit it needs the **Apple Developer Program, 99 USD a
  year**, then TestFlight or ad-hoc install. That breaks "free".
- It is a second app to write and keep (Swift, or a rewrite of the Android
  app in a cross-platform tool).

So: OwnTracks now. If Julian later pays for the developer account, an
iPhone Family Hub app can replace OwnTracks with the same server endpoints.

## 7. Server load and disk

Disk free: about 2.8 GB (checked by the other session; check `df -h` again
before deploy, as the memory note says).

Estimate for 2 phones (3 with Erlina):

- Requests: at most 1 POST per phone per minute = under 3,000 a day. The
  e2-micro handles this easily; each request is a few hundred bytes.
- Last position only: under 1 KB in total.
- History, 7 days: about 700 points per phone per day (still at 2 min,
  moving at 30 s), about 60 bytes a row in SQLite → about 40 KB per phone
  per day → **under 1 MB a week for the family**. Even 90 days stays under
  15 MB.
- RAM: SQLite adds almost nothing. No new service, no Docker.
- Logs are the real disk risk. Keep location requests at debug level or
  off.

## 8. Steps (when Julian says go)

1. Server: SQLite `last` table only, `POST /api/location`,
   `GET /api/locations`, tests.
2. Android: permissions flow, `LocationService`, upload, pause, Map tab.
3. Server: `POST /api/owntracks` for Erlina. Then set up OwnTracks on her
   iPhone.
4. Web app: read-only Map tab.
5. FCM "locate now".
6. Places and alerts: a `places` table (name, centre, radius, e.g. Home
   100 m, School 150 m). The server keeps each person's "inside/outside"
   state and, on a change, sends "Sally got home" to the others through
   the existing FCM and Web Push paths. Add hysteresis (must be inside for
   2 points or 2 min) so GPS jumps do not cause false alerts. Places are
   set from the Android app's Map tab.

Each step is its own PR. Run the scanner-triage skill before each deploy.

## 9. Decisions (Julian, 2026-11-25)

1. **Who sees whom:** everyone sees everyone who shares.
2. **History:** none. The server keeps only each person's last position.
   No `points` table, no history endpoint, no nightly clean-up job.
3. **Erlina:** OwnTracks on her iPhone (background sharing, and she sees
   the family in OwnTracks). The web app is not her main way to share. An
   iPhone Family Hub app is parked: it needs a Mac and 99 USD a year (6).
4. **Identity:** each phone says who it is. No per-phone secret.
5. **Map tiles:** public OpenStreetMap tiles, with the attribution line.
6. **Discord `/where`:** no.
7. **Place alerts:** yes, as step 6.

Still open: none for the first steps. For step 6, Julian picks the places
and their radius.

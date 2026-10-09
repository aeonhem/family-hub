"""Family Hub as a web app, so Erlina can add it to her iPhone home screen.

The same Today / Dinner / Chores / Memos tabs as the Android app, plus memo
alerts and a 7am summary through Web Push (iOS 16.4+ once it's on the home
screen).

Runs on the same server as the Discord bot and shares its Google sign-in
(bot/token.json) and calendar code (bot/calendar_store.py), so everything
follows docs/calendar-conventions.md.
"""
from __future__ import annotations

import asyncio
import base64
import functools
import hashlib
import hmac
import json
import logging
import os
import secrets
import sys
import time as clock
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from aiohttp import web
from dotenv import load_dotenv

HERE = Path(__file__).resolve().parent
BOT_DIR = Path(os.getenv("BOT_DIR", HERE.parent / "bot"))
sys.path.insert(0, str(BOT_DIR))
import calendar_store as cs  # noqa: E402

load_dotenv(HERE / ".env")  # local runs; on the server systemd passes /etc/familyhub-web.env
load_dotenv(BOT_DIR / ".env")  # calendar id, timezone and token file, shared with the bot

log = logging.getLogger("familyhub-web")

FAMILY = ["Julian", "Sally", "Erlina"]
KID = os.getenv("KID_NAME", "Erlina")
TZ = ZoneInfo(os.getenv("TIMEZONE", "Australia/Brisbane"))
MORNING = time.fromisoformat(os.getenv("MORNING_TIME", "07:00"))
MORNING_LATEST = time(11, 0)
STATE_DIR = Path(os.getenv("STATE_DIRECTORY", HERE / "state"))
STATIC = HERE / "static"
WATCH_EVERY = 30  # seconds between memo checks
MAX_LOGIN_FAILURES = 8  # per address, per LOCKOUT
LOCKOUT = 15 * 60
# Weather for the house: Molendinar QLD, from Julian's map link. Open-Meteo is
# free and needs no key. Fetched at most once an hour, shared by every phone.
WEATHER_PLACE = os.getenv("WEATHER_PLACE", "Molendinar")
WEATHER_LAT = float(os.getenv("WEATHER_LAT", "-27.9744"))
WEATHER_LON = float(os.getenv("WEATHER_LON", "153.359"))
WEATHER_EVERY = 60 * 60
WEATHER_URL = "https://api.open-meteo.com/v1/forecast"


# ---------- pure helpers (tested) ----------

def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def session_token(secret: bytes, passcode: str) -> str:
    """What the browser keeps after the passcode is typed once. Changing the
    passcode signs everyone out."""
    return b64url(hmac.new(secret, passcode.encode(), hashlib.sha256).digest())


def item_json(i: cs.Item) -> dict:
    return {
        "id": i.event_id,
        "kind": i.kind,
        "title": i.title,
        "day": i.day.isoformat(),
        "end_day": (i.end_day or i.day).isoformat(),
        "time": i.start.strftime("%H:%M") if i.start else None,
        "start_at": int(i.start.timestamp() * 1000) if i.start else None,
        "ends_at": int(i.end.timestamp() * 1000) if i.end else None,
        "done": i.done,
        "for": i.for_who,
        "from": i.from_who,
        "cook": i.cook,
        "done_by": i.done_by,
    }


def memo_recipients(memo: cs.Item, subs: list[dict]) -> list[dict]:
    """Subscriptions that should buzz for [memo]: addressed to that person (or
    everyone), and not the person who sent it."""
    sender = (memo.from_who or "").lower()
    return [s for s in subs if memo.is_for(s["person"]) and s["person"].lower() != sender]


def morning_text(items: list[cs.Item], d: date, name: str) -> str:
    dinner = next((i for i in items if i.kind == "dinner" and i.covers(d)), None)
    chores = [i for i in items if i.kind == "chore" and not i.done and i.covers(d) and i.is_for(name)]
    events = [i for i in items if i.kind == "event" and i.covers(d)]
    parts = [f"Dinner: {dinner.title}" if dinner else "Dinner not planned yet"]
    parts.append(f"{len(chores)} chore{'s' if len(chores) != 1 else ''} today" if chores else "No chores today")
    if events:
        parts.append(f"{len(events)} event{'s' if len(events) != 1 else ''}")
    return " · ".join(parts)


# WMO weather codes, as Open-Meteo reports them. Same as the Android app's Weather.kt.
def weather_label(code: int) -> str:
    table = {0: "Clear", 1: "Mostly clear", 2: "Partly cloudy", 3: "Cloudy", 45: "Fog", 48: "Fog",
             61: "Light rain", 66: "Light rain", 63: "Rain", 65: "Heavy rain", 67: "Heavy rain",
             80: "Showers", 81: "Heavy showers", 82: "Heavy showers", 95: "Thunderstorms",
             96: "Storms with hail", 99: "Storms with hail"}
    if code in (51, 53, 55, 56, 57):
        return "Drizzle"
    if code in (71, 73, 75, 77, 85, 86):
        return "Snow"
    return table.get(code, "Unknown")


def weather_icon(code: int, is_day: bool) -> str:
    if code == 0:
        return "☀️" if is_day else "🌙"
    if code == 1:
        return "🌤️" if is_day else "🌙"
    groups = [((2,), "⛅"), ((3,), "☁️"), ((45, 48), "🌫️"), ((51, 53, 55, 56, 57, 80, 81, 82), "🌦️"),
              ((61, 63, 65, 66, 67), "🌧️"), ((71, 73, 75, 77, 85, 86), "❄️"), ((95, 96, 99), "⛈️")]
    return next((icon for codes, icon in groups if code in codes), "🌡️")


def weather_json(data: dict, fetched_at: float) -> dict:
    """Open-Meteo's reply, cut down to what the Today card shows."""
    cur, daily = data["current"], data["daily"]
    code, is_day = int(cur["weather_code"]), cur.get("is_day", 1) == 1
    rain = (daily.get("precipitation_probability_max") or [None])[0]
    return {
        "place": WEATHER_PLACE,
        "temp": cur["temperature_2m"],
        "feels": cur.get("apparent_temperature", cur["temperature_2m"]),
        "label": weather_label(code),
        "icon": weather_icon(code, is_day),
        "high": daily["temperature_2m_max"][0],
        "low": daily["temperature_2m_min"][0],
        "rain": rain,
        "fetched_at": int(fetched_at * 1000),
    }


def name_or_none(value) -> str | None:
    return value if value in FAMILY else None


# ---------- files on disk ----------

class Store:
    """Small JSON files in STATE_DIR: push subscriptions, memos already
    pushed, and the keys this server made for itself."""

    def __init__(self, folder: Path):
        self.folder = folder
        folder.mkdir(parents=True, exist_ok=True)

    def read(self, name: str, default):
        try:
            return json.loads((self.folder / name).read_text())
        except (FileNotFoundError, json.JSONDecodeError):
            return default

    def write(self, name: str, value) -> None:
        tmp = self.folder / (name + ".tmp")
        tmp.write_text(json.dumps(value))
        tmp.replace(self.folder / name)

    def secret(self) -> bytes:
        path = self.folder / "session_secret"
        if not path.exists():
            path.write_bytes(secrets.token_bytes(32))
            path.chmod(0o600)
        return path.read_bytes()

    def vapid(self) -> tuple[Path, str]:
        """Web Push signing key: (private key file, public key for the browser)."""
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec

        path = self.folder / "vapid_private.pem"
        if not path.exists():
            key = ec.generate_private_key(ec.SECP256R1())
            path.write_bytes(key.private_bytes(
                serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            path.chmod(0o600)
        key = serialization.load_pem_private_key(path.read_bytes(), password=None)
        public = key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        return path, b64url(public)


# ---------- Google and push calls (blocking libraries, run off the event loop) ----------

# The Google client's HTTP layer isn't thread-safe, so every call uses this one thread.
_google = ThreadPoolExecutor(max_workers=1, thread_name_prefix="google")
_push = ThreadPoolExecutor(max_workers=2, thread_name_prefix="push")


async def gcal(fn, *args):
    return await asyncio.get_running_loop().run_in_executor(_google, functools.partial(fn, *args))


def _calendar() -> cs.FamilyCalendar:
    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build

    token = Path(os.getenv("GOOGLE_TOKEN_FILE", "token.json"))
    if not token.is_absolute():
        token = BOT_DIR / token
    creds = Credentials.from_authorized_user_file(str(token))
    service = build("calendar", "v3", credentials=creds, cache_discovery=False)
    return cs.FamilyCalendar(service, os.environ["FAMILY_CALENDAR_ID"], TZ)


def _send_push(sub: dict, payload: dict, key_file: Path, contact: str) -> int | None:
    """Sends one notification. Returns the push service's status when it
    says the subscription is gone (404/410), else None."""
    from pywebpush import WebPushException, webpush

    try:
        webpush(sub, json.dumps(payload), vapid_private_key=str(key_file),
                vapid_claims={"sub": contact}, ttl=12 * 3600, headers={"Urgency": "high"})
    except WebPushException as e:
        status = getattr(e.response, "status_code", None)
        if status in (404, 410):
            return status
        raise
    return None


async def fetch_weather() -> dict:
    from aiohttp import ClientSession, ClientTimeout

    params = {
        "latitude": WEATHER_LAT, "longitude": WEATHER_LON,
        "current": "temperature_2m,apparent_temperature,weather_code,is_day",
        "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        "timezone": str(TZ), "forecast_days": 1,
    }
    async with ClientSession(timeout=ClientTimeout(total=10)) as session:
        async with session.get(WEATHER_URL, params=params) as resp:
            resp.raise_for_status()
            return weather_json(await resp.json(), clock.time())


# ---------- the app ----------

def today() -> date:
    return datetime.now(TZ).date()


class Hub:
    def __init__(self, cal: cs.FamilyCalendar, store: Store, passcode: str, contact: str):
        self.cal = cal
        self.store = store
        self.passcode = passcode
        self.token = session_token(store.secret(), passcode)
        self.vapid_key, self.vapid_public = store.vapid()
        self.contact = contact
        self.failures: dict[str, list[float]] = {}
        self.subs: list[dict] = store.read("subscriptions.json", [])
        self.state: dict = store.read("state.json", {})
        self.weather_cache: dict | None = None
        self.weather_lock = asyncio.Lock()

    # --- auth ---

    @web.middleware
    async def auth(self, request: web.Request, handler):
        if request.path.startswith("/api/") and request.path != "/api/login":
            given = request.headers.get("Authorization", "").removeprefix("Bearer ")
            if not hmac.compare_digest(given.encode(), self.token.encode()):
                raise web.HTTPUnauthorized(text="Passcode needed")
        return await handler(request)

    async def login(self, request: web.Request) -> web.Response:
        ip = request.headers.get("X-Forwarded-For", request.remote or "").split(",")[0].strip()
        now = clock.monotonic()
        recent = [t for t in self.failures.get(ip, []) if now - t < LOCKOUT]
        if len(recent) >= MAX_LOGIN_FAILURES:
            return web.json_response({"error": "Too many tries. Wait 15 minutes."}, status=429)
        body = await request.json()
        if hmac.compare_digest(str(body.get("passcode", "")).strip().encode(), self.passcode.encode()):
            self.failures.pop(ip, None)
            return web.json_response({"token": self.token})
        self.failures[ip] = recent + [now]
        await asyncio.sleep(1)
        return web.json_response({"error": "That's not the passcode."}, status=403)

    # --- reading ---

    async def items(self, request: web.Request) -> web.Response:
        d = today()
        week_start = d - timedelta(days=d.weekday())
        items = await gcal(self.cal.items, week_start - timedelta(days=14), d + timedelta(days=14))
        return web.json_response({"today": d.isoformat(), "items": [item_json(i) for i in items]})

    async def weather(self, request: web.Request) -> web.Response:
        async with self.weather_lock:
            fresh = self.weather_cache and clock.time() - self.weather_cache["fetched_at"] / 1000 < WEATHER_EVERY
            if not fresh:
                try:
                    self.weather_cache = await fetch_weather()
                except Exception:
                    log.exception("weather fetch failed")
                    if not self.weather_cache:
                        return web.json_response({"error": "Can't get the weather right now"}, status=502)
        return web.json_response(self.weather_cache)

    # --- writing ---

    async def toggle_chore(self, request: web.Request) -> web.Response:
        body = await request.json()
        item = await gcal(self.cal.get, request.match_info["id"])
        if item.kind != "chore":
            raise web.HTTPBadRequest(text="Not a chore")
        await gcal(self.cal.set_chore_done, item, bool(body.get("done")), name_or_none(body.get("by")) or "")
        return web.json_response({"ok": True})

    async def add_chore(self, request: web.Request) -> web.Response:
        body = await request.json()
        title = str(body.get("title", "")).strip()[:200]
        if not title:
            raise web.HTTPBadRequest(text="Chore needs a name")
        day = date.fromisoformat(body["day"])
        await gcal(self.cal.add_chore, day, title, name_or_none(body.get("for")), name_or_none(body.get("from")))
        return web.json_response({"ok": True})

    async def save_dinner(self, request: web.Request) -> web.Response:
        body = await request.json()
        meal = str(body.get("meal", "")).strip()[:200]
        if not meal:
            raise web.HTTPBadRequest(text="Dinner needs a name")
        existing = None
        if body.get("id"):
            existing = await gcal(self.cal.get, body["id"])
            if existing.kind != "dinner":
                raise web.HTTPBadRequest(text="Not a dinner")
        cook = body.get("cook")
        cook = cook if cook in FAMILY + ["Takeaway"] else None
        await gcal(self.cal.save_dinner, existing, date.fromisoformat(body["day"]),
                   time.fromisoformat(body["time"]), meal, cook)
        return web.json_response({"ok": True})

    async def send_memo(self, request: web.Request) -> web.Response:
        body = await request.json()
        text = str(body.get("body", "")).strip()[:1000]
        if not text:
            raise web.HTTPBadRequest(text="Memo is empty")
        to = body.get("to") if body.get("to") in FAMILY else "Everyone"
        await gcal(self.cal.send_memo, text, name_or_none(body.get("from")) or "Someone", to)
        return web.json_response({"ok": True})

    # --- push ---

    async def vapid(self, request: web.Request) -> web.Response:
        return web.json_response({"key": self.vapid_public})

    async def subscribe(self, request: web.Request) -> web.Response:
        body = await request.json()
        person, sub = name_or_none(body.get("person")), body.get("subscription") or {}
        if not person or not str(sub.get("endpoint", "")).startswith("https://"):
            raise web.HTTPBadRequest(text="Bad subscription")
        self.subs = [s for s in self.subs if s["sub"]["endpoint"] != sub["endpoint"]]
        self.subs.append({"person": person, "sub": sub})
        self.store.write("subscriptions.json", self.subs)
        return web.json_response({"ok": True})

    async def unsubscribe(self, request: web.Request) -> web.Response:
        endpoint = (await request.json()).get("endpoint")
        self.subs = [s for s in self.subs if s["sub"]["endpoint"] != endpoint]
        self.store.write("subscriptions.json", self.subs)
        return web.json_response({"ok": True})

    async def push(self, subs: list[dict], payload: dict) -> None:
        loop = asyncio.get_running_loop()
        for s in subs:
            try:
                gone = await loop.run_in_executor(
                    _push, _send_push, s["sub"], payload, self.vapid_key, self.contact)
            except Exception:
                log.exception("push to %s failed", s["person"])
                continue
            if gone:
                log.info("dropping %s's expired subscription", s["person"])
                self.subs = [x for x in self.subs if x["sub"]["endpoint"] != s["sub"]["endpoint"]]
                self.store.write("subscriptions.json", self.subs)

    async def deliver_memos(self) -> None:
        first_run = "seen_memos" not in self.state
        seen = set(self.state.get("seen_memos", []))
        d = today()
        memos = [i for i in await gcal(self.cal.items, d - timedelta(days=1), d + timedelta(days=1))
                 if i.kind == "memo"]
        new = [m for m in memos if m.event_id not in seen]
        if not new:
            return
        if not first_run:  # don't push old memos the first time the server starts
            for m in new:
                await self.push(memo_recipients(m, self.subs), {
                    "title": f"Memo from {m.from_who or 'the family'}",
                    "body": m.title, "tag": m.event_id, "url": "/?tab=memos",
                })
        self.state["seen_memos"] = (self.state.get("seen_memos", []) + [m.event_id for m in new])[-500:]
        self.store.write("state.json", self.state)

    async def morning(self) -> None:
        now = datetime.now(TZ)
        if not (MORNING <= now.time() < MORNING_LATEST) or self.state.get("morning_sent") == now.date().isoformat():
            return
        kid_subs = [s for s in self.subs if s["person"] == KID]
        if kid_subs:
            items = await gcal(self.cal.items, now.date(), now.date())
            await self.push(kid_subs, {"title": f"Good morning {KID}", "body": morning_text(items, now.date(), KID),
                                       "tag": "morning", "url": "/"})
        self.state["morning_sent"] = now.date().isoformat()
        self.store.write("state.json", self.state)

    async def watch(self, app: web.Application):
        async def loop():
            while True:
                try:
                    await self.deliver_memos()
                    await self.morning()
                except Exception:
                    log.exception("memo check failed; will retry")
                await asyncio.sleep(WATCH_EVERY)

        task = asyncio.create_task(loop())
        yield
        task.cancel()


@web.middleware
async def errors(request: web.Request, handler):
    """Calendar problems come back as a short message the page can show."""
    try:
        resp = await handler(request)
    except web.HTTPException:
        raise
    except Exception as e:
        log.exception("%s %s failed", request.method, request.path)
        return web.json_response({"error": f"Couldn't reach the Family calendar ({type(e).__name__})"}, status=502)
    if isinstance(resp, web.FileResponse) or request.path == "/":
        # Small app; always check for a newer version.
        resp.headers["Cache-Control"] = "no-cache"
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["Referrer-Policy"] = "no-referrer"
    resp.headers["Content-Security-Policy"] = (
        "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'")
    return resp


def make_app(hub: Hub) -> web.Application:
    app = web.Application(middlewares=[errors, hub.auth], client_max_size=64 * 1024)
    app.router.add_post("/api/login", hub.login)
    app.router.add_get("/api/items", hub.items)
    app.router.add_get("/api/weather", hub.weather)
    app.router.add_post("/api/chores", hub.add_chore)
    app.router.add_post("/api/chores/{id}/done", hub.toggle_chore)
    app.router.add_post("/api/dinner", hub.save_dinner)
    app.router.add_post("/api/memos", hub.send_memo)
    app.router.add_get("/api/push/key", hub.vapid)
    app.router.add_post("/api/push/subscribe", hub.subscribe)
    app.router.add_post("/api/push/unsubscribe", hub.unsubscribe)

    async def index(request):
        return web.FileResponse(STATIC / "index.html")

    async def worker(request):
        # Served from the root so it can handle the whole site.
        return web.FileResponse(STATIC / "sw.js", headers={"Content-Type": "text/javascript"})

    app.router.add_get("/", index)
    app.router.add_get("/sw.js", worker)
    app.router.add_static("/", STATIC)
    app.cleanup_ctx.append(hub.watch)
    return app


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    passcode = os.getenv("WEB_PASSCODE", "").strip()
    if not passcode:
        raise SystemExit("Set WEB_PASSCODE")
    host = os.getenv("WEB_HOST", "localhost")
    hub = Hub(_calendar(), Store(STATE_DIR), passcode, os.getenv("WEB_CONTACT") or f"https://{host}")
    web.run_app(make_app(hub), host=os.getenv("WEB_BIND", "127.0.0.1"), port=int(os.getenv("WEB_PORT", "8080")))


if __name__ == "__main__":
    main()

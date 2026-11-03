import os
import sys
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
os.environ.setdefault("FAMILY_CALENDAR_ID", "test")
import server  # noqa: E402
from server import cs  # noqa: E402

TZ = ZoneInfo("Australia/Brisbane")
D = date(2026, 10, 7)


def item(kind, title="x", day=D, start=None, **meta):
    return cs.Item(title, kind, title, day, start, meta.pop("done", False), {k.rstrip("_").replace("_", "-"): v for k, v in meta.items()})


def subs(*people):
    return [{"person": p, "sub": {"endpoint": f"https://push.example/{p}"}} for p in people]


def test_memo_recipients_skip_sender_and_others():
    everyone = item("memo", from_="Sally")
    assert [s["person"] for s in server.memo_recipients(everyone, subs("Julian", "Sally", "Erlina"))] == ["Julian", "Erlina"]
    to_erlina = item("memo", for_="Erlina", from_="Julian")
    assert [s["person"] for s in server.memo_recipients(to_erlina, subs("Julian", "Sally", "Erlina"))] == ["Erlina"]


def test_morning_text():
    items = [item("dinner", "Tacos"), item("chore", "Bins", for_="Erlina"), item("chore", "Dishes", for_="Sally"),
             item("chore", "Bed", for_="Erlina", done=True), item("event", "Swim")]
    assert server.morning_text(items, D, "Erlina") == "Dinner: Tacos · 1 chore today · 1 event"
    assert server.morning_text([], D, "Erlina") == "Dinner not planned yet · No chores today"


def test_item_json_times():
    start = datetime(2026, 10, 7, 18, 30, tzinfo=TZ)
    ev = cs.parse_event({"id": "d", "summary": "🍽 Pho", "description": "cook: Julian",
                         "start": {"dateTime": start.isoformat()}, "end": {"dateTime": "2026-10-07T19:30:00+10:00"}}, TZ)
    j = server.item_json(ev)
    assert j["kind"] == "dinner" and j["time"] == "18:30" and j["cook"] == "Julian"
    assert j["day"] == j["end_day"] == "2026-10-07"
    assert j["ends_at"] - j["start_at"] == 3600_000
    chore = server.item_json(item("chore", "Bins", for_="Erlina"))
    assert chore["time"] is None and chore["for"] == "Erlina"


def test_session_token_changes_with_passcode():
    a = server.session_token(b"k", "one")
    assert a == server.session_token(b"k", "one") and a != server.session_token(b"k", "two")


def test_store_makes_keys_once(tmp_path):
    store = server.Store(tmp_path)
    assert store.secret() == store.secret()
    path, public = store.vapid()
    assert path.exists() and store.vapid()[1] == public
    assert len(server.base64.urlsafe_b64decode(public + "==")) == 65  # uncompressed P-256 point


# ---------- school emails ----------

import base64  # noqa: E402
from datetime import timedelta  # noqa: E402

import school_mail  # noqa: E402


def b64(text):
    return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")


def gmail_msg(id_, sender, subject, when, plain=None, html=None):
    parts = []
    if plain is not None:
        parts.append({"mimeType": "text/plain", "body": {"data": b64(plain)}})
    if html is not None:
        parts.append({"mimeType": "text/html", "body": {"data": b64(html)}})
    parts.append({"mimeType": "application/pdf", "filename": "note.pdf", "body": {"attachmentId": "a"}})
    return {
        "id": id_, "threadId": "t" + id_, "snippet": "snip", "internalDate": str(int(when.timestamp() * 1000)),
        "payload": {"mimeType": "multipart/mixed", "parts": parts,
                    "headers": [{"name": "From", "value": sender}, {"name": "Subject", "value": subject}]},
    }


def test_from_school_only_matches_arcadia():
    assert school_mail.from_school("office@arcadia.qld.edu.au")
    assert school_mail.from_school("news@mail.arcadia.qld.edu.au")
    assert not school_mail.from_school("arcadia.qld.edu.au@evil.com")
    assert not school_mail.from_school("someone@notarcadia.qld.edu.au")


def test_parse_message_skips_greeting_and_quotes():
    when = datetime(2026, 10, 9, 8, 15, tzinfo=TZ)
    plain = ("Dear Parents and Carers,\n\nYear 11 camp forms are due this Friday. Please return them to the office."
             "\n\nKind regards,\nMs Lee\n\nOn Mon, 5 Oct 2026 someone wrote:\n> old stuff")
    e = school_mail.parse_message(gmail_msg("1", "Ms Lee <Lee@Arcadia.qld.edu.au>", "Camp forms", when, plain=plain), TZ)
    assert e.sender == "Ms Lee" and e.address == "lee@arcadia.qld.edu.au" and e.subject == "Camp forms"
    assert e.summary.startswith("Year 11 camp forms are due this Friday.")
    assert "old stuff" not in e.body and e.received == when


def test_parse_message_falls_back_to_html():
    when = datetime(2026, 10, 9, 8, 15, tzinfo=TZ)
    html = "<html><style>p{}</style><p>Hi all,</p><p>Sports day moved to <b>Tuesday</b> &amp; uniforms needed.</p></html>"
    e = school_mail.parse_message(gmail_msg("2", "news@arcadia.qld.edu.au", "Sports", when, html=html), TZ)
    assert e.summary == "Sports day moved to Tuesday & uniforms needed."


def test_summarise_cuts_at_a_sentence():
    text = "First point here. " * 30
    s = school_mail.summarise(text)
    assert len(s) <= school_mail.SUMMARY_CHARS and s.endswith(".")


def test_school_lists_split_new_and_seen():
    def email(id_, days_ago):
        return school_mail.Email(id_, id_, "Office", "o@arcadia.qld.edu.au", "S",
                                 datetime(2026, 10, 9, 9, 0, tzinfo=TZ) - timedelta(days=days_ago), "sum", "body")
    emails = [email("a", 0), email("b", 1), email("c", 10), email("d", 20)]
    new, seen = server.school_lists(emails, {"b": "2026-10-09", "c": "2026-10-01"}, date(2026, 10, 9))
    assert [e["id"] for e in new] == ["a"]
    assert [e["id"] for e in seen] == ["b", "c"]
    assert new[0]["link"].endswith("#all/a") and new[0]["time"] == "09:00"


class FakeMailbox:
    def recent(self, days):
        return [school_mail.Email("m1", "t1", "Office", "o@arcadia.qld.edu.au", "Camp",
                                  datetime.now(TZ), "Forms due", "Forms due Friday")]


def run_client(hub, check):
    """Runs [check](client) against the app, without a pytest plugin."""
    import asyncio

    from aiohttp.test_utils import TestClient, TestServer

    async def go():
        async with TestClient(TestServer(server.make_app(hub))) as client:
            await check(client)
    asyncio.run(go())


def test_school_api_needs_the_parents_passcode(tmp_path):
    hub = server.Hub(None, server.Store(tmp_path), "family", "https://x", FakeMailbox(), "parents")
    run_client(hub, lambda client: _school_api(client, tmp_path))


async def _school_api(client, tmp_path):
    family = (await (await client.post("/api/login", json={"passcode": "family"})).json())["token"]
    assert (await client.get("/api/school", headers={"Authorization": f"Bearer {family}"})).status == 401
    resp = await client.post("/api/school/login", json={"passcode": "parents"})
    parents = (await resp.json())["token"]
    assert parents != family
    auth = {"Authorization": f"Bearer {parents}"}
    body = await (await client.get("/api/school", headers=auth)).json()
    assert [e["id"] for e in body["new"]] == ["m1"] and body["seen"] == []
    assert (await client.post("/api/school/m1/seen", json={"seen": True}, headers=auth)).status == 200
    body = await (await client.get("/api/school", headers=auth)).json()
    assert body["new"] == [] and [e["id"] for e in body["seen"]] == ["m1"]
    # Shared: the swipe is saved on the server, so the other phone sees it too.
    assert server.Store(tmp_path).read("state.json", {})["school_seen"] == {"m1": server.today().isoformat()}
    # The parents' token can't be used for the family calendar endpoints, and vice versa.
    assert (await client.get("/api/push/key", headers=auth)).status == 401


def test_school_off_without_parents_passcode(tmp_path):
    hub = server.Hub(None, server.Store(tmp_path), "family", "https://x")
    run_client(hub, _school_off)


async def _school_off(client):
    assert (await client.post("/api/school/login", json={"passcode": ""})).status == 503
    assert (await client.get("/api/school", headers={"Authorization": "Bearer "})).status == 401


def test_weather_json():
    # A real Open-Meteo reply for Molendinar, trimmed. Same sample as the app's WeatherTest.kt.
    data = {"current": {"time": "2026-10-09T14:45", "temperature_2m": 24.9, "apparent_temperature": 24.1,
                        "weather_code": 0, "is_day": 1},
            "daily": {"time": ["2026-10-09"], "temperature_2m_max": [25.5], "temperature_2m_min": [18.1],
                      "precipitation_probability_max": [59]}}
    w = server.weather_json(data, 42)
    assert w == {"place": "Molendinar", "temp": 24.9, "feels": 24.1, "label": "Clear", "icon": "☀️",
                 "high": 25.5, "low": 18.1, "rain": 59, "fetched_at": 42000}
    data["daily"]["precipitation_probability_max"] = [None]
    assert server.weather_json(data, 0)["rain"] is None


def test_weather_codes():
    assert server.weather_label(2) == "Partly cloudy"
    assert server.weather_label(80) == "Showers"
    assert server.weather_label(95) == "Thunderstorms"
    assert server.weather_icon(0, False) == "🌙"
    assert server.weather_icon(63, True) == "🌧️"


# ---------- Android memo alerts (FCM) ----------

import fcm  # noqa: E402


class FakeCalendar:
    def __init__(self, items):
        self.list = items

    def items(self, start, end):
        return self.list


class FakeSender:
    def __init__(self, gone=()):
        self.sent, self.gone = [], set(gone)

    def send(self, token, data):
        if token in self.gone:
            raise fcm.Gone()
        self.sent.append((token, data))


def memo(event_id, from_, for_=None):
    start = datetime(2026, 10, 9, 6, 50, 9, tzinfo=TZ)
    return cs.Item(event_id, "memo", "Water the garden?", start.date(), start, False,
                   {k: v for k, v in (("from", from_), ("for", for_)) if v})


def test_fcm_message_is_data_only_and_high_priority():
    msg = fcm.message("tok", {"title": "Memo from Sally", "begin": 5})["message"]
    assert msg["token"] == "tok" and "notification" not in msg
    assert msg["data"] == {"title": "Memo from Sally", "begin": "5"}
    assert msg["android"]["priority"] == "HIGH"


def test_fcm_gone_tokens():
    assert fcm.is_gone(404, "")
    assert fcm.is_gone(400, '{"error": {"message": "The registration token is not a valid FCM registration token"}}')
    assert not fcm.is_gone(400, '{"error": {"message": "Invalid JSON payload"}}')
    assert not fcm.is_gone(500, "")


def test_phone_alert_carries_begin_seconds():
    data = server.phone_alert(memo("e1", "Sally"))
    assert data["title"] == "Memo from Sally" and data["body"] == "Water the garden?" and data["memo"] == "e1"
    assert data["begin"] == str(int(datetime(2026, 10, 9, 6, 50, 9, tzinfo=TZ).timestamp()))


def test_new_memos_reach_phones_but_not_the_sender(tmp_path):
    store = server.Store(tmp_path)
    store.write("phones.json", [{"person": "Julian", "token": "j"}, {"person": "Sally", "token": "s"},
                                {"person": "Julian", "token": "old"}])
    sender = FakeSender(gone={"old"})
    cal = FakeCalendar([memo("e0", "Sally")])
    hub = server.Hub(cal, store, "family", "https://x", None, "parents", sender)

    async def go():
        await hub.deliver_memos()  # first run only remembers what's there
        cal.list = [memo("e0", "Sally"), memo("e1", "Sally"), memo("e2", "Julian", "Erlina")]
        await hub.deliver_memos()
        await hub.deliver_memos()  # nothing twice
    import asyncio
    asyncio.run(go())
    assert [(t, d["memo"]) for t, d in sender.sent] == [("j", "e1")]
    # The token Firebase said is gone is forgotten.
    assert [p["token"] for p in store.read("phones.json", [])] == ["j", "s"]


def test_phone_register_needs_the_parents_passcode(tmp_path):
    hub = server.Hub(None, server.Store(tmp_path), "family", "https://x", None, "parents")
    run_client(hub, lambda client: _phone_api(client, tmp_path))


async def _phone_api(client, tmp_path):
    family = (await (await client.post("/api/login", json={"passcode": "family"})).json())["token"]
    body = {"person": "Julian", "token": "abc"}
    assert (await client.post("/api/phone/register", json=body,
                              headers={"Authorization": f"Bearer {family}"})).status == 401
    parents = (await (await client.post("/api/school/login", json={"passcode": "parents"})).json())["token"]
    auth = {"Authorization": f"Bearer {parents}"}
    resp = await client.post("/api/phone/register", json=body, headers=auth)
    assert resp.status == 200 and (await resp.json())["push"] is False  # no Firebase key on this server
    await client.post("/api/phone/register", json={"person": "Sally", "token": "abc"}, headers=auth)
    assert server.Store(tmp_path).read("phones.json", []) == [{"person": "Sally", "token": "abc"}]
    assert (await client.post("/api/phone/register", json={"person": "Bob", "token": "x"}, headers=auth)).status == 400
    await client.post("/api/phone/unregister", json={"token": "abc"}, headers=auth)
    assert server.Store(tmp_path).read("phones.json", []) == []


# ---------- Smart Life lights ----------

import lights  # noqa: E402

# The bedroom lamp as Tuya reported it on 2026-11-03 (a Genio 9W bulb), trimmed.
LAMP = {"id": "d1", "name": "Bedroom lamp", "online": True, "status": [
    {"code": "switch_led", "value": True}, {"code": "work_mode", "value": "white"},
    {"code": "bright_value_v2", "value": 1000}, {"code": "temp_value_v2", "value": 465},
    {"code": "colour_data_v2", "value": '{"h":0,"s":1000,"v":1000}'},
    {"code": "scene_data_v2", "value": '{"scene_num":1,"scene_units":[]}'}]}


def lamp(**status):
    d = {**LAMP, "status": [dict(s) for s in LAMP["status"]]}
    for s in d["status"]:
        if s["code"] in status:
            s["value"] = status[s["code"]]
    return lights.light_json(d)


def test_string_to_sign_hashes_body_and_sorts_query():
    s = lights.string_to_sign("GET", "/v1.0/token", {"grant_type": 1, "a": "b"}, b"")
    assert s == "GET\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n\n/v1.0/token?a=b&grant_type=1"
    assert lights.string_to_sign("POST", "/x", None, b"{}").endswith("\n\n/x")


def test_sign_is_upper_hex_and_uses_the_token():
    a = lights.sign("id", "secret", "", "1700000000000", "n", "GET\nh\n\n/v1.0/token")
    assert a == a.upper() and len(a) == 64
    assert a != lights.sign("id", "secret", "tok", "1700000000000", "n", "GET\nh\n\n/v1.0/token")


def test_brightness_percent_round_trip():
    assert lights.to_percent(10, "bright_value_v2") == 1 and lights.to_percent(1000, "bright_value_v2") == 100
    assert lights.from_percent(100, "bright_value") == 255 and lights.from_percent(1, "bright_value") == 25
    for p in (1, 37, 50, 100):
        assert lights.to_percent(lights.from_percent(p, "bright_value_v2"), "bright_value_v2") == p


def test_colour_conversions():
    assert lights.hex_to_hs("#ff0000") == (0, 1000)
    assert lights.hex_to_hs("#0000ff") == (240, 1000)
    assert lights.hs_to_hex(120, 1000) == "#00ff00"
    assert lights.valid_hex("#Ff8800") and not lights.valid_hex("ff8800") and not lights.valid_hex("#ff880")


def test_light_json_reads_mode_colour_and_scene():
    j = lamp()
    assert lights.public(j) == {"id": "d1", "name": "Bedroom lamp", "online": True, "on": True, "brightness": 100,
                                "mode": "white", "color": "#ff0000", "temp": 46, "scene": None,
                                "can": {"color": True, "temp": True, "scenes": True}}
    # In colour mode the brightness is the colour's own level.
    c = lamp(work_mode="colour", colour_data_v2='{"h":240,"s":1000,"v":505}')
    assert c["brightness"] == 50 and c["color"] == "#0000ff"
    assert lamp(work_mode="scene")["scene"] == "night"
    plug = lights.light_json({"id": "p", "name": "Fan", "status": [{"code": "switch_1", "value": False}]})
    assert plug["on"] is False and plug["brightness"] is None and plug["can"] == {"color": False, "temp": False, "scenes": False}
    assert lights.light_json({"id": "s", "status": [{"code": "temp_current", "value": 22}]}) is None


def test_commands_follow_the_mode():
    white = lamp()
    assert lights.commands_for(white, on=False) == [{"code": "switch_led", "value": False}]
    assert lights.commands_for(white, brightness=1) == [{"code": "bright_value_v2", "value": 10}]
    assert lights.commands_for(white, temp=100) == [{"code": "work_mode", "value": "white"},
                                                   {"code": "temp_value_v2", "value": 1000}]
    colour = lights.commands_for(white, color="#0000ff")
    assert colour == [{"code": "work_mode", "value": "colour"},
                      {"code": "colour_data_v2", "value": {"h": 240, "s": 1000, "v": 1000}}]
    # Dimming a colour changes the colour's level, not the white brightness.
    blue = lamp(work_mode="colour", colour_data_v2='{"h":240,"s":1000,"v":1000}')
    assert lights.commands_for(blue, brightness=1) == [{"code": "colour_data_v2", "value": {"h": 240, "s": 1000, "v": 10}}]
    scene = lights.commands_for(white, scene="rainbow")
    assert scene[0] == {"code": "work_mode", "value": "scene"} and scene[1]["value"]["scene_num"] == 6
    # Dimming during a scene goes back to white.
    assert lights.commands_for(lamp(work_mode="scene"), brightness=100)[0] == {"code": "work_mode", "value": "white"}


def test_scenes_fit_the_bulbs_ranges():
    for label, data in lights.SCENES.values():
        assert 1 <= data["scene_num"] <= 8 and data["scene_units"], label
        for u in data["scene_units"]:
            assert 0 <= u["h"] <= 360 and all(0 <= u[k] <= 1000 for k in ("s", "v", "bright", "temperature")), label
            assert 0 <= u["unit_gradient_duration"] <= 100 and u["unit_change_mode"] in ("static", "jump", "gradient")


class FakeLights:
    def __init__(self):
        self.sent = []

    def list(self):
        return [lamp()]

    def set(self, device_id, **change):
        if device_id != "d1":
            raise KeyError(device_id)
        self.sent.append((device_id, change))


def test_lights_api_for_both_passcodes(tmp_path):
    fake = FakeLights()
    hub = server.Hub(None, server.Store(tmp_path), "family", "https://x", None, "parents", None, fake)
    run_client(hub, lambda client: _lights_api(client, fake))


async def _lights_api(client, fake):
    assert (await client.get("/api/lights", headers={"Authorization": "Bearer nope"})).status == 401
    family = (await (await client.post("/api/login", json={"passcode": "family"})).json())["token"]
    parents = (await (await client.post("/api/school/login", json={"passcode": "parents"})).json())["token"]
    for token in (family, parents):
        body = await (await client.get("/api/lights", headers={"Authorization": f"Bearer {token}"})).json()
        # The device's Tuya codes stay on the server.
        assert [set(j) for j in body["lights"]] == [set(lights.public(lamp()))]
        assert [s["id"] for s in body["scenes"]] == list(lights.SCENES)
    auth = {"Authorization": f"Bearer {family}"}
    ok = [{"on": True}, {"brightness": 70}, {"color": "#ff8800", "brightness": 40}, {"temp": 0}, {"scene": "ocean"}]
    for b in ok:
        assert (await client.post("/api/lights/d1", json=b, headers=auth)).status == 200, b
    bad = [{"on": "yes"}, {"brightness": 0}, {"brightness": True}, {"temp": 101}, {"color": "red"},
           {"scene": "disco"}, {"color": "#ff8800", "scene": "ocean"}]
    for b in bad:
        assert (await client.post("/api/lights/d1", json=b, headers=auth)).status == 400, b
    assert (await client.post("/api/lights/nope", json={"on": True}, headers=auth)).status == 404
    assert fake.sent == [("d1", b) for b in ok]


def test_lights_off_without_keys(tmp_path):
    hub = server.Hub(None, server.Store(tmp_path), "family", "https://x", None, "parents")
    run_client(hub, _lights_off)


async def _lights_off(client):
    parents = (await (await client.post("/api/school/login", json={"passcode": "parents"})).json())["token"]
    assert (await client.get("/api/lights", headers={"Authorization": f"Bearer {parents}"})).status == 503


def test_query_is_signed_unencoded():
    # Tuya rejects a signature over "codes=a%2Cb"; it signs the query as written.
    assert lights.string_to_sign("GET", "/x", {"codes": "a,b"}, b"").endswith("\n\n/x?codes=a,b")


def test_sent_state_shows_until_the_device_reports():
    stale = {**LAMP, "update_time": 1000}
    sent = (2000.0, {"work_mode": "colour", "colour_data_v2": {"h": 240, "s": 1000, "v": 1000}, "switch_led": False})
    j = lights.light_json(lights.with_sent(stale, sent))
    assert j["mode"] == "colour" and j["color"] == "#0000ff" and j["on"] is False
    # Once the bulb reports after the command, its own state wins.
    fresh = {**LAMP, "update_time": 3000}
    assert lights.light_json(lights.with_sent(fresh, sent))["mode"] == "white"
    assert lights.with_sent(stale, None) is stale


# ---------- Bring! shopping list ----------

import shopping  # noqa: E402


class FakeShopping:
    def __init__(self):
        self.list = [{"id": "u1", "name": "Milk", "note": "low fat"}]
        self.added = []

    async def items(self):
        return {"list": "Family", "items": self.list, "recent": []}

    async def add(self, name, note=""):
        self.added.append((name, note))

    async def done(self, item_id):
        if item_id == "down":
            raise shopping.ShoppingError("Can't reach Bring! right now")
        if not any(i["id"] == item_id for i in self.list):
            raise KeyError(item_id)
        self.list = [i for i in self.list if i["id"] != item_id]

    async def close(self):
        pass


def test_shopping_api_for_both_passcodes(tmp_path):
    fake = FakeShopping()
    hub = server.Hub(None, server.Store(tmp_path), "family", "https://x", None, "parents", home_shopping=fake)
    run_client(hub, lambda client: _shopping_api(client, fake))


async def _shopping_api(client, fake):
    assert (await client.get("/api/shopping", headers={"Authorization": "Bearer nope"})).status == 401
    family = (await (await client.post("/api/login", json={"passcode": "family"})).json())["token"]
    parents = (await (await client.post("/api/school/login", json={"passcode": "parents"})).json())["token"]
    for token in (family, parents):
        body = await (await client.get("/api/shopping", headers={"Authorization": f"Bearer {token}"})).json()
        assert body["list"] == "Family" and body["items"][0]["name"] == "Milk"
    auth = {"Authorization": f"Bearer {family}"}
    assert (await client.post("/api/shopping", json={"name": "  Eggs ", "note": " 12 "}, headers=auth)).status == 200
    for bad in [{}, {"name": ""}, {"name": "  "}, {"name": 5}, {"name": "x" * 61}, {"name": "Eggs", "note": None},
                {"name": "Eggs", "note": "x" * 81}]:
        assert (await client.post("/api/shopping", json=bad, headers=auth)).status == 400, bad
    assert fake.added == [("Eggs", "12")]
    assert (await client.post("/api/shopping/u1/done", headers=auth)).status == 200
    assert (await client.post("/api/shopping/u1/done", headers=auth)).status == 404
    down = await client.post("/api/shopping/down/done", headers=auth)
    assert down.status == 502 and (await down.json())["error"] == "Can't reach Bring! right now"


def test_shopping_off_without_keys(tmp_path):
    hub = server.Hub(None, server.Store(tmp_path), "family", "https://x", None, "parents")
    run_client(hub, _shopping_off)


async def _shopping_off(client):
    family = (await (await client.post("/api/login", json={"passcode": "family"})).json())["token"]
    assert (await client.get("/api/shopping", headers={"Authorization": f"Bearer {family}"})).status == 503


def test_pick_list_by_name_or_default():
    from types import SimpleNamespace as L
    lists = [L(listUuid="a", name="Mine"), L(listUuid="b", name="Family Shopping")]
    assert shopping.pick_list(lists, "family shopping ", "a").listUuid == "b"
    assert shopping.pick_list(lists, None, "a").listUuid == "a"
    try:
        shopping.pick_list(lists, "Groceries", "a")
        raise AssertionError("expected ShoppingError")
    except shopping.ShoppingError as e:
        assert "Mine, Family Shopping" in str(e)

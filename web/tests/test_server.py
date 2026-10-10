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

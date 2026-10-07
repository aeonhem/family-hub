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

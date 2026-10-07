import sys
from datetime import date
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import calendar_store as cs  # noqa: E402

TZ = ZoneInfo("Australia/Brisbane")


def test_titles():
    assert cs.parse_title("🍽 Tacos") == ("dinner", False, "Tacos")
    assert cs.parse_title("🍽️ Tacos") == ("dinner", False, "Tacos")
    assert cs.parse_title("☐ Bins out") == ("chore", False, "Bins out")
    assert cs.parse_title("✅ Bins out") == ("chore", True, "Bins out")
    assert cs.parse_title("📣 Milk please") == ("memo", False, "Milk please")
    assert cs.parse_title("Swimming") == ("event", False, "Swimming")


def test_description_round_trip():
    meta, note = cs.parse_description("for: Erlina\nFrom: Sally\nRemember gloves")
    assert meta == {"for": "Erlina", "from": "Sally"}
    assert note == "Remember gloves"
    assert cs.build_description({"for": "Erlina", "done-by": None}, note) == "for: Erlina\nRemember gloves"


def test_parse_event_all_day_and_timed():
    chore = cs.parse_event({"id": "a1", "summary": "☐ Bins", "description": "for: Erlina",
                            "start": {"date": "2026-10-06"}}, TZ)
    assert chore.day == date(2026, 10, 6) and chore.start is None and chore.is_for("Erlina")
    dinner = cs.parse_event({"id": "b2", "summary": "🍽 Pasta", "description": "cook: Sally",
                             "start": {"dateTime": "2026-10-06T08:00:00Z"}}, TZ)
    assert dinner.day == date(2026, 10, 6) and dinner.start.hour == 18 and dinner.cook == "Sally"


def test_is_for():
    memo = cs.Item("m", "memo", "hi", date(2026, 10, 6), None, meta={"for": "Sally"})
    assert not memo.is_for("Erlina")
    assert cs.Item("m", "memo", "hi", date(2026, 10, 6), None).is_for("Erlina")


def test_multi_day_and_midnight_end():
    camp = cs.parse_event({"id": "c", "summary": "Camp", "start": {"date": "2026-10-05"},
                           "end": {"date": "2026-10-10"}}, TZ)
    assert camp.end_day == date(2026, 10, 9) and camp.covers(date(2026, 10, 7))
    party = cs.parse_event({"id": "p", "summary": "Party", "start": {"dateTime": "2026-10-06T19:00:00+10:00"},
                            "end": {"dateTime": "2026-10-07T00:00:00+10:00"}}, TZ)
    assert party.end_day == date(2026, 10, 6) and not party.covers(date(2026, 10, 7))


class FakeEvents:
    """Records Calendar API calls instead of making them."""

    def __init__(self):
        self.calls = []

    def _call(self, name, **kw):
        self.calls.append((name, kw))
        return self

    def insert(self, **kw):
        return self._call("insert", **kw)

    def patch(self, **kw):
        return self._call("patch", **kw)

    def execute(self):
        return {}


class FakeService:
    def __init__(self):
        self.ev = FakeEvents()

    def events(self):
        return self.ev


def test_add_chore_is_all_day():
    svc = FakeService()
    cs.FamilyCalendar(svc, "cal", TZ).add_chore(date(2026, 10, 7), "Bins", "Erlina", "Sally")
    (name, kw), = svc.ev.calls
    assert name == "insert"
    assert kw["body"]["summary"] == "☐ Bins"
    assert kw["body"]["start"] == {"date": "2026-10-07"} and kw["body"]["end"] == {"date": "2026-10-08"}
    assert kw["body"]["description"] == "for: Erlina\nfrom: Sally"


def test_save_dinner_new_and_existing():
    from datetime import time
    svc = FakeService()
    fc = cs.FamilyCalendar(svc, "cal", TZ)
    fc.save_dinner(None, date(2026, 10, 7), time(18, 30), "Tacos", "Julian")
    existing = cs.parse_event({"id": "d1_20261008", "summary": "🍽 Soup", "description": "cook: Sally\nUse the big pot",
                               "start": {"dateTime": "2026-10-08T18:00:00+10:00"},
                               "end": {"dateTime": "2026-10-08T19:00:00+10:00"}}, TZ)
    fc.save_dinner(existing, date(2026, 10, 8), time(19, 0), "Pho", None)
    (n1, k1), (n2, k2) = svc.ev.calls
    assert n1 == "insert" and k1["body"]["summary"] == "🍽 Tacos"
    assert k1["body"]["start"]["dateTime"] == "2026-10-07T18:30:00+10:00"
    assert k1["body"]["end"]["dateTime"] == "2026-10-07T19:30:00+10:00"
    assert k1["body"]["description"] == "cook: Julian"
    # Only that night's occurrence is patched, and the note is kept.
    assert n2 == "patch" and k2["eventId"] == "d1_20261008"
    assert k2["body"]["description"] == "Use the big pot"


def test_timed_event_end():
    ev = cs.parse_event({"id": "e", "summary": "Swim", "start": {"dateTime": "2026-10-06T16:00:00+10:00"},
                         "end": {"dateTime": "2026-10-06T17:00:00+10:00"}}, TZ)
    assert ev.end.hour == 17

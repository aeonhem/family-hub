import sys
from datetime import date, timedelta
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


class ListingEvents(FakeEvents):
    """Answers list() with fixed events and records deletes."""

    def __init__(self, events):
        super().__init__()
        self.events = events
        self._next = {}

    def list(self, **kw):
        self._next = {"items": self.events}
        return self._call("list", **kw)

    def delete(self, **kw):
        self._next = {}
        return self._call("delete", **kw)

    def execute(self):
        out, self._next = self._next, {}
        return out


def chore_ev(eid, day, done=False, who="Erlina"):
    nxt = date.fromisoformat(day) + timedelta(days=1)
    return {"id": eid, "summary": f"{'✅' if done else '☐'} Dishes", "description": f"for: {who}",
            "start": {"date": day}, "end": {"date": nxt.isoformat()}}


def test_missed_chores():
    today = date(2026, 11, 15)
    open_old = cs.parse_event(chore_ev("a", "2026-11-14"), TZ)
    done_old = cs.parse_event(chore_ev("b", "2026-11-14", done=True), TZ)
    open_today = cs.parse_event(chore_ev("c", "2026-11-15"), TZ)
    assert cs.is_missed(open_old, today)
    assert not cs.is_missed(done_old, today) and not cs.is_missed(open_today, today)
    assert not cs.visible_chore(open_old, today) and cs.visible_chore(done_old, today)


def test_clear_missed_chores_deletes_only_unticked_past_ones():
    events = [
        chore_ev("old_open", "2026-11-13"),
        chore_ev("rep_20261114", "2026-11-14"),  # one day of a repeating chore
        chore_ev("old_done", "2026-11-14", done=True),
        chore_ev("today_open", "2026-11-15"),
        {"id": "swim", "summary": "Swimming", "start": {"date": "2026-11-14"}, "end": {"date": "2026-11-15"}},
    ]
    svc = FakeService()
    svc.ev = ListingEvents(events)
    assert cs.FamilyCalendar(svc, "cal", TZ).clear_missed_chores(date(2026, 11, 15)) == 2
    deleted = [kw["eventId"] for name, kw in svc.ev.calls if name == "delete"]
    assert deleted == ["old_open", "rep_20261114"]


# ---------- Family Chores calendar ----------


class NotFound(Exception):
    """Looks like googleapiclient's HttpError for a 404."""

    class resp:  # noqa: N801
        status = 404


class TwoCalendars(FakeEvents):
    """Events per calendar id; answers list/get/patch/delete per calendar."""

    def __init__(self, by_cal):
        super().__init__()
        self.by_cal = by_cal
        self._next = {}

    def list(self, **kw):
        self._next = {"items": self.by_cal.get(kw["calendarId"], [])}
        return self._call("list", **kw)

    def get(self, **kw):
        match = [e for e in self.by_cal.get(kw["calendarId"], []) if e["id"] == kw["eventId"]]
        self._next = match[0] if match else NotFound()
        return self._call("get", **kw)

    def delete(self, **kw):
        self._next = {}
        return self._call("delete", **kw)

    def execute(self):
        out, self._next = self._next, {}
        if isinstance(out, Exception):
            raise out
        return out


def two_cal_service(family, chores):
    svc = FakeService()
    svc.ev = TwoCalendars({"fam": family, "chores": chores})
    return svc


def test_add_chore_goes_to_chores_calendar_without_reminder():
    svc = FakeService()
    cs.FamilyCalendar(svc, "fam", TZ, "chores").add_chore(date(2026, 10, 7), "Bins", "Erlina", "Sally")
    (name, kw), = svc.ev.calls
    assert kw["calendarId"] == "chores"
    assert kw["body"]["reminders"] == {"useDefault": False, "overrides": []}


def test_blank_chores_id_keeps_one_calendar():
    svc = FakeService()
    fc = cs.FamilyCalendar(svc, "fam", TZ, "  ")
    assert fc.calendar_ids == ["fam"]
    fc.add_chore(date(2026, 10, 7), "Bins", None, None)
    assert svc.ev.calls[0][1]["calendarId"] == "fam"


def test_memos_and_dinners_stay_on_family():
    from datetime import time
    svc = FakeService()
    fc = cs.FamilyCalendar(svc, "fam", TZ, "chores")
    fc.send_memo("Milk", "Sally", "Julian")
    fc.save_dinner(None, date(2026, 10, 7), time(18, 0), "Tacos", None)
    assert [kw["calendarId"] for _, kw in svc.ev.calls] == ["fam", "fam"]


def test_items_merge_both_calendars_in_order():
    swim = {"id": "swim", "summary": "Swimming", "start": {"dateTime": "2026-11-14T16:00:00+10:00"},
            "end": {"dateTime": "2026-11-14T17:00:00+10:00"}}
    svc = two_cal_service([chore_ev("old", "2026-11-14"), swim], [chore_ev("new", "2026-11-13")])
    items = cs.FamilyCalendar(svc, "fam", TZ, "chores").items(date(2026, 11, 13), date(2026, 11, 14))
    assert [(i.event_id, i.calendar_id) for i in items] == [("new", "chores"), ("old", "fam"), ("swim", "fam")]


def test_chore_still_on_family_is_ticked_and_cleared_there():
    svc = two_cal_service([chore_ev("old", "2026-11-14")], [chore_ev("new", "2026-11-14")])
    fc = cs.FamilyCalendar(svc, "fam", TZ, "chores")
    old = fc.get("old")  # chores calendar says 404, Family has it
    assert old.calendar_id == "fam"
    fc.set_chore_done(old, True, "Erlina")
    # The fake doesn't keep the tick, so both still look open and past.
    assert fc.clear_missed_chores(date(2026, 11, 15)) == 2
    writes = [(n, kw["calendarId"], kw["eventId"]) for n, kw in svc.ev.calls if n in ("patch", "delete")]
    assert writes == [("patch", "fam", "old"), ("delete", "fam", "old"), ("delete", "chores", "new")]


def test_get_finds_moved_chore_on_chores_calendar_first():
    svc = two_cal_service([], [chore_ev("moved", "2026-11-14")])
    assert cs.FamilyCalendar(svc, "fam", TZ, "chores").get("moved").calendar_id == "chores"


def test_get_missing_everywhere_raises():
    import pytest
    svc = two_cal_service([], [])
    with pytest.raises(NotFound):
        cs.FamilyCalendar(svc, "fam", TZ, "chores").get("gone")

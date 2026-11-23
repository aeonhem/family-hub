import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import move_chores  # noqa: E402


class Events:
    def __init__(self, items, fail=()):
        self.items, self.fail, self.moves, self._next = items, set(fail), [], None

    def list(self, **kw):
        self._next = {"items": self.items}
        return self

    def move(self, **kw):
        if kw["eventId"] in self.fail:
            self._next = RuntimeError("no access")
        else:
            self.moves.append(kw)
            self._next = {}
        return self

    def execute(self):
        out, self._next = self._next, None
        if isinstance(out, Exception):
            raise out
        return out


class Service:
    def __init__(self, ev):
        self.ev = ev

    def events(self):
        return self.ev


ITEMS = [
    {"id": "open", "summary": "☐ Bins", "start": {"date": "2026-11-20"}},
    {"id": "done", "summary": "✅ Dishes", "start": {"date": "2025-01-02"}},
    {"id": "series", "summary": "☐ Feed cat", "recurrence": ["RRULE:FREQ=DAILY"], "start": {"date": "2026-11-01"}},
    {"id": "series_20261105", "summary": "✅ Feed cat", "recurringEventId": "series", "start": {"date": "2026-11-05"}},
    {"id": "swim", "summary": "Swimming", "start": {"date": "2026-11-20"}},
    {"id": "dinner", "summary": "🍽 Tacos", "start": {"dateTime": "2026-11-20T18:00:00+10:00"}},
    {"id": "gone", "summary": "☐ Old", "status": "cancelled", "start": {"date": "2026-11-20"}},
]


def test_moves_only_chores_and_series_masters():
    ev = Events(ITEMS)
    moved, failed = move_chores.move_all(Service(ev), "fam", "chores")
    assert (moved, failed) == (3, [])
    assert [(m["eventId"], m["calendarId"], m["destination"]) for m in ev.moves] == [
        ("open", "fam", "chores"), ("done", "fam", "chores"), ("series", "fam", "chores")]


def test_dry_run_moves_nothing():
    ev = Events(ITEMS)
    assert move_chores.move_all(Service(ev), "fam", "chores", dry_run=True) == (3, [])
    assert ev.moves == []


def test_one_failure_does_not_stop_the_rest():
    ev = Events(ITEMS, fail={"done"})
    moved, failed = move_chores.move_all(Service(ev), "fam", "chores")
    assert moved == 2 and len(failed) == 1 and "Dishes" in failed[0]

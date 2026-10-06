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

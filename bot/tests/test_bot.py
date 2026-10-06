import asyncio
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import discord
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bot  # noqa: E402
import calendar_store as cs  # noqa: E402

TZ = ZoneInfo("Australia/Brisbane")
D = date(2026, 10, 7)  # a Wednesday


def item(kind, title, day=D, start=None, done=False, end_day=None, **meta):
    return cs.Item(title, kind, title, day, start, done, {k.replace("_", "-"): v for k, v in meta.items()},
                   end_day=end_day)


def test_today_shows_multi_day_events_and_dinner():
    items = [
        item("event", "Camp", day=D - timedelta(days=2), end_day=D + timedelta(days=2)),
        item("dinner", "Pasta", start=datetime(2026, 10, 7, 18, tzinfo=TZ), cook="Sally"),
    ]
    text = bot.today_text(items, D)
    assert "All day  Camp" in text
    assert "6:00pm  Dinner: **Pasta** (Sally cooking)" in text


def test_dinner_week_text():
    items = [item("dinner", "Tacos", cook="Takeaway"), item("dinner", "Soup", day=D + timedelta(days=1))]
    lines = bot.dinner_week_text(items, D).splitlines()
    assert lines[0] == "Tonight: **Tacos** (takeaway)"
    assert lines[1] == "Tomorrow: Soup"
    assert lines[2] == "Friday: not planned yet"


def test_week_count_is_chores_she_ticked():
    items = [
        item("chore", "Bins", done=True, done_by="Erlina"),
        item("chore", "Dishes", done=True, done_by="Sally", **{"for": "Everyone"}),
        item("chore", "Old", day=D - timedelta(days=7), done=True, done_by="Erlina"),
    ]
    assert bot.ticked_this_week(items, D) == 1


def test_kid_chores_today_and_overdue():
    items = [
        item("chore", "Today"),
        item("chore", "Late", day=D - timedelta(days=2)),
        item("chore", "Done late", day=D - timedelta(days=2), done=True),
        item("chore", "Sally's", **{"for": "Sally"}),
    ]
    assert [c.title for c in bot.kid_chores(items, D)] == ["Late", "Today"]


def test_long_memo_is_clipped():
    assert len(bot.memo_text(item("memo", "x" * 3000, **{"from": "Julian"}))) == bot.DISCORD_LIMIT


def test_morning_catch_up_window():
    bot.state.clear()
    assert not bot.not_sent_this_morning(datetime(2026, 10, 7, 6, 59, tzinfo=TZ))
    assert bot.not_sent_this_morning(datetime(2026, 10, 7, 9, 30, tzinfo=TZ))
    assert not bot.not_sent_this_morning(datetime(2026, 10, 7, 11, 30, tzinfo=TZ))
    bot.state["morning_sent"] = "2026-10-07"
    assert not bot.not_sent_this_morning(datetime(2026, 10, 7, 9, 30, tzinfo=TZ))


class FakeCal:
    def __init__(self, items):
        self._items = items

    def items(self, start, end):
        return self._items


class FakeKid:
    def __init__(self, fail_on=None, status=500):
        self.sent, self.fail_on, self.status = [], fail_on, status

    async def send(self, text):
        if self.fail_on and self.fail_on in text:
            resp = type("R", (), {"status": self.status, "reason": "x"})()
            raise discord.HTTPException(resp, "nope")
        self.sent.append(text)


@pytest.fixture
def memo_env(monkeypatch, tmp_path):
    monkeypatch.setattr(bot, "STATE_FILE", tmp_path / "state.json")
    monkeypatch.setattr(bot, "KID_ID", 1)
    bot.state.clear()

    def setup(items, kid):
        monkeypatch.setattr(bot, "cal", FakeCal(items), raising=False)

        async def fetch_user(uid):
            return kid
        monkeypatch.setattr(bot.client, "fetch_user", fetch_user)
    return setup


def test_memos_first_run_then_new_only(memo_env):
    kid = FakeKid()
    old = item("memo", "old")
    memo_env([old], kid)
    asyncio.run(bot.deliver_memos())
    assert kid.sent == []
    memo_env([old, item("memo", "hi", **{"from": "Sally"}), item("memo", "mine", **{"from": "Erlina"}),
              item("memo", "for Julian", **{"for": "Julian"})], kid)
    asyncio.run(bot.deliver_memos())
    assert kid.sent == ["**Memo from Sally**\nhi"]


def test_failed_memo_does_not_resend_earlier_ones(memo_env):
    kid = FakeKid(fail_on="second")
    memo_env([], kid)
    asyncio.run(bot.deliver_memos())  # first run
    memo_env([item("memo", "first"), item("memo", "second")], kid)
    with pytest.raises(discord.HTTPException):
        asyncio.run(bot.deliver_memos())
    kid.fail_on = None
    asyncio.run(bot.deliver_memos())
    assert [t.split("\n")[1] for t in kid.sent] == ["first", "second"]


def test_memo_discord_rejects_is_skipped(memo_env):
    kid = FakeKid(fail_on="bad", status=400)
    memo_env([], kid)
    asyncio.run(bot.deliver_memos())
    memo_env([item("memo", "bad"), item("memo", "good")], kid)
    asyncio.run(bot.deliver_memos())
    asyncio.run(bot.deliver_memos())
    assert [t.split("\n")[1] for t in kid.sent] == ["good"]

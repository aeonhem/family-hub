"""The Family Google Calendar, read and written per docs/calendar-conventions.md.

Same rules as the Android app: the title prefix says what an event is, and
`key: value` lines in the description say who it's for, from, and so on.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

DINNER = "🍽"
CHORE_OPEN = "☐"
CHORE_DONE = "✅"
MEMO = "📣"
NOTE = "📝"

_META_LINE = re.compile(r"^\s*(for|from|cook|done-by)\s*:\s*(.+?)\s*$", re.IGNORECASE)
EVERYONE = {"everyone", "all", ""}


@dataclass
class Item:
    event_id: str
    kind: str  # event | dinner | chore | memo | note
    title: str
    day: date
    start: datetime | None  # None for all-day events
    done: bool = False
    meta: dict[str, str] = field(default_factory=dict)
    note: str = ""
    end_day: date | None = None  # last day it covers; None means just [day]

    def covers(self, d: date) -> bool:
        """True when the event is on [d], including the middle of a multi-day event."""
        return self.day <= d <= (self.end_day or self.day)

    @property
    def for_who(self) -> str | None:
        return self.meta.get("for")

    @property
    def from_who(self) -> str | None:
        return self.meta.get("from")

    @property
    def cook(self) -> str | None:
        return self.meta.get("cook")

    @property
    def done_by(self) -> str | None:
        return self.meta.get("done-by")

    def is_for(self, name: str) -> bool:
        """True when addressed to [name], to everyone, or to nobody in particular."""
        who = (self.for_who or "").strip().lower()
        return who in EVERYONE or who == name.lower()


def parse_title(raw: str) -> tuple[str, bool, str]:
    t = raw.strip().replace("️", "", 1)
    for prefix, kind, done in (
        (DINNER, "dinner", False),
        (CHORE_OPEN, "chore", False),
        (CHORE_DONE, "chore", True),
        (MEMO, "memo", False),
        (NOTE, "note", False),
    ):
        if t.startswith(prefix):
            return kind, done, t[len(prefix):].strip()
    return "event", False, raw.strip()


def parse_description(desc: str | None) -> tuple[dict[str, str], str]:
    meta: dict[str, str] = {}
    rest: list[str] = []
    for line in (desc or "").splitlines():
        m = _META_LINE.match(line)
        if m:
            meta[m.group(1).lower()] = m.group(2)
        else:
            rest.append(line)
    return meta, "\n".join(rest).strip()


def build_description(meta: dict[str, str | None], note: str = "") -> str:
    lines = [f"{k}: {v}" for k, v in meta.items() if v]
    if note:
        lines.append(note)
    return "\n".join(lines)


def parse_event(ev: dict, tz: ZoneInfo) -> Item:
    kind, done, title = parse_title(ev.get("summary", ""))
    meta, note = parse_description(ev.get("description"))
    start, end = ev.get("start", {}), ev.get("end", {})
    if "dateTime" in start:
        dt = _local(start["dateTime"], tz)
        day, start_dt = dt.date(), dt
        # An event ending exactly at midnight doesn't cover the next day.
        end_day = (_local(end["dateTime"], tz) - timedelta(microseconds=1)).date() if "dateTime" in end else day
    else:
        day, start_dt = date.fromisoformat(start["date"]), None
        # All-day end dates are exclusive.
        end_day = date.fromisoformat(end["date"]) - timedelta(days=1) if "date" in end else day
    return Item(ev["id"], kind, title, day, start_dt, done, meta, note, max(end_day, day))


def _local(stamp: str, tz: ZoneInfo) -> datetime:
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(tz)


class FamilyCalendar:
    """Thin wrapper over the Calendar API. `service` is a googleapiclient resource."""

    def __init__(self, service, calendar_id: str, tz: ZoneInfo):
        self.service = service
        self.calendar_id = calendar_id
        self.tz = tz

    def items(self, start: date, end: date) -> list[Item]:
        """Every event on any local day in start..end (inclusive)."""
        time_min = datetime.combine(start - timedelta(days=1), time.min, self.tz).isoformat()
        time_max = datetime.combine(end + timedelta(days=2), time.min, self.tz).isoformat()
        out: list[Item] = []
        page = None
        while True:
            resp = self.service.events().list(
                calendarId=self.calendar_id, timeMin=time_min, timeMax=time_max,
                singleEvents=True, orderBy="startTime", maxResults=250, pageToken=page,
            ).execute()
            for ev in resp.get("items", []):
                if ev.get("status") == "cancelled":
                    continue
                item = parse_event(ev, self.tz)
                if item.day <= end and (item.end_day or item.day) >= start:
                    out.append(item)
            page = resp.get("nextPageToken")
            if not page:
                return out

    def set_chore_done(self, item: Item, done: bool, by: str) -> None:
        meta: dict[str, str | None] = dict(item.meta)
        meta["done-by"] = by if done else None
        prefix = CHORE_DONE if done else CHORE_OPEN
        self.service.events().patch(
            calendarId=self.calendar_id, eventId=item.event_id,
            body={"summary": f"{prefix} {item.title}", "description": build_description(meta, item.note)},
        ).execute()

    def send_memo(self, body: str, sender: str, to: str) -> None:
        """The Android app notifies the people it's for. No calendar reminder:
        Google keeps reminders per person, so one here would only buzz the
        account the bot signs in as."""
        start = datetime.now(self.tz) + timedelta(minutes=1)
        self.service.events().insert(
            calendarId=self.calendar_id,
            body={
                "summary": f"{MEMO} {body}",
                "description": build_description({"from": sender, "for": to}),
                "start": {"dateTime": start.isoformat()},
                "end": {"dateTime": (start + timedelta(minutes=5)).isoformat()},
                "reminders": {"useDefault": False, "overrides": []},
            },
        ).execute()

    def get(self, event_id: str) -> Item:
        ev = self.service.events().get(calendarId=self.calendar_id, eventId=event_id).execute()
        return parse_event(ev, self.tz)

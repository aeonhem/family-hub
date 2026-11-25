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
    end: datetime | None = None  # None for all-day events
    repeats: bool = False  # one day of a repeating event
    calendar_id: str = ""  # the calendar it came from; "" means the Family one

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


def is_missed(item: Item, today: date) -> bool:
    """An open chore whose day has passed. These vanish at midnight: they're
    never marked done, so they never count towards anyone's ticked chores."""
    return item.kind == "chore" and not item.done and (item.end_day or item.day) < today


def visible_chore(item: Item, today: date) -> bool:
    return item.kind == "chore" and not is_missed(item, today)


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


def parse_event(ev: dict, tz: ZoneInfo, calendar_id: str = "") -> Item:
    kind, done, title = parse_title(ev.get("summary", ""))
    meta, note = parse_description(ev.get("description"))
    start, end = ev.get("start", {}), ev.get("end", {})
    end_dt = None
    if "dateTime" in start:
        dt = _local(start["dateTime"], tz)
        day, start_dt = dt.date(), dt
        end_dt = _local(end["dateTime"], tz) if "dateTime" in end else dt
        # An event ending exactly at midnight doesn't cover the next day.
        end_day = (end_dt - timedelta(microseconds=1)).date() if "dateTime" in end else day
    else:
        day, start_dt = date.fromisoformat(start["date"]), None
        # All-day end dates are exclusive.
        end_day = date.fromisoformat(end["date"]) - timedelta(days=1) if "date" in end else day
    return Item(ev["id"], kind, title, day, start_dt, done, meta, note, max(end_day, day), end_dt,
                bool(ev.get("recurringEventId")), calendar_id)


def _local(stamp: str, tz: ZoneInfo) -> datetime:
    return datetime.fromisoformat(stamp.replace("Z", "+00:00")).astimezone(tz)


def _not_found(e: Exception) -> bool:
    """True for the Calendar API's 404/410 (googleapiclient HttpError)."""
    return getattr(getattr(e, "resp", None), "status", None) in (404, 410)


class FamilyCalendar:
    """Thin wrapper over the Calendar API. `service` is a googleapiclient resource.

    Chores go on their own calendar when [chores_calendar_id] is set, so each
    person can mute their alerts. Reads merge both calendars, and old chores
    still on Family keep working. Blank means one calendar for everything."""

    def __init__(self, service, calendar_id: str, tz: ZoneInfo, chores_calendar_id: str | None = None):
        self.service = service
        self.calendar_id = calendar_id
        self.chores_calendar_id = (chores_calendar_id or "").strip() or None
        self.tz = tz

    @property
    def calendar_ids(self) -> list[str]:
        return [self.calendar_id] + ([self.chores_calendar_id] if self.chores_calendar_id else [])

    def _cal(self, item: Item) -> str:
        return item.calendar_id or self.calendar_id

    def items(self, start: date, end: date) -> list[Item]:
        """Every event on any local day in start..end (inclusive), from every calendar."""
        out: list[Item] = []
        for cal_id in self.calendar_ids:
            out += self._items(cal_id, start, end)
        if len(self.calendar_ids) > 1:
            # Same order the API gives for one calendar: by start, all-day at local midnight.
            out.sort(key=lambda i: i.start or datetime.combine(i.day, time.min, self.tz))
        return out

    def _items(self, cal_id: str, start: date, end: date) -> list[Item]:
        time_min = datetime.combine(start - timedelta(days=1), time.min, self.tz).isoformat()
        time_max = datetime.combine(end + timedelta(days=2), time.min, self.tz).isoformat()
        out: list[Item] = []
        page = None
        while True:
            resp = self.service.events().list(
                calendarId=cal_id, timeMin=time_min, timeMax=time_max,
                singleEvents=True, orderBy="startTime", maxResults=250, pageToken=page,
            ).execute()
            for ev in resp.get("items", []):
                if ev.get("status") == "cancelled":
                    continue
                item = parse_event(ev, self.tz, cal_id)
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
            calendarId=self._cal(item), eventId=item.event_id,
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

    def add_chore(self, day: date, title: str, for_who: str | None, from_who: str | None) -> None:
        """An all-day event on [day], like the Android app's "Add a chore".
        On the chores calendar when there is one. No reminder for the bot's own
        account; everyone else's alerts follow their own calendar settings."""
        self.service.events().insert(
            calendarId=self.chores_calendar_id or self.calendar_id,
            body={
                "summary": f"{CHORE_OPEN} {title}",
                "description": build_description({"for": for_who, "from": from_who}),
                "start": {"date": day.isoformat()},
                "end": {"date": (day + timedelta(days=1)).isoformat()},
                "reminders": {"useDefault": False, "overrides": []},
            },
        ).execute()

    def save_dinner(self, existing: Item | None, day: date, at: time, meal: str, cook: str | None) -> None:
        """Adds dinner on [day], or changes [existing] (just that night, if it repeats).
        An hour long, the same as the Android app makes them."""
        start = datetime.combine(day, at, self.tz)
        meta: dict[str, str | None] = dict(existing.meta) if existing else {}
        meta["cook"] = cook
        body = {
            "summary": f"{DINNER} {meal}",
            "description": build_description(meta, existing.note if existing else ""),
            "start": {"dateTime": start.isoformat(), "timeZone": str(self.tz)},
            "end": {"dateTime": (start + timedelta(hours=1)).isoformat(), "timeZone": str(self.tz)},
        }
        events = self.service.events()
        if existing:
            events.patch(calendarId=self._cal(existing), eventId=existing.event_id, body=body).execute()
        else:
            events.insert(calendarId=self.calendar_id, body=body).execute()

    def clear_missed_chores(self, today: date, look_back: int = 14) -> int:
        """Deletes open chores from before [today]. For a repeating chore only
        that day's occurrence goes; the rest of the series stays."""
        missed = [i for i in self.items(today - timedelta(days=look_back), today - timedelta(days=1))
                  if is_missed(i, today)]
        for i in missed:
            self.service.events().delete(calendarId=self._cal(i), eventId=i.event_id).execute()
        return len(missed)

    def get(self, event_id: str) -> Item:
        """Looks on the chores calendar first, then Family. Moved chores keep
        their ID, so old links and Discord buttons still find them."""
        ids = self.calendar_ids[::-1]
        for n, cal_id in enumerate(ids):
            last = n == len(ids) - 1
            try:
                ev = self.service.events().get(calendarId=cal_id, eventId=event_id).execute()
            except Exception as e:
                if _not_found(e) and not last:
                    continue
                raise
            if ev.get("status") == "cancelled":
                if not last:
                    continue
                raise LookupError("That chore has been cleared")
            return parse_event(ev, self.tz, cal_id)
        raise LookupError("That chore has been cleared")

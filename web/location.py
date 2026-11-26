"""Where everyone is: the last position each person shared, nothing older.

Julian's choice (docs/location-sharing-plan.md): no history. Each new position
replaces the old one, and turning sharing off deletes the row. The rows sit in
one small SQLite file in STATE_DIR, on this server only. Positions are never
logged.
"""
from __future__ import annotations

import math
import sqlite3
import time
from dataclasses import asdict, dataclass
from pathlib import Path

MAX_POINTS = 100  # per upload; a phone that was offline sends what it queued
MAX_FUTURE_MS = 5 * 60 * 1000  # phone clocks drift a little, not by more than this


@dataclass(frozen=True)
class Point:
    lat: float
    lon: float
    at: int  # when the phone took the fix, epoch milliseconds
    acc: float | None = None  # metres
    battery: int | None = None  # percent


def _number(value, lo: float, hi: float, what: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not lo <= value <= hi:
        raise ValueError(f"{what} must be a number from {lo:g} to {hi:g}")
    return float(value)


def parse_point(raw, now_ms: int) -> Point:
    """One point from a phone, checked. Raises ValueError with a short reason."""
    if not isinstance(raw, dict):
        raise ValueError("each point must be an object")
    at = raw.get("t")
    if isinstance(at, bool) or not isinstance(at, int) or at <= 0 or at > now_ms + MAX_FUTURE_MS:
        raise ValueError("t must be the fix time in epoch milliseconds")
    acc = raw.get("acc")
    battery = raw.get("battery")
    if battery is not None and (isinstance(battery, bool) or not isinstance(battery, int) or not 0 <= battery <= 100):
        raise ValueError("battery must be a whole number from 0 to 100")
    return Point(
        lat=_number(raw.get("lat"), -90, 90, "lat"),
        lon=_number(raw.get("lon"), -180, 180, "lon"),
        at=at,
        acc=None if acc is None else _number(acc, 0, 100_000, "acc"),
        battery=battery,
    )


def newest(points: list, now_ms: int) -> Point:
    """The latest of an upload's points; only that one is kept."""
    if not isinstance(points, list) or not 1 <= len(points) <= MAX_POINTS:
        raise ValueError(f"points must be a list of 1 to {MAX_POINTS} points")
    return max((parse_point(p, now_ms) for p in points), key=lambda p: p.at)


class Locations:
    def __init__(self, path: Path):
        self.db = sqlite3.connect(path)
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS last ("
            " person TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,"
            " acc REAL, battery INTEGER, at INTEGER NOT NULL, received INTEGER NOT NULL)")
        self.db.commit()
        path.chmod(0o600)

    def put(self, person: str, p: Point, received_ms: int | None = None) -> bool:
        """Keeps [p] as [person]'s position unless a newer one is already stored
        (a queued, older upload can arrive late). True when it was kept."""
        received = received_ms if received_ms is not None else int(time.time() * 1000)
        cur = self.db.execute(
            "INSERT INTO last (person, lat, lon, acc, battery, at, received) VALUES (?, ?, ?, ?, ?, ?, ?)"
            " ON CONFLICT(person) DO UPDATE SET lat=excluded.lat, lon=excluded.lon, acc=excluded.acc,"
            " battery=excluded.battery, at=excluded.at, received=excluded.received"
            " WHERE excluded.at > last.at",
            (person, p.lat, p.lon, p.acc, p.battery, p.at, received))
        self.db.commit()
        return cur.rowcount > 0

    def forget(self, person: str) -> None:
        self.db.execute("DELETE FROM last WHERE person = ?", (person,))
        self.db.commit()

    def everyone(self) -> list[dict]:
        rows = self.db.execute("SELECT person, lat, lon, acc, battery, at FROM last ORDER BY person").fetchall()
        return [{"person": r[0], **asdict(Point(lat=r[1], lon=r[2], acc=r[3], battery=r[4], at=r[5]))} for r in rows]

    def close(self) -> None:
        self.db.close()


# ---------- places and alerts ("Sally left work") ----------
# The places live in STATE_DIR/places.json on the server only (they are home
# addresses, so they stay out of git): [{"name", "lat", "lon", "radius"}].

LEAVE_MARGIN = 80  # metres past the radius before "left" counts, so GPS noise at the edge does not flip it
MAX_ACC = 250  # metres; rougher fixes are too vague to say where someone is
CONFIRM = 2  # points in a row that must agree before a change is announced


@dataclass(frozen=True)
class Place:
    name: str
    lat: float
    lon: float
    radius: float  # metres


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    a = (math.sin(math.radians(lat2 - lat1) / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2))
         * math.sin(math.radians(lon2 - lon1) / 2) ** 2)
    return 6_371_000 * 2 * math.asin(math.sqrt(a))


def parse_places(raw) -> list[Place]:
    """Places from places.json. A bad entry is skipped, not fatal."""
    out = []
    for r in raw if isinstance(raw, list) else []:
        try:
            out.append(Place(str(r["name"]), _number(r["lat"], -90, 90, "lat"), _number(r["lon"], -180, 180, "lon"),
                             _number(r["radius"], 10, 5_000, "radius")))
        except (KeyError, TypeError, ValueError):
            continue
    return out


def where(places: list[Place], p: Point, current: str | None) -> str | None:
    """The place [p] is in. Someone already at a place stays there until they
    are LEAVE_MARGIN past its edge."""
    def near(pl: Place, extra: float) -> bool:
        return distance_m(p.lat, p.lon, pl.lat, pl.lon) <= pl.radius + extra
    for pl in places:
        if pl.name == current and near(pl, LEAVE_MARGIN):
            return current
    return next((pl.name for pl in places if near(pl, 0)), None)


class Presence:
    """Which place each person is at, plus a change waiting for its second
    point. [state] is plain JSON so the server can keep it across restarts:
    {person: {"at": place or None, "maybe": place or None, "count": n}}."""

    def __init__(self, state: dict | None = None):
        self.state: dict = state if isinstance(state, dict) else {}

    def update(self, person: str, places: list[Place], p: Point) -> tuple[list[tuple[str, str]], bool]:
        """Returns (events, changed). Events are ("left", place) and
        ("arrived", place). The first point for a person sets their place
        without an alert."""
        if not places or (p.acc is not None and p.acc > MAX_ACC):
            return [], False
        s = self.state.get(person)
        if s is None:
            self.state[person] = {"at": where(places, p, None), "maybe": None, "count": 0}
            return [], True
        now = where(places, p, s["at"])
        if now == s["at"]:
            changed = s["maybe"] is not None or s["count"] != 0
            s["maybe"], s["count"] = None, 0
            return [], changed
        if now == s["maybe"]:
            s["count"] += 1
        else:
            s["maybe"], s["count"] = now, 1
        if s["count"] < CONFIRM:
            return [], True
        events = ([("left", s["at"])] if s["at"] else []) + ([("arrived", now)] if now else [])
        s["at"], s["maybe"], s["count"] = now, None, 0
        return events, True

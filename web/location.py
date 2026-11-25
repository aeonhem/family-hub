"""Where everyone is: the last position each person shared, nothing older.

Julian's choice (docs/location-sharing-plan.md): no history. Each new position
replaces the old one, and turning sharing off deletes the row. The rows sit in
one small SQLite file in STATE_DIR, on this server only. Positions are never
logged.
"""
from __future__ import annotations

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

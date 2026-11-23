"""One-time move of every chore from Family to the Family Chores calendar.

Run in this folder, after CHORES_CALENDAR_ID is set in .env:

    python move_chores.py --dry-run   # list what would move
    python move_chores.py             # move it

Moves open, done and repeating chores, past ones too. events.move keeps each
event's ID, title and description, so ticks, done-by and old links stay the
same. Safe to run again: chores already moved are no longer on Family.
"""
from __future__ import annotations

import argparse
import os

from dotenv import load_dotenv

import calendar_store as cs


def chores_on(service, calendar_id: str) -> list[dict]:
    """Every chore on [calendar_id]. A repeating chore is one series event;
    its one-day changes (ticks) move with it, so they are skipped here."""
    out, page = [], None
    while True:
        resp = service.events().list(
            calendarId=calendar_id, singleEvents=False, showDeleted=False,
            maxResults=2500, pageToken=page,
        ).execute()
        for ev in resp.get("items", []):
            if ev.get("status") == "cancelled" or ev.get("recurringEventId"):
                continue
            if cs.parse_title(ev.get("summary", ""))[0] == "chore":
                out.append(ev)
        page = resp.get("nextPageToken")
        if not page:
            return out


def move_all(service, family_id: str, chores_id: str, dry_run: bool = False) -> tuple[int, list[str]]:
    """Returns (moved, failures). One failure doesn't stop the rest."""
    moved, failed = 0, []
    for ev in chores_on(service, family_id):
        label = f"{ev.get('summary', '')} ({(ev.get('start') or {}).get('date', '?')})"
        if dry_run:
            print("would move", label)
            moved += 1
            continue
        try:
            service.events().move(calendarId=family_id, eventId=ev["id"], destination=chores_id).execute()
            moved += 1
        except Exception as e:  # noqa: BLE001 - report and carry on
            failed.append(f"{label}: {e}")
    return moved, failed


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dry-run", action="store_true", help="only list the chores")
    args = parser.parse_args()

    load_dotenv()
    family_id = os.environ["FAMILY_CALENDAR_ID"]
    chores_id = (os.getenv("CHORES_CALENDAR_ID") or "").strip()
    if not chores_id:
        raise SystemExit("Set CHORES_CALENDAR_ID in .env first.")
    if chores_id == family_id:
        raise SystemExit("CHORES_CALENDAR_ID is the Family calendar.")

    from google.oauth2.credentials import Credentials
    from googleapiclient.discovery import build

    creds = Credentials.from_authorized_user_file(os.getenv("GOOGLE_TOKEN_FILE", "token.json"))
    service = build("calendar", "v3", credentials=creds, cache_discovery=False)
    moved, failed = move_all(service, family_id, chores_id, args.dry_run)
    print(f"{'Would move' if args.dry_run else 'Moved'} {moved} chores.")
    for f in failed:
        print("FAILED", f)
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()

"""The family's Bring! shopping list, for everyone's phones.

Bring! has no public API, so this signs in through bring-api (the library Home
Assistant uses) as a helper Bring! account that has been invited to the
family's list; nobody's own Bring! password sits on the server. Turned on by
putting that account's sign-in at STATE_DIR/bring.json (see web/README.md).
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from pathlib import Path

import aiohttp
from bring_api import Bring
from bring_api.exceptions import BringAuthException, BringException, BringMissingFieldException

log = logging.getLogger("familyhub-web.shopping")

MAX_NAME = 60  # Bring!'s own app cuts item names around here
MAX_NOTE = 80
RECENT = 12  # recently bought items shown for a one tap re-add
# After a refused sign-in (wrong password, no such list), wait this long before
# trying again, so phones refreshing every minute don't get the account locked.
SIGNIN_RETRY = 5 * 60


class ShoppingError(Exception):
    pass


# Urgent items carry this word at the start of their Bring! note ("Urgent" or
# "Urgent · 2 litres"), so the Bring! app shows it too and nothing extra is stored.
URGENT = "Urgent"
_URGENT_RE = re.compile(r"^\s*urgent\b[\s·:,-]*", re.IGNORECASE)


def split_note(spec: str | None) -> tuple[bool, str]:
    """A Bring! note as (urgent, the rest of the note)."""
    spec = spec or ""
    m = _URGENT_RE.match(spec)
    return (True, spec[m.end():].strip()) if m else (False, spec.strip())


def join_note(urgent: bool, note: str) -> str:
    if not urgent:
        return note
    return f"{URGENT} · {note}" if note else URGENT


def item_json(purchase) -> dict:
    urgent, note = split_note(purchase.specification)
    return {"id": purchase.uuid, "name": purchase.itemId, "note": note, "urgent": urgent}


def pick_list(lists: list, wanted: str | None, default_uuid: str):
    """The list named in bring.json (any case), else the account's default list."""
    if wanted:
        found = next((l for l in lists if l.name.strip().lower() == wanted.strip().lower()), None)
        if not found:
            raise ShoppingError(f"No Bring! list called {wanted!r}. The account has: "
                                + ", ".join(l.name for l in lists))
        return found
    return next((l for l in lists if l.listUuid == default_uuid), lists[0] if lists else None)


class Shopping:
    def __init__(self, key_file: Path):
        keys = json.loads(key_file.read_text())
        self.email, self.password = keys["email"], keys["password"]
        self.list_name = keys.get("list")  # the family list's name, as it shows in Bring!
        self.session: aiohttp.ClientSession | None = None
        self.bring: Bring | None = None
        self.list_uuid = ""
        self.title = ""
        self.lock = asyncio.Lock()
        self.refused: tuple[float, Exception] | None = None  # (when, why) the last sign-in was refused

    async def _ready(self) -> Bring:
        """Signs in on first use. bring-api renews the token by itself after that."""
        async with self.lock:
            if not self.bring:
                if self.refused and time.monotonic() - self.refused[0] < SIGNIN_RETRY:
                    raise self.refused[1]
                session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15))
                bring = Bring(session, self.email, self.password)
                try:
                    auth = await bring.login()
                    chosen = pick_list((await bring.load_lists()).lists, self.list_name, auth.bringListUUID)
                    if not chosen:
                        raise ShoppingError("The Bring! account has no lists. Invite it to the family list.")
                except BaseException as e:
                    await session.close()
                    if isinstance(e, (BringAuthException, ShoppingError)):
                        self.refused = (time.monotonic(), e)
                    raise
                self.session, self.bring = session, bring
                self.list_uuid, self.title = chosen.listUuid, chosen.name
        return self.bring

    async def _call(self, fn):
        try:
            return await fn(await self._ready())
        except BringAuthException as e:
            raise ShoppingError("Bring! didn't accept the sign-in on the server") from e
        except (BringException, BringMissingFieldException) as e:
            raise ShoppingError("Can't reach Bring! right now") from e

    async def items(self) -> dict:
        lst = await self._call(lambda b: b.get_list(self.list_uuid))
        return {"list": self.title, "items": [item_json(p) for p in lst.items.purchase],
                "recent": [item_json(p) for p in lst.items.recently[:RECENT]]}

    async def add(self, name: str, note: str = "", urgent: bool = False) -> None:
        await self._call(lambda b: b.save_item(self.list_uuid, name, join_note(urgent, note)))

    async def _find(self, item_id: str):
        """The item on the list, so a phone can only change what's really there."""
        lst = await self._call(lambda b: b.get_list(self.list_uuid))
        found = next((p for p in lst.items.purchase if p.uuid == item_id), None)
        if not found:
            raise KeyError(item_id)
        return found

    async def set_urgent(self, item_id: str, urgent: bool) -> None:
        """Moves an item between the Urgent and Everyday sections, keeping its note."""
        found = await self._find(item_id)
        note = join_note(urgent, split_note(found.specification)[1])
        await self._call(lambda b: b.update_item(self.list_uuid, found.itemId, note, found.uuid))

    async def done(self, item_id: str) -> None:
        """Ticks an item off (Bring! moves it to its recent list)."""
        found = await self._find(item_id)
        await self._call(lambda b: b.complete_item(self.list_uuid, found.itemId, found.specification, found.uuid))

    async def close(self) -> None:
        if self.session:
            await self.session.close()


if __name__ == "__main__":
    # Try the sign-in from a PC before putting it on the server:
    #   python shopping.py bring.json            shows the chosen list and every list
    #   python shopping.py bring.json "Milk"     adds an item
    import sys

    async def main():
        shop = Shopping(Path(sys.argv[1]))
        try:
            if len(sys.argv) > 2:
                await shop.add(sys.argv[2])
                print(f"Added {sys.argv[2]}")
            got = await shop.items()
            print(f"List {got['list']!r}: " + (", ".join(i["name"] for i in got["items"]) or "(empty)"))
            print("Lists on this account: " + ", ".join(l.name for l in (await shop.bring.load_lists()).lists))
        finally:
            await shop.close()

    asyncio.run(main())

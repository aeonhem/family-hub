"""Family Hub on Discord, so Erlina gets it on her iPhone.

- A morning DM with today's events, dinner and her chores (with tick buttons).
- New memos addressed to her (or everyone) arrive as DMs.
- /today, /dinner, /chores and /memo, or just type "dinner?" in the DM.

Everything is read from and written to the Family Google Calendar.
"""
from __future__ import annotations

import asyncio
import functools
import json
import logging
import os
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import discord
from discord import app_commands
from discord.ext import tasks
from dotenv import load_dotenv
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build

import calendar_store as cs

load_dotenv()
log = logging.getLogger("familyhub")

TZ = ZoneInfo(os.getenv("TIMEZONE", "Australia/Brisbane"))
KID = os.getenv("KID_NAME", "Erlina")
KID_ID = int(os.getenv("ERLINA_DISCORD_USER_ID") or 0)
MORNING = time.fromisoformat(os.getenv("MORNING_TIME", "07:00")).replace(tzinfo=TZ)
# Sent within a minute of MORNING, or later up to this time if the bot was down.
MORNING_LATEST = time(11, 0)
STATE_FILE = Path(os.getenv("STATE_FILE", "state.json"))
DISCORD_LIMIT = 2000
ALERT_AFTER_FAILURES = 10  # about ten minutes of failed memo checks
ALERT_EVERY = timedelta(hours=6)


def _family_ids() -> dict[int, str]:
    """Discord user id -> family name. Erlina always; parents optional."""
    ids = {KID_ID: KID} if KID_ID else {}
    for pair in filter(None, os.getenv("PARENT_DISCORD_IDS", "").split(",")):
        name, _, uid = pair.partition(":")
        if uid.strip().isdigit():
            ids[int(uid)] = name.strip()
    return ids


FAMILY = _family_ids()
PARENT_IDS = [uid for uid in FAMILY if uid != KID_ID]

cal: cs.FamilyCalendar  # set in main()


def _calendar() -> cs.FamilyCalendar:
    # No scopes passed: use whatever the token was granted (calendar.events
    # for tokens from the current authorize.py).
    creds = Credentials.from_authorized_user_file(os.getenv("GOOGLE_TOKEN_FILE", "token.json"))
    service = build("calendar", "v3", credentials=creds, cache_discovery=False)
    return cs.FamilyCalendar(service, os.environ["FAMILY_CALENDAR_ID"], TZ)


# ---------- Google calls ----------

# The Google client's HTTP layer (httplib2) isn't thread-safe, so every call
# goes through this one worker thread.
_google = ThreadPoolExecutor(max_workers=1, thread_name_prefix="google")
_failures = {"count": 0, "alerted": None}


async def gcal(fn, *args):
    try:
        result = await asyncio.get_running_loop().run_in_executor(_google, functools.partial(fn, *args))
    except Exception as e:
        _failures["count"] += 1
        if _failures["count"] >= ALERT_AFTER_FAILURES:
            await _alert_parents(e)
        raise
    _failures["count"] = 0
    return result


async def _alert_parents(error: Exception) -> None:
    """Tell the parents once in a while if the calendar keeps failing, so a
    revoked Google sign-in doesn't go unnoticed."""
    last = _failures["alerted"]
    if last and datetime.now(TZ) - last < ALERT_EVERY:
        return
    _failures["alerted"] = datetime.now(TZ)
    text = (f"Family Hub bot can't reach the Family calendar ({type(error).__name__}). "
            "If it says RefreshError, run authorize.py again and copy the new token.json to the server.")
    log.error(text)
    for uid in PARENT_IDS:
        try:
            await (await client.fetch_user(uid)).send(text)
        except discord.HTTPException:
            log.exception("couldn't alert %s", uid)


# ---------- state (memos already sent, last morning message) ----------

def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text())
    except (FileNotFoundError, ValueError):
        return {}


state: dict = {}


def save_state() -> None:
    state["seen_memos"] = state.get("seen_memos", [])[-500:]
    STATE_FILE.write_text(json.dumps(state))


# ---------- text ----------

def today() -> date:
    return datetime.now(TZ).date()


def fmt_time(dt: datetime) -> str:
    return dt.strftime("%I:%M%p").lstrip("0").lower()


def clip(text: str) -> str:
    return text if len(text) <= DISCORD_LIMIT else text[:DISCORD_LIMIT - 1] + "…"


def week_bounds(d: date) -> tuple[date, date]:
    start = d - timedelta(days=d.weekday())
    return start, start + timedelta(days=6)


def kid_chores(items: list[cs.Item], d: date) -> list[cs.Item]:
    """Chores for Erlina (or anyone) today, plus older ones still open."""
    chores = [i for i in items if i.kind == "chore" and i.is_for(KID) and (i.day == d or (i.day < d and not i.done))]
    return sorted(chores, key=lambda c: (c.done, c.day, c.title))


def ticked_this_week(items: list[cs.Item], d: date) -> int:
    """Chores Erlina ticked herself this week (same count as the app)."""
    start, end = week_bounds(d)
    return sum(1 for i in items if i.kind == "chore" and i.done and start <= i.day <= end
               and (i.done_by or "").lower() == KID.lower())


def cook_text(dinner: cs.Item) -> str:
    if not dinner.cook:
        return ""
    return " (takeaway)" if dinner.cook.lower() == "takeaway" else f" ({dinner.cook} cooking)"


def dinner_line(items: list[cs.Item], d: date) -> str | None:
    dinner = next((i for i in items if i.kind == "dinner" and i.day == d), None)
    if not dinner:
        return None
    when = f"{fmt_time(dinner.start)}  " if dinner.start else ""
    return f"{when}Dinner: **{dinner.title}**{cook_text(dinner)}"


def today_text(items: list[cs.Item], d: date, greeting: bool = False) -> str:
    lines = [f"Good morning {KID}! Here's today" if greeting else f"**Today, {d.strftime('%A %d %B').replace(' 0', ' ')}**"]
    events = [i for i in items if i.kind == "event" and i.covers(d)]
    for e in events:
        lines.append(f"{fmt_time(e.start)}  {e.title}" if e.start and e.day == d else f"All day  {e.title}")
    if (dl := dinner_line(items, d)):
        lines.append(dl)
    if not events and not dl:
        lines.append("Nothing on the calendar today.")
    return clip("\n".join(lines))


def chores_text(chores: list[cs.Item]) -> str:
    if not chores:
        return "No chores today. Enjoy!"
    done = sum(c.done for c in chores)
    return f"**Your chores** ({done} of {len(chores)} done). Tap one to tick it."


def dinner_week_text(items: list[cs.Item], d: date) -> str:
    lines = []
    tonight = next((i for i in items if i.kind == "dinner" and i.day == d), None)
    if tonight:
        at = f" at {fmt_time(tonight.start)}" if tonight.start else ""
        lines.append(f"Tonight: **{tonight.title}**{at}{cook_text(tonight)}")
    else:
        lines.append("Tonight: not planned yet")
    for offset in range(1, 7):
        day = d + timedelta(days=offset)
        dinner = next((i for i in items if i.kind == "dinner" and i.day == day), None)
        label = "Tomorrow" if offset == 1 else day.strftime("%A")
        lines.append(f"{label}: {dinner.title if dinner else 'not planned yet'}")
    return clip("\n".join(lines))


def memo_text(m: cs.Item) -> str:
    return clip(f"**Memo from {m.from_who or 'the family'}**\n{m.title}")


SORRY = "Sorry, I couldn't reach the family calendar just now. Try again in a minute."


# ---------- chore buttons ----------

class ChoreButton(discord.ui.DynamicItem[discord.ui.Button], template=r"chore:(?P<id>[A-Za-z0-9_]+)"):
    """A tick button per chore. The event id lives in custom_id so buttons keep
    working after the bot restarts."""

    def __init__(self, event_id: str, label: str = "Chore", done: bool = False):
        super().__init__(discord.ui.Button(
            label=label[:80],
            style=discord.ButtonStyle.success if done else discord.ButtonStyle.secondary,
            custom_id=f"chore:{event_id}",
        ))
        self.event_id = event_id

    @classmethod
    async def from_custom_id(cls, interaction: discord.Interaction, item: discord.ui.Button, match):
        return cls(match["id"], item.label or "Chore")

    async def callback(self, interaction: discord.Interaction) -> None:
        name = FAMILY.get(interaction.user.id)
        if not name:
            await interaction.response.send_message("Sorry, these buttons are just for the family.", ephemeral=True)
            return
        await interaction.response.defer()
        try:
            item = await gcal(cal.get, self.event_id)
            now_done = not item.done
            await gcal(cal.set_chore_done, item, now_done, name)
            content, view, chores, week_count = await chores_message()
        except Exception:
            log.exception("chore tick failed")
            await interaction.followup.send(SORRY, ephemeral=True)
            return
        await interaction.edit_original_response(content=content, view=view)
        if now_done:
            done = sum(c.done for c in chores)
            cheer = "All done, amazing!" if done == len(chores) else f"Nice! {done} of {len(chores)} chores done."
            await interaction.followup.send(f"{cheer} That's {week_count} this week.")


async def chores_message() -> tuple[str, discord.ui.View, list[cs.Item], int]:
    d = today()
    start, end = week_bounds(d)
    items = await gcal(cal.items, min(start, d - timedelta(days=14)), end)
    chores = kid_chores(items, d)
    view = discord.ui.View(timeout=None)
    for c in chores[:25]:
        prefix = "✅" if c.done else "☐"
        overdue = " (overdue)" if c.day < d and not c.done else ""
        view.add_item(ChoreButton(c.event_id, f"{prefix} {c.title}{overdue}", c.done))
    return chores_text(chores), view, chores, ticked_this_week(items, d)


# ---------- the bot ----------

intents = discord.Intents.default()
intents.message_content = True  # lets her just type "dinner?" in the DM
client = discord.Client(intents=intents)
tree = app_commands.CommandTree(client)


def family_only(interaction: discord.Interaction) -> bool:
    return interaction.user.id in FAMILY


@tree.error
async def on_command_error(interaction: discord.Interaction, error: app_commands.AppCommandError):
    log.error("command failed", exc_info=error)
    if interaction.response.is_done():
        await interaction.followup.send(SORRY, ephemeral=True)
    else:
        await interaction.response.send_message(SORRY, ephemeral=True)


@tree.command(name="today", description="What's on today, dinner and your chores")
async def today_cmd(interaction: discord.Interaction):
    if not family_only(interaction):
        return await interaction.response.send_message("Sorry, this bot is just for the family.", ephemeral=True)
    await interaction.response.defer()
    d = today()
    items = await gcal(cal.items, d, d)
    await interaction.followup.send(today_text(items, d))
    content, view, _, _ = await chores_message()
    await interaction.followup.send(content, view=view)


@tree.command(description="What's for dinner tonight and this week")
async def dinner(interaction: discord.Interaction):
    if not family_only(interaction):
        return await interaction.response.send_message("Sorry, this bot is just for the family.", ephemeral=True)
    await interaction.response.defer()
    d = today()
    items = await gcal(cal.items, d, d + timedelta(days=6))
    await interaction.followup.send(dinner_week_text(items, d))


@tree.command(description="Your chores, with buttons to tick them off")
async def chores(interaction: discord.Interaction):
    if not family_only(interaction):
        return await interaction.response.send_message("Sorry, this bot is just for the family.", ephemeral=True)
    await interaction.response.defer()
    content, view, _, _ = await chores_message()
    await interaction.followup.send(content, view=view)


@tree.command(description="Send a memo to the family")
@app_commands.describe(message="What do you want to say?", to="Who it's for")
@app_commands.choices(to=[app_commands.Choice(name=n, value=n) for n in ("Everyone", "Julian", "Sally")])
async def memo(interaction: discord.Interaction, message: str, to: app_commands.Choice[str] | None = None):
    name = FAMILY.get(interaction.user.id)
    if not name:
        return await interaction.response.send_message("Sorry, this bot is just for the family.", ephemeral=True)
    await interaction.response.defer()
    target = to.value if to else "Everyone"
    await gcal(cal.send_memo, message, name, target)
    await interaction.followup.send(clip(f"Sent to {target.lower() if target == 'Everyone' else target}: {message}"))


@client.event
async def on_message(message: discord.Message):
    if message.author.bot or not isinstance(message.channel, discord.DMChannel):
        return
    if message.author.id not in FAMILY:
        return
    words = set(re.findall(r"[a-z]+", message.content.lower()))
    d = today()
    try:
        if words & {"dinner", "dinners", "tea"}:
            items = await gcal(cal.items, d, d + timedelta(days=6))
            await message.channel.send(dinner_week_text(items, d))
        elif words & {"chore", "chores"}:
            content, view, _, _ = await chores_message()
            await message.channel.send(content, view=view)
        elif "today" in words:
            items = await gcal(cal.items, d, d)
            await message.channel.send(today_text(items, d))
        else:
            await message.channel.send(
                "Try `/today`, `/dinner`, `/chores` or `/memo`, or just ask me about dinner or chores."
            )
    except Exception:
        log.exception("reply to DM failed")
        await message.channel.send(SORRY)


@tasks.loop(seconds=60)
async def every_minute():
    """DM Erlina new memos, and send the morning message once it's time (or
    late, if the bot was down at MORNING)."""
    try:
        await deliver_memos()
        if not_sent_this_morning(datetime.now(TZ)):
            await send_morning()
    except Exception:
        log.exception("minute check failed; will retry next minute")


async def deliver_memos() -> None:
    """DM Erlina any new memo meant for her that she didn't send herself."""
    first_run = "seen_memos" not in state
    seen = set(state.get("seen_memos", []))
    d = today()
    items = await gcal(cal.items, d - timedelta(days=1), d + timedelta(days=1))
    memos = [i for i in items if i.kind == "memo"]
    if first_run:
        # Don't flood her with old memos the first time the bot starts.
        state["seen_memos"] = [m.event_id for m in memos]
        save_state()
        return
    new = [m for m in memos if m.event_id not in seen and m.is_for(KID) and (m.from_who or "").lower() != KID.lower()]
    if not new:
        return
    kid = await client.fetch_user(KID_ID)
    for m in new:
        try:
            await kid.send(memo_text(m))
        except discord.HTTPException as e:
            # A memo Discord rejects outright would fail forever; skip it.
            # Anything else (Discord down, rate limits) is retried next minute.
            if not (400 <= e.status < 500) or e.status == 429:
                raise
            log.warning("skipping memo %s that Discord rejected: %s", m.event_id, e)
        # Saved one at a time so a later failure never re-sends this one.
        state.setdefault("seen_memos", []).append(m.event_id)
        save_state()


def not_sent_this_morning(now: datetime) -> bool:
    return MORNING.replace(tzinfo=None) <= now.time() < MORNING_LATEST and state.get("morning_sent") != now.date().isoformat()


async def send_morning() -> None:
    d = today()
    items = await gcal(cal.items, d, d)
    content, view, _, _ = await chores_message()
    kid = await client.fetch_user(KID_ID)
    await kid.send(today_text(items, d, greeting=True))
    await kid.send(content, view=view)
    state["morning_sent"] = d.isoformat()
    save_state()


@client.event
async def setup_hook():
    client.add_dynamic_items(ChoreButton)
    await tree.sync()
    every_minute.start()


@client.event
async def on_ready():
    log.info("Logged in as %s", client.user)


def main() -> None:
    global cal
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if not KID_ID:
        raise SystemExit("Set ERLINA_DISCORD_USER_ID in .env")
    cal = _calendar()
    state.update(load_state())
    client.run(os.environ["DISCORD_TOKEN"], log_handler=None)


if __name__ == "__main__":
    main()

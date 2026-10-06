"""Family Hub on Discord, so Erlina gets it on her iPhone.

- A morning DM with today's events, dinner and her chores (with tick buttons).
- New memos addressed to her (or everyone) arrive as DMs.
- /today, /dinner, /chores and /memo, or just type "dinner?" in the DM.

Everything is read from and written to the Family Google Calendar.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
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
KID_ID = int(os.environ["ERLINA_DISCORD_USER_ID"])
MORNING = time.fromisoformat(os.getenv("MORNING_TIME", "07:00")).replace(tzinfo=TZ)
STATE_FILE = Path(os.getenv("STATE_FILE", "state.json"))
SCOPES = ["https://www.googleapis.com/auth/calendar"]


def _family_ids() -> dict[int, str]:
    """Discord user id -> family name. Erlina always; parents optional."""
    ids = {KID_ID: KID}
    for pair in filter(None, os.getenv("PARENT_DISCORD_IDS", "").split(",")):
        name, _, uid = pair.partition(":")
        if uid.strip().isdigit():
            ids[int(uid)] = name.strip()
    return ids


FAMILY = _family_ids()


def _calendar() -> cs.FamilyCalendar:
    creds = Credentials.from_authorized_user_file(os.getenv("GOOGLE_TOKEN_FILE", "token.json"), SCOPES)
    service = build("calendar", "v3", credentials=creds, cache_discovery=False)
    return cs.FamilyCalendar(service, os.environ["FAMILY_CALENDAR_ID"], TZ)


cal = _calendar()


# ---------- state (which memos Erlina has already been sent) ----------

def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text())
    except (FileNotFoundError, ValueError):
        return {}


def save_state(state: dict) -> None:
    state["seen_memos"] = state.get("seen_memos", [])[-500:]
    STATE_FILE.write_text(json.dumps(state))


# ---------- text ----------

def today() -> date:
    return datetime.now(TZ).date()


def fmt_time(dt: datetime) -> str:
    return dt.strftime("%I:%M%p").lstrip("0").lower()


def week_bounds(d: date) -> tuple[date, date]:
    start = d - timedelta(days=d.weekday())
    return start, start + timedelta(days=6)


def kid_chores(items: list[cs.Item], d: date) -> list[cs.Item]:
    """Chores for Erlina (or anyone) today, plus older ones still open."""
    chores = [i for i in items if i.kind == "chore" and i.is_for(KID) and (i.day == d or (i.day < d and not i.done))]
    return sorted(chores, key=lambda c: (c.done, c.day, c.title))


def dinner_line(items: list[cs.Item], d: date) -> str | None:
    dinner = next((i for i in items if i.kind == "dinner" and i.day == d), None)
    if not dinner:
        return None
    when = f"{fmt_time(dinner.start)}  " if dinner.start else ""
    cook = f" ({dinner.cook} cooking)" if dinner.cook and dinner.cook != "Takeaway" else (" (takeaway)" if dinner.cook else "")
    return f"{when}Dinner: **{dinner.title}**{cook}"


def today_text(items: list[cs.Item], d: date, greeting: bool = False) -> str:
    lines = [f"Good morning {KID}! Here's today" if greeting else f"**Today, {d.strftime('%A %d %B').replace(' 0', ' ')}**"]
    events = [i for i in items if i.kind == "event" and i.day == d]
    for e in events:
        lines.append(f"{fmt_time(e.start)}  {e.title}" if e.start else f"All day  {e.title}")
    if (dl := dinner_line(items, d)):
        lines.append(dl)
    if not events and not dl:
        lines.append("Nothing on the calendar today.")
    return "\n".join(lines)


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
        cook = f" ({tonight.cook} cooking)" if tonight.cook and tonight.cook != "Takeaway" else ""
        lines.append(f"Tonight: **{tonight.title}**{at}{cook}")
    else:
        lines.append("Tonight: not planned yet")
    for offset in range(1, 7):
        day = d + timedelta(days=offset)
        dinner = next((i for i in items if i.kind == "dinner" and i.day == day), None)
        label = "Tomorrow" if offset == 1 else day.strftime("%A")
        lines.append(f"{label}: {dinner.title if dinner else 'not planned yet'}")
    return "\n".join(lines)


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
        item = await asyncio.to_thread(cal.get, self.event_id)
        now_done = not item.done
        await asyncio.to_thread(cal.set_chore_done, item, now_done, name)
        content, view, chores, week_count = await chores_message()
        await interaction.edit_original_response(content=content, view=view)
        if now_done:
            done = sum(c.done for c in chores)
            cheer = "All done, amazing!" if done == len(chores) else f"Nice! {done} of {len(chores)} chores done."
            await interaction.followup.send(f"{cheer} That's {week_count} this week.")


async def chores_message() -> tuple[str, discord.ui.View, list[cs.Item], int]:
    d = today()
    start, end = week_bounds(d)
    items = await asyncio.to_thread(cal.items, min(start, d - timedelta(days=14)), end)
    chores = kid_chores(items, d)
    week_count = sum(1 for i in items if i.kind == "chore" and i.is_for(KID) and i.done and start <= i.day <= end)
    view = discord.ui.View(timeout=None)
    for c in chores[:25]:
        prefix = "✅" if c.done else "☐"
        overdue = " (overdue)" if c.day < d and not c.done else ""
        view.add_item(ChoreButton(c.event_id, f"{prefix} {c.title}{overdue}", c.done))
    return chores_text(chores), view, chores, week_count


# ---------- the bot ----------

intents = discord.Intents.default()
intents.message_content = True  # lets her just type "dinner?" in the DM
client = discord.Client(intents=intents)
tree = app_commands.CommandTree(client)


def family_only(interaction: discord.Interaction) -> bool:
    return interaction.user.id in FAMILY


@tree.command(name="today", description="What's on today, dinner and your chores")
async def today_cmd(interaction: discord.Interaction):
    if not family_only(interaction):
        return await interaction.response.send_message("Sorry, this bot is just for the family.", ephemeral=True)
    await interaction.response.defer()
    d = today()
    items = await asyncio.to_thread(cal.items, d, d)
    await interaction.followup.send(today_text(items, d))
    content, view, _, _ = await chores_message()
    await interaction.followup.send(content, view=view)


@tree.command(description="What's for dinner tonight and this week")
async def dinner(interaction: discord.Interaction):
    if not family_only(interaction):
        return await interaction.response.send_message("Sorry, this bot is just for the family.", ephemeral=True)
    await interaction.response.defer()
    d = today()
    items = await asyncio.to_thread(cal.items, d, d + timedelta(days=6))
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
    await asyncio.to_thread(cal.send_memo, message, name, target)
    await interaction.followup.send(f"Sent to {target.lower() if target == 'Everyone' else target}: {message}")


@client.event
async def on_message(message: discord.Message):
    if message.author.bot or not isinstance(message.channel, discord.DMChannel):
        return
    if message.author.id not in FAMILY:
        return
    text = message.content.lower()
    d = today()
    if "dinner" in text or "tea" in text.split():
        items = await asyncio.to_thread(cal.items, d, d + timedelta(days=6))
        await message.channel.send(dinner_week_text(items, d))
    elif "chore" in text:
        content, view, _, _ = await chores_message()
        await message.channel.send(content, view=view)
    elif "today" in text:
        items = await asyncio.to_thread(cal.items, d, d)
        await message.channel.send(today_text(items, d))
    else:
        await message.channel.send(
            "Try `/today`, `/dinner`, `/chores` or `/memo`, or just ask me about dinner or chores."
        )


@tasks.loop(seconds=60)
async def memo_poll():
    """DM Erlina any new memo meant for her that she didn't send herself."""
    try:
        await _deliver_memos()
    except Exception:
        log.exception("memo poll failed; will retry next minute")


async def _deliver_memos():
    state = load_state()
    first_run = "seen_memos" not in state
    seen = set(state.get("seen_memos", []))
    d = today()
    items = await asyncio.to_thread(cal.items, d - timedelta(days=1), d + timedelta(days=1))
    new = [
        i for i in items
        if i.kind == "memo" and i.event_id not in seen and i.is_for(KID)
        and (i.from_who or "").lower() != KID.lower()
    ]
    if new and not first_run:
        kid = await client.fetch_user(KID_ID)
        for m in new:
            sender = m.from_who or "the family"
            await kid.send(f"**Memo from {sender}**\n{m.title}")
    seen_list = state.get("seen_memos", []) + [m.event_id for m in new]
    if first_run:
        # Don't flood her with old memos the first time the bot starts.
        seen_list = [i.event_id for i in items if i.kind == "memo"]
    state["seen_memos"] = seen_list
    save_state(state)


@tasks.loop(time=MORNING)
async def morning():
    try:
        d = today()
        items = await asyncio.to_thread(cal.items, d, d)
        kid = await client.fetch_user(KID_ID)
        await kid.send(today_text(items, d, greeting=True))
        content, view, _, _ = await chores_message()
        await kid.send(content, view=view)
    except Exception:
        log.exception("morning message failed")


@client.event
async def setup_hook():
    client.add_dynamic_items(ChoreButton)
    await tree.sync()
    memo_poll.start()
    morning.start()


@client.event
async def on_ready():
    log.info("Logged in as %s", client.user)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    client.run(os.environ["DISCORD_TOKEN"], log_handler=None)

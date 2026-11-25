# Plan: move chores to their own calendar

Status: code done (2026-11-23, branch `chores-calendar`); the calendar is not
made yet, and the move script (`bot/move_chores.py`) has not run.

## Why

Chores are all-day events on the shared **Family** calendar. Google gives each
person their own default "all-day event" notification for that calendar, so
Julian and Sally get alerts for Erlina's chores. Google keeps reminders per
person, so the app cannot turn these off for other people. With chores on a
separate calendar, each person can turn off notifications for that calendar
only. Events, dinners and notes on Family keep their alerts.

## The new calendar

- Name: **Family Chores**, made in Google Calendar by one parent.
- Shared with each family member and with the bot's Google account, with
  "Make changes to events".
- Each person who does not want chore alerts sets "All-day event
  notifications" to none for Family Chores only.
- Its ID goes in the bot and web server env as `CHORES_CALENDAR_ID`.

## Changes

### Bot and web server (`bot/calendar_store.py`, `bot/bot.py`, `web/server.py`)

1. `FamilyCalendar` takes an optional `chores_calendar_id`. If it is blank,
   everything works as now (one calendar).
2. Reading: load events from both calendars and merge them. Items record the
   calendar they came from (new `calendar_id` field on `Item`).
3. `add_chore` writes to the chores calendar, with
   `reminders: {useDefault: False, overrides: []}` for the bot's own account.
4. `set_chore_done`, `clear_missed_chores` and the get/delete calls use the
   item's own `calendar_id`, so old chores still on Family keep working.
5. Read `CHORES_CALENDAR_ID` from env; add it to `.env.example` and the READMEs.

### Android app (`data/CalendarRepository.kt`, `ui/HubViewModel.kt`)

1. Find the chores calendar by name "Family Chores" (writable, synced), the
   same way the app finds Family. Save its ID in prefs as `choresCalendarId`.
2. Turn on sync for it with `ensureSynced`, like Family.
3. `load` reads both calendar IDs and merges the results.
4. `addChore` writes to the chores calendar if the phone has it; if not, it
   falls back to Family, so nothing breaks on a phone that is not set up yet.
5. Ticking, editing and stopping chores need no change: they use the event ID,
   which is unique on the phone.

### Moving existing chores

A one-time script uses the Calendar API `events.move` to move open and
repeating `☐` chores and all done (`✅`) chores from Family to Family Chores.
`events.move` keeps each event's ID, title and description, so ticks and
`done-by` stay the same. The app still reads chores from both calendars, so a
chore added on a phone that is not set up yet still shows.

### Docs and tests

- Update `docs/calendar-conventions.md` and `docs/architecture.md`.
- Tests: chores are written to the chores calendar; reading merges both; a
  chore still on Family can be ticked and cleared; a blank
  `CHORES_CALENDAR_ID` works as today.

## Order

1. Make and share the calendar (manual, a parent).
2. Bot and web server change, then deploy (check disk space first, and run
   scanner triage).
3. Android app change, then install on each phone.
4. Run the move script.
5. Each person turns off notifications for Family Chores.

## Open questions

1. Answered 2026-11-23: Erlina uses the web app. The web server reads the
   calendars through the bot's account, so Erlina needs no Google access to
   Family Chores. Only the parents' phones need it.
2. Answered 2026-11-23: a parent's account owns Family Chores. That parent
   shares it with the other parent and the bot's account ("Make changes to
   events").
3. Answered 2026-11-23: done chores from past weeks move too.

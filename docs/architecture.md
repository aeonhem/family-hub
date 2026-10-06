# Architecture

## Features (from the original idea)

1. **Dinner plan**: what's for dinner each night, and when.
2. **Chores**: a list that can be ticked off (the tick matters, it's the reward).
3. **Notes / memos**: general family comms; a memo pushes a notification.
4. **Calendar**: shared family events.

## Google Calendar is the backend

Everything is an event on the **Family** calendar that Google creates for a
Google family group. Julian and Sally already see it, so on day one dinner,
chores and memos appear in the Calendar app they use with no install at all.

```
          ┌──────────────────────────────────┐
          │  Google Calendar: "Family"       │
          │  events, 🍽 dinners, ☐/✅ chores, │
          │  📣 memos, 📝 notes               │
          └──────┬──────────────────┬────────┘
                 │ phone calendar   │ Calendar API (your Google sign-in)
       ┌─────────┴───────┐   ┌──────┴─────────────┐
       │ Android app     │   │ Discord bot        │
       │ (Julian, Sally) │   │ (Erlina, iPhone)   │
       └─────────────────┘   └────────────────────┘
```

- **Storage and sync**: Google Calendar. Edits on any device show everywhere.
- **Push to Android**: the app posts its own notification when a memo for
  that phone's person arrives. (Not Google Calendar reminders: Google keeps
  reminders per person, so one set by the sender only buzzes the sender.)
- **Push to Erlina**: the bot polls the calendar and DMs her anything new
  addressed to her. Discord DMs are normal iPhone notifications.
- **The Android app** reads and writes the same calendar through the phone's
  own calendar storage (what Google Calendar syncs), so no sign-in or API key.
  It shows Today / Dinner / Chores / Memos, with proper tick boxes.
- **Distribution**: GitHub Actions builds the APK; download and sideload it.

How each thing is stored as an event is in
[calendar-conventions.md](calendar-conventions.md).

## Why this shape

- No server or database to run, back up, or pay for.
- Julian and Sally get value before the app exists.
- Erlina doesn't need a Google account; the bot reads the calendar for her,
  signed in once as Julian (family calendars can't be shared outside the family).

## Trade-offs

- Notes are a stretch for a calendar (they're stored as all-day events).
- Ticking a chore renames the event; the app and bot hide that detail. For a
  repeating chore only that day's occurrence changes.
- Memos are 5-minute events, so they also show in Google Calendar. If anyone
  gets a second alert from Google Calendar, turn off that calendar's default
  notifications in Google Calendar settings.
- The bot runs on a free Google Cloud server (e2-micro, free tier).

# Architecture

## Features (from the original idea)

1. **Dinner plan**: what's for dinner each night, and when.
2. **Chores**: a list that can be ticked off (the tick matters, it's the reward).
3. **Notes / memos**: general family comms; a memo pushes a notification.
4. **Calendar**: shared family events.

## Pieces

```
            ┌──────────────────────────┐
            │   Firebase (free tier)   │
            │  Firestore: meals,       │
            │  chores, notes, memos    │
            │  FCM: push to Android    │
            └─────┬──────────────┬─────┘
                  │              │
     ┌────────────┴───┐   ┌──────┴─────────────┐
     │ Android app    │   │ Discord bot        │
     │ (Julian, Sally)│   │ (Erlina, iPhone)   │
     └────────┬───────┘   └──────┬─────────────┘
              │                  │
            ┌─┴──────────────────┴─┐
            │ Google Calendar:     │
            │ shared "Family" cal  │
            └──────────────────────┘
```

- **Shared data** lives in Firestore. Real-time listeners keep every phone in
  sync without a server of our own.
- **Calendar** stays in Google Calendar, because Julian and Sally already use
  it. A dedicated "Family" calendar is shared between them; the app shows it
  next to dinner and chores. The bot reads it (service account) for Erlina.
- **Push**: Android gets memos via Firebase Cloud Messaging. Erlina gets them
  as Discord DMs, which arrive as normal iPhone notifications.
- **Distribution**: GitHub Actions builds the APK; download and sideload it.

## Why not...

- **A web app**: needs hosting/a domain (ruled out).
- **An iOS app**: needs a paid Apple developer account to install outside
  TestFlight/App Store. Discord gives Erlina push + buttons for free.
- **Google Sheets as the database**: tempting, but no real-time sync or push.

## Open questions

- Where the bot runs 24/7: a home PC, a Raspberry Pi, or a free cloud VM.
- Whether Erlina should see the whole calendar or only events she's in.

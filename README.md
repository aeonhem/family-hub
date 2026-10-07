# Family Hub

One place for Julian, Sally and Erlina to see what's on, what's for dinner,
what chores need doing (and tick them off), and to send each other memos.

Everything lives in the existing **Family** Google Calendar, so it already
shows up in Google Calendar on Julian's and Sally's phones. The app and the bot are friendlier front ends on top of it.

| Who | How they use it |
|---|---|
| Julian, Sally (Android) | Google Calendar as-is, plus a sideloaded app with Today / Dinner / Chores / Memos tabs |
| Erlina (iPhone) | Her choice of a Discord bot (morning DM, memos, chore buttons, `/today` etc.) or a web app on her home screen with the same tabs as the Android app. Both run on one free cloud server. |

No paid domain and no database of our own.

See [docs/architecture.md](docs/architecture.md) and [docs/calendar-conventions.md](docs/calendar-conventions.md).

## Layout

```
android/    Kotlin + Jetpack Compose app (sideloaded APK)
bot/        Discord bot for Erlina (Python, discord.py)
web/        Web app for Erlina's home screen (Python server + plain HTML/JS)
docs/       Architecture and calendar conventions
```

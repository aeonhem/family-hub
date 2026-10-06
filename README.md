# Family Hub

One place for Julian, Sally and Erlina to see what's on, what's for dinner,
what chores need doing (and tick them off), and to send each other memos.

| Who | How they use it |
|---|---|
| Julian, Sally (Android) | Sideloaded Android app, push notifications |
| Erlina (iPhone) | Discord bot: DMs for memos, buttons to tick chores, `/today` etc. |

No web app and no paid domain. Everything runs on free tiers.

See [docs/architecture.md](docs/architecture.md).

## Layout

```
android/    Kotlin + Jetpack Compose app (sideloaded APK)
bot/        Discord bot for Erlina (Python, discord.py)
firebase/   Firestore security rules and data model
docs/       Architecture and decisions
```

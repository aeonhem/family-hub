# Android app

Kotlin + Jetpack Compose. Four tabs from the approved mockup: Today, Dinner,
Chores, Memos.

It reads and writes the **Family** calendar through the phone's own calendar
storage (the one Google Calendar syncs), so there's no Google sign-in or API
key to set up. Events follow [../docs/calendar-conventions.md](../docs/calendar-conventions.md).

## Install

1. On the phone, open https://github.com/aeonhem/family-hub/releases/latest
   (signed in to GitHub) and download `FamilyHub.apk`.
2. Open it and allow installing from this source when Android asks.
3. On first launch: allow calendar access, pick your name, and it finds the
   Family calendar by itself.

Every build is signed with the same committed key and has a higher version
number, so new versions install over the old one.

## Automatic updates with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) watches the repo's
releases and offers each new build with one tap.

1. Install Obtainium from its GitHub releases page (`app-arm64-v8a-release.apk`
   suits almost every modern phone).
2. Because this repo is private, Obtainium needs a read-only GitHub token:
   on GitHub go to Settings > Developer settings > Personal access tokens >
   Fine-grained tokens > Generate new token. Repository access: only
   `aeonhem/family-hub`. Permissions: Contents, read-only. Copy the token.
3. In Obtainium: Settings > GitHub, paste the token.
4. Add App, paste `https://github.com/aeonhem/family-hub`, then Add.
   Obtainium links it to the already-installed Family Hub.
5. Obtainium checks in the background and notifies you when there's an
   update; tap Update, then Install.

## Build locally

```
cd android
./gradlew assembleDebug
```

Needs JDK 17 and the Android SDK (Android Studio installs both).

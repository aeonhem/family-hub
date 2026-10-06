# Android app

Kotlin + Jetpack Compose. Four tabs from the approved mockup: Today, Dinner,
Chores, Memos.

It reads and writes the **Family** calendar through the phone's own calendar
storage (the one Google Calendar syncs), so there's no Google sign-in or API
key to set up. Events follow [../docs/calendar-conventions.md](../docs/calendar-conventions.md).

## Install

1. Open the repo's **Releases**, pick **Family Hub (latest)**, and download
   `FamilyHub.apk` on the phone (or grab it from a workflow run's artifacts).
2. Open it and allow installing from this source when Android asks.
3. On first launch: allow calendar access, pick your name, and it finds the
   Family calendar by itself.

Every build is signed with the same committed key, so new versions install
over the old one.

## Build locally

```
cd android
./gradlew assembleDebug
```

Needs JDK 17 and the Android SDK (Android Studio installs both).

# Startup (Android)

Meeting + task reminder app. Kotlin, Jetpack Compose, min SDK 26.

## Build the APK
1. Open this folder in Android Studio (Ladybug or newer). Let Gradle sync.
2. Debug APK: Build > Build Bundle(s) / APK(s) > Build APK(s)
   or `gradle assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`
3. Install on a phone (Android 8.0+).

## Swap in your own look
- Photo: delete `res/drawable/meeting_bg.xml`, add `res/drawable/meeting_bg.jpg` (<1 MB).
- Font: add a font to `res/font/` and set `AppFont` in `Ui.kt`
  (the reference looks like Satoshi / Outfit / General Sans).
- Avatars are initials; replace `Avatar()` with your images if wanted.

## Features
- Onboarding, Home (members, glass cards, Daily Standup list, search)
- Meeting screen with timer ring, reaction stickers (sparkle button)
- New meeting: time, remind 5/10/30 min before, loud alarm toggle
- Notifications + alarm (exact alarms, Snooze 5 min, survives reboot)
- Haptics on every tap and when the widget is pinned
- Bouncy spring animation on buttons and on the widget preview after pinning
- 2x2 home-screen widget, 28dp corners, live countdown to next meeting
  (menu on Daily Standup card > Add widget, or the "Add to home screen" button)

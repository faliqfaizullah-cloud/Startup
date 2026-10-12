# Startup (Android)

Meeting + task reminder app. Kotlin, Jetpack Compose, min SDK 26.

## Build the APK
1. Open this folder in Android Studio (Ladybug or newer). Let Gradle sync.
2. Debug APK: Build > Build Bundle(s) / APK(s) > Build APK(s)
   or `gradle assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`
3. Install on a phone (Android 8.0+).

## Features
- Register / login (accounts are stored on the phone only), empty start, per-account data
- Meetings with reminder notifications, loud alarm, Snooze, survives reboot
- Join Google Meet, Microsoft Teams and Zoom from the app (opens the app, falls back to the browser)
- Meeting screen with timer ring and reaction stickers
- 2x2 home-screen widget, 28dp corners, live countdown
- Haptics on every tap, bouncy press animation
- **Transcripts** (1.3.0, Malay added in 1.4.0):
  - Import any audio or video file (MP3, M4A, WAV, MP4, 3GP, WebM ...) or record your voice in the app
  - Turns the speech into text with timestamps, fully offline (Vosk speech engine)
  - A language pack (about 40 MB) is downloaded once per language, then no internet is needed
  - Copy, Share, Save as .txt, timestamps on/off, delete
  - Languages: English (US/UK/India), Chinese, Hindi, Spanish, French, German, Japanese
  - **Malay, Indonesian, Tamil** use an offline Whisper engine (sherpa-onnx 1.13.8, bundled).
    Pick a model size once: Tiny ~104 MB, Base ~161 MB, Small ~376 MB (Small is best for Malay). One-time download, then offline.
  - **Malay (Google)**: uses the phone's own Google recognizer on a file. Needs Android 13+, internet and the microphone
    permission; audio may be processed by Google. Experimental, depends on the phone; it only appears when available.

## Notes on Transcripts
- Whisper cuts audio into ~28 s pieces at quiet moments and skips near-silent pieces; it is slower than the other languages and adds punctuation.
- The APK is larger (~60 MB) because the Whisper engine's native libraries (arm64 + armv7) are included.
- Output has no punctuation and accuracy depends on audio quality; the small models trade accuracy for speed and size.
- Keep the app open while a long recording is processed (the screen stays on automatically).
- Video is read for its audio track only. In-app recording is voice only.

## Swap in your own look
- Photo: delete `res/drawable/meeting_bg.xml`, add `res/drawable/meeting_bg.jpg` (<1 MB).
- Font: add a font to `res/font/` and set `AppFont` in `Ui.kt`.

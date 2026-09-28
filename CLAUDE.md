# Jarvis for Android — notes for Claude

Anil's personal voice assistant app ("Jarvis"), package `com.anil.jarvis`. Java, no Kotlin, no XML layouts
(all UI is built in code). minSdk 26, targetSdk 34, AGP 8.7.3, Gradle 8.9.

## How Anil works with you (follow these)

- **Reply in Telugu** (he writes Telugu / romanized Telugu). Keep it simple and practical; he tests on his Samsung phone.
- **Never push without his word.** Commit locally, tell him what changed, and wait. Push to `main` only when he says
  "బిల్డ్ చెయ్" / "Build చేయి". Every push to `main` builds a signed APK and publishes release `v1.0.<run number>`
  (`.github/workflows/build.yml`); the app then shows him an update notification. He installs updates by hand —
  the app must never auto-download or auto-install.
- **Secrets:** API keys and the GitHub token live only on his phone (Settings). Never print, log or commit them.
  The signing key is encrypted in the repo and decrypted in CI with the `JARVIS_KEY_PASS` secret.
- **Honest errors:** never silently fall back to another AI model or provider; say what failed and how to fix it.
- **Models:** he picks every model by hand in Settings (live lists from each company's API). Jarvis never auto-picks.

## Safety rules built into Jarvis (keep them)

- Never pays for anything, except the guarded MobiKwik ticket flow with his confirmation and a rupee limit.
- Never types passwords, OTPs or PINs; never operates banking / payment apps.
- Asks before sending, posting, deleting or calling; messages go only after he says "పంపు" / yes.
- No piracy apps or sites.

## Map of the code (`app/src/main/java/com/anil/jarvis/`)

- `MainActivity` — main screen (aurora theme, hologram header orb, chat, ➕ attach menu, quick actions, Live button).
- `SheetActivity` — the small "Hey Jarvis" panel over other apps.
- `SettingsActivity` — every setting as coloured glass cards; `Models` = live model pickers.
- `Brain` — prompts and the three providers (OpenAI Responses API, Anthropic Messages, Gemini generateContent);
  `Tools` — all phone tools (DEFS + execute); `Coder` / `AppMaker` — Python, websites, Android apps via GitHub.
- `LiveSession` + `LiveScreen` + `HoloOrb` — OpenAI Realtime voice mode (full-screen hologram), English practice
  (`Brain.tutorInstructions`) and the interpreter share the same hand-over.
- `VoiceIO`, `NaturalVoice` (OpenAI TTS), `BargeIn`, `Karaoke` — voice in/out; `WakeService` — wake word.
- `NotifyListener` (reads message notifications) → `ScamGuard` (on-phone scam / new-autopay warnings).
- `Usage` — API cost meter (token counts × list prices, balances, low-balance warnings); `Http` feeds it.
- `Updater` / `UpdateReceiver` / `UpdateJob` — manual in-app updates from GitHub releases.
- `Ui` — palette (aurora colours `C_*`, glass helpers), `IconView` — icons drawn in code.

## Checking your work

There is no emulator. Before committing, at least compile: `./gradlew assembleDebug` if the Android SDK is
available; otherwise compile the sources with `javac` against `android.jar` (stubs are needed for OkHttp,
ONNX Runtime, Vosk, ZXing). CI is the real build: after a push, check the Actions run and the latest release.

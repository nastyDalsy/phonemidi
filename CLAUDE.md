# PhoneMIDI — notes for Claude

## The user's setup (remember this)
- Synth: **M-VAVE FM-1 running the Skiff firmware** (custom, github.com/illuminus5/skiff), a
  six-track groovebox. Earlier it ran the official V15 firmware.
- Skiff MIDI: track *n* listens on channel *n* (T1–T6 = ch 1–6, FM-1 menu MIDI IN = CH1-6).
  CC map: 1 mod wheel, 5 glide, 7 track level, 10 pan, 11 expression, 71 resonance, 72 release,
  73 attack, 74 brightness/cutoff, 75 decay, 91 reverb, 93 chorus, 94 delay. Pitch bend ±2 st.
  Program Change = engine preset. Drum engine DRUM: GM keys, 8 lanes (kick 36, rim 37, snare 38,
  clap 39, closed hat 42, tom 45, open hat 46, bell 56). Clock in via USB or TRS (menu MIDI CLOCK).
  USB MIDI port is named "Felucca". Skiff does not document Bluetooth MIDI: prefer USB-C cable.
- Default part layout used by the app for Skiff: T1 strings/melody, T2 pad/chords, T3 bass,
  T4 arpeggio, T6 drums.
- Also uses Ableton (phone as BLE MIDI device, or the web page through IAC/loopMIDI).
- Talks in Spanish (sometimes Catalan): answer and write the UI in Spanish.

## Git: where the current code is (read this first)
- Repository: `nastyDalsy/phonemidi` (https://github.com/nastyDalsy/phonemidi).
- **The up-to-date app is on branch `claude/pensive-goldberg-q6rd9q`**, not on `main`.
  `main` still has only the original upload (8 Nov 2026, commit ca27f5a) and is far behind.
  The old branch `claude/intelligent-planck-s5xi9v` is already included in it.
- Before changing anything in a new conversation:
  `git fetch origin claude/pensive-goldberg-q6rd9q` and start from it
  (`git checkout -B <your-branch> origin/claude/pensive-goldberg-q6rd9q`), or merge it into your
  branch. Starting from `main` gives the old app and loses all later work.
- The `latest` release (APK + manos.html) is rebuilt from whichever branch was pushed last, so a
  push from a branch based on `main` would replace the release with the old app.

## How to build and ship
- No Android SDK in the cloud container: the APK is built by GitHub Actions
  (`.github/workflows/build.yml`) on every push and published to the `latest` release
  (`PhoneMIDI.apk` and `manos.html`). Check the run and the release body for the commit.
- The hand page `web/manos.html` is also bundled into the APK (`ManosActivity`, WebView) with
  MediaPipe from the npm tarball and the hand model, both fetched at build time.
- To test `web/manos.html` here: jsdelivr is blocked, so serve the npm package
  (`npm pack @mediapipe/tasks-vision@0.10.14`) and the model locally and open
  `manos.html?mp=<local package>&model=<local model>` in Playwright with fake camera flags.

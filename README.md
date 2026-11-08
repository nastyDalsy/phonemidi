# PhoneMIDI

Phone motion / camera / microphone -> Bluetooth MIDI (CC, pitch bend, note triggers).

## Get the APK (no Android Studio needed)
Every push builds the app automatically with GitHub Actions (~3-5 min) and publishes it.

**Direct download (open this on the phone):**
https://github.com/nastyDalsy/phonemidi/releases/download/latest/PhoneMIDI.apk

Or: repo -> Releases -> "Latest APK" -> `PhoneMIDI.apk`.

Install it on the phone (allow "install unknown apps" for your browser / file manager).

Build locally instead (needs the Android SDK, e.g. via Android Studio):

    ./gradlew assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk

Or open the folder in Android Studio and press Run.

## Use
- **Bluetooth: ON** makes the phone appear as a BLE MIDI device (named like the phone).
  The app remembers this and turns it back on next time.
- **Connect synth / USB** connects the phone TO a BLE MIDI synth, a USB MIDI device,
  or the phone's own USB MIDI port (set USB mode to "MIDI" when plugged into a PC).
- Each card = one mapping: source -> CC / pitch bend / note trigger. Camera/mic sources
  only switch on when a mapping using them is enabled. The green line under each card
  shows the value being sent right now.
- **Solo** on a card: only that mapping sends MIDI. Use it for MIDI learn so the DAW
  doesn't pick up a different sensor. Tap again to release.
- **Center tilt here**: hold the phone the way you like and tap it; that position becomes
  the middle value (64). Long-press to go back to "flat on the table".
- **Panic**: sends All Notes Off / All Sound Off on all 16 channels.
- Ableton: Options > MIDI map mode, click a parameter, press **Solo** on a card and move
  the phone. Live learns the CC.
- Keep the app open with the screen on (it stops sensors when backgrounded).

## Project layout
    app/src/main/java/com/dalsy/phonemidi/
      MainActivity.java   UI + mapping engine (sensor value -> MIDI message)
      SensorEngine.java   accelerometer, gyro, camera motion, microphone -> 0..1 values
      MidiHub.java        sends every message to BLE + any connected MIDI outputs
      BlePeripheral.java  makes the phone a standard BLE MIDI device
      Mapping.java        one mapping rule (saved as JSON)
    .github/workflows/build.yml  CI that builds the APK

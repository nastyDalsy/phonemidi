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
1. Turn on Bluetooth on the synth (FM-1: hold HOME) or plug it in with a USB-C cable.
2. In the app tap **Conectar FM-1 / USB** and pick the synth. Do not pair it in Android settings.
3. Tap **Probar nota** to hear that it works.
4. Pick a **scene** (Expresivo, Vibrato, Voz, Percusión, Cámara). Each scene is a few
   gestures -> sound controls. Tap a gesture to change what it controls (pitch bend,
   vibrato, volume, expression, brightness, reverb...) and choose Suave / Medio / Fuerte.
5. **Centrar inclinación**: hold the phone the way you play and tap it.
- **Ajustes avanzados** has the full mapping editor, camera choice, Solo for MIDI learn,
  "visible por Bluetooth" (phone shows up as a MIDI device for Ableton) and mic -> audio output.
- Keep the app open with the screen on (it stops sensors when backgrounded).

## Project layout
    app/src/main/java/com/dalsy/phonemidi/
      MainActivity.java   UI + mapping engine (sensor value -> MIDI message)
      SensorEngine.java   accelerometer, gyro, camera motion, microphone -> 0..1 values
      MidiHub.java        sends every message to BLE + any connected MIDI outputs
      BlePeripheral.java  makes the phone a standard BLE MIDI device
      Mapping.java        one mapping rule (saved as JSON)
    .github/workflows/build.yml  CI that builds the APK

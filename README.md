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
4. Pick a **scene**. The first six play *notes* quantized to a scale (Arpa, Theremin, Ritmo,
   Golpes, Arpegio, Mano with the camera) so it always sounds musical; **Ajustes** in the
   Melodía card changes scale, root, octaves, register, tempo and when it sounds. The camera
   view shows what is being tracked (green = movement, ring = your hand).
   The others are expression scenes (no notes). Pick a **scene** (Expresivo, Vibrato, Voz, Percusión, Cámara). Each scene is a few
   gestures -> sound controls. Tap a gesture to change what it controls (pitch bend,
   vibrato, volume, expression, brightness, reverb...) and choose Suave / Medio / Fuerte.
5. **Centrar inclinación**: hold the phone the way you play and tap it.
- **Hand scenes** (Mano Pro, Acordes, Arpa de aire, Manos) use real hand tracking
  (MediaPipe, 21 points per hand). Put the phone on a stand facing you, front camera,
  good light. Mano Pro: slide the right hand to pick the note, pinch thumb + index to play,
  tilt the wrist to bend, raise the other hand for vibrato. Acordes: 1-5 fingers = chords
  I-V of the scale, fist = silence.
- **Ajustes avanzados** has the full mapping editor, camera choice, Solo for MIDI learn,
  "visible por Bluetooth" (phone shows up as a MIDI device for Ableton) and mic -> audio output.
- **Pantalla apagada / otras apps**: turn it on and PhoneMIDI keeps playing with the screen
  off, in other apps or while recording video (a notification shows it; stop it there).
  Motion scenes always work. The microphone gets silence while another app records audio,
  and the camera scenes pause while another app uses the camera (they resume when it is free).

## Hands on a computer (faster, more precise)
`web/manos.html` does the hand tracking with the computer's webcam and GPU and sends MIDI
straight to the synth (FM-1 over USB-C). Download `manos.html` from the latest release, open
it in Chrome or Edge, press **Empezar** and allow camera + MIDI. Modes: Mano Pro, Acordes,
Arpa de aire, Theremin, Expresión. Nothing to install; it needs internet the first time to
load the hand model.

## Project layout
    app/src/main/java/com/dalsy/phonemidi/
      MainActivity.java   UI
      Core.java           playing engine (sensors -> MIDI), independent of the screen
      PlayService.java    foreground service: keeps playing with the screen off / in other apps
      SensorEngine.java   accelerometer, gyro, camera motion, microphone -> 0..1 values
      MidiHub.java        sends every message to BLE + any connected MIDI outputs
      BlePeripheral.java  makes the phone a standard BLE MIDI device
      Mapping.java        one mapping rule (saved as JSON)
      Melody.java         quantizes a sensor to a scale and plays notes (5 play modes)
      CamView.java        shows what the camera tracking sees
      LadderView.java     the scale steps with the current one lit
      HandTracker.java    hand tracking -> position, pinch, open/closed, wrist tilt, fingers
    .github/workflows/build.yml  CI that builds the APK

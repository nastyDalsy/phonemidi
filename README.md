# PhoneMIDI

Phone motion / camera / microphone -> Bluetooth MIDI (CC, pitch bend, note triggers).

## Build the APK (no Android Studio needed)
1. Create an empty GitHub repo, then from this folder:
   git init && git add . && git commit -m "PhoneMIDI" && git branch -M main
   git remote add origin https://github.com/<you>/PhoneMIDI.git && git push -u origin main
2. Repo -> Actions -> "Build APK" runs automatically (~3-5 min).
3. On your phone open: repo -> Releases -> "Latest APK" -> download app-debug.apk -> install
   (allow "install unknown apps" for your browser).
Or open the folder in Android Studio and press Run.

## Use
- "Bluetooth: ON" makes the phone appear as a BLE MIDI device (named like the phone).
- "Connect synth / USB" connects the phone TO a BLE MIDI synth, a USB MIDI device,
  or the phone's own USB MIDI port (set USB mode to "MIDI" when plugged into a PC).
- Each card = one mapping: source -> CC / pitch bend / note trigger. Camera/mic sources
  only switch on when a mapping using them is enabled.
- Ableton: Options > MIDI map mode, click a parameter, move the phone. Live learns the CC.
- Keep the app open with the screen on (it stops sensors when backgrounded).

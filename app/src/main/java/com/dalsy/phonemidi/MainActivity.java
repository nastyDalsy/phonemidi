package com.dalsy.phonemidi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.midi.MidiDeviceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

public class MainActivity extends Activity {

    private static final String[] NOTE_NAMES =
            {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final String[] TYPE_NAMES = {"Control change (CC)", "Pitch bend", "Nota"};

    /** Musician-friendly names for each sensor source (same order as SensorEngine). */
    private static final String[] GESTURES = {
            "Inclinar izquierda ↔ derecha",
            "Inclinar adelante ↔ atrás",
            "Girar el teléfono (twist)",
            "Rotar en X",
            "Rotar en Y",
            "Velocidad de movimiento",
            "Sacudir",
            "Cámara: cuánto te mueves",
            "Cámara: luz",
            "Cámara: mano izquierda ↔ derecha",
            "Cámara: mano arriba ↕ abajo",
            "Voz: volumen",
            "Voz: agudo ↔ grave",
            "Mano: izquierda ↔ derecha",
            "Mano: altura",
            "Mano: pinza (pulgar + índice)",
            "Mano: abrir ↔ cerrar",
            "Mano: girar la muñeca",
            "Mano: dedos levantados",
            "Otra mano: altura",
            "Otra mano: abrir ↔ cerrar",
            "Mano: visible"
    };

    /** What a gesture can control, in musical terms. */
    private static final String[] DEST_NAMES = {
            "Pitch bend (afinación)", "Vibrato (mod wheel)", "Volumen", "Expresión",
            "Brillo (filtro)", "Resonancia", "Reverb", "Chorus", "Nota (disparo)",
            "Otro CC (avanzado)"};
    private static final int[] DEST_TYPE = {Mapping.PB, Mapping.CC, Mapping.CC, Mapping.CC,
            Mapping.CC, Mapping.CC, Mapping.CC, Mapping.CC, Mapping.NOTE, Mapping.CC};
    private static final int[] DEST_NUM = {0, 1, 7, 11, 74, 71, 91, 93, 60, -1};

    private static final String[] SCENES = {
            "Arpa", "Theremin", "Ritmo", "Golpes", "Arpegio",
            "Mano Pro", "Acordes", "Arpa de aire",
            "Expresivo", "Vibrato", "Voz", "Manos"};
    private static final int FIRST_EXPRESSIVE = 8;
    private static final String[] SCENE_HELP = {
            "Inclina el teléfono a izquierda/derecha: cada escalón de la escala suena una nota, como pasar los dedos por un arpa. Inclinar adelante/atrás abre el brillo.",
            "Mantén pulsado el pad y la nota sigue tu inclinación, siempre dentro de la escala. Moverte añade vibrato.",
            "Un ritmo constante: inclina para elegir la nota. Solo suena mientras te mueves; si te detienes, calla.",
            "Elige la nota inclinando y sacude el teléfono para tocarla, más fuerte = más volumen.",
            "Muévete y toca un arpegio: más movimiento = más rápido. Inclinar izquierda/derecha cambia la nota base.",
            "Teléfono apoyado frente a ti. Mano derecha: muévela a los lados para elegir la nota y junta pulgar e índice (pinza) para tocarla; deslízala con la pinza cerrada para ligar notas. Gira la muñeca para doblar la nota. Sube la otra mano para vibrato.",
            "Levanta dedos: 1 a 5 dedos = acordes I, II, III, IV y V de la escala. El puño calla. Sube o baja la mano para cambiar el brillo.",
            "Pasa la mano de un lado a otro frente a la cámara: cada franja suena una nota, como un arpa en el aire. Gira la muñeca para doblar.",
            "Sin notas: inclina para doblar la afinación y abrir el brillo; muévete para dar vibrato.",
            "Sin notas: mientras más te mueves, más vibrato.",
            "Sin notas: tu voz controla expresión y brillo.",
            "Sin notas: tú tocas el FM-1 y la cámara lee tus manos. Girar la muñeca dobla la nota, la altura de la mano da vibrato, abrir la mano abre el brillo."};
    private static final int[] PITCH_SRCS = {0, 1, 2, 13, 14, 18, 9, 10, 12};
    private static final String[] GATE_NAMES = {
            "Siempre", "Mientras te mueves (o se ve la mano)", "Mientras mantienes pulsado el pad",
            "Al hacer pinza (pulgar + índice)"};
    private static final String[] DIV_NAMES = {"Negras (1/4)", "Corcheas (1/8)", "Semicorcheas (1/16)"};
    private static final float[] SENS_LEVELS = {0.7f, 1.5f, 3f};
    private static final String[] SENS_NAMES = {"Suave", "Medio", "Fuerte"};

    private SensorEngine eng;
    private MidiHub hub;
    private final ArrayList<Mapping> maps = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashSet<Integer> held = new HashSet<>();

    private LinearLayout listBox;
    private TextView status;
    private Button btBtn, camBtn, passBtn, advToggle;
    private LinearLayout playBox, advBox, camBox;
    private final Melody melody = new Melody();
    private TextView noteView, padView;
    private LadderView ladder;
    private CheckBox melodyOn;
    private CamView camView;
    private TextView camCaption;
    private TextView connLine, sceneHelp;
    private final ArrayList<Button> sceneBtns = new ArrayList<>();
    private final ArrayList<Mapping> playMaps = new ArrayList<>();
    private final ArrayList<ProgressBar> playBars = new ArrayList<>();
    private final ArrayList<TextView> playLabels = new ArrayList<>();
    private final ArrayList<Mapping> barMaps = new ArrayList<>();
    private final ArrayList<ProgressBar> bars = new ArrayList<>();
    private final ArrayList<TextView> barLabels = new ArrayList<>();

    /** When set, only this mapping sends MIDI (makes DAW "MIDI learn" easy). Not saved. */
    private Mapping solo = null;

    private boolean camFront = true;
    private int tickCount = 0;
    private boolean ticking = false;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        eng = new SensorEngine(this);
        hub = new MidiHub(this, () -> ui.post(this::updateStatus));
        loadMaps();
        buildUi();
        askPerms();
        autoStartBle();
        updateStatus();
    }

    /** Turn the BLE MIDI peripheral back on if it was on last time. */
    private void autoStartBle() {
        if (prefs().getBoolean("bleOn", false) && !hub.ble.running) hub.ble.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyNeeds();
        ticking = true;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticking = false;
        ui.removeCallbacks(tick);
        eng.stopAll();
        releaseHeldNotes();
        saveMaps();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hub.shutdown();
    }

    // ------------------------------------------------------------ permissions

    private void askPerms() {
        ArrayList<String> want = new ArrayList<>();
        want.add(Manifest.permission.CAMERA);
        want.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            want.add(Manifest.permission.BLUETOOTH_CONNECT);
            want.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            want.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        ArrayList<String> need = new ArrayList<>();
        for (String p : want) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        applyNeeds();
        autoStartBle();
        updateStatus();
    }

    // ------------------------------------------------------------ mappings persistence

    private SharedPreferences prefs() {
        return getSharedPreferences("phonemidi", Context.MODE_PRIVATE);
    }

    private void defaults() {
        fillScene(0);
        prefs().edit().putInt("scene", 0).apply();
    }

    private Mapping mk(int src, int type, int num, float sens, float smooth) {
        Mapping m = new Mapping(src, type, num, sens, true);
        m.smooth = smooth;
        return m;
    }

    private void mel(int mode, int pitchSrc, float sens, int scale, int octaves, int gate, boolean dyn) {
        melody.fromJson(new JSONObject()); // defaults
        melody.enabled = true;
        melody.mode = mode;
        melody.pitchSrc = pitchSrc;
        melody.sens = sens;
        melody.scale = scale;
        melody.octaves = octaves;
        melody.gateSrc = gate;
        melody.dynVel = dyn;
    }

    /** Fills {@link #maps} (and the note player) with one of the ready-made scenes. */
    private void fillScene(int i) {
        maps.clear();
        melody.fromJson(new JSONObject()); // notes off unless the scene turns them on
        switch (i) {
            case 1: // Theremin
                mel(Melody.GLIDE, 0, 2f, 1, 2, Melody.GATE_TOUCH, true);
                maps.add(mk(1, Mapping.CC, 74, 1.2f, 0.3f));
                maps.add(mk(5, Mapping.CC, 1, 2.5f, 0.5f));
                break;
            case 2: // Ritmo
                mel(Melody.RHYTHM, 0, 2f, 4, 2, 5, true);
                maps.add(mk(1, Mapping.CC, 74, 1.2f, 0.3f));
                break;
            case 3: // Golpes
                mel(Melody.SHAKE, 0, 2f, 0, 2, Melody.GATE_ALWAYS, true);
                maps.add(mk(1, Mapping.CC, 74, 1.2f, 0.3f));
                break;
            case 4: // Arpegio
                mel(Melody.ARP, 0, 2f, 3, 2, Melody.GATE_ALWAYS, true);
                maps.add(mk(1, Mapping.CC, 74, 1.2f, 0.3f));
                break;
            case 5: // Mano Pro: pinch to play, slide to choose, wrist bends
                mel(Melody.GLIDE, SensorEngine.HAND_X, 1f, 1, 2, SensorEngine.HAND_PINCH, false);
                maps.add(mk(SensorEngine.HAND_ROLL, Mapping.PB, 0, 1.0f, 0.2f));
                maps.add(mk(SensorEngine.HAND2_Y, Mapping.CC, 1, 1.0f, 0.3f));
                maps.add(mk(SensorEngine.HAND_OPEN, Mapping.CC, 74, 1.0f, 0.3f));
                break;
            case 6: // Acordes con los dedos
                mel(Melody.CHORD, SensorEngine.HAND_FINGERS, 1f, 2, 1, SensorEngine.HAND_SEEN, false);
                maps.add(mk(SensorEngine.HAND_Y, Mapping.CC, 74, 1.0f, 0.3f));
                maps.add(mk(SensorEngine.HAND2_Y, Mapping.CC, 1, 1.0f, 0.3f));
                break;
            case 7: // Arpa de aire
                mel(Melody.STEP, SensorEngine.HAND_X, 1f, 0, 2, SensorEngine.HAND_SEEN, false);
                maps.add(mk(SensorEngine.HAND_ROLL, Mapping.PB, 0, 1.0f, 0.2f));
                maps.add(mk(SensorEngine.HAND_Y, Mapping.CC, 74, 1.0f, 0.3f));
                break;
            case 8: // Expresivo
                maps.add(mk(0, Mapping.PB, 0, 1.2f, 0.35f));
                maps.add(mk(1, Mapping.CC, 74, 1.2f, 0.3f));
                maps.add(mk(5, Mapping.CC, 1, 2.5f, 0.5f));
                break;
            case 9: // Vibrato
                maps.add(mk(5, Mapping.CC, 1, 2.5f, 0.5f));
                maps.add(mk(1, Mapping.CC, 74, 1.2f, 0.3f));
                break;
            case 10: // Voz
                maps.add(mk(11, Mapping.CC, 11, 1.5f, 0.3f));
                maps.add(mk(12, Mapping.CC, 74, 1.0f, 0.4f));
                break;
            case 11: // Manos (expresión)
                maps.add(mk(SensorEngine.HAND_ROLL, Mapping.PB, 0, 1.0f, 0.2f));
                maps.add(mk(SensorEngine.HAND_Y, Mapping.CC, 1, 1.0f, 0.3f));
                maps.add(mk(SensorEngine.HAND_OPEN, Mapping.CC, 74, 1.0f, 0.3f));
                break;
            default: // Arpa
                mel(Melody.STEP, 0, 2f, 0, 2, Melody.GATE_ALWAYS, true);
                maps.add(mk(1, Mapping.CC, 74, 1.2f, 0.3f));
                break;
        }
    }

    private void applyScene(int i) {
        releaseHeldNotes();
        solo = null;
        fillScene(i);
        prefs().edit().putInt("scene", i).apply();
        saveMaps();
        rebuild();
        applyNeeds();
        updateStatus();
    }

    private void loadMaps() {
        camFront = prefs().getBoolean("camFront", true);
        eng.calX = prefs().getFloat("calX", 0f);
        eng.calY = prefs().getFloat("calY", 0f);
        String s = prefs().getString("maps", null);
        if (s == null) {
            defaults();
            return;
        }
        try {
            JSONArray a = new JSONArray(s);
            maps.clear();
            for (int i = 0; i < a.length(); i++) maps.add(Mapping.fromJson(a.getJSONObject(i)));
            String ms = prefs().getString("melody", null);
            if (ms != null) {
                melody.fromJson(new JSONObject(ms));
            } else {
                prefs().edit().putInt("scene", -1).apply(); // old save: no scene highlighted
            }
        } catch (Exception e) {
            defaults();
        }
    }

    private void saveMaps() {
        JSONArray a = new JSONArray();
        for (Mapping m : maps) a.put(m.toJson());
        prefs().edit().putString("maps", a.toString()).putString("melody", melody.toJson().toString())
                .putBoolean("camFront", camFront).apply();
    }

    private void changed() {
        saveMaps();
        applyNeeds();
    }

    private void applyNeeds() {
        boolean[] need = new boolean[4]; // motion, camera, mic, hands
        for (Mapping m : maps) {
            if (m.enabled) need[SensorEngine.kind(m.source)] = true;
        }
        if (melody.enabled) {
            if (melody.pitchSrc >= 0) need[SensorEngine.kind(melody.pitchSrc)] = true;
            if (melody.gateSrc >= 0) need[SensorEngine.kind(melody.gateSrc)] = true;
            if (melody.dynVel || melody.mode == Melody.ARP || melody.mode == Melody.SHAKE) need[0] = true;
        }
        boolean cam = need[1] || need[3];
        if (camBox != null) camBox.setVisibility(cam ? View.VISIBLE : View.GONE);
        eng.setMotion(need[0]);
        eng.setHands(need[3]);
        eng.setCamera(cam, camFront);
        boolean mic = need[2];
        eng.setPassthrough(prefs().getBoolean("micPass", false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED);
        eng.setAudio(mic);
    }

    // ------------------------------------------------------------ engine tick

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) return;
            process();
            tickCount++;
            if (tickCount % 4 == 0) updateBars();
            if (tickCount % 60 == 0) updateStatus();
            ui.postDelayed(this, 15);
        }
    };

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    private void process() {
        long now = SystemClock.uptimeMillis();
        melody.update(eng.v, now, hub);
        for (Mapping m : maps) {
            if (!m.enabled) continue;
            boolean muted = solo != null && solo != m;
            float raw = eng.v[m.source];
            switch (SensorEngine.GAIN_MODE[m.source]) {
                case 0: raw = raw * m.sens; break;
                case 1: raw = 0.5f + (raw - 0.5f) * m.sens; break;
                default: break;
            }
            raw = clamp01(raw);
            m.lastRaw = raw;
            m.filtered += (1f - m.smooth) * (raw - m.filtered);

            if (m.type == Mapping.NOTE) {
                if (!m.above && raw >= m.threshold && now - m.lastTrig >= 100) {
                    m.above = true;
                    m.lastTrig = now;
                    if (muted) continue;
                    int vel = Math.max(1, Math.min(127, 40 + Math.round(raw * 87f)));
                    final int ch = m.channel, nn = m.number;
                    hub.send(new byte[]{(byte) (0x90 | ch), (byte) nn, (byte) vel});
                    final int key = (ch << 8) | nn;
                    held.add(key);
                    ui.postDelayed(() -> {
                        if (held.remove(key)) {
                            hub.send(new byte[]{(byte) (0x80 | ch), (byte) nn, 0});
                        }
                    }, 120);
                } else if (m.above && raw < m.threshold * 0.6f) {
                    m.above = false;
                }
                continue;
            }

            if (muted) continue;
            float val = m.outMin + m.filtered * (m.outMax - m.outMin);
            val = Math.max(0f, Math.min(127f, val));
            if (m.type == Mapping.CC) {
                int iv = Math.round(val);
                if (iv != m.lastSent) {
                    m.lastSent = iv;
                    hub.send(new byte[]{(byte) (0xB0 | m.channel), (byte) m.number, (byte) iv});
                }
            } else {
                int pb = Math.round(val / 127f * 16383f);
                if (pb != m.lastSent) {
                    m.lastSent = pb;
                    hub.send(new byte[]{(byte) (0xE0 | m.channel), (byte) (pb & 0x7F),
                            (byte) ((pb >> 7) & 0x7F)});
                }
            }
        }
    }

    /** All notes off + all sound off on every channel, for stuck notes. */
    private void panic() {
        releaseHeldNotes();
        for (int ch = 0; ch < 16; ch++) {
            hub.send(new byte[]{(byte) (0xB0 | ch), 123, 0});
            hub.send(new byte[]{(byte) (0xB0 | ch), 120, 0});
        }
        Toast.makeText(this, "Notas cortadas", Toast.LENGTH_SHORT).show();
    }

    private void setSolo(Mapping m) {
        solo = (solo == m) ? null : m;
        for (Mapping x : maps) x.lastSent = -1; // re-send current values
        rebuild();
        updateStatus();
    }

    private void releaseHeldNotes() {
        melody.stop(hub);
        for (int key : new ArrayList<>(held)) {
            hub.send(new byte[]{(byte) (0x80 | (key >> 8)), (byte) (key & 0xFF), 0});
        }
        held.clear();
    }

    private void updateBars() {
        for (int i = 0; i < bars.size(); i++) {
            Mapping m = barMaps.get(i);
            float shown = m.type == Mapping.NOTE ? m.lastRaw : m.filtered;
            bars.get(i).setProgress(Math.round(clamp01(shown) * 1000f));
            barLabels.get(i).setText(liveText(m));
        }
        if (noteView != null) {
            if (melody.enabled) {
                boolean snd = melody.isSounding();
                int note = snd ? melody.shown : melody.noteForDegree(melody.curDeg);
                noteView.setText((snd ? "♪ " : "· ") + Melody.noteLabel(note));
                noteView.setTextColor(snd ? 0xFF7CE0B0 : 0xFF8A8AA0);
                ladder.set(melody.degrees(), melody.curDeg, snd);
            } else {
                noteView.setText("Notas apagadas");
                noteView.setTextColor(0xFF8A8AA0);
            }
            if (camBox.getVisibility() == View.VISIBLE) {
                int ps = melody.enabled ? melody.pitchSrc : -1;
                if (ps == 9) {
                    camView.setGuides(melody.degrees(), true, melody.curDeg, 0f, 1f, false);
                } else if (ps == 10) {
                    camView.setGuides(melody.degrees(), false, melody.curDeg, 0f, 1f, false);
                } else if (ps == SensorEngine.HAND_X) {
                    camView.setGuides(melody.degrees(), true, melody.curDeg, 0.1f, 0.9f, false);
                } else if (ps == SensorEngine.HAND_Y) {
                    camView.setGuides(melody.degrees(), false, melody.curDeg, 0.1f, 0.9f, true);
                } else {
                    camView.setGuides(0, true, -1, 0f, 1f, false);
                }
                camView.invalidate();
            }
        }
        for (int i = 0; i < playBars.size(); i++) {
            Mapping m = playMaps.get(i);
            float shown = m.type == Mapping.NOTE ? m.lastRaw : m.filtered;
            playBars.get(i).setProgress(Math.round(clamp01(shown) * 1000f));
            playLabels.get(i).setText(friendlyLive(m));
        }
    }

    private String liveText(Mapping m) {
        String ch = "ch " + (m.channel + 1);
        if (!m.enabled) return "off";
        String muted = (solo != null && solo != m) ? "  (muted by solo)" : "";
        if (m.type == Mapping.NOTE) {
            return "Note " + noteName(m.number) + " · " + ch + (m.above ? "  ● HIT" : "") + muted;
        }
        int v = Math.max(0, m.lastSent);
        if (m.type == Mapping.CC) return "CC " + m.number + " = " + v + " · " + ch + muted;
        return "Pitch bend = " + (v - 8192) + " · " + ch + muted;
    }

    // ------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(12);
        v.setTextColor(0xFF8A8AA0);
        v.setLetterSpacing(0.1f);
        v.setPadding(0, dp(18), 0, dp(6));
        return v;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF121216);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(22), dp(14), dp(40));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("PhoneMIDI");
        title.setTextSize(26);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title);

        connLine = new TextView(this);
        connLine.setTextSize(15);
        connLine.setPadding(0, dp(6), 0, dp(8));
        root.addView(connLine);

        LinearLayout rowA = new LinearLayout(this);
        Button conn = new Button(this);
        conn.setText("Conectar FM-1 / USB");
        conn.setOnClickListener(v -> showConnect());
        Button test = new Button(this);
        test.setText("♪ Probar nota");
        test.setOnClickListener(v -> testNote());
        rowA.addView(conn, weight());
        rowA.addView(test, weight());
        root.addView(rowA);

        // ---- scenes
        sceneBtns.clear();
        LinearLayout sr = null;
        for (int i = 0; i < SCENES.length; i++) {
            if (i == 0) root.addView(label("ESCENAS CON NOTAS · el teléfono toca la melodía"));
            if (i == FIRST_EXPRESSIVE) root.addView(label("ESCENAS DE EXPRESIÓN · el sintetizador toca, tú lo moldeas"));
            if (i % 3 == 0) {
                sr = new LinearLayout(this);
                root.addView(sr);
            }
            final int idx = i;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setOnClickListener(v -> applyScene(idx));
            sceneBtns.add(b);
            sr.addView(b, weight());
        }
        sceneHelp = new TextView(this);
        sceneHelp.setTextSize(13);
        sceneHelp.setTextColor(0xFFB8B8C8);
        sceneHelp.setPadding(dp(4), dp(6), dp(4), 0);
        root.addView(sceneHelp);

        // ---- melody card
        root.addView(label("MELODÍA"));
        LinearLayout mbox = new LinearLayout(this);
        mbox.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable mbg = new android.graphics.drawable.GradientDrawable();
        mbg.setColor(0xFF1E1E26);
        mbg.setCornerRadius(dp(12));
        mbox.setBackground(mbg);
        mbox.setPadding(dp(12), dp(8), dp(12), dp(12));
        LinearLayout mhead = new LinearLayout(this);
        mhead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        melodyOn = new CheckBox(this);
        melodyOn.setText("Tocar notas");
        melodyOn.setTextColor(0xFFFFFFFF);
        melodyOn.setOnCheckedChangeListener((b, c) -> {
            if (c == melody.enabled) return;
            melody.enabled = c;
            if (!c) melody.stop(hub);
            changed();
            updateStatus();
        });
        Button mset = new Button(this);
        mset.setText("Ajustes ✎");
        mset.setAllCaps(false);
        mset.setOnClickListener(v -> showMelodySettings());
        mhead.addView(melodyOn, weight());
        mhead.addView(mset);
        mbox.addView(mhead);
        noteView = new TextView(this);
        noteView.setTextSize(34);
        noteView.setTextColor(0xFFFFFFFF);
        noteView.setPadding(0, dp(4), 0, dp(4));
        mbox.addView(noteView);
        ladder = new LadderView(this);
        mbox.addView(ladder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        padView = new TextView(this);
        padView.setText("MANTÉN PULSADO PARA TOCAR");
        padView.setTextSize(16);
        padView.setTextColor(0xFFFFFFFF);
        padView.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable pbg = new android.graphics.drawable.GradientDrawable();
        pbg.setColor(0xFF2D5A47);
        pbg.setCornerRadius(dp(12));
        padView.setBackground(pbg);
        padView.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN) {
                melody.touch = true;
            } else if (act == android.view.MotionEvent.ACTION_UP
                    || act == android.view.MotionEvent.ACTION_CANCEL) {
                melody.touch = false;
            }
            return true;
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        plp.topMargin = dp(10);
        mbox.addView(padView, plp);
        root.addView(mbox);

        // ---- camera view
        camBox = new LinearLayout(this);
        camBox.setOrientation(LinearLayout.VERTICAL);
        camBox.addView(label("CÁMARA · lo que ve y sigue la app"));
        camView = new CamView(this, eng);
        camBox.addView(camView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        camCaption = new TextView(this);
        camCaption.setTextSize(12);
        camCaption.setTextColor(0xFF8A8AA0);
        camCaption.setPadding(dp(4), dp(4), dp(4), 0);
        camBox.addView(camCaption);
        camBox.setVisibility(View.GONE);
        root.addView(camBox);

        // ---- gestures
        root.addView(label("GESTOS → SONIDO · toca uno para cambiar qué controla"));
        playBox = new LinearLayout(this);
        playBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(playBox);

        LinearLayout rowB = new LinearLayout(this);
        Button addG = new Button(this);
        addG.setText("+ Añadir gesto");
        addG.setOnClickListener(v -> addGesture());
        Button center = new Button(this);
        center.setText("Centrar inclinación");
        center.setOnClickListener(v -> {
            if (eng.calibrate()) {
                prefs().edit().putFloat("calX", eng.calX).putFloat("calY", eng.calY).apply();
                Toast.makeText(this, "Listo: esta posición es el centro", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Activa primero un gesto de inclinación", Toast.LENGTH_SHORT).show();
            }
        });
        center.setOnLongClickListener(v -> {
            eng.calX = 0f;
            eng.calY = 0f;
            prefs().edit().remove("calX").remove("calY").apply();
            Toast.makeText(this, "Centro: teléfono plano sobre la mesa", Toast.LENGTH_SHORT).show();
            return true;
        });
        rowB.addView(addG, weight());
        rowB.addView(center, weight());
        root.addView(rowB);

        TextView tip = new TextView(this);
        tip.setText("Sostén el teléfono como lo vas a tocar y pulsa «Centrar inclinación». "
                + "Mantén pulsado para volver a «plano sobre la mesa».");
        tip.setTextSize(12);
        tip.setTextColor(0xFF8A8AA0);
        tip.setPadding(dp(4), dp(4), dp(4), 0);
        root.addView(tip);

        Button panic = new Button(this);
        panic.setText("Pánico (cortar notas colgadas)");
        panic.setOnClickListener(v -> panic());
        root.addView(panic);

        // ---- advanced
        advToggle = new Button(this);
        advToggle.setAllCaps(false);
        advToggle.setOnClickListener(v -> {
            boolean show = advBox.getVisibility() != View.VISIBLE;
            advBox.setVisibility(show ? View.VISIBLE : View.GONE);
            advToggle.setText(show ? "Ajustes avanzados ▴" : "Ajustes avanzados ▾");
        });
        advToggle.setText("Ajustes avanzados ▾");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(18);
        root.addView(advToggle, alp);

        advBox = new LinearLayout(this);
        advBox.setOrientation(LinearLayout.VERTICAL);
        advBox.setVisibility(View.GONE);
        root.addView(advBox);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFFB8B8C8);
        status.setPadding(0, dp(6), 0, dp(10));
        advBox.addView(status);

        LinearLayout row1 = new LinearLayout(this);
        btBtn = new Button(this);
        btBtn.setAllCaps(false);
        btBtn.setOnClickListener(v -> toggleBle());
        camBtn = new Button(this);
        camBtn.setAllCaps(false);
        camBtn.setOnClickListener(v -> {
            camFront = !camFront;
            changed();
            updateStatus();
        });
        row1.addView(btBtn, weight());
        row1.addView(camBtn, weight());
        advBox.addView(row1);

        TextView btHelp = new TextView(this);
        btHelp.setText("«Visible por Bluetooth» es para Ableton / computadora: el teléfono aparece "
                + "como dispositivo MIDI. Para el FM-1 déjalo apagado y usa «Conectar FM-1 / USB».");
        btHelp.setTextSize(12);
        btHelp.setTextColor(0xFF8A8AA0);
        btHelp.setPadding(dp(4), dp(2), dp(4), dp(6));
        advBox.addView(btHelp);

        passBtn = new Button(this);
        passBtn.setAllCaps(false);
        passBtn.setOnClickListener(v -> {
            boolean on = !prefs().getBoolean("micPass", false);
            prefs().edit().putBoolean("micPass", on).apply();
            if (on) Toast.makeText(this,
                    "El micrófono sale por la salida de audio. Usa audífonos o cable para evitar acople.",
                    Toast.LENGTH_LONG).show();
            applyNeeds();
            updateStatus();
        });
        advBox.addView(passBtn);

        advBox.addView(label("EDITOR DE MAPEOS"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        advBox.addView(listBox);

        Button reset = new Button(this);
        reset.setText("Restaurar escena «Arpa»");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("¿Restaurar?")
                .setMessage("Tus gestos y notas actuales se reemplazan por la escena Arpa.")
                .setPositiveButton("Restaurar", (d, w) -> applyScene(0))
                .setNegativeButton("Cancelar", null)
                .show());
        advBox.addView(reset);

        setContentView(sv);
        rebuild();
    }

    private static int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return 0;
    }

    private TextView dlgLabel(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        v.setTextColor(0xFFB8B8C8);
        v.setPadding(0, dp(12), 0, 0);
        return v;
    }

    private void showMelodySettings() {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(6), dp(18), dp(6));
        sv.addView(box);

        box.addView(dlgLabel("Cómo suenan las notas"));
        box.addView(spinner(Melody.MODE_NAMES, melody.mode, pos -> {
            melody.stop(hub);
            melody.mode = pos;
            applyNeeds();
        }));

        String[] pitchNames = new String[PITCH_SRCS.length];
        for (int i = 0; i < pitchNames.length; i++) pitchNames[i] = GESTURES[PITCH_SRCS[i]];
        box.addView(dlgLabel("Qué elige la nota"));
        box.addView(spinner(pitchNames, indexOf(PITCH_SRCS, melody.pitchSrc), pos -> {
            melody.stop(hub);
            melody.pitchSrc = PITCH_SRCS[pos];
            melody.sens = SensorEngine.GAIN_MODE[melody.pitchSrc] == 1 ? 2f : 1f;
            if (melody.gateSrc >= 0) melody.gateSrc = gateForMoving();
            applyNeeds();
        }));

        box.addView(dlgLabel("Escala"));
        box.addView(spinner(Melody.SCALE_NAMES, melody.scale, pos -> melody.scale = pos));
        box.addView(dlgLabel("Tono (nota base)"));
        box.addView(spinner(Melody.ROOT_NAMES, melody.root, pos -> {
            melody.stop(hub);
            melody.root = pos;
        }));
        box.addView(dlgLabel("Registro"));
        box.addView(spinner(Melody.LOW_NAMES, melody.low, pos -> {
            melody.stop(hub);
            melody.low = pos;
        }));
        slider(box, "Octavas", 1, 4, melody.octaves, n -> "" + n, n -> melody.octaves = n);
        slider(box, "Sensibilidad (cuánto hay que inclinar)", 5, 60, Math.round(melody.sens * 10f),
                p -> String.format("%.1fx", p / 10f), p -> melody.sens = p / 10f);

        box.addView(dlgLabel("Cuándo suena"));
        int gi = melody.gateSrc == Melody.GATE_ALWAYS ? 0 : (melody.gateSrc == Melody.GATE_TOUCH ? 2
                : (melody.gateSrc == SensorEngine.HAND_PINCH ? 3 : 1));
        box.addView(spinner(GATE_NAMES, gi, pos -> {
            melody.stop(hub);
            melody.gateSrc = pos == 0 ? Melody.GATE_ALWAYS
                    : (pos == 2 ? Melody.GATE_TOUCH
                    : (pos == 3 ? SensorEngine.HAND_PINCH : gateForMoving()));
            applyNeeds();
            updateStatus();
        }));

        CheckBox dyn = new CheckBox(this);
        dyn.setText("Más fuerte si te mueves más fuerte");
        dyn.setTextColor(0xFFFFFFFF);
        dyn.setChecked(melody.dynVel);
        dyn.setOnCheckedChangeListener((b, c) -> {
            melody.dynVel = c;
            applyNeeds();
        });
        box.addView(dyn);

        box.addView(dlgLabel("Modo Ritmo: figura"));
        box.addView(spinner(DIV_NAMES, melody.div, pos -> melody.div = pos));
        slider(box, "Modo Ritmo: tempo", 40, 220, melody.bpm, n -> n + " bpm", n -> melody.bpm = n);

        String[] chs = new String[16];
        for (int i = 0; i < 16; i++) chs[i] = "Canal " + (i + 1);
        box.addView(dlgLabel("Canal MIDI de las notas"));
        box.addView(spinner(chs, melody.channel, pos -> {
            melody.stop(hub);
            melody.channel = pos;
        }));

        new AlertDialog.Builder(this)
                .setTitle("Ajustes de melodía")
                .setView(sv)
                .setPositiveButton("Listo", null)
                .setOnDismissListener(d -> {
                    saveMaps();
                    applyNeeds();
                    updateStatus();
                })
                .show();
    }

    /** Sensor used for "only while moving", depending on what picks the note. */
    private int gateForMoving() {
        if (SensorEngine.kind(melody.pitchSrc) == 3) return SensorEngine.HAND_SEEN;
        if (melody.pitchSrc >= 9 && melody.pitchSrc <= 10) return 7;
        if (melody.pitchSrc >= 11) return 11;
        return 5;
    }

    private void testNote() {
        if (hub.outs.isEmpty() && !hub.ble.running) {
            Toast.makeText(this, "Primero conecta el FM-1 (botón «Conectar»).", Toast.LENGTH_SHORT).show();
        }
        hub.send(new byte[]{(byte) 0x90, 60, 100});
        ui.postDelayed(() -> hub.send(new byte[]{(byte) 0x80, 60, 0}), 500);
    }

    private String destName(Mapping m) {
        if (m.type == Mapping.PB) return DEST_NAMES[0];
        if (m.type == Mapping.NOTE) return "Nota " + noteName(m.number);
        for (int i = 0; i < DEST_NUM.length; i++) {
            if (DEST_TYPE[i] == Mapping.CC && DEST_NUM[i] == m.number) return DEST_NAMES[i];
        }
        return "CC " + m.number;
    }

    private void chooseDest(final Mapping m) {
        new AlertDialog.Builder(this)
                .setTitle(GESTURES[m.source] + " controla…")
                .setItems(DEST_NAMES, (d, i) -> {
                    if (i == DEST_NAMES.length - 1) {
                        advBox.setVisibility(View.VISIBLE);
                        advToggle.setText("Ajustes avanzados ▴");
                        Toast.makeText(this, "Edita el número de CC en el editor de mapeos.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    releaseHeldNotes();
                    m.type = DEST_TYPE[i];
                    m.number = DEST_NUM[i];
                    m.lastSent = -1;
                    changed();
                    rebuild();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void addGesture() {
        new AlertDialog.Builder(this)
                .setTitle("Elige un gesto")
                .setItems(GESTURES, (d, src) -> {
                    float sens = SensorEngine.GAIN_MODE[src] == 2 ? 1f
                            : (SensorEngine.GAIN_MODE[src] == 0 ? 2f : 1.2f);
                    Mapping m = mk(src, Mapping.CC, 74, sens, 0.3f);
                    maps.add(m);
                    changed();
                    rebuild();
                    chooseDest(m);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private View buildPlayRow(final Mapping m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1E1E26);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        CheckBox cb = new CheckBox(this);
        cb.setChecked(m.enabled);
        cb.setOnCheckedChangeListener((b, c) -> {
            m.enabled = c;
            m.lastSent = -1;
            changed();
        });
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setOnClickListener(v -> chooseDest(m));
        TextView g = new TextView(this);
        g.setText(GESTURES[m.source]);
        g.setTextSize(16);
        g.setTextColor(0xFFFFFFFF);
        TextView d = new TextView(this);
        d.setText("→ " + destName(m) + "  ✎");
        d.setTextSize(15);
        d.setTextColor(0xFF7CE0B0);
        txt.addView(g);
        txt.addView(d);
        Button del = new Button(this);
        del.setText("✕");
        del.setMinWidth(0);
        del.setMinimumWidth(0);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("¿Quitar este gesto?")
                .setMessage(GESTURES[m.source] + " → " + destName(m))
                .setPositiveButton("Quitar", (dd, w) -> {
                    releaseHeldNotes();
                    maps.remove(m);
                    saveMaps();
                    rebuild();
                    applyNeeds();
                    updateStatus();
                })
                .setNegativeButton("Cancelar", null)
                .show());
        top.addView(cb);
        top.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(del);
        card.addView(top);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        blp.topMargin = dp(10);
        card.addView(bar, blp);

        TextView live = new TextView(this);
        live.setTextSize(12);
        live.setTextColor(0xFF8FD3A8);
        live.setPadding(0, dp(4), 0, 0);
        live.setText(friendlyLive(m));
        card.addView(live);

        if (m.type != Mapping.NOTE) {
            LinearLayout sr = new LinearLayout(this);
            int near = 0;
            double best = 1e9;
            for (int i = 0; i < SENS_LEVELS.length; i++) {
                double diff = Math.abs(Math.log(m.sens / SENS_LEVELS[i]));
                if (diff < best) { best = diff; near = i; }
            }
            for (int i = 0; i < SENS_LEVELS.length; i++) {
                final float lv = SENS_LEVELS[i];
                Button sb = new Button(this);
                sb.setAllCaps(false);
                sb.setText(SENS_NAMES[i]);
                sb.setTextColor(i == near ? 0xFF7CE0B0 : 0xFFB8B8C8);
                sb.setOnClickListener(v -> {
                    m.sens = lv;
                    saveMaps();
                    rebuild();
                });
                sr.addView(sb, weight());
            }
            card.addView(sr);
        }

        playMaps.add(m);
        playBars.add(bar);
        playLabels.add(live);
        return card;
    }

    private String friendlyLive(Mapping m) {
        if (!m.enabled) return "apagado";
        if (m.type == Mapping.NOTE) return m.above ? "● ¡toque!" : "esperando…";
        int v = Math.max(0, m.lastSent);
        if (m.type == Mapping.PB) {
            return Math.round((v - 8192) / 81.92f) + "%";
        }
        return Math.round(v / 1.27f) + "%";
    }

    private void rebuild() {
        listBox.removeAllViews();
        playBox.removeAllViews();
        bars.clear();
        barMaps.clear();
        barLabels.clear();
        playMaps.clear();
        playBars.clear();
        playLabels.clear();
        for (Mapping m : maps) {
            playBox.addView(buildPlayRow(m));
            listBox.addView(buildRow(m));
        }
        if (maps.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("Sin gestos. Elige una escena o pulsa «+ Añadir gesto».");
            e.setTextColor(0xFFB8B8C8);
            playBox.addView(e);
        }
    }

    private Spinner spinner(String[] items, int sel, final IntConsumer cb) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(sel, false);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                cb.accept(pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        return sp;
    }

    private void slider(LinearLayout parent, final String label, final int min, int max, int val,
                        final IntFunction<String> fmt, final IntConsumer cb) {
        final TextView t = new TextView(this);
        t.setTextSize(12);
        t.setTextColor(0xFFB8B8C8);
        t.setText(label + ": " + fmt.apply(val));
        t.setPadding(0, dp(6), 0, 0);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(val - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                t.setText(label + ": " + fmt.apply(v));
                if (fromUser) cb.accept(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                saveMaps();
            }
        });
        parent.addView(t);
        parent.addView(sb);
    }

    private static String noteName(int n) {
        return NOTE_NAMES[n % 12] + (n / 12 - 2) + " (" + n + ")";
    }

    private View buildRow(final Mapping m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF1E1E26);
        bg.setCornerRadius(dp(10));
        if (solo == m) bg.setStroke(dp(2), 0xFFFFB020);
        card.setBackground(bg);
        card.setPadding(dp(12), dp(8), dp(12), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        card.setLayoutParams(clp);

        // row 1: enabled + source + delete
        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(android.view.Gravity.CENTER_VERTICAL);
        CheckBox cb = new CheckBox(this);
        cb.setChecked(m.enabled);
        cb.setOnCheckedChangeListener((b, c) -> {
            m.enabled = c;
            m.lastSent = -1;
            changed();
        });
        Spinner src = spinner(GESTURES, m.source, pos -> {
            if (pos == m.source) return;
            m.source = pos;
            m.lastSent = -1;
            changed();
        });
        Button del = new Button(this);
        del.setText("✕");
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("¿Quitar este mapeo?")
                .setMessage(GESTURES[m.source])
                .setPositiveButton("Quitar", (d, w) -> {
                    if (solo == m) solo = null;
                    maps.remove(m);
                    saveMaps();
                    rebuild();
                    applyNeeds();
                    updateStatus();
                })
                .setNegativeButton("Cancelar", null)
                .show());
        Button soloBtn = new Button(this);
        soloBtn.setText(solo == m ? "SOLO" : "Solo");
        if (solo == m) soloBtn.setTextColor(0xFFFFB020);
        soloBtn.setOnClickListener(v -> setSolo(m));
        for (Button bt : new Button[]{soloBtn, del}) {
            bt.setMinWidth(0);
            bt.setMinimumWidth(0);
            bt.setPadding(dp(10), bt.getPaddingTop(), dp(10), bt.getPaddingBottom());
        }
        r1.addView(cb);
        r1.addView(src, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r1.addView(soloBtn);
        r1.addView(del);
        card.addView(r1);

        // row 2: type + channel
        LinearLayout r2 = new LinearLayout(this);
        Spinner tp = spinner(TYPE_NAMES, m.type, pos -> {
            if (pos == m.type) return;
            m.type = pos;
            m.lastSent = -1;
            saveMaps();
            rebuild();
        });
        String[] chs = new String[16];
        for (int i = 0; i < 16; i++) chs[i] = "Ch " + (i + 1);
        Spinner chSp = spinner(chs, m.channel, pos -> {
            if (pos == m.channel) return;
            m.channel = pos;
            m.lastSent = -1;
            saveMaps();
        });
        r2.addView(tp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        r2.addView(chSp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(r2);

        if (m.type == Mapping.CC) {
            slider(card, "Número de CC", 0, 127, m.number, n -> "" + n, n -> {
                m.number = n;
                m.lastSent = -1;
            });
        } else if (m.type == Mapping.NOTE) {
            slider(card, "Nota", 0, 127, m.number, MainActivity::noteName, n -> m.number = n);
        }

        int sensP = Math.round(100f * (float) (Math.log(m.sens / 0.25f) / Math.log(32.0)));
        slider(card, "Sensibilidad", 0, 100, Math.max(0, Math.min(100, sensP)),
                p -> String.format("%.1fx", 0.25f * Math.pow(32.0, p / 100.0)),
                p -> m.sens = (float) (0.25 * Math.pow(32.0, p / 100.0)));

        if (m.type != Mapping.NOTE) {
            slider(card, "Suavizado", 0, 95, Math.round(m.smooth * 100f), p -> p + "%",
                    p -> m.smooth = p / 100f);
            slider(card, "Salida mínima", 0, 127, Math.round(m.outMin), p -> "" + p,
                    p -> m.outMin = p);
            slider(card, "Salida máxima", 0, 127, Math.round(m.outMax), p -> "" + p,
                    p -> m.outMax = p);
        } else {
            slider(card, "Umbral de disparo", 5, 95, Math.round(m.threshold * 100f), p -> p + "%",
                    p -> m.threshold = p / 100f);
        }

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        blp.topMargin = dp(12);
        card.addView(bar, blp);
        TextView live = new TextView(this);
        live.setTextSize(12);
        live.setTextColor(0xFF8FD3A8);
        live.setPadding(0, dp(4), 0, 0);
        live.setText(liveText(m));
        card.addView(live);
        bars.add(bar);
        barMaps.add(m);
        barLabels.add(live);
        return card;
    }

    private void toggleBle() {
        if (hub.ble.running) {
            hub.ble.stop();
            prefs().edit().putBoolean("bleOn", false).apply();
        } else if (!hub.ble.start()) {
            Toast.makeText(this, hub.ble.status, Toast.LENGTH_LONG).show();
            askPerms();
        } else {
            prefs().edit().putBoolean("bleOn", true).apply();
        }
        updateStatus();
    }

    private void updateStatus() {
        if (status == null) return;
        if (!hub.outs.isEmpty()) {
            connLine.setText("● Conectado: " + hub.outs.get(0).name);
            connLine.setTextColor(0xFF7CE0B0);
        } else if (hub.ble.running) {
            connLine.setText("◐ Visible como dispositivo MIDI (modo computadora)");
            connLine.setTextColor(0xFFFFB020);
        } else {
            connLine.setText("○ Sin conectar — pulsa «Conectar FM-1 / USB»");
            connLine.setTextColor(0xFFFF8A80);
        }
        int sc = prefs().getInt("scene", 0);
        for (int i = 0; i < sceneBtns.size(); i++) {
            sceneBtns.get(i).setText(SCENES[i]);
            sceneBtns.get(i).setTextColor(i == sc ? 0xFF7CE0B0 : 0xFFFFFFFF);
        }
        sceneHelp.setText(sc >= 0 && sc < SCENE_HELP.length ? SCENE_HELP[sc] : "");
        if (melodyOn.isChecked() != melody.enabled) melodyOn.setChecked(melody.enabled);
        padView.setVisibility(melody.enabled && melody.gateSrc == Melody.GATE_TOUCH
                ? View.VISIBLE : View.GONE);
        ladder.setVisibility(melody.enabled ? View.VISIBLE : View.GONE);
        int ps = melody.enabled ? melody.pitchSrc : -1;
        boolean camPitch = ps == 9 || ps == 10 || ps == SensorEngine.HAND_X || ps == SensorEngine.HAND_Y;
        if (eng.handsActive()) {
            camCaption.setText("Verde = la mano que toca · naranja = la otra mano · círculo lleno = pinza cerrada"
                    + (camPitch ? " · las franjas son las notas" : "")
                    + ". Apoya el teléfono, usa la cámara frontal y buena luz.");
        } else {
            camCaption.setText("Verde = lo que la cámara ve moverse · anillo = tu mano"
                    + (camPitch ? " · las franjas son las notas de la escala" : " · X e Y mueven los gestos de cámara"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Bluetooth MIDI (modo computadora): ").append(hub.ble.status);
        if (hub.outs.isEmpty()) {
            sb.append("\nSalida directa: ninguna");
        } else {
            for (MidiHub.Out o : hub.outs) sb.append("\nSalida directa → ").append(o.name);
        }
        if (solo != null) {
            sb.append("\nSOLO: solo '").append(GESTURES[solo.source]).append("' envía MIDI");
        }
        status.setText(sb.toString());
        btBtn.setText(hub.ble.running ? "Visible por Bluetooth: SÍ" : "Visible por Bluetooth: NO");
        camBtn.setText(camFront ? "Cámara: frontal" : "Cámara: trasera");
        passBtn.setText(prefs().getBoolean("micPass", false)
                ? "Micrófono → salida de audio: SÍ" : "Micrófono → salida de audio: NO");
    }

    // ------------------------------------------------------------ connect dialog

    @SuppressLint("MissingPermission")
    private void showConnect() {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> acts = new ArrayList<>();

        for (final MidiHub.Out o : hub.outs) {
            labels.add("✕ Desconectar " + o.name);
            acts.add(() -> hub.close(o));
        }
        for (final MidiDeviceInfo i : hub.listDevices()) {
            labels.add("USB / sistema: " + MidiHub.nameOf(i));
            acts.add(() -> hub.openInfo(i));
        }
        final int fixed = labels.size();

        final ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        ListView lv = new ListView(this);
        lv.setAdapter(ad);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Elige tu FM-1 o dispositivo (buscando Bluetooth…)")
                .setView(lv)
                .setNegativeButton("Cerrar", null)
                .create();
        lv.setOnItemClickListener((p, v, pos, id) -> {
            acts.get(pos).run();
            dlg.dismiss();
        });

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        final BluetoothLeScanner scanner = (adapter != null && adapter.isEnabled())
                ? adapter.getBluetoothLeScanner() : null;

        final HashSet<String> seen = new HashSet<>();
        final ScanCallback scb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                if (d == null || !seen.add(d.getAddress())) return;
                String n = null;
                try { n = d.getName(); } catch (Exception ignored) { }
                ScanRecord rec = r.getScanRecord();
                boolean midi = false;
                if (rec != null) {
                    List<ParcelUuid> u = rec.getServiceUuids();
                    midi = u != null && u.contains(new ParcelUuid(BlePeripheral.SERVICE));
                    if (n == null) n = rec.getDeviceName();
                }
                if (n == null && !midi) return;
                if (n == null) n = d.getAddress();
                final BluetoothDevice dev = d;
                int idx = midi ? fixed : labels.size();
                labels.add(idx, (midi ? "♪ BLE MIDI: " : "Bluetooth: ") + n);
                acts.add(idx, () -> hub.openBle(dev));
                ad.notifyDataSetChanged();
            }
        };

        boolean canScan = scanner != null && (Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED);
        if (canScan) {
            try {
                scanner.startScan(scb);
            } catch (Exception ignored) {
                canScan = false;
            }
        }
        final boolean scanning = canScan;
        dlg.setOnDismissListener(d -> {
            if (scanning) {
                try { scanner.stopScan(scb); } catch (Exception ignored) { }
            }
        });
        dlg.show();
        if (!scanning) {
            dlg.setTitle("No se puede buscar Bluetooth (falta permiso o está apagado)");
        }
    }
}

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
    private static final String[] TYPE_NAMES = {"Control change (CC)", "Pitch bend", "Note trigger"};

    private SensorEngine eng;
    private MidiHub hub;
    private final ArrayList<Mapping> maps = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final HashSet<Integer> held = new HashSet<>();

    private LinearLayout listBox;
    private TextView status;
    private Button btBtn, camBtn;
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
        maps.clear();
        maps.add(new Mapping(0, Mapping.CC, 21, 1.0f, true));   // tilt left/right
        maps.add(new Mapping(1, Mapping.CC, 22, 1.0f, true));   // tilt top/bottom
        maps.add(new Mapping(5, Mapping.CC, 23, 2.0f, true));   // rotation speed
        Mapping shake = new Mapping(6, Mapping.NOTE, 36, 2.0f, true); // shake -> note C1
        shake.threshold = 0.5f;
        maps.add(shake);
        maps.add(new Mapping(7, Mapping.CC, 24, 1.0f, false));  // camera motion
        maps.add(new Mapping(9, Mapping.CC, 25, 1.0f, false));  // camera X
        maps.add(new Mapping(10, Mapping.CC, 26, 1.0f, false)); // camera Y
        maps.add(new Mapping(11, Mapping.CC, 27, 1.5f, false)); // mic level
        maps.add(new Mapping(12, Mapping.CC, 28, 1.0f, false)); // mic brightness
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
        } catch (Exception e) {
            defaults();
        }
    }

    private void saveMaps() {
        JSONArray a = new JSONArray();
        for (Mapping m : maps) a.put(m.toJson());
        prefs().edit().putString("maps", a.toString()).putBoolean("camFront", camFront).apply();
    }

    private void changed() {
        saveMaps();
        applyNeeds();
    }

    private void applyNeeds() {
        boolean motion = false, cam = false, mic = false;
        for (Mapping m : maps) {
            if (!m.enabled) continue;
            if (m.source <= 6) motion = true;
            else if (m.source <= 10) cam = true;
            else mic = true;
        }
        eng.setMotion(motion);
        eng.setCamera(cam, camFront);
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
        Toast.makeText(this, "All notes off sent", Toast.LENGTH_SHORT).show();
    }

    private void setSolo(Mapping m) {
        solo = (solo == m) ? null : m;
        for (Mapping x : maps) x.lastSent = -1; // re-send current values
        rebuild();
        updateStatus();
    }

    private void releaseHeldNotes() {
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

        status = new TextView(this);
        status.setTextSize(13);
        status.setTextColor(0xFFB8B8C8);
        status.setPadding(0, dp(6), 0, dp(10));
        root.addView(status);

        LinearLayout row1 = new LinearLayout(this);
        btBtn = new Button(this);
        btBtn.setOnClickListener(v -> toggleBle());
        Button conn = new Button(this);
        conn.setText("Connect synth / USB");
        conn.setOnClickListener(v -> showConnect());
        row1.addView(btBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(conn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        camBtn = new Button(this);
        camBtn.setOnClickListener(v -> {
            camFront = !camFront;
            changed();
            updateStatus();
        });
        Button center = new Button(this);
        center.setText("Center tilt here");
        center.setOnClickListener(v -> {
            if (eng.calibrate()) {
                prefs().edit().putFloat("calX", eng.calX).putFloat("calY", eng.calY).apply();
                Toast.makeText(this, "Tilt centered on current position", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Enable a tilt/motion mapping first", Toast.LENGTH_SHORT).show();
            }
        });
        center.setOnLongClickListener(v -> {
            eng.calX = 0f;
            eng.calY = 0f;
            prefs().edit().remove("calX").remove("calY").apply();
            Toast.makeText(this, "Tilt center reset to flat", Toast.LENGTH_SHORT).show();
            return true;
        });
        row2.addView(camBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(center, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        Button add = new Button(this);
        add.setText("+ Add mapping");
        add.setOnClickListener(v -> {
            int used = 29;
            for (Mapping m : maps) if (m.type == Mapping.CC) used = Math.max(used, m.number);
            maps.add(new Mapping(0, Mapping.CC, Math.min(127, used + 1), 1.0f, true));
            saveMaps();
            rebuild();
            applyNeeds();
        });
        Button panic = new Button(this);
        panic.setText("Panic (notes off)");
        panic.setOnClickListener(v -> panic());
        row3.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row3.addView(panic, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row3);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(10), 0, 0);
        root.addView(listBox);

        Button reset = new Button(this);
        reset.setText("Reset all mappings to defaults");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Reset all mappings?")
                .setMessage("Your current mappings will be replaced by the defaults.")
                .setPositiveButton("Reset", (d, w) -> {
                    solo = null;
                    defaults();
                    saveMaps();
                    rebuild();
                    applyNeeds();
                    updateStatus();
                })
                .setNegativeButton("Cancel", null)
                .show());
        root.addView(reset);

        setContentView(sv);
        rebuild();
    }

    private void rebuild() {
        listBox.removeAllViews();
        bars.clear();
        barMaps.clear();
        barLabels.clear();
        for (Mapping m : maps) listBox.addView(buildRow(m));
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
        Spinner src = spinner(SensorEngine.NAMES, m.source, pos -> {
            if (pos == m.source) return;
            m.source = pos;
            m.lastSent = -1;
            changed();
        });
        Button del = new Button(this);
        del.setText("✕");
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete this mapping?")
                .setMessage(SensorEngine.NAMES[m.source])
                .setPositiveButton("Delete", (d, w) -> {
                    if (solo == m) solo = null;
                    maps.remove(m);
                    saveMaps();
                    rebuild();
                    applyNeeds();
                    updateStatus();
                })
                .setNegativeButton("Cancel", null)
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
            slider(card, "CC number", 0, 127, m.number, n -> "" + n, n -> {
                m.number = n;
                m.lastSent = -1;
            });
        } else if (m.type == Mapping.NOTE) {
            slider(card, "Note", 0, 127, m.number, MainActivity::noteName, n -> m.number = n);
        }

        int sensP = Math.round(100f * (float) (Math.log(m.sens / 0.25f) / Math.log(32.0)));
        slider(card, "Sensitivity", 0, 100, Math.max(0, Math.min(100, sensP)),
                p -> String.format("%.1fx", 0.25f * Math.pow(32.0, p / 100.0)),
                p -> m.sens = (float) (0.25 * Math.pow(32.0, p / 100.0)));

        if (m.type != Mapping.NOTE) {
            slider(card, "Smoothing", 0, 95, Math.round(m.smooth * 100f), p -> p + "%",
                    p -> m.smooth = p / 100f);
            slider(card, "Output min", 0, 127, Math.round(m.outMin), p -> "" + p,
                    p -> m.outMin = p);
            slider(card, "Output max", 0, 127, Math.round(m.outMax), p -> "" + p,
                    p -> m.outMax = p);
        } else {
            slider(card, "Trigger threshold", 5, 95, Math.round(m.threshold * 100f), p -> p + "%",
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
        StringBuilder sb = new StringBuilder();
        sb.append("Bluetooth MIDI (for Ableton / DAW): ").append(hub.ble.status);
        if (hub.outs.isEmpty()) {
            sb.append("\nDirect output: none (use 'Connect synth / USB')");
        } else {
            for (MidiHub.Out o : hub.outs) sb.append("\nDirect output → ").append(o.name);
        }
        if (solo != null) {
            sb.append("\nSOLO: only '").append(SensorEngine.NAMES[solo.source])
                    .append("' is sending — use this for MIDI learn, tap Solo again to release");
        }
        status.setText(sb.toString());
        btBtn.setText(hub.ble.running ? "Bluetooth: ON" : "Bluetooth: OFF");
        camBtn.setText(camFront ? "Camera: front" : "Camera: back");
    }

    // ------------------------------------------------------------ connect dialog

    @SuppressLint("MissingPermission")
    private void showConnect() {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> acts = new ArrayList<>();

        for (final MidiHub.Out o : hub.outs) {
            labels.add("✕ Disconnect " + o.name);
            acts.add(() -> hub.close(o));
        }
        for (final MidiDeviceInfo i : hub.listDevices()) {
            labels.add("USB / system: " + MidiHub.nameOf(i));
            acts.add(() -> hub.openInfo(i));
        }
        final int fixed = labels.size();

        final ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        ListView lv = new ListView(this);
        lv.setAdapter(ad);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Tap a device (scanning Bluetooth…)")
                .setView(lv)
                .setNegativeButton("Close", null)
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
            dlg.setTitle("Bluetooth scan unavailable (permission/Bluetooth off)");
        }
    }
}

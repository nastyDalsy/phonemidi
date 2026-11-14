package com.dalsy.phonemidi;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Turns a sensor value into musical notes: the position is quantized to a scale, so
 * whatever you do lands on a note that fits. All calls come from the UI thread.
 */
public class Melody {

    public static final String[] SCALE_NAMES = {
            "Pentatónica menor", "Pentatónica mayor", "Mayor", "Menor natural",
            "Dórica", "Frigia", "Menor armónica", "Blues", "Tonos enteros"};
    public static final int[][] SCALES = {
            {0, 3, 5, 7, 10},
            {0, 2, 4, 7, 9},
            {0, 2, 4, 5, 7, 9, 11},
            {0, 2, 3, 5, 7, 8, 10},
            {0, 2, 3, 5, 7, 9, 10},
            {0, 1, 3, 5, 7, 8, 10},
            {0, 2, 3, 5, 7, 8, 11},
            {0, 3, 5, 6, 7, 10},
            {0, 2, 4, 6, 8, 10}};

    public static final int GLIDE = 0, STEP = 1, RHYTHM = 2, SHAKE = 3, ARP = 4;
    public static final String[] MODE_NAMES = {
            "Fluido (una nota sigue tu mano)",
            "Escalera (suena cada escalón)",
            "Ritmo (reloj, tú eliges la nota)",
            "Golpes (sacude para tocar)",
            "Arpegio (más movimiento = más rápido)"};

    public static final String[] ROOT_NAMES =
            {"Do", "Do#", "Re", "Re#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"};
    public static final int[] LOW_NOTES = {36, 48, 60};
    public static final String[] LOW_NAMES = {"Grave", "Medio", "Agudo"};

    /** gate source codes besides a sensor index */
    public static final int GATE_ALWAYS = -1, GATE_TOUCH = -2;

    // ---- settings (saved)
    public boolean enabled = false;
    public int mode = STEP;
    public int pitchSrc = 0;
    public float sens = 2f;
    public int scale = 0;
    public int root = 0;        // 0..11
    public int low = 1;         // index in LOW_NOTES
    public int octaves = 2;     // 1..4
    public int gateSrc = GATE_ALWAYS;
    public boolean dynVel = true;
    public int bpm = 110;
    public int div = 1;         // 0 = 1/4, 1 = 1/8, 2 = 1/16
    public int channel = 0;

    // ---- runtime
    /** True while a finger is on the on-screen pad. */
    public volatile boolean touch = false;
    public volatile int curDeg = 0;
    public volatile int shown = -1;
    private int prevDeg = -1;
    private int curNote = -1;
    private long gateUntil = 0, lastOn = 0, nextTick = 0;
    private boolean above = false;
    private int arpStep = 0;
    private final ArrayList<long[]> pending = new ArrayList<>(); // {note, offTime}

    public int degrees() {
        return SCALES[scale].length * octaves;
    }

    public int noteForDegree(int deg) {
        int[] sc = SCALES[scale];
        int n = sc.length;
        int d = Math.max(0, deg);
        int note = LOW_NOTES[low] + root + (d / n) * 12 + sc[d % n];
        return Math.max(0, Math.min(127, note));
    }

    public static String noteLabel(int n) {
        if (n < 0) return "–";
        return ROOT_NAMES[n % 12] + (n / 12 - 1);
    }

    public boolean isSounding() {
        return curNote >= 0 || !pending.isEmpty();
    }

    // ------------------------------------------------------------ playing

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    private float position(float[] v) {
        float raw = v[pitchSrc];
        switch (SensorEngine.GAIN_MODE[pitchSrc]) {
            case 0: raw = raw * sens; break;
            case 1: raw = 0.5f + (raw - 0.5f) * sens; break;
            default: break;
        }
        return clamp01(raw);
    }

    private int velocity(float[] v, float hit) {
        if (!dynVel) return 100;
        float d = clamp01(Math.max(v[5] * 2.5f, hit));
        return 50 + Math.round(d * 77f);
    }

    private void on(int note, int vel, MidiHub hub, long now) {
        hub.send(new byte[]{(byte) (0x90 | channel), (byte) note, (byte) vel});
        shown = note;
        lastOn = now;
    }

    private void off(int note, MidiHub hub) {
        hub.send(new byte[]{(byte) (0x80 | channel), (byte) note, 0});
    }

    private void playShort(int note, int vel, long durMs, MidiHub hub, long now) {
        on(note, vel, hub, now);
        pending.add(new long[]{note, now + Math.max(40, durMs)});
    }

    private void flushOffs(long now, MidiHub hub) {
        for (int i = pending.size() - 1; i >= 0; i--) {
            long[] p = pending.get(i);
            if (now >= p[1]) {
                off((int) p[0], hub);
                pending.remove(i);
            }
        }
    }

    /** Silence everything this player started. */
    public void stop(MidiHub hub) {
        if (curNote >= 0) off(curNote, hub);
        for (long[] p : pending) off((int) p[0], hub);
        pending.clear();
        curNote = -1;
        prevDeg = -1;
        nextTick = 0;
        above = false;
    }

    public void update(float[] v, long now, MidiHub hub) {
        flushOffs(now, hub);
        if (!enabled) {
            if (curNote >= 0) stop(hub);
            return;
        }

        int n = degrees();
        float f = position(v) * n;
        int deg = curDeg;
        if (prevDeg < 0 || deg >= n) {
            deg = Math.min(n - 1, (int) f);
        } else if (f < deg - 0.12f || f > deg + 1.12f) { // hysteresis: no flutter on a boundary
            deg = Math.max(0, Math.min(n - 1, (int) f));
        }
        curDeg = deg;
        boolean changed = prevDeg >= 0 && deg != prevDeg;

        boolean gate;
        if (gateSrc == GATE_ALWAYS) {
            gate = true;
        } else if (gateSrc == GATE_TOUCH) {
            gate = touch;
        } else {
            if (v[gateSrc] > 0.06f) gateUntil = now + 220;
            gate = now < gateUntil;
        }

        switch (mode) {
            case GLIDE: {
                int want = gate ? noteForDegree(deg) : -1;
                if (want != curNote) {
                    int old = curNote;
                    if (want >= 0) on(want, velocity(v, 0f), hub, now);
                    if (old >= 0) off(old, hub);
                    curNote = want;
                }
                break;
            }
            case STEP: {
                if (changed && gate && now - lastOn >= 50) {
                    playShort(noteForDegree(deg), velocity(v, 0f), 220, hub, now);
                }
                break;
            }
            case RHYTHM: {
                long interval = (long) (60000.0 / Math.max(40, bpm) / (div == 0 ? 1 : (div == 1 ? 2 : 4)));
                if (nextTick == 0 || now - nextTick > interval * 4) nextTick = now;
                if (now >= nextTick) {
                    if (gate) playShort(noteForDegree(deg), velocity(v, 0f), (long) (interval * 0.8), hub, now);
                    nextTick += interval;
                    if (nextTick < now) nextTick = now + interval;
                }
                break;
            }
            case SHAKE: {
                float s = clamp01(v[6] * 2f);
                if (!above && s >= 0.5f && now - lastOn >= 120) {
                    above = true;
                    int vel = dynVel ? 40 + Math.round(s * 87f) : 100;
                    playShort(noteForDegree(deg), vel, 280, hub, now);
                } else if (above && s < 0.3f) {
                    above = false;
                }
                break;
            }
            default: { // ARP
                float sp = clamp01(v[5] * 3f);
                if (sp > 0.05f && gate) {
                    long interval = (long) (450 - 360 * sp);
                    if (now >= nextTick) {
                        int[] pat = {0, 2, 4, 7, 4, 2};
                        int note = noteForDegree(deg + pat[arpStep % pat.length]);
                        arpStep++;
                        playShort(note, velocity(v, 0f), (long) (interval * 0.8), hub, now);
                        nextTick = now + interval;
                    }
                } else {
                    nextTick = 0;
                    arpStep = 0;
                }
                break;
            }
        }
        prevDeg = deg;
    }

    // ------------------------------------------------------------ persistence

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("on", enabled);
            o.put("mode", mode);
            o.put("src", pitchSrc);
            o.put("sens", sens);
            o.put("scale", scale);
            o.put("root", root);
            o.put("low", low);
            o.put("oct", octaves);
            o.put("gate", gateSrc);
            o.put("dyn", dynVel);
            o.put("bpm", bpm);
            o.put("div", div);
            o.put("ch", channel);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private static int ci(int x, int lo, int hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    public void fromJson(JSONObject o) {
        enabled = o.optBoolean("on", false);
        mode = ci(o.optInt("mode", STEP), 0, MODE_NAMES.length - 1);
        pitchSrc = ci(o.optInt("src", 0), 0, SensorEngine.COUNT - 1);
        double s = o.optDouble("sens", 2.0);
        sens = (float) (Double.isNaN(s) ? 2.0 : Math.max(0.5, Math.min(6.0, s)));
        scale = ci(o.optInt("scale", 0), 0, SCALES.length - 1);
        root = ci(o.optInt("root", 0), 0, 11);
        low = ci(o.optInt("low", 1), 0, LOW_NOTES.length - 1);
        octaves = ci(o.optInt("oct", 2), 1, 4);
        gateSrc = ci(o.optInt("gate", GATE_ALWAYS), GATE_TOUCH, SensorEngine.COUNT - 1);
        dynVel = o.optBoolean("dyn", true);
        bpm = ci(o.optInt("bpm", 110), 40, 220);
        div = ci(o.optInt("div", 1), 0, 2);
        channel = ci(o.optInt("ch", 0), 0, 15);
        curDeg = 0;
        prevDeg = -1;
    }
}

package com.dalsy.phonemidi;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * The playing engine, independent of the screen: sensors, MIDI outputs, mappings and the
 * note player. Lives as long as the app process, so it keeps playing while the screen is
 * off or another app is open (as long as {@link PlayService} keeps the process alive).
 */
public class Core {
    private static Core inst;

    public static synchronized Core get(Context c) {
        if (inst == null) inst = new Core(c.getApplicationContext());
        return inst;
    }

    public final Context app;
    public final SensorEngine eng;
    public final MidiHub hub;
    public final ArrayList<Mapping> maps = new ArrayList<>();
    public final Melody melody = new Melody();
    public final HashSet<Integer> held = new HashSet<>();
    /** When set, only this mapping sends MIDI (makes DAW "MIDI learn" easy). Not saved. */
    public Mapping solo = null;
    public boolean loaded = false;

    /** Set by the visible screen to refresh itself when MIDI connections change. */
    public Runnable onHubChanged;
    /** What the camera should do (kept here so it can be re-opened in the background). */
    public boolean needCam = false, camFront = true;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private long lastCamTry = 0;

    private Core(Context c) {
        app = c;
        eng = new SensorEngine(c);
        hub = new MidiHub(c, () -> ui.post(() -> {
            Runnable r = onHubChanged;
            if (r != null) r.run();
        }));
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) return;
        running = true;
        ui.post(tick);
    }

    /** Stop sensors and silence notes. MIDI connections stay open. */
    public void stop() {
        running = false;
        ui.removeCallbacks(tick);
        eng.stopAll();
        releaseHeldNotes();
    }

    public static synchronized void shutdown() {
        if (inst == null) return;
        inst.stop();
        inst.hub.shutdown();
        inst = null;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            process();
            retryCamera();
            ui.postDelayed(this, 15);
        }
    };

    /** Another app (e.g. a video recorder) may take the camera; take it back when it is free. */
    private void retryCamera() {
        if (!needCam || eng.isCameraOn()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastCamTry < 3000) return;
        lastCamTry = now;
        eng.setCamera(true, camFront);
    }

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

    public void releaseHeldNotes() {
        melody.stop(hub);
        for (int key : new ArrayList<>(held)) {
            hub.send(new byte[]{(byte) (0x80 | (key >> 8)), (byte) (key & 0xFF), 0});
        }
        held.clear();
    }
}

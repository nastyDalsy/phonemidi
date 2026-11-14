package com.dalsy.phonemidi;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.SystemClock;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Real hand tracking (MediaPipe Hand Landmarker, 21 points per hand, up to 2 hands).
 * Turns the hand into musical controls: position, height, pinch, open/closed,
 * wrist tilt and number of raised fingers. Writes them into {@link SensorEngine#v}.
 */
public class HandTracker {

    /** Bones of the hand skeleton, as landmark index pairs. */
    public static final int[][] BONES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4},
            {0, 5}, {5, 6}, {6, 7}, {7, 8},
            {5, 9}, {9, 10}, {10, 11}, {11, 12},
            {9, 13}, {13, 14}, {14, 15}, {15, 16},
            {13, 17}, {17, 18}, {18, 19}, {19, 20}, {0, 17}};

    private static final int OUT = 256;

    private final Context ctx;
    private final SensorEngine eng;
    private volatile HandLandmarker lm;
    private volatile boolean busy = false;
    private boolean initStarted = false;
    public volatile String status = "Cargando detector de manos…";

    private Bitmap bmp;
    private int[] bufA, bufB;
    private boolean useA = true;
    private volatile int fw = OUT, fh = OUT;
    private long lastTs = 0, lastSeen = 0, sentAt = 0;

    private final OneEuro fx = new OneEuro(4f, 10f), fy = new OneEuro(4f, 10f);
    private final OneEuro froll = new OneEuro(3f, 0.08f), fopen = new OneEuro(3f, 3f);
    private final OneEuro f2y = new OneEuro(4f, 10f), f2open = new OneEuro(3f, 3f);
    private int fingerCand = -1, fingerCandN = 0;

    public HandTracker(Context c, SensorEngine e) {
        ctx = c.getApplicationContext();
        eng = e;
    }

    public boolean ready() {
        return lm != null;
    }

    public synchronized void start() {
        if (lm != null || initStarted) return;
        initStarted = true;
        new Thread(this::init, "pm-hands-init").start();
    }

    private volatile boolean gpu = false;

    private void init() {
        String err = "";
        for (Delegate d : new Delegate[]{Delegate.GPU, Delegate.CPU}) {
            try {
                lm = create(d);
                gpu = d == Delegate.GPU;
                status = "Muestra la mano a la cámara";
                return;
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
        }
        status = "Detector de manos no disponible: " + err;
        synchronized (this) {
            initStarted = false;
        }
    }

    private HandLandmarker create(Delegate d) {
        BaseOptions base = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .setDelegate(d)
                .build();
        HandLandmarker.HandLandmarkerOptions o = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this::onResult)
                .setErrorListener(e -> {
                    busy = false;
                    status = "Error del detector: " + e.getMessage();
                    if (gpu) fallbackToCpu();
                })
                .build();
        return HandLandmarker.createFromOptions(ctx, o);
    }

    private void fallbackToCpu() {
        gpu = false;
        new Thread(() -> {
            HandLandmarker old = lm;
            lm = null;
            try { if (old != null) old.close(); } catch (Throwable ignored) { }
            try {
                lm = create(Delegate.CPU);
                status = "Muestra la mano a la cámara";
            } catch (Throwable t) {
                status = "Detector de manos no disponible: " + t.getMessage();
            }
        }, "pm-hands-cpu").start();
    }

    // ------------------------------------------------------------ frames (camera thread)

    /** Converts the camera frame to an upright, mirrored colour picture and sends it to the detector. */
    public void frame(Image img, int camRot, boolean front) {
        Image.Plane[] pl = img.getPlanes();
        if (pl.length < 3) return;
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int yRs = pl[0].getRowStride(), yPs = pl[0].getPixelStride();
        int uRs = pl[1].getRowStride(), uPs = pl[1].getPixelStride();
        int vRs = pl[2].getRowStride(), vPs = pl[2].getPixelStride();
        int w = img.getWidth(), h = img.getHeight();
        boolean tall = camRot == 90 || camRot == 270;
        int W, H;
        if (tall) {
            W = OUT;
            H = Math.max(1, Math.round(OUT * (float) w / h));
        } else {
            W = Math.max(1, Math.round(OUT * (float) w / h));
            H = OUT;
        }
        int[] buf = useA ? bufA : bufB;
        if (buf == null || buf.length != W * H) {
            buf = new int[W * H];
            if (useA) bufA = buf; else bufB = buf;
        }
        for (int oy = 0; oy < H; oy++) {
            float y = (oy + 0.5f) / H;
            for (int ox = 0; ox < W; ox++) {
                float x = (ox + 0.5f) / W;
                if (front) x = 1f - x;
                float u, vv;
                switch (camRot) {
                    case 90:  vv = 1f - x; u = y; break;
                    case 180: u = 1f - x;  vv = 1f - y; break;
                    case 270: vv = x;      u = 1f - y; break;
                    default:  u = x;       vv = y; break;
                }
                int sx = Math.min(w - 1, Math.max(0, (int) (u * w)));
                int sy = Math.min(h - 1, Math.max(0, (int) (vv * h)));
                int Y = yb.get(sy * yRs + sx * yPs) & 0xFF;
                int cx = sx >> 1, cy = sy >> 1;
                int d = (ub.get(cy * uRs + cx * uPs) & 0xFF) - 128;
                int e = (vb.get(cy * vRs + cx * vPs) & 0xFF) - 128;
                int r = Y + ((91881 * e) >> 16);
                int g = Y - ((22554 * d + 46802 * e) >> 16);
                int b = Y + ((116130 * d) >> 16);
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                buf[oy * W + ox] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        eng.vizW = W;
        eng.vizH = H;
        eng.vizPix = buf;
        useA = !useA;

        long now = SystemClock.uptimeMillis();
        if (busy && now - sentAt > 1000) busy = false; // never get stuck
        HandLandmarker l = lm;
        if (l == null || busy) return;
        if (bmp == null || bmp.getWidth() != W || bmp.getHeight() != H) {
            bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        }
        bmp.setPixels(buf, 0, W, 0, 0, W, H);
        fw = W;
        fh = H;
        MPImage mp = new BitmapImageBuilder(bmp).build();
        long ts = Math.max(now, lastTs + 1);
        lastTs = ts;
        busy = true;
        sentAt = now;
        try {
            l.detectAsync(mp, ts);
        } catch (Throwable t) {
            busy = false;
            status = "Error del detector: " + t.getMessage();
        }
    }

    // ------------------------------------------------------------ results (detector thread)

    private void onResult(HandLandmarkerResult r, MPImage input) {
        busy = false;
        long now = SystemClock.uptimeMillis();
        List<List<NormalizedLandmark>> hs = r.landmarks();
        if (hs == null || hs.isEmpty()) {
            if (now - lastSeen > 150) lost();
            return;
        }
        int n = Math.min(2, hs.size());
        float[][] pts = new float[n][42];
        for (int i = 0; i < n; i++) {
            List<NormalizedLandmark> hand = hs.get(i);
            for (int k = 0; k < 21 && k < hand.size(); k++) {
                pts[i][2 * k] = hand.get(k).x();
                pts[i][2 * k + 1] = hand.get(k).y();
            }
        }
        // The hand on the right of the picture plays; the other one shapes the sound.
        if (n == 2 && palmX(pts[1]) > palmX(pts[0])) {
            float[] t = pts[0];
            pts[0] = pts[1];
            pts[1] = t;
        }
        double t = now / 1000.0;
        primary(pts[0], t);
        if (n == 2) secondary(pts[1], t);
        eng.v[SensorEngine.HAND_SEEN] = 1f;
        eng.hands = pts;
        lastSeen = now;
        status = n == 2 ? "Dos manos" : "Una mano";
    }

    private void lost() {
        eng.v[SensorEngine.HAND_PINCH] = 0f;
        eng.v[SensorEngine.HAND_SEEN] = 0f;
        eng.hands = new float[0][];
        fingerCand = -1;
        fingerCandN = 0;
        status = "Muestra la mano a la cámara";
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    private static float palmX(float[] p) {
        return (p[0] + p[10] + p[18] + p[26] + p[34]) / 5f;
    }

    private static float palmY(float[] p) {
        return (p[1] + p[11] + p[19] + p[27] + p[35]) / 5f;
    }

    private float dist(float[] p, int a, int b) {
        float dx = (p[2 * a] - p[2 * b]) * fw;
        float dy = (p[2 * a + 1] - p[2 * b + 1]) * fh;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float openness(float[] p, float size) {
        float s = dist(p, 8, 0) + dist(p, 12, 0) + dist(p, 16, 0) + dist(p, 20, 0);
        return s / 4f / size;
    }

    private int fingers(float[] p) {
        int c = 0;
        int[][] f = {{8, 6}, {12, 10}, {16, 14}, {20, 18}};
        for (int[] q : f) {
            if (dist(p, q[0], 0) > dist(p, q[1], 0) * 1.15f) c++;
        }
        float size = Math.max(1f, dist(p, 0, 9));
        if (dist(p, 4, 5) / size > 0.6f && dist(p, 4, 17) > dist(p, 3, 17) * 1.05f) c++;
        return c;
    }

    private void primary(float[] p, double t) {
        float size = Math.max(1f, dist(p, 0, 9));
        float[] v = eng.v;
        v[SensorEngine.HAND_X] = clamp01((fx.filter(palmX(p), t) - 0.1f) / 0.8f);
        v[SensorEngine.HAND_Y] = clamp01(1f - (fy.filter(palmY(p), t) - 0.1f) / 0.8f);

        float pinch = dist(p, 4, 8) / size;
        v[SensorEngine.HAND_PINCH] = clamp01((0.85f - pinch) / 0.55f);

        v[SensorEngine.HAND_OPEN] = clamp01((fopen.filter(openness(p, size), t) - 1.0f) / 0.9f);

        float dx = (p[18] - p[0]) * fw, dy = (p[19] - p[1]) * fh;
        float ang = (float) Math.toDegrees(Math.atan2(dx, -dy));
        float dead = Math.max(0f, Math.abs(ang) - 8f) * Math.signum(ang);
        v[SensorEngine.HAND_ROLL] = clamp01(0.5f + froll.filter(dead, t) / 100f);

        int c = fingers(p);
        if (c == fingerCand) {
            fingerCandN++;
        } else {
            fingerCand = c;
            fingerCandN = 1;
        }
        if (fingerCandN >= 2) v[SensorEngine.HAND_FINGERS] = (c + 0.5f) / 6f;
    }

    private void secondary(float[] p, double t) {
        float size = Math.max(1f, dist(p, 0, 9));
        eng.v[SensorEngine.HAND2_Y] = clamp01(1f - (f2y.filter(palmY(p), t) - 0.1f) / 0.8f);
        eng.v[SensorEngine.HAND2_OPEN] = clamp01((f2open.filter(openness(p, size), t) - 1.0f) / 0.9f);
    }

    /** One Euro filter: smooth when the hand is still, fast when it moves. */
    static class OneEuro {
        private final float minCut, beta;
        private float x = Float.NaN, dx = 0f;
        private double lt;

        OneEuro(float minCut, float beta) {
            this.minCut = minCut;
            this.beta = beta;
        }

        private static float alpha(float cut, double dt) {
            double tau = 1.0 / (2 * Math.PI * cut);
            return (float) (1.0 / (1.0 + tau / dt));
        }

        float filter(float v, double t) {
            if (Float.isNaN(x)) {
                x = v;
                lt = t;
                return v;
            }
            double dt = Math.max(1e-3, t - lt);
            lt = t;
            float d = (float) ((v - x) / dt);
            dx += alpha(1f, dt) * (d - dx);
            float cut = minCut + beta * Math.abs(dx);
            x += alpha(cut, dt) * (v - x);
            return x;
        }
    }
}

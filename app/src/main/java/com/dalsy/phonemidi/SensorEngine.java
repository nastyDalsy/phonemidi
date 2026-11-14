package com.dalsy.phonemidi;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Turns phone motion (accelerometer + gyro), camera movement and microphone
 * into normalized 0..1 values in {@link #v}.
 */
public class SensorEngine implements SensorEventListener {

    public static final String[] NAMES = {
            "Tilt left/right",      // 0
            "Tilt top/bottom",      // 1
            "Twist (gyro Z)",       // 2
            "Rotate X (gyro)",      // 3
            "Rotate Y (gyro)",      // 4
            "Rotation speed",       // 5
            "Shake / acceleration", // 6
            "Camera: motion amount",// 7
            "Camera: brightness",   // 8
            "Camera: motion X",     // 9
            "Camera: motion Y",     // 10
            "Mic: level",           // 11
            "Mic: brightness (hi/lo)" // 12
    };
    public static final int COUNT = NAMES.length;

    /** 0 = value * sens, 1 = centered around 0.5, 2 = no gain */
    public static final int[] GAIN_MODE = {1, 1, 1, 1, 1, 0, 0, 0, 2, 2, 2, 0, 0};

    /** Latest normalized values. */
    public final float[] v = new float[COUNT];

    private final Context ctx;
    private final SensorManager sm;

    // motion
    private boolean motionOn = false;
    private float gx, gy, gz;
    private boolean gInit = false;
    /** Gravity reading treated as "flat / centered" for the tilt sources. */
    public volatile float calX = 0f, calY = 0f;

    // camera
    private boolean camOn = false;
    private boolean camFrontNow = true;
    private int camGen = 0;
    private int camRot = 90;
    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice camDev;
    private CameraCaptureSession camSession;
    private ImageReader reader;
    private float[] prevGrid;
    private float cx = 0.5f, cy = 0.5f;
    private static final int GW = 32, GH = 24;
    /** Latest picture of what the camera sees (screen orientation), for the on-screen view. */
    public volatile int[] vizPix;
    public volatile int vizW, vizH;

    public boolean isCameraOn() { return camOn; }

    // audio
    private volatile boolean audioRun = false;
    private volatile int audioGen = 0;
    private Thread audioThread;
    /** When true the mic audio is also played out (to headphones / USB audio / speaker). */
    private volatile boolean passthrough = false;
    private volatile boolean wantAudio = false;
    private volatile float passGain = 1.0f;

    public SensorEngine(Context c) {
        ctx = c;
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        resetValues();
    }

    private void resetValues() {
        for (int i = 0; i < COUNT; i++) v[i] = 0f;
        v[0] = v[1] = v[2] = v[3] = v[4] = 0.5f;
        v[9] = v[10] = 0.5f;
    }

    private static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    // ------------------------------------------------------------ motion

    public void setMotion(boolean on) {
        if (on == motionOn || sm == null) return;
        motionOn = on;
        if (on) {
            gInit = false;
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (a != null) sm.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME);
            if (g != null) sm.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
        } else {
            sm.unregisterListener(this);
            for (int i = 0; i <= 6; i++) v[i] = (i <= 4) ? 0.5f : 0f;
        }
    }

    private static float center(float w) {
        if (Math.abs(w) < 0.05f) w = 0f;
        return clamp(0.5f + w / 12f, 0f, 1f);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (!gInit) {
                gx = x; gy = y; gz = z;
                gInit = true;
            }
            gx += 0.1f * (x - gx);
            gy += 0.1f * (y - gy);
            gz += 0.1f * (z - gz);
            v[0] = (clamp((gx - calX) / 9.81f, -1f, 1f) + 1f) / 2f;
            v[1] = (clamp((gy - calY) / 9.81f, -1f, 1f) + 1f) / 2f;
            float lx = x - gx, ly = y - gy, lz = z - gz;
            float mag = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            v[6] = clamp(mag / 15f, 0f, 1f);
        } else if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            v[3] = center(x);
            v[4] = center(y);
            v[2] = center(z);
            float mag = (float) Math.sqrt(x * x + y * y + z * z);
            if (mag < 0.05f) mag = 0f;
            v[5] = clamp(mag / 6f, 0f, 1f);
        }
    }

    /** Use the current phone position as the center (0.5) for both tilt sources. */
    public boolean calibrate() {
        if (!motionOn || !gInit) return false;
        calX = gx;
        calY = gy;
        return true;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ------------------------------------------------------------ camera

    public void setCamera(boolean on, boolean front) {
        if (!on) {
            if (camOn) stopCamera();
            return;
        }
        if (camOn && camFrontNow == front) return;
        if (camOn) stopCamera();
        startCamera(front);
    }

    @SuppressLint("MissingPermission")
    private void startCamera(boolean front) {
        if (ctx.checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            CameraCharacteristics cc = null;
            int want = front ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            for (String s : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(s);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == want) {
                    id = s;
                    cc = c;
                    break;
                }
            }
            if (id == null) return;
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            camRot = so == null ? 90 : so;
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) return;
            Size best = null;
            for (Size s : sizes) {
                if (s.getWidth() >= 320 && s.getWidth() <= 1280) {
                    if (best == null || (long) s.getWidth() * s.getHeight()
                            < (long) best.getWidth() * best.getHeight()) best = s;
                }
            }
            if (best == null) best = sizes[sizes.length - 1];

            camFrontNow = front;
            camOn = true;
            prevGrid = null;
            final int gen = ++camGen;
            camThread = new HandlerThread("pm-cam");
            camThread.start();
            camHandler = new Handler(camThread.getLooper());
            reader = ImageReader.newInstance(best.getWidth(), best.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img != null) analyze(img);
                } catch (Exception ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, camHandler);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (gen != camGen) {
                        d.close();
                        return;
                    }
                    camDev = d;
                    try {
                        d.createCaptureSession(Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession s) {
                                        if (gen != camGen) {
                                            s.close();
                                            return;
                                        }
                                        camSession = s;
                                        try {
                                            CaptureRequest.Builder b =
                                                    d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            b.addTarget(reader.getSurface());
                                            s.setRepeatingRequest(b.build(), null, camHandler);
                                        } catch (Exception ignored) {
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                    }
                                }, camHandler);
                    } catch (CameraAccessException ignored) {
                    }
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                    // Another app took the camera: allow a later restart.
                    if (gen == camGen) camOn = false;
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    d.close();
                    if (gen == camGen) camOn = false;
                }
            }, camHandler);
        } catch (Exception e) {
            camOn = false;
        }
    }

    private void stopCamera() {
        camOn = false;
        camGen++;
        try { if (camSession != null) camSession.close(); } catch (Exception ignored) { }
        try { if (camDev != null) camDev.close(); } catch (Exception ignored) { }
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        if (camThread != null) camThread.quitSafely();
        camSession = null;
        camDev = null;
        reader = null;
        camThread = null;
        camHandler = null;
        prevGrid = null;
        v[7] = 0f;
        v[8] = 0f;
        v[9] = 0.5f;
        v[10] = 0.5f;
    }

    private void analyze(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int rs = p.getRowStride();
        int ps = p.getPixelStride();
        int w = img.getWidth();
        int h = img.getHeight();
        int cw = w / GW;
        int ch = h / GH;
        if (cw < 1 || ch < 1) return;

        float[] grid = new float[GW * GH];
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                int sum = 0, n = 0;
                for (int y = gy * ch; y < (gy + 1) * ch; y += 2) {
                    for (int x = gx * cw; x < (gx + 1) * cw; x += 2) {
                        sum += buf.get(y * rs + x * ps) & 0xFF;
                        n++;
                    }
                }
                grid[gy * GW + gx] = n > 0 ? (float) sum / n : 0f;
            }
        }

        float bright = 0f;
        for (float g : grid) bright += g;
        bright /= grid.length * 255f;
        v[8] = clamp(bright, 0f, 1f);

        float[] prev = prevGrid;
        prevGrid = grid;
        buildViz(grid, prev);
        if (prev == null) return;

        int active = 0;
        float sw = 0f, su = 0f, sv = 0f;
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                int i = gy * GW + gx;
                float d = Math.abs(grid[i] - prev[i]);
                if (d > 14f) {
                    active++;
                    sw += d;
                    su += d * (gx + 0.5f) / GW;
                    sv += d * (gy + 0.5f) / GH;
                }
            }
        }
        float motion = clamp(active / (float) (GW * GH) * 6f, 0f, 1f);
        v[7] = motion;

        if (active >= 3 && sw > 0f) {
            float u = su / sw;
            float vv = sv / sw;
            float x, y;
            switch (camRot) {
                case 90:  x = 1f - vv; y = u; break;
                case 180: x = 1f - u;  y = 1f - vv; break;
                case 270: x = vv;      y = 1f - u; break;
                default:  x = u;       y = vv; break;
            }
            if (camFrontNow) x = 1f - x; // mirror like a selfie view
            cx += 0.5f * (x - cx);
            cy += 0.5f * (y - cy);
            v[9] = clamp(cx, 0f, 1f);
            v[10] = clamp(cy, 0f, 1f);
        }
    }

    /** Grey picture + green where things moved, rotated/mirrored like the tracked point. */
    private void buildViz(float[] grid, float[] prev) {
        boolean tall = camRot == 90 || camRot == 270;
        int W = tall ? GH : GW, H = tall ? GW : GH;
        int[] px = new int[W * H];
        for (int oy = 0; oy < H; oy++) {
            for (int ox = 0; ox < W; ox++) {
                float x = (ox + 0.5f) / W, y = (oy + 0.5f) / H;
                if (camFrontNow) x = 1f - x;
                float u, vv;
                switch (camRot) {
                    case 90:  vv = 1f - x; u = y; break;
                    case 180: u = 1f - x;  vv = 1f - y; break;
                    case 270: vv = x;      u = 1f - y; break;
                    default:  u = x;       vv = y; break;
                }
                int gx = Math.min(GW - 1, Math.max(0, (int) (u * GW)));
                int gy = Math.min(GH - 1, Math.max(0, (int) (vv * GH)));
                int i = gy * GW + gx;
                int g = (int) clamp(grid[i] * 0.7f, 0f, 255f);
                float d = prev == null ? 0f : Math.abs(grid[i] - prev[i]);
                int r = g, gr = g, b = g;
                if (d > 14f) {
                    float t = clamp((d - 14f) / 50f, 0f, 1f);
                    r = (int) (g * (1f - t));
                    gr = (int) (g * (1f - t) + 255f * t);
                    b = (int) (g * (1f - t) + 120f * t);
                }
                px[oy * W + ox] = 0xFF000000 | (r << 16) | (gr << 8) | b;
            }
        }
        vizW = W;
        vizH = H;
        vizPix = px;
    }

    // ------------------------------------------------------------ audio

    public boolean isPassthrough() { return passthrough; }

    /** Play the microphone out loud (mic -> audio output). Takes effect immediately. */
    public void setPassthrough(boolean on) {
        if (on == passthrough) return;
        passthrough = on;
        stopAudio();            // restart the capture loop with / without the output
        setAudio(wantAudio);
    }

    private void stopAudio() {
        audioRun = false;
        audioGen++;
        Thread t = audioThread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(300); } catch (InterruptedException ignored) { }
        }
    }

    /** Mic level meter / passthrough needs the capture loop; mapping needs set {@code on}. */
    public void setAudio(boolean on) {
        wantAudio = on;
        on = on || passthrough;
        if (!on) {
            stopAudio();
            return;
        }
        if (audioRun) return;
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        audioRun = true;
        final int gen = ++audioGen;
        audioThread = new Thread(() -> audioLoop(gen), "pm-audio");
        audioThread.start();
    }

    @SuppressLint("MissingPermission")
    private void audioLoop(int gen) {
        AudioRecord ar = null;
        AudioTrack at = null;
        try {
            int sr = 44100;
            int min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, 4096);
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, sr,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                if (gen == audioGen) audioRun = false;
                return;
            }
            ar.startRecording();
            if (passthrough) {
                int tmin = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                at = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(sr)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(tmin, 2048))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
                at.play();
            }
            short[] buf = new short[1024];
            float lvl = 0f, br = 0f;
            while (audioRun && gen == audioGen) {
                int r = ar.read(buf, 0, buf.length);
                if (r <= 0) continue;
                if (at != null) at.write(buf, 0, r);
                double s = 0;
                int zc = 0;
                for (int i = 0; i < r; i++) {
                    s += (double) buf[i] * buf[i];
                    if (i > 0 && ((buf[i] >= 0) != (buf[i - 1] >= 0))) zc++;
                }
                double rms = Math.sqrt(s / r) / 32768.0;
                double db = 20.0 * Math.log10(rms + 1e-9);
                float l = clamp((float) ((db + 60.0) / 60.0), 0f, 1f);
                lvl = l > lvl ? l : lvl * 0.85f + l * 0.15f;
                v[11] = lvl;
                if (l > 0.15f) {
                    float b = clamp(((float) zc / r) / 0.3f, 0f, 1f);
                    br += 0.3f * (b - br);
                } else {
                    br *= 0.95f;
                }
                v[12] = br;
            }
        } catch (Exception ignored) {
            if (gen == audioGen) audioRun = false;
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception ignored) { }
                ar.release();
            }
            if (at != null) {
                try { at.stop(); } catch (Exception ignored) { }
                at.release();
            }
            if (gen == audioGen) {
                v[11] = 0f;
                v[12] = 0f;
            }
        }
    }

    public void stopAll() {
        setMotion(false);
        setCamera(false, camFrontNow);
        wantAudio = false;
        stopAudio();
    }
}

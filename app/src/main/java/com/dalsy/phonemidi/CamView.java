package com.dalsy.phonemidi;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Shows what the camera sensing "sees": brightness as grey, things that moved in green,
 * the tracked hand as a ring, and (optionally) the note bands when the camera picks notes.
 */
public class CamView extends View {
    private final SensorEngine eng;
    private Bitmap bmp;
    private int bw, bh;
    private final Paint pix = new Paint();
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint();
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final float dp;

    // note bands (guides): 0 = none, else count of bands; vertical = bands split left/right
    private int guideN = 0;
    private boolean guideVert = true;
    private int guideCur = -1;
    private float guideLo = 0f, guideHi = 1f;
    private boolean guideFlip = false;
    private final Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CamView(Context c, SensorEngine e) {
        super(c);
        eng = e;
        dp = c.getResources().getDisplayMetrics().density;
        pix.setFilterBitmap(false);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f * dp);
        ring.setColor(0xFFFFB020);
        txt.setColor(0xFFB8B8C8);
        txt.setTextSize(14f * dp);
        txt.setTextAlign(Paint.Align.CENTER);
        bone.setStyle(Paint.Style.STROKE);
        bone.setStrokeWidth(3f * dp);
        bone.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Note bands over the picture: n bands between lo..hi; flip = band 0 at the bottom/right. */
    public void setGuides(int n, boolean vertical, int current, float lo, float hi, boolean flip) {
        guideN = n;
        guideVert = vertical;
        guideCur = current;
        guideLo = lo;
        guideHi = hi;
        guideFlip = flip;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF0A0A0E);
        int[] px = eng.vizPix;
        int w = eng.vizW, h = eng.vizH;
        if (!eng.isCameraOn() || px == null || w <= 0 || h <= 0 || px.length < w * h) {
            canvas.drawText("Esperando la cámara…", getWidth() / 2f, getHeight() / 2f, txt);
            if (eng.handsActive()) {
                canvas.drawText(eng.handStatus(), getWidth() / 2f, getHeight() / 2f + 22f * dp, txt);
            }
            return;
        }
        if (bmp == null || bw != w || bh != h) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bw = w;
            bh = h;
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
        float vw = getWidth(), vh = getHeight();
        float scale = Math.min(vw / w, vh / h);
        float dw = w * scale, dh = h * scale;
        float l = (vw - dw) / 2f, t = (vh - dh) / 2f;
        dst.set(l, t, l + dw, t + dh);
        pix.setFilterBitmap(w > 64);
        canvas.drawBitmap(bmp, null, dst, pix);

        if (guideN > 1) {
            for (int i = 0; i < guideN; i++) {
                int k = guideFlip ? guideN - 1 - i : i;
                band.setColor(k == guideCur ? 0x597CE0B0 : ((k & 1) == 0 ? 0x1FFFFFFF : 0x0AFFFFFF));
                float fa = guideLo + (guideHi - guideLo) * i / guideN;
                float fb = guideLo + (guideHi - guideLo) * (i + 1) / guideN;
                if (guideVert) {
                    canvas.drawRect(l + dw * fa, t, l + dw * fb, t + dh, band);
                } else {
                    canvas.drawRect(l, t + dh * fa, l + dw, t + dh * fb, band);
                }
            }
        }

        if (eng.handsActive()) {
            float[][] hs = eng.hands;
            for (int hi = 0; hi < hs.length; hi++) {
                float[] p = hs[hi];
                int col = hi == 0 ? 0xFF7CE0B0 : 0xFFFFB020;
                bone.setColor(col);
                dot.setColor(col);
                for (int[] bn : HandTracker.BONES) {
                    canvas.drawLine(l + p[2 * bn[0]] * dw, t + p[2 * bn[0] + 1] * dh,
                            l + p[2 * bn[1]] * dw, t + p[2 * bn[1] + 1] * dh, bone);
                }
                for (int k = 0; k < 21; k++) {
                    canvas.drawCircle(l + p[2 * k] * dw, t + p[2 * k + 1] * dh, 3.5f * dp, dot);
                }
                if (hi == 0) {
                    float mx = l + (p[8] + p[16]) / 2f * dw, my = t + (p[9] + p[17]) / 2f * dh;
                    boolean closed = eng.v[SensorEngine.HAND_PINCH] > 0.7f;
                    ring.setStyle(closed ? Paint.Style.FILL : Paint.Style.STROKE);
                    canvas.drawCircle(mx, my, (closed ? 12f : 9f) * dp, ring);
                    ring.setStyle(Paint.Style.STROKE);
                    int fingers = (int) (eng.v[SensorEngine.HAND_FINGERS] * 6f);
                    canvas.drawText("✋ " + fingers, l + p[0] * dw, t + p[1] * dh + 26f * dp, txt);
                }
            }
            if (hs.length == 0) {
                canvas.drawText(eng.handStatus(), getWidth() / 2f, t + dh - 14f * dp, txt);
            }
            return;
        }

        float cx = l + eng.v[9] * dw, cy = t + eng.v[10] * dh;
        canvas.drawCircle(cx, cy, 16f * dp, ring);
        canvas.drawLine(cx - 24f * dp, cy, cx + 24f * dp, cy, ring);
        canvas.drawLine(cx, cy - 24f * dp, cx, cy + 24f * dp, ring);
    }
}

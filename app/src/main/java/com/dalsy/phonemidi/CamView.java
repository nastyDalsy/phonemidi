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
    }

    public void setGuides(int n, boolean vertical, int current) {
        guideN = n;
        guideVert = vertical;
        guideCur = current;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF0A0A0E);
        int[] px = eng.vizPix;
        int w = eng.vizW, h = eng.vizH;
        if (!eng.isCameraOn() || px == null || w <= 0 || h <= 0 || px.length < w * h) {
            canvas.drawText("Esperando la cámara…", getWidth() / 2f, getHeight() / 2f, txt);
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
        canvas.drawBitmap(bmp, null, dst, pix);

        if (guideN > 1) {
            for (int i = 0; i < guideN; i++) {
                band.setColor(i == guideCur ? 0x4D7CE0B0 : ((i & 1) == 0 ? 0x1AFFFFFF : 0x0DFFFFFF));
                if (guideVert) {
                    float a = l + dw * i / guideN, b = l + dw * (i + 1) / guideN;
                    canvas.drawRect(a, t, b, t + dh, band);
                } else {
                    float a = t + dh * i / guideN, b = t + dh * (i + 1) / guideN;
                    canvas.drawRect(l, a, l + dw, b, band);
                }
            }
        }

        float cx = l + eng.v[9] * dw, cy = t + eng.v[10] * dh;
        canvas.drawCircle(cx, cy, 16f * dp, ring);
        canvas.drawLine(cx - 24f * dp, cy, cx + 24f * dp, cy, ring);
        canvas.drawLine(cx, cy - 24f * dp, cx, cy + 24f * dp, ring);
    }
}

package com.dalsy.phonemidi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A row of steps: the notes of the scale, with the current one lit. */
public class LadderView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private int n = 0, cur = -1;
    private boolean live = false;

    public LadderView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
    }

    public void set(int steps, int current, boolean sounding) {
        if (steps != n || current != cur || sounding != live) {
            n = steps;
            cur = current;
            live = sounding;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (n <= 0) return;
        float gap = 3f * dp;
        float w = (getWidth() - gap * (n - 1)) / n;
        float h = getHeight();
        for (int i = 0; i < n; i++) {
            // taller steps = higher notes
            float top = h * (1f - (i + 1f) / n * 0.8f - 0.2f * 0f);
            p.setColor(i == cur ? (live ? 0xFF7CE0B0 : 0xFF4A8F72) : 0xFF2A2A36);
            float x = i * (w + gap);
            canvas.drawRoundRect(x, top, x + w, h, 3f * dp, 3f * dp, p);
        }
    }
}

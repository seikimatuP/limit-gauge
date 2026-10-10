package com.ynozue.limitgauge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Draws the thin circular gauge used by the notification and every widget.
 *
 * The bitmap is an alpha mask (ALPHA_8): the track is drawn at {@link #TRACK_ALPHA}, the progress
 * arc and the centre glyph at full strength. The ImageView that shows it tints it with
 * {@code android:tint="@color/lg_gauge"} (or {@code lg_critical}) and {@code tintMode="src_in"}, so the
 * colours live in colors.xml and follow the light/dark resources of whoever inflates the view
 * (System UI for the notification, the launcher or lock screen for widgets). An alpha-only bitmap is
 * also a quarter of the size of an ARGB one, which keeps the RemoteViews parcels small.
 */
final class GaugeBitmap {
    /** Track opacity relative to the progress arc: the "light blue" ring behind the "dark blue" one. */
    static final int TRACK_ALPHA = 72;
    /** Ring thickness as a fraction of the diameter (thin, like the lock screen widgets it copies). */
    static final float STROKE_RATIO = 0.09f;
    /** Centre glyph size as a fraction of the diameter. */
    static final float ICON_RATIO = 0.42f;
    /** Upper bound for one side in pixels, so a large density never produces a huge parcel. */
    static final int MAX_PX = 320;

    private GaugeBitmap() {}

    /**
     * @param sizePx  diameter in pixels (the ImageView's size, e.g. from a {@code ring_*} dimen)
     * @param percent how much of the ring to fill, 0..100 (clamped); 0 draws the track only
     * @param icon    true to draw the gauge glyph in the middle
     */
    static Bitmap ring(Context c, int sizePx, int percent, boolean icon) {
        int px = Math.max(8, Math.min(MAX_PX, sizePx));
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bmp);

        float stroke = Math.max(1.5f, px * STROKE_RATIO);
        float inset = stroke / 2f;
        RectF oval = new RectF(inset, inset, px - inset, px - inset);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFFFFFFFF);

        p.setAlpha(TRACK_ALPHA);
        canvas.drawOval(oval, p);

        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped > 0) {
            p.setAlpha(255);
            if (clamped >= 100) {
                p.setStrokeCap(Paint.Cap.BUTT);
                canvas.drawOval(oval, p);
            } else {
                // Start at 12 o'clock and run clockwise.
                canvas.drawArc(oval, -90f, 360f * clamped / 100f, false, p);
            }
        }

        if (icon) {
            Drawable glyph = c.getDrawable(R.drawable.ic_stat_gauge);
            if (glyph != null) {
                glyph = glyph.mutate();
                int s = Math.round(px * ICON_RATIO);
                int left = (px - s) / 2;
                glyph.setBounds(left, left, left + s, left + s);
                glyph.draw(canvas);
            }
        }
        return bmp;
    }

    /** Same as {@link #ring(Context, int, int, boolean)} with the diameter taken from a dimen resource. */
    static Bitmap ringForDimen(Context c, int dimenRes, int percent, boolean icon) {
        return ring(c, c.getResources().getDimensionPixelSize(dimenRes), percent, icon);
    }
}

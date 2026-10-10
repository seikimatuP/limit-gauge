package com.ynozue.limitgauge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
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
 *
 * The one exception is {@link #ringShadowed}, a pre-coloured ARGB ring with a dark outline for the
 * background-less lock screen widget.
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

    /** Colour of the soft outline drawn under the shadowed ring (black at about 60%, like the text shadow). */
    static final int SHADOW_COLOR = 0x99000000;
    /** Outline width on each side of the ring stroke, as a fraction of the diameter. */
    static final float SHADOW_RATIO = 0.035f;

    /**
     * The same ring in a fixed colour with a dark outline under it, for the lock screen widget that has
     * no background and must stay readable on any wallpaper. An ALPHA_8 mask cannot hold a coloured
     * shadow, so this one is ARGB_8888 and already coloured: its ImageView must not tint it. The other
     * widgets and the notification keep using {@link #ring}, so their look does not change.
     *
     * @param color ARGB colour of the progress arc and glyph; the track is the same colour at
     *              {@link #TRACK_ALPHA}
     */
    static Bitmap ringShadowed(Context c, int sizePx, int percent, boolean icon, int color) {
        int px = Math.max(8, Math.min(MAX_PX, sizePx));
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        float halo = Math.max(1f, px * SHADOW_RATIO);
        float stroke = Math.max(1.5f, px * STROKE_RATIO);
        // Leave room for the outline and its blur (and the 1px downward offset) inside the bitmap.
        float inset = stroke / 2f + halo * 2f;
        RectF oval = new RectF(inset, inset, px - inset, px - inset);
        RectF shadowOval = new RectF(oval);
        shadowOval.offset(0f, Math.max(1f, halo / 2f));

        int clamped = Math.max(0, Math.min(100, percent));

        // 1) Outline: a wider, blurred, translucent black stroke under the full circle.
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setStyle(Paint.Style.STROKE);
        sh.setStrokeWidth(stroke + halo * 2f);
        sh.setColor(SHADOW_COLOR);
        sh.setMaskFilter(new BlurMaskFilter(halo, BlurMaskFilter.Blur.NORMAL));
        canvas.drawOval(shadowOval, sh);

        // 2) Track and progress arc in the fixed colour.
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        p.setAlpha(Color.alpha(color) * TRACK_ALPHA / 255);
        canvas.drawOval(oval, p);
        if (clamped > 0) {
            p.setColor(color);
            if (clamped >= 100) {
                p.setStrokeCap(Paint.Cap.BUTT);
                canvas.drawOval(oval, p);
            } else {
                canvas.drawArc(oval, -90f, 360f * clamped / 100f, false, p);
            }
        }

        // 3) Glyph: a dark copy one step down, then the coloured one.
        if (icon) {
            Drawable glyph = c.getDrawable(R.drawable.ic_stat_gauge);
            if (glyph != null) {
                glyph = glyph.mutate();
                int s = Math.round(px * ICON_RATIO);
                int left = (px - s) / 2;
                int dy = Math.max(1, Math.round(halo / 2f));
                glyph.setTint(SHADOW_COLOR);
                glyph.setBounds(left, left + dy, left + s, left + s + dy);
                glyph.draw(canvas);
                glyph.setTint(color);
                glyph.setBounds(left, left, left + s, left + s);
                glyph.draw(canvas);
            }
        }
        return bmp;
    }
}

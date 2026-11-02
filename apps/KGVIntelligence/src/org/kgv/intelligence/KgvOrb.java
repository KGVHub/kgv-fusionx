package org.kgv.intelligence;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import java.io.IOException;

/**
 * KGV X Intelligence's orb: a fluid core of glowing dots, sheets curling around it and
 * electric sparks in a glass sphere, in deep reds. It plays res/raw/kgv_orb.webp (made by
 * tools/render_orb.py: a seamless 10 s loop at 60 fps, on the chat card's colour), which
 * Android decodes off the UI thread. It brightens while listening or working and swells
 * with the voice.
 */
final class KgvOrb extends View {
    static final int RED = 0xFFD4101F;
    static final int ROSE = 0xFFE0485A;
    static final int MAGENTA = 0xFFB01040;
    static final int SCARLET = 0xFFD8302A;
    static final int HOT = 0xFFFFD2D7;
    /** The orb's colours, for the edge glow and buttons. */
    static final int[] PALETTE = {RED, ROSE, HOT, MAGENTA, SCARLET};

    private static final float IDLE_ALPHA = 0.85f;

    private final AnimatedImageDrawable mOrb;
    private float mLevel;
    private float mSwell;

    KgvOrb(Context context) {
        super(context);
        AnimatedImageDrawable orb = null;
        try {
            Drawable d = ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.getResources(), R.raw.kgv_orb));
            if (d instanceof AnimatedImageDrawable) {
                orb = (AnimatedImageDrawable) d;
                orb.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                orb.setCallback(this);
            }
        } catch (IOException e) {
            Log.w("KGVOrb", "can't load the orb", e);
        }
        mOrb = orb;
        setAlpha(IDLE_ALPHA);
    }

    /** Calm (idle) or bright (listening, thinking or working). */
    void setActive(boolean active) {
        animate().alpha(active ? 1f : IDLE_ALPHA).setDuration(400);
    }

    /** The voice level in dB (about -2 to 10), from Voice: the orb swells with it. */
    void setVoiceLevel(float rmsDb) {
        mLevel = Math.max(mLevel, Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f)));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mOrb != null) mOrb.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mOrb != null) mOrb.stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mOrb || super.verifyDrawable(who);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        int s = Math.min(w, h);
        if (mOrb != null) mOrb.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mOrb == null) return;
        // Swell with the voice, easing back.
        mSwell += (mLevel - mSwell) * 0.3f;
        mLevel *= 0.85f;
        float scale = 1f + 0.08f * mSwell;
        canvas.save();
        canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
        mOrb.draw(canvas);
        canvas.restore();
    }
}

package org.kgv.intelligence;

import android.animation.TimeAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;

/**
 * The assistant's glow: soft, slowly turning colours along the edges of the screen, like
 * Siri. Brighter while the assistant listens or works; the voice level makes it pulse.
 */
final class SiriGlow extends View {
    // KgvOrb's reds, turning around the screen.
    private static final int[] COLORS = {
            KgvOrb.PALETTE[0], KgvOrb.PALETTE[2], KgvOrb.PALETTE[4], KgvOrb.PALETTE[3],
            KgvOrb.PALETTE[1], KgvOrb.PALETTE[0],
    };
    // Soft outer glow to sharp inner line: stroke width (dp) and alpha.
    private static final float[] WIDTHS = {34, 20, 10, 3.5f};
    private static final int[] ALPHAS = {40, 70, 120, 255};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix mMatrix = new Matrix();
    private final RectF mRect = new RectF();
    private final TimeAnimator mSpin = new TimeAnimator();
    private final float mDensity;
    private float mCorner;
    private SweepGradient mShader;
    private float mAngle;
    private float mIntensity = 0.55f;
    private float mTarget = 0.55f;
    private float mLevel;
    // Degrees per second, easing towards mTargetSpeed.
    private float mSpeed = 60f;
    private float mTargetSpeed = 60f;

    SiriGlow(Context context) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mPaint.setStyle(Paint.Style.STROKE);
        mSpin.setTimeListener((animation, totalTime, deltaTime) -> {
            mSpeed += (mTargetSpeed - mSpeed) * 0.08f;
            mAngle = (mAngle + mSpeed * deltaTime / 1000f) % 360f;
            mIntensity += (mTarget + mLevel * 0.45f - mIntensity) * 0.12f;
            mLevel *= 0.9f;
            invalidate();
        });
    }

    /** Calm (idle), or bright and faster (listening or working). */
    void setActive(boolean active) {
        mTarget = active ? 1f : 0.55f;
        mTargetSpeed = active ? 140f : 60f;
    }

    /** The voice level from SpeechRecognizer, in dB (about -2 to 10). */
    void setVoiceLevel(float rmsDb) {
        mLevel = Math.max(mLevel, Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f)));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mSpin.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        mSpin.cancel();
        super.onDetachedFromWindow();
    }

    /** Follow the screen's rounded corners, where the display reports them. */
    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        RoundedCorner c = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
        mCorner = c != null ? c.getRadius() : 0;
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mShader = new SweepGradient(w / 2f, h / 2f, COLORS, null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mShader == null) return;
        mMatrix.setRotate(mAngle, getWidth() / 2f, getHeight() / 2f);
        mShader.setLocalMatrix(mMatrix);
        mPaint.setShader(mShader);
        float grow = 0.75f + 0.5f * mIntensity;
        for (int i = 0; i < WIDTHS.length; i++) {
            float w = WIDTHS[i] * mDensity * grow;
            mPaint.setStrokeWidth(w);
            mPaint.setAlpha(Math.round(ALPHAS[i] * Math.min(1f, mIntensity)));
            // Centre the stroke on the screen edge so only the inner half shows.
            mRect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(mRect, mCorner, mCorner, mPaint);
        }
    }
}

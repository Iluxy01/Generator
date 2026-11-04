package app.holst;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Полоска внизу: потянуть вверх или нажать, чтобы перейти к разблокировке. */
public final class UnlockHandle extends View {
    private final float density = getResources().getDisplayMetrics().density;
    private final float threshold = 64 * density;
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String hint;
    private final String lockedHint;
    private boolean locked;
    private float downY;
    private float drag;
    private boolean moved;
    private boolean fired;
    private Runnable onUnlock;

    public UnlockHandle(Context c, AttributeSet a) {
        super(c, a);
        hint = c.getString(R.string.unlock_hint);
        lockedHint = c.getString(R.string.lock_handle_hint);
        pill.setColor(0xF2FFFFFF);
        pill.setShadowLayer(6 * density, 0, density, 0x99000000);
        text.setColor(0xF2FFFFFF);
        text.setTextSize(12.5f * getResources().getDisplayMetrics().scaledDensity);
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(6 * density, 0, density, 0xCC000000);
        setContentDescription(hint);
        setClickable(true);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    /** В режиме блокировки полоска ни на что не реагирует и только подсказывает, как выйти. */
    void setLocked(boolean v) {
        locked = v;
        drag = 0;
        invalidate();
    }

    void setOnUnlock(Runnable r) {
        onUnlock = r;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (locked) {
            text.setAlpha(170);
            c.drawText(lockedHint, getWidth() / 2f, getHeight() * 0.42f + 22 * density, text);
            return;
        }
        float cx = getWidth() / 2f;
        float half = 22 * density;
        float y = getHeight() * 0.42f - drag;
        float r = 2.5f * density;
        c.drawRoundRect(cx - half, y - r, cx + half, y + r, r, r, pill);
        text.setAlpha((int) (242 * Math.max(0f, 1f - drag / threshold)));
        c.drawText(hint, cx, getHeight() * 0.42f + 22 * density, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (locked) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getRawY();
                drag = 0;
                moved = false;
                fired = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                drag = Math.max(0, downY - e.getRawY());
                if (drag > 6 * density) moved = true;
                invalidate();
                if (drag >= threshold && !fired) {
                    fired = true;
                    fire();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) performClick();
                settle();
                return true;
            case MotionEvent.ACTION_CANCEL:
                settle();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        fire();
        return true;
    }

    private void fire() {
        if (!locked && onUnlock != null) onUnlock.run();
    }

    private void settle() {
        ValueAnimator a = ValueAnimator.ofFloat(drag, 0f).setDuration(180);
        a.addUpdateListener(v -> {
            drag = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }
}

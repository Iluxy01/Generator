package app.holst;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Кружок палитры. Показывает цвет, размер кисти или ластик. */
public final class ColorDot extends View {
    private final float density = getResources().getDisplayMetrics().density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFFFFFFFF;
    private float radiusDp = 11;
    private boolean picked;
    private boolean eraser;

    public ColorDot(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    ColorDot(Context c) {
        super(c);
        init();
    }

    private void init() {
        ring.setStyle(Paint.Style.STROKE);
        setClickable(true);
    }

    void setColor(int color) {
        this.color = color;
        invalidate();
    }

    void setRadiusDp(float r) {
        radiusDp = r;
        invalidate();
    }

    void setPicked(boolean v) {
        picked = v;
        invalidate();
    }

    void setEraser(boolean v) {
        eraser = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = radiusDp * density;
        if (eraser) {
            ring.setColor(0xFFFFFFFF);
            ring.setStrokeWidth(1.8f * density);
            c.drawCircle(cx, cy, r, ring);
            float d = r * 0.7f;
            c.drawLine(cx - d, cy + d, cx + d, cy - d, ring);
        } else {
            fill.setColor(color | 0xFF000000);
            c.drawCircle(cx, cy, r, fill);
            ring.setColor(0x66FFFFFF);
            ring.setStrokeWidth(density);
            c.drawCircle(cx, cy, r, ring);
        }
        if (picked) {
            ring.setColor(0xFFFFFFFF);
            ring.setStrokeWidth(2f * density);
            c.drawCircle(cx, cy, r + 4.5f * density, ring);
        }
    }
}

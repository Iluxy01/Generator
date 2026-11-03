package app.holst;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

/** Маленькая копия обоев в приложении. Только показывает; рисуют на самих обоях. */
public final class PreviewView extends View implements Repo.Listener {
    private final App app = App.get();
    private Bitmap frame;

    public PreviewView(Context c, AttributeSet a) {
        super(c, a);
        setClipToOutline(true);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int h = MeasureSpec.getSize(heightSpec);
        int[] s = Bg.screen(getContext());
        setMeasuredDimension(Math.round(h * (float) s[0] / s[1]), h);
    }

    void refresh() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        frame = Bg.compose(getContext(), app.repo.strokes(), getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        app.repo.addListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        app.repo.removeListener(this);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (frame != null) c.drawBitmap(frame, 0, 0, null);
        else c.drawColor(Bg.SHEET);
    }

    @Override
    public void onStrokeAdded(Stroke s, boolean atEnd) {
        refresh();
    }

    @Override
    public void onReset() {
        refresh();
    }

    @Override
    public void onLiveChanged() {}
}

package app.holst;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import java.util.List;

/**
 * Маленькая копия обоев в приложении. Только показывает; рисуют на самих обоях.
 * Собирается в отдельном потоке и только пока приложение на экране: открытое в фоне окно не должно
 * тормозить холст на экране блокировки.
 */
public final class PreviewView extends View implements Repo.Listener {
    private static final long REFRESH_GAP_MS = 400;

    private final App app = App.get();
    private Bitmap frame;
    private boolean shown;
    private boolean composing;
    private boolean dirty = true;

    private final Runnable composeLater = this::compose;

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
        dirty = true;
        compose();
    }

    private void compose() {
        if (!shown || composing || !dirty || getWidth() <= 0 || getHeight() <= 0) return;
        composing = true;
        dirty = false;
        final int w = getWidth();
        final int h = getHeight();
        final List<Stroke> strokes = app.repo.strokes();
        final Context context = getContext();
        app.render.execute(() -> {
            final Bitmap out = Bg.compose(context, strokes, w, h);
            post(() -> {
                composing = false;
                frame = out;
                invalidate();
                if (dirty) postDelayed(composeLater, REFRESH_GAP_MS);
            });
        });
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        shown = visibility == VISIBLE;
        if (shown) compose();
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

    private void markDirty() {
        dirty = true;
        removeCallbacks(composeLater);
        postDelayed(composeLater, REFRESH_GAP_MS);
    }

    @Override
    public void onStrokeAdded(Stroke s, boolean atEnd) {
        markDirty();
    }

    @Override
    public void onReset() {
        markDirty();
    }
}

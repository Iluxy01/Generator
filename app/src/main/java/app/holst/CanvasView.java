package app.holst;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Сам холст: подложка, общий рисунок и то, что рисуется прямо сейчас у тебя и у друга. */
public final class CanvasView extends View implements Repo.Listener {
    interface DrawingListener {
        void onDrawing(boolean drawing);
    }

    private static final long LIVE_SEND_MS = 220;
    private static final int MIN_STEP = 12;
    private static final int MAX_PAIRS = 1200;

    private final App app = App.get();
    private final Paint paint = Renderer.newPaint();
    private Bitmap bg;
    private Bitmap layer;
    private Canvas layerCanvas;
    private int width;
    private int height;
    private DrawingListener drawingListener;
    private int renderedRevision = -1;

    private int color = 0xFFFFFFFF;
    private int brush = 120;
    private boolean eraser;

    private boolean drawing;
    private String curId;
    private int[] cur = new int[512];
    private int curCount;
    private int sentCount;
    private int seq;

    private final Runnable liveSender = new Runnable() {
        @Override
        public void run() {
            if (!drawing) return;
            flushLive();
            postDelayed(this, LIVE_SEND_MS);
        }
    };

    public CanvasView(Context c, AttributeSet a) {
        super(c, a);
    }

    void setDrawingListener(DrawingListener l) {
        drawingListener = l;
    }

    void setBrush(int color, int width, boolean eraser) {
        this.color = color;
        this.brush = width;
        this.eraser = eraser;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        app.repo.addListener(this);
        refreshIfStale();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        finishStroke();
        app.repo.removeListener(this);
    }

    /** Лист мог измениться, пока окно не было видно. */
    void refreshIfStale() {
        if (layer != null && renderedRevision != app.repo.revision()) rebuild();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        width = w;
        height = h;
        bg = Bg.load(getContext(), w, h);
        layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        layerCanvas = new Canvas(layer);
        rebuild();
        if (Build.VERSION.SDK_INT >= 29) {
            // Движение от края экрана — это рисование, а не жест «назад».
            int band = (int) (200 * getResources().getDisplayMetrics().density);
            int top = Math.max(0, h / 2 - band / 2);
            List<Rect> rects = new ArrayList<>();
            rects.add(new Rect(0, top, (int) (40 * getResources().getDisplayMetrics().density), top + band));
            rects.add(new Rect(w - (int) (40 * getResources().getDisplayMetrics().density), top, w, top + band));
            setSystemGestureExclusionRects(rects);
        }
    }

    private void rebuild() {
        if (layer == null) return;
        layer.eraseColor(0);
        Renderer.drawAll(layerCanvas, app.repo.strokes(), width, height);
        renderedRevision = app.repo.revision();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (layer == null) {
            c.drawColor(Bg.SHEET);
            return;
        }
        if (bg != null) c.drawBitmap(bg, 0, 0, null);
        else c.drawColor(Bg.SHEET);

        List<Repo.Live> live = app.repo.liveStrokes();
        if (!drawing && live.isEmpty()) {
            c.drawBitmap(layer, 0, 0, null);
            return;
        }
        // Отдельный слой нужен ластику: он должен стирать рисунок, но не подложку.
        int save = c.saveLayer(null, null);
        c.drawBitmap(layer, 0, 0, null);
        for (Repo.Live l : live) {
            Renderer.draw(c, l.pts, l.count, l.color, l.width, l.erase, width, height, paint);
        }
        if (drawing) Renderer.draw(c, cur, curCount, color, brush, eraser, width, height, paint);
        c.restoreToCount(save);
    }

    // ---------- Касания ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startStroke();
                addPoint(e.getX(), e.getY());
                postDelayed(liveSender, LIVE_SEND_MS);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!drawing) return true;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    addPoint(e.getHistoricalX(0, i), e.getHistoricalY(0, i));
                }
                addPoint(e.getX(0), e.getY(0));
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drawing) {
                    addPoint(e.getX(0), e.getY(0));
                    finishStroke();
                }
                return true;
            default:
                return true;
        }
    }

    private void startStroke() {
        drawing = true;
        curId = Stroke.newId();
        curCount = 0;
        sentCount = 0;
        seq = 0;
        if (drawingListener != null) drawingListener.onDrawing(true);
    }

    private void addPoint(float x, float y) {
        int qx = Renderer.qx(x, width);
        int qy = Renderer.qy(y, width, height);
        if (curCount >= 2 && Math.abs(qx - cur[curCount - 2]) < MIN_STEP
                && Math.abs(qy - cur[curCount - 1]) < MIN_STEP) {
            return;
        }
        if (curCount + 2 > cur.length) cur = Arrays.copyOf(cur, cur.length * 2);
        cur[curCount++] = qx;
        cur[curCount++] = qy;
        if (curCount >= MAX_PAIRS * 2) {
            // Очень длинный штрих делим на части: у ретрансляторов есть предел размера события.
            commit();
            curId = Stroke.newId();
            cur[0] = qx;
            cur[1] = qy;
            curCount = 2;
            sentCount = 0;
            seq = 0;
        }
    }

    private void flushLive() {
        if (curCount <= sentCount) return;
        int from = Math.max(0, sentCount - 2);
        app.sync.sendLive(curId, color, brush, eraser, seq++, Arrays.copyOfRange(cur, from, curCount));
        sentCount = curCount;
    }

    private void commit() {
        if (curCount >= 2) {
            app.repo.addMine(curId, color, brush, eraser, Arrays.copyOf(cur, curCount));
        }
    }

    private void finishStroke() {
        if (!drawing) return;
        removeCallbacks(liveSender);
        commit();
        drawing = false;
        curCount = 0;
        invalidate();
        if (drawingListener != null) drawingListener.onDrawing(false);
    }

    // ---------- Лист ----------

    @Override
    public void onStrokeAdded(Stroke s, boolean atEnd) {
        if (layer == null) return;
        if (atEnd) {
            Renderer.draw(layerCanvas, s, width, height, paint);
            paint.setXfermode(null);
            renderedRevision = app.repo.revision();
            invalidate();
        } else {
            rebuild();
        }
    }

    @Override
    public void onReset() {
        rebuild();
    }

    @Override
    public void onLiveChanged() {
        invalidate();
    }
}

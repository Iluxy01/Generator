package app.holst;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Сам холст: подложка, общий рисунок и то, что рисуется прямо сейчас у тебя и у друга.
 * Главный поток занят только касаниями и показом: полную картинку собирает отдельный поток.
 */
public final class CanvasView extends View implements Repo.Listener {
    interface DrawingListener {
        void onDrawing(boolean drawing);
    }

    private static final long LIVE_SEND_MS = 300;
    private static final int MIN_STEP = 10;
    private static final int MAX_PAIRS = 1200;
    private static final long BAKE_DELAY_MS = 150;
    /** Контуры в каждом кадре тоже не бесплатны: больше стольких держать невыгодно. */
    private static final int BAKE_MAX = 6;
    private static final long PLAYBACK_STALE_MS = 12_000;
    private static final long RENDER_GAP_MS = 100;
    private static final long FRESH_SEC = 20;

    private final App app = App.get();
    private final Paint paint = Renderer.newPaint();
    private final Path tmpPath = new Path();
    private Bitmap bg;
    private Bitmap layer;
    private Bitmap spare;
    private Canvas layerCanvas;
    private int width;
    private int height;
    /** Размер картинки рисунка: на слабых телефонах меньше экрана. */
    private int layerW;
    private int layerH;
    private final Rect screenRect = new Rect();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Device device;
    private DrawingListener drawingListener;
    private int renderedRevision = -1;
    private boolean rendering;
    private boolean renderDirty;
    private boolean shown = true;

    /**
     * Законченные штрихи, ещё не впечатанные в картинку. Каждая запись в большую картинку заново
     * отправляет её в видеокарту, и на слабых телефонах это заметный рывок. Поэтому штрихи
     * пара штрихов рисуется как контуры, а в картинку ложится, когда палец поднят.
     */
    private static final class Baking {
        final Stroke stroke;
        final Path path;

        Baking(Stroke stroke, Path path) {
            this.stroke = stroke;
            this.path = path;
        }
    }

    private final List<Baking> pending = new ArrayList<>();
    private int seenClearEpoch;

    /** Штрихи друга, которые ещё проигрываются. */
    private final LinkedHashMap<String, Playback> playing = new LinkedHashMap<>();
    private final LinkedHashMap<String, Stroke> playingStrokes = new LinkedHashMap<>();
    private long lastFrame;

    private int color = 0xFFFFFFFF;
    private int brush = 120;
    private boolean eraser;

    private boolean drawing;
    private String curId;
    private int[] cur = new int[512];
    private int curCount;
    private int sentCount;
    private int seq;
    private long downTime;
    private int lastPointMs;
    private int sentMs;
    private final Path curPath = new Path();
    private float curX;
    private float curY;
    private float midX;
    private float midY;

    private final Runnable liveSender = new Runnable() {
        @Override
        public void run() {
            if (!drawing) return;
            flushLive();
            postDelayed(this, LIVE_SEND_MS);
        }
    };
    private final Runnable renderLater = this::renderIfNeeded;
    private final Runnable bakeLater = this::bakeNow;

    public CanvasView(Context c, AttributeSet a) {
        super(c, a);
        device = Device.get(c);
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
        seenClearEpoch = app.repo.clearEpoch();
        app.repo.addListener(this);
        refreshIfStale();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        finishStroke();
        app.repo.removeListener(this);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        shown = visibility == VISIBLE;
        if (!shown && !playing.isEmpty()) {
            // Никто не смотрит: проигрывать незачем, штрихи сразу ложатся на лист.
            playing.clear();
            playingStrokes.clear();
            requestRender();
        }
        if (shown) refreshIfStale();
    }

    /** Лист мог измениться, пока окно не было видно. */
    void refreshIfStale() {
        if (layer != null && renderedRevision != app.repo.revision()) requestRender();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) return;
        width = w;
        height = h;
        screenRect.set(0, 0, w, h);
        float scale = Math.min(1f, (float) device.maxWidth / w);
        layerW = Math.round(w * scale);
        layerH = Math.round(h * scale);
        // Растягивать со сглаживанием нужно, только если картинка меньше экрана.
        bitmapPaint.setFilterBitmap(scale < 1f);
        spare = null;
        layer = Bitmap.createBitmap(layerW, layerH, Bitmap.Config.ARGB_8888);
        layerCanvas = new Canvas(layer);
        final Context context = getContext();
        final int lw = layerW;
        final int lh = layerH;
        app.render.execute(() -> {
            final Bitmap loaded = Bg.load(context, lw, lh);
            post(() -> {
                if (w == width && h == height) {
                    bg = loaded;
                    invalidate();
                }
            });
        });
        requestRender();
        if (Build.VERSION.SDK_INT >= 29) {
            // Движение от края экрана — это рисование, а не жест «назад».
            float density = getResources().getDisplayMetrics().density;
            int band = (int) (200 * density);
            int edge = (int) (40 * density);
            int top = Math.max(0, h / 2 - band / 2);
            List<Rect> rects = new ArrayList<>();
            rects.add(new Rect(0, top, edge, top + band));
            rects.add(new Rect(w - edge, top, w, top + band));
            setSystemGestureExclusionRects(rects);
        }
    }

    // ---------- Сборка картинки в отдельном потоке ----------

    private void requestRender() {
        renderDirty = true;
        if (!rendering) {
            removeCallbacks(renderLater);
            postDelayed(renderLater, 0);
        }
    }

    private void renderIfNeeded() {
        if (rendering || !renderDirty || layer == null) return;
        rendering = true;
        renderDirty = false;
        final int w = layerW;
        final int h = layerH;
        final int revision = app.repo.revision();
        final List<Stroke> list = app.repo.strokes();
        final Set<String> skip = new HashSet<>();
        final List<String> included = new ArrayList<>();
        for (Playback p : playing.values()) {
            if (p.isFinished() && app.repo.contains(p.id)) included.add(p.id);
            else skip.add(p.id);
        }
        final List<Baking> pendingAtStart = new ArrayList<>(pending);
        final Bitmap target = spare;
        spare = null;
        app.render.execute(() -> {
            Bitmap out = target;
            if (out == null) out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            else out.eraseColor(0);
            Canvas c = new Canvas(out);
            Paint p = Renderer.newPaint();
            for (Stroke s : list) {
                if (!skip.contains(s.id)) Renderer.draw(c, s, w, h, p);
            }
            final Bitmap done = out;
            post(() -> onRendered(done, revision, included, pendingAtStart, w, h));
        });
    }

    private void onRendered(Bitmap out, int revision, List<String> included, List<Baking> covered, int w, int h) {
        rendering = false;
        if (w != layerW || h != layerH) {
            renderIfNeeded();
            return;
        }
        spare = layer;
        layer = out;
        layerCanvas = new Canvas(layer);
        pending.removeAll(covered);
        for (String id : included) {
            playing.remove(id);
            playingStrokes.remove(id);
        }
        // Всё, что появилось во время сборки, лежит в очереди или запросило новую сборку.
        renderedRevision = renderDirty ? revision : app.repo.revision();
        invalidate();
        if (renderDirty) postDelayed(renderLater, RENDER_GAP_MS);
    }

    // ---------- Показ ----------

    @Override
    protected void onDraw(Canvas c) {
        if (bg != null) c.drawBitmap(bg, null, screenRect, bitmapPaint);
        else c.drawColor(Bg.SHEET);
        if (layer == null) return;

        long now = SystemClock.uptimeMillis();
        boolean animating = advancePlayback(now);

        boolean anyErase = drawing && eraser;
        for (Playback p : playing.values()) anyErase |= p.erase;
        for (Baking b : pending) anyErase |= b.stroke.erase;
        // Отдельный слой нужен только ластику: он стирает рисунок, но не подложку.
        int save = anyErase ? c.saveLayer(null, null) : -1;
        c.drawBitmap(layer, null, screenRect, bitmapPaint);
        for (Baking b : pending) {
            if (b.path != null) {
                Renderer.setup(paint, b.stroke.color, b.stroke.width, b.stroke.erase, width);
                c.drawPath(b.path, paint);
            } else {
                Renderer.draw(c, b.stroke, width, height, paint);
            }
        }
        for (Playback p : playing.values()) {
            if (p.hasPoints()) Renderer.drawPartial(c, tmpPath, p, width, height, paint);
        }
        if (drawing && curCount > 0) drawCurrent(c);
        if (save != -1) c.restoreToCount(save);

        if (animating) postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    private boolean advancePlayback(long now) {
        if (playing.isEmpty()) return false;
        float dt = lastFrame == 0 ? 16f : Math.min(now - lastFrame, 80);
        lastFrame = now;
        boolean animating = false;
        for (Playback p : new ArrayList<>(playing.values())) {
            boolean wasFinished = p.isFinished();
            p.advance(dt, now);
            if (p.isFinished()) {
                if (!wasFinished) commitPlayback(p);
                continue;
            }
            if (!p.hasFinal() && now - p.lastDataAt() > PLAYBACK_STALE_MS) {
                // Штрих оборвался: если он был закончен, его досылка всё равно придёт.
                playing.remove(p.id);
                continue;
            }
            animating = true;
        }
        return animating;
    }

    private void commitPlayback(Playback p) {
        Stroke s = playingStrokes.get(p.id);
        if (s != null && !rendering && !renderDirty && app.repo.isLast(s)) {
            addPending(s);
            playing.remove(p.id);
            playingStrokes.remove(p.id);
        } else {
            // Штрих уже виден целиком; лист перестроится с ним в нужном порядке.
            requestRender();
        }
    }

    private void addPending(Stroke s) {
        pending.add(new Baking(s, Renderer.buildPath(s.pts, width, height)));
        if (!rendering && !renderDirty) renderedRevision = app.repo.revision();
        removeCallbacks(bakeLater);
        postDelayed(bakeLater, BAKE_DELAY_MS);
        invalidate();
    }

    private void bakeNow() {
        if (pending.isEmpty() || layerCanvas == null) return;
        if (rendering || (drawing && pending.size() < BAKE_MAX)) {
            postDelayed(bakeLater, BAKE_DELAY_MS);
            return;
        }
        for (Baking b : pending) Renderer.draw(layerCanvas, b.stroke, layerW, layerH, paint);
        pending.clear();
        invalidate();
    }

    private void drawCurrent(Canvas c) {
        Renderer.setup(paint, color, brush, eraser, width);
        if (curCount < 4) {
            c.drawPoint(curX, curY, paint);
            return;
        }
        c.drawPath(curPath, paint);
        c.drawLine(midX, midY, curX, curY, paint);
    }

    // ---------- Касания ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Точки приходят сразу, а не раз в кадр: линия успевает за пальцем.
                if (device.unbufferedTouch) requestUnbufferedDispatch(e);
                startStroke(e.getEventTime());
                addPoint(e.getX(), e.getY(), e.getEventTime());
                postDelayed(liveSender, LIVE_SEND_MS);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!drawing) return true;
                for (int i = 0; i < e.getHistorySize(); i++) {
                    addPoint(e.getHistoricalX(0, i), e.getHistoricalY(0, i), e.getHistoricalEventTime(i));
                }
                addPoint(e.getX(0), e.getY(0), e.getEventTime());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (drawing) {
                    addPoint(e.getX(0), e.getY(0), e.getEventTime());
                    finishStroke();
                }
                return true;
            default:
                return true;
        }
    }

    private void startStroke(long time) {
        drawing = true;
        curId = Stroke.newId();
        curCount = 0;
        sentCount = 0;
        seq = 0;
        sentMs = 0;
        lastPointMs = 0;
        downTime = time;
        curPath.rewind();
        if (drawingListener != null) drawingListener.onDrawing(true);
    }

    private void addPoint(float x, float y, long time) {
        int qx = Renderer.qx(x, width);
        int qy = Renderer.qy(y, width, height);
        if (curCount >= 2 && Math.abs(qx - cur[curCount - 2]) < MIN_STEP
                && Math.abs(qy - cur[curCount - 1]) < MIN_STEP) {
            return;
        }
        if (curCount + 2 > cur.length) cur = Arrays.copyOf(cur, cur.length * 2);
        cur[curCount++] = qx;
        cur[curCount++] = qy;
        lastPointMs = (int) (time - downTime);
        if (curCount == 2) {
            curPath.moveTo(x, y);
            midX = x;
            midY = y;
        } else {
            midX = (curX + x) / 2f;
            midY = (curY + y) / 2f;
            curPath.quadTo(curX, curY, midX, midY);
        }
        curX = x;
        curY = y;
        if (curCount >= MAX_PAIRS * 2) {
            // Очень длинный штрих делим на части: у ретрансляторов есть предел размера события.
            commit();
            curId = Stroke.newId();
            cur[0] = qx;
            cur[1] = qy;
            curCount = 2;
            sentCount = 0;
            seq = 0;
            sentMs = 0;
            downTime = time;
            lastPointMs = 0;
            curPath.rewind();
            curPath.moveTo(x, y);
            midX = x;
            midY = y;
        }
    }

    private void flushLive() {
        if (curCount <= sentCount) return;
        int from = Math.max(0, sentCount - 2);
        app.sync.sendLive(curId, color, brush, eraser, seq++, Arrays.copyOfRange(cur, from, curCount),
                sentMs, lastPointMs);
        sentCount = curCount;
        sentMs = lastPointMs;
    }

    private void commit() {
        if (curCount >= 2) {
            app.repo.addMine(curId, color, brush, eraser, Arrays.copyOf(cur, curCount), lastPointMs);
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
    public void onLiveChunk(LiveChunk c) {
        if (!shown || layer == null) return;
        Playback p = playing.get(c.id);
        if (p == null) {
            if (playing.size() >= device.maxPlaying) return;
            p = new Playback(c.id, c.color, c.width, c.erase);
            playing.put(c.id, p);
        }
        p.addChunk(c, SystemClock.uptimeMillis());
        postInvalidateOnAnimation();
    }

    @Override
    public void onStrokeAdded(Stroke s, boolean atEnd) {
        if (layer == null) return;
        long now = SystemClock.uptimeMillis();
        Playback p = playing.get(s.id);
        if (p != null) {
            // Живые куски уже показаны, готовый штрих просто дополняет их.
            p.setFinal(s.pts, s.duration, now);
            playingStrokes.put(s.id, s);
            postInvalidateOnAnimation();
            return;
        }
        boolean fresh = !s.mine && shown && playing.size() < device.maxPlaying
                && Math.abs(System.currentTimeMillis() / 1000 - s.sec) <= FRESH_SEC;
        if (fresh) {
            p = new Playback(s.id, s.color, s.width, s.erase);
            p.setFinal(s.pts, s.duration, now);
            playing.put(s.id, p);
            playingStrokes.put(s.id, s);
            postInvalidateOnAnimation();
            return;
        }
        if (!atEnd) {
            requestRender();
            return;
        }
        addPending(s);
    }

    @Override
    public void onReset() {
        if (seenClearEpoch != app.repo.clearEpoch()) {
            seenClearEpoch = app.repo.clearEpoch();
            playing.clear();
            playingStrokes.clear();
            pending.clear();
        } else {
            pending.removeIf(b -> !app.repo.contains(b.stroke.id));
            for (String id : new ArrayList<>(playingStrokes.keySet())) {
                if (!app.repo.contains(id)) {
                    playing.remove(id);
                    playingStrokes.remove(id);
                }
            }
        }
        requestRender();
    }
}

package app.holst;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Обои, на которых можно рисовать. Android не передаёт настоящим обоям касания на экране
 * блокировки, поэтому поверх него лежит окно с той же самой картинкой: выглядит и работает как
 * обои, а всё нарисованное сразу уходит другу и в настоящие обои.
 */
public final class LockActivity extends Activity implements Repo.Listener {
    private static final int[] COLORS = {
        0xFFFFFFFF, 0xFF111114, 0xFFFFD60A, 0xFFFF453A, 0xFF32D74B, 0xFF0A84FF, 0xFFFF5FC4,
    };
    private static final int[] WIDTHS = {60, 120, 260};
    private static final float[] DOT_RADII = {4f, 7f, 11f};
    private static final long CHROME_BACK_MS = 500;
    private static final long CONFIRM_MS = 3000;

    private static LockActivity alive;
    private static boolean visible;

    private App app;
    private CanvasView canvas;
    private View clockBox;
    private View bar;
    private View panel;
    private TextView friendChip;
    private TextView clearButton;
    private ColorDot currentDot;
    private final List<ColorDot> colorDots = new ArrayList<>();
    private final List<ColorDot> sizeDots = new ArrayList<>();
    private ColorDot eraserDot;
    private boolean confirmClear;

    private final Runnable chromeBack = () -> setChromeVisible(true);
    private final Runnable clearReset = () -> {
        confirmClear = false;
        clearButton.setText(R.string.clear_all);
        clearButton.setTextColor(Color.WHITE);
    };

    static boolean isVisible() {
        return visible;
    }

    static void dismiss() {
        LockActivity a = alive;
        if (a != null && !a.isFinishing()) {
            a.finish();
            a.overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        alive = this;
        app = App.get();
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(false);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        setContentView(R.layout.activity_lock);

        canvas = findViewById(R.id.canvas);
        clockBox = findViewById(R.id.clock_box);
        bar = findViewById(R.id.bar);
        panel = findViewById(R.id.panel);
        friendChip = findViewById(R.id.friend_chip);
        currentDot = findViewById(R.id.current_dot);
        clearButton = findViewById(R.id.clear_all);

        UnlockHandle handle = findViewById(R.id.handle);
        handle.setOnUnlock(this::unlock);
        canvas.setDrawingListener(this::onDrawing);
        currentDot.setOnClickListener(v -> togglePanel());
        ImageView undo = findViewById(R.id.undo);
        undo.setOnClickListener(v -> app.repo.undoMine());
        clearButton.setOnClickListener(v -> onClearTapped());

        drawEdgeToEdge();
        buildPanel();
        applyBrush();
    }

    /**
     * Рисуем на весь экран, но системные панели не прячем: при скрытии Android показывает поверх
     * окна подсказку «Viewing full screen», которая закрывает и затемняет холст.
     */
    private void drawEdgeToEdge() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        // Панель инструментов не должна попадать под кнопки навигации.
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            int nav = insets.getSystemWindowInsetBottom();
            int bottom = Math.max(dp(20), nav + dp(2));
            FrameLayout.LayoutParams barLp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            barLp.bottomMargin = bottom;
            bar.setLayoutParams(barLp);
            FrameLayout.LayoutParams panelLp = (FrameLayout.LayoutParams) panel.getLayoutParams();
            panelLp.bottomMargin = bottom + dp(88);
            panel.setLayoutParams(panelLp);
            return insets;
        });
    }

    private void buildPanel() {
        LinearLayout colors = findViewById(R.id.colors_row);
        for (int i = 0; i < COLORS.length; i++) {
            ColorDot d = new ColorDot(this);
            d.setColor(COLORS[i]);
            final int index = i;
            d.setOnClickListener(v -> {
                app.prefs.setColorIndex(index);
                app.prefs.setEraser(false);
                applyBrush();
                setPanelOpen(false);
            });
            colors.addView(d, new LinearLayout.LayoutParams(0, dp(46), 1f));
            colorDots.add(d);
        }
        eraserDot = new ColorDot(this);
        eraserDot.setEraser(true);
        eraserDot.setContentDescription(getString(R.string.eraser));
        eraserDot.setOnClickListener(v -> {
            app.prefs.setEraser(true);
            applyBrush();
            setPanelOpen(false);
        });
        colors.addView(eraserDot, new LinearLayout.LayoutParams(0, dp(46), 1f));

        LinearLayout sizes = findViewById(R.id.sizes_row);
        for (int i = 0; i < WIDTHS.length; i++) {
            ColorDot d = new ColorDot(this);
            d.setRadiusDp(DOT_RADII[i]);
            final int index = i;
            d.setOnClickListener(v -> {
                app.prefs.setSizeIndex(index);
                applyBrush();
            });
            sizes.addView(d, i, new LinearLayout.LayoutParams(dp(46), dp(46)));
            sizeDots.add(d);
        }
    }

    private void applyBrush() {
        int colorIndex = clamp(app.prefs.colorIndex(), COLORS.length);
        int sizeIndex = clamp(app.prefs.sizeIndex(), WIDTHS.length);
        boolean eraser = app.prefs.eraser();
        int color = COLORS[colorIndex];
        canvas.setBrush(color, eraser ? WIDTHS[sizeIndex] * 3 : WIDTHS[sizeIndex], eraser);
        for (int i = 0; i < colorDots.size(); i++) colorDots.get(i).setPicked(!eraser && i == colorIndex);
        eraserDot.setPicked(eraser);
        for (int i = 0; i < sizeDots.size(); i++) {
            ColorDot d = sizeDots.get(i);
            d.setColor(eraser ? 0xFFFFFFFF : color);
            d.setPicked(i == sizeIndex);
        }
        currentDot.setEraser(eraser);
        currentDot.setColor(color);
    }

    private static int clamp(int v, int size) {
        return v < 0 || v >= size ? 0 : v;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void togglePanel() {
        setPanelOpen(panel.getVisibility() != View.VISIBLE);
    }

    private void setPanelOpen(boolean open) {
        panel.setVisibility(open ? View.VISIBLE : View.GONE);
    }

    private void onClearTapped() {
        if (!confirmClear) {
            confirmClear = true;
            clearButton.setText(R.string.clear_confirm);
            clearButton.setTextColor(0xFFFF6B60);
            clearButton.postDelayed(clearReset, CONFIRM_MS);
        } else {
            clearButton.removeCallbacks(clearReset);
            clearReset.run();
            app.repo.clearAll();
            setPanelOpen(false);
        }
    }

    private void onDrawing(boolean drawing) {
        canvas.removeCallbacks(chromeBack);
        if (drawing) {
            setPanelOpen(false);
            setChromeVisible(false);
        } else {
            canvas.postDelayed(chromeBack, CHROME_BACK_MS);
        }
    }

    private void setChromeVisible(boolean show) {
        float alpha = show ? 1f : 0f;
        long time = show ? 220 : 120;
        clockBox.animate().alpha(alpha).setDuration(time).start();
        bar.animate().alpha(alpha).setDuration(time).start();
    }

    private void unlock() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (!km.isKeyguardLocked()) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                finish();
                overridePendingTransition(0, 0);
            }

            @Override
            public void onDismissError() {
                // Показать обычный экран блокировки: на нём разблокировка точно доступна.
                finish();
                overridePendingTransition(0, 0);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        app.repo.addListener(this);
        app.sync.start();
        app.sync.catchUp();
        canvas.refreshIfStale();
        onLiveChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        app.repo.removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (alive == this) alive = null;
    }

    @Override
    public void onBackPressed() {
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onStrokeAdded(Stroke s, boolean atEnd) {}

    @Override
    public void onReset() {}

    @Override
    public void onLiveChanged() {
        boolean friendDrawing = !app.repo.liveStrokes().isEmpty();
        friendChip.setVisibility(friendDrawing ? View.VISIBLE : View.INVISIBLE);
    }
}

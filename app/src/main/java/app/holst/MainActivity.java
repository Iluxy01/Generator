package app.holst;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Главный экран: вид холста, код и две кнопки. Смена обоев и выключение — по нажатию на холст. */
public final class MainActivity extends Activity {
    private static final int REQ_PICK = 1;

    private App app;
    private PreviewView preview;
    private TextView codeView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = App.get();
        if (!app.prefs.onboarded()) {
            if (app.prefs.chosen() && Settings.canDrawOverlays(this)) {
                // Обновились со старой версии: всё уже настроено.
                app.prefs.setOnboarded(true);
            } else {
                startActivity(new Intent(this, OnboardingActivity.class));
                finish();
                return;
            }
        }
        setContentView(R.layout.activity_main);
        preview = findViewById(R.id.preview);
        codeView = findViewById(R.id.code);
        preview.setOnClickListener(v -> showCanvasMenu());
        findViewById(R.id.share).setOnClickListener(v -> copyCode());
        findViewById(R.id.join).setOnClickListener(v -> askFriendCode());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (preview == null) return;
        app.sync.start();
        app.sync.catchUp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preview == null) return;
        if (app.prefs.enabled()) CanvasService.start(this);
        codeView.setText(Room.pretty(app.prefs.code()));
        preview.refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (preview != null && !app.prefs.enabled()) app.sync.stop();
    }

    private void copyCode() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), app.prefs.code()));
        // На Android 13 и новее система сама показывает, что текст скопирован.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show();
    }

    private void askFriendCode() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(20)});
        input.setHint(R.string.join_hint);
        input.setSingleLine(true);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.join_title)
                .setMessage(R.string.join_message)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.join_ok, (d, w) -> {
                    String code = Room.normalize(input.getText().toString());
                    if (code.length() != 12) {
                        Toast.makeText(this, R.string.join_bad, Toast.LENGTH_LONG).show();
                        return;
                    }
                    app.joinRoom(code);
                    app.markWallpaperDirty();
                    codeView.setText(Room.pretty(code));
                    preview.refresh();
                    if (app.prefs.enabled()) CanvasService.start(this);
                    Toast.makeText(this, R.string.join_done, Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void showCanvasMenu() {
        boolean enabled = app.prefs.enabled();
        CharSequence[] items = {
            getString(R.string.menu_picture),
            getString(R.string.blank_sheet),
            getString(R.string.menu_settings),
            getString(enabled ? R.string.menu_off : R.string.menu_on),
        };
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    startPicker();
                    break;
                case 1:
                    app.disk.execute(() -> {
                        Bg.useBlankSheet(this);
                        runOnUiThread(this::onBackgroundChanged);
                    });
                    break;
                case 2:
                    startActivity(new Intent(this, OnboardingActivity.class));
                    break;
                default:
                    setCanvasEnabled(!enabled);
            }
        }).show();
    }

    private void startPicker() {
        try {
            startActivityForResult(Bg.pickIntent(), REQ_PICK);
        } catch (RuntimeException e) {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        app.disk.execute(() -> {
            final boolean ok = Bg.importPicture(this, uri);
            runOnUiThread(() -> {
                if (ok) onBackgroundChanged();
                else Toast.makeText(this, R.string.picture_failed, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void onBackgroundChanged() {
        app.enableCanvas();
        preview.refresh();
    }

    private void setCanvasEnabled(boolean on) {
        if (on) {
            app.enableCanvas();
            return;
        }
        app.prefs.setEnabled(false);
        CanvasService.stop(this);
        WatchdogJob.cancel(this);
        LockActivity.dismiss();
        app.disk.execute(() -> Bg.restoreLockWallpaper(this));
        Toast.makeText(this, R.string.canvas_off, Toast.LENGTH_LONG).show();
    }
}

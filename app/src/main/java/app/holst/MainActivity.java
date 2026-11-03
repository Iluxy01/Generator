package app.holst;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** Настройка: что лежит под рисунками, с кем общий лист и какие нужны разрешения. */
public final class MainActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_NOTIFICATIONS = 2;
    private static final String APK_LINK =
            "https://github.com/Iluxy01/Generator/releases/latest/download/Holst.apk";

    private App app;
    private PreviewView preview;
    private TextView stepTitle;
    private TextView codeView;
    private TextView statusView;
    private TextView peerView;
    private Switch enabledSwitch;
    private View permissionsCard;
    private Button overlayButton;
    private Button notificationsButton;
    private Button batteryButton;
    private TextView oemHint;
    private boolean bindingSwitch;

    private final Runnable statusListener = this::refreshStatus;
    private final Runnable statusTimer = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            statusView.postDelayed(this, 10_000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = App.get();
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        stepTitle = findViewById(R.id.step_title);
        codeView = findViewById(R.id.code);
        statusView = findViewById(R.id.status);
        peerView = findViewById(R.id.peer_status);
        enabledSwitch = findViewById(R.id.enabled);
        permissionsCard = findViewById(R.id.permissions_card);
        overlayButton = findViewById(R.id.perm_overlay);
        notificationsButton = findViewById(R.id.perm_notifications);
        batteryButton = findViewById(R.id.perm_battery);
        oemHint = findViewById(R.id.oem_hint);

        findViewById(R.id.pick).setOnClickListener(v -> pickPicture());
        findViewById(R.id.blank).setOnClickListener(v -> useBlankSheet());
        findViewById(R.id.share).setOnClickListener(v -> shareCode());
        findViewById(R.id.join).setOnClickListener(v -> askFriendCode());
        findViewById(R.id.try_it).setOnClickListener(v ->
                startActivity(new Intent(this, LockActivity.class)));
        overlayButton.setOnClickListener(v -> openOverlaySettings());
        notificationsButton.setOnClickListener(v -> requestNotifications());
        batteryButton.setOnClickListener(v -> openBatterySettings());
        enabledSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!bindingSwitch) setCanvasEnabled(checked);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        app.sync.addStatusListener(statusListener);
        app.sync.start();
        app.sync.catchUp();
        statusView.post(statusTimer);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (app.prefs.enabled()) CanvasService.start(this);
        refreshAll();
        preview.refresh();
    }

    @Override
    protected void onStop() {
        super.onStop();
        app.sync.removeStatusListener(statusListener);
        statusView.removeCallbacks(statusTimer);
        if (!app.prefs.enabled()) app.sync.stop();
    }

    private void refreshAll() {
        boolean chosen = app.prefs.chosen();
        boolean enabled = app.prefs.enabled();
        stepTitle.setText(chosen ? R.string.step_change_bg : R.string.step_pick_bg);
        codeView.setText(Room.pretty(app.prefs.code()));
        bindingSwitch = true;
        enabledSwitch.setChecked(enabled);
        bindingSwitch = false;
        enabledSwitch.setEnabled(chosen);
        refreshStatus();

        boolean overlay = Settings.canDrawOverlays(this);
        boolean notifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean battery = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        bindPermission(overlayButton, overlay);
        bindPermission(notificationsButton, notifications);
        bindPermission(batteryButton, battery);
        permissionsCard.setVisibility(chosen ? View.VISIBLE : View.GONE);

        String maker = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        boolean xiaomi = maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
        oemHint.setVisibility(xiaomi ? View.VISIBLE : View.GONE);
    }

    private void bindPermission(Button b, boolean granted) {
        b.setEnabled(!granted);
        b.setText(granted ? R.string.perm_ready : R.string.perm_allow);
        b.setAlpha(granted ? 0.55f : 1f);
    }

    private void refreshStatus() {
        int open = app.sync.openCount();
        int total = app.sync.totalCount();
        statusView.setText(open > 0
                ? getString(R.string.status_online, open, total)
                : getString(R.string.status_offline));
        statusView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                open > 0 ? R.drawable.dot_on : R.drawable.dot_off, 0, 0, 0);

        long seen = app.sync.peerSeenAt();
        if (app.sync.peerOnline()) {
            peerView.setText(R.string.peer_online);
        } else if (seen != 0) {
            long minutes = Math.max(1, (System.currentTimeMillis() - seen) / 60_000);
            peerView.setText(getString(R.string.peer_seen, minutes));
        } else {
            peerView.setText(R.string.peer_none);
        }
    }

    // ---------- Подложка ----------

    private void pickPicture() {
        Intent i = Build.VERSION.SDK_INT >= 33
                ? new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_PICK);
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
                if (ok) onBackgroundChosen();
                else Toast.makeText(this, R.string.picture_failed, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void useBlankSheet() {
        app.disk.execute(() -> {
            Bg.useBlankSheet(this);
            runOnUiThread(this::onBackgroundChosen);
        });
    }

    /** Выбрана подложка — экран блокировки становится холстом. */
    private void onBackgroundChosen() {
        boolean first = !app.prefs.chosen();
        app.prefs.setChosen(true);
        app.markWallpaperDirty();
        preview.refresh();
        if (!app.prefs.enabled()) setCanvasEnabled(true);
        else CanvasService.start(this);
        refreshAll();
        if (first) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotifications();
            } else if (!Settings.canDrawOverlays(this)) {
                explainOverlay();
            }
        }
    }

    private void setCanvasEnabled(boolean on) {
        app.prefs.setEnabled(on);
        if (on) {
            app.markWallpaperDirty();
            CanvasService.start(this);
        } else {
            CanvasService.stop(this);
            WatchdogJob.cancel(this);
            LockActivity.dismiss();
            app.disk.execute(() -> Bg.restoreLockWallpaper(this));
            Toast.makeText(this, R.string.canvas_off, Toast.LENGTH_LONG).show();
        }
        refreshAll();
    }

    // ---------- Друг ----------

    private void shareCode() {
        String text = getString(R.string.share_text, APK_LINK, Room.pretty(app.prefs.code()));
        startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null));
    }

    private void askFriendCode() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(12)});
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
                    refreshAll();
                    preview.refresh();
                    Toast.makeText(this, R.string.join_done, Toast.LENGTH_LONG).show();
                })
                .show();
    }

    // ---------- Разрешения ----------

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshAll();
        if (requestCode == REQ_NOTIFICATIONS && !Settings.canDrawOverlays(this)) explainOverlay();
    }

    private void explainOverlay() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_title)
                .setMessage(R.string.overlay_message)
                .setNegativeButton(R.string.later, null)
                .setPositiveButton(R.string.open_settings, (d, w) -> openOverlaySettings())
                .show();
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}

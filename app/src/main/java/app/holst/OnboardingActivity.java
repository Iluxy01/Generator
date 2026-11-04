package app.holst;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

/** Первый запуск: всё, что нужно настроить, на одном экране. Состояние обновляется само. */
public final class OnboardingActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int REQ_NOTIFICATIONS = 2;

    private App app;
    private TextView bgStatus;
    private Button overlay;
    private Button notifications;
    private Button battery;
    private Button start;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = App.get();
        setContentView(R.layout.activity_onboarding);
        bgStatus = findViewById(R.id.bg_status);
        overlay = findViewById(R.id.perm_overlay);
        notifications = findViewById(R.id.perm_notifications);
        battery = findViewById(R.id.perm_battery);
        start = findViewById(R.id.start);

        findViewById(R.id.pick).setOnClickListener(v -> startPicker());
        findViewById(R.id.blank).setOnClickListener(v -> app.disk.execute(() -> {
            Bg.useBlankSheet(this);
            runOnUiThread(this::onBackgroundChosen);
        }));
        overlay.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));
        notifications.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            }
        });
        battery.setOnClickListener(v -> openBatterySettings());
        start.setOnClickListener(v -> finishSetup());

        if (Build.VERSION.SDK_INT < 33) findViewById(R.id.row_notifications).setVisibility(View.GONE);
        String maker = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        boolean xiaomi = maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
        findViewById(R.id.oem_hint).setVisibility(xiaomi ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private boolean notificationsGranted() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        boolean chosen = app.prefs.chosen();
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean notificationsOk = notificationsGranted();
        boolean batteryOk = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        bgStatus.setText(!chosen ? R.string.onboarding_bg_hint
                : Bg.hasPicture(this) ? R.string.onboarding_bg_picture : R.string.onboarding_bg_blank);
        bind(overlay, overlayOk);
        bind(notifications, notificationsOk);
        bind(battery, batteryOk);
        start.setEnabled(chosen);
        start.setAlpha(chosen ? 1f : 0.45f);
        start.setText(overlayOk && notificationsOk && batteryOk
                ? R.string.onboarding_start : R.string.onboarding_start_partial);
    }

    private void bind(Button b, boolean granted) {
        b.setEnabled(!granted);
        b.setText(granted ? R.string.perm_ready : R.string.perm_allow);
        b.setAlpha(granted ? 0.55f : 1f);
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
                if (ok) onBackgroundChosen();
                else Toast.makeText(this, R.string.picture_failed, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void onBackgroundChosen() {
        app.enableCanvas();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void finishSetup() {
        app.prefs.setOnboarded(true);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}

package app.holst;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import java.util.List;

/**
 * Держит связь с другом, обновляет настоящие обои и выводит холст на экран блокировки: как только
 * экран гаснет, слой с холстом уже подготовлен, и при включении он сразу на месте.
 */
public final class CanvasService extends Service implements Repo.Listener {
    private static final String TAG = "Holst";
    private static final int ID_SERVICE = 1;
    private static final int ID_FRIEND = 2;
    private static final int ID_LAYER = 3;
    private static final long WALLPAPER_DEBOUNCE_MS = 2500;
    private static final long FRIEND_NOTIFY_EVERY_MS = 60_000;

    private App app;
    private long lastFriendNotify;
    private int writtenRevision = -1;
    private boolean writeRunning;

    private final Runnable writeWallpaper = this::writeWallpaperNow;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                onScreenOn();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                LockActivity.dismiss();
                cancelLayerNotification();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network n) {
            app.main.post(() -> app.sync.onNetworkChanged());
        }
    };

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, CanvasService.class));
        } catch (RuntimeException e) {
            // На Android 12+ запуск из фона может быть запрещён; следующая попытка — при открытии
            // приложения или по сторожевому заданию.
            Log.w(TAG, "service start refused", e);
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, CanvasService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        app = App.get();
        goForeground();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screen, f);
        }
        try {
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(network);
        } catch (RuntimeException e) {
            Log.w(TAG, "no network callback", e);
        }
        app.repo.addListener(this);
        app.sync.start();
        WatchdogJob.schedule(this);
        requestWallpaper(0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (!app.prefs.enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        requestWallpaper(0);
        return START_STICKY;
    }

    private void goForeground() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, App.CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.service_title))
                .setContentText(getString(R.string.service_text))
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_SERVICE, n);
        }
    }

    // ---------- Экран ----------

    private boolean inCall() {
        int mode = getSystemService(AudioManager.class).getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE;
    }

    private void onScreenOff() {
        // Экран погас: обои должны быть свежими ещё до следующего включения.
        app.main.removeCallbacks(writeWallpaper);
        writeWallpaperNow();
        if (!inCall() && Settings.canDrawOverlays(this)) launchLayer();
    }

    private void onScreenOn() {
        app.sync.catchUp();
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (!km.isKeyguardLocked()) {
            // Телефон ещё не успел заблокироваться — холст не нужен, вернём человека к его делам.
            LockActivity.dismiss();
            return;
        }
        if (inCall() || LockActivity.isVisible()) return;
        if (Settings.canDrawOverlays(this)) launchLayer();
        else launchLayerByNotification();
    }

    private void launchLayer() {
        try {
            Intent i = new Intent(this, LockActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            startActivity(i, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle());
        } catch (RuntimeException e) {
            Log.w(TAG, "layer start refused", e);
        }
    }

    /** Запасной путь без разрешения «поверх окон»: полноэкранное уведомление. */
    private void launchLayerByNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) return;
        PendingIntent pi = PendingIntent.getActivity(this, 1,
                new Intent(this, LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, App.CH_LAYER)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.layer_notification))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(ID_LAYER, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "layer notification refused", e);
        }
    }

    private void cancelLayerNotification() {
        getSystemService(NotificationManager.class).cancel(ID_LAYER);
    }

    // ---------- Обои ----------

    private void requestWallpaper(long delay) {
        if (!app.prefs.enabled() || !app.prefs.chosen()) return;
        app.main.removeCallbacks(writeWallpaper);
        app.main.postDelayed(writeWallpaper, delay);
    }

    private void writeWallpaperNow() {
        final int revision = app.repo.revision();
        if (!app.prefs.enabled() || !app.prefs.chosen()) return;
        if (writeRunning) {
            requestWallpaper(WALLPAPER_DEBOUNCE_MS);
            return;
        }
        boolean dirty = app.consumeWallpaperDirty();
        if (revision == writtenRevision && !dirty) return;
        writeRunning = true;
        writtenRevision = revision;
        final List<Stroke> copy = app.repo.strokes();
        app.disk.execute(() -> {
            Bg.writeLockWallpaper(this, copy);
            app.main.post(() -> writeRunning = false);
        });
    }

    // ---------- Лист ----------

    @Override
    public void onStrokeAdded(Stroke s, boolean atEnd) {
        requestWallpaper(WALLPAPER_DEBOUNCE_MS);
        if (!s.mine) notifyFriend();
    }

    @Override
    public void onReset() {
        requestWallpaper(WALLPAPER_DEBOUNCE_MS);
    }

    @Override
    public void onLiveChanged() {
        // Живые штрихи показывает слой; в обои попадают только законченные.
    }

    private void notifyFriend() {
        if (LockActivity.isVisible()) return;
        long now = System.currentTimeMillis();
        if (now - lastFriendNotify < FRIEND_NOTIFY_EVERY_MS) return;
        lastFriendNotify = now;
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, App.CH_FRIEND)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.friend_title))
                .setContentText(getString(R.string.friend_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            getSystemService(NotificationManager.class).notify(ID_FRIEND, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "notify refused", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        app.main.removeCallbacks(writeWallpaper);
        app.repo.removeListener(this);
        app.repo.saveAsync();
        try {
            unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
            // Уже снят.
        }
        try {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(network);
        } catch (RuntimeException ignored) {
            // Не был зарегистрирован.
        }
        cancelLayerNotification();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

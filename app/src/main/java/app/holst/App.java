package app.holst;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class App extends Application {
    static final String CH_SERVICE = "service";
    static final String CH_FRIEND = "friend";
    static final String CH_LAYER = "layer";

    private static App self;

    final Handler main = new Handler(Looper.getMainLooper());
    /** Подписи и сеть: подпись занимает миллисекунды, но главный поток она тормозить не должна. */
    final ExecutorService net = Executors.newSingleThreadExecutor();
    /** Живые штрихи и сверка листов: не должны стоять в очереди за повторной отправкой. */
    final ExecutorService live = Executors.newSingleThreadExecutor();
    /** Сохранение листа, импорт картинки, запись обоев. */
    final ExecutorService disk = Executors.newSingleThreadExecutor();
    /** Перестроение картинки: главный поток занят касаниями. */
    final ExecutorService render = Executors.newSingleThreadExecutor();
    Prefs prefs;
    Repo repo;
    Sync sync;

    static App get() {
        return self;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        prefs = new Prefs(this);
        repo = new Repo(this, disk);
        sync = new Sync(this);

        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel service = new NotificationChannel(CH_SERVICE,
                getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN);
        service.setShowBadge(false);
        nm.createNotificationChannel(service);
        nm.createNotificationChannel(new NotificationChannel(CH_FRIEND,
                getString(R.string.channel_friend), NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel layer = new NotificationChannel(CH_LAYER,
                getString(R.string.channel_layer), NotificationManager.IMPORTANCE_HIGH);
        layer.setSound(null, null);
        layer.enableVibration(false);
        layer.setShowBadge(false);
        nm.createNotificationChannel(layer);
    }

    private volatile boolean wallpaperDirty = true;

    /** Подложка поменялась: обои надо переписать, даже если штрихи те же. */
    void markWallpaperDirty() {
        wallpaperDirty = true;
    }

    boolean isWallpaperDirty() {
        return wallpaperDirty;
    }

    boolean consumeWallpaperDirty() {
        boolean was = wallpaperDirty;
        wallpaperDirty = false;
        return was;
    }

    /** Подложка выбрана: экран блокировки становится холстом. */
    void enableCanvas() {
        prefs.setChosen(true);
        prefs.setEnabled(true);
        markWallpaperDirty();
        CanvasService.start(this);
    }

    /** Присоединиться к общему листу по коду друга. */
    void joinRoom(String code) {
        sync.stop();
        repo.reset();
        repo.saveAsync();
        prefs.setCode(code);
        sync.start();
    }
}

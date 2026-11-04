package app.holst;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Что может этот телефон. На слабых устройствах картинка холста считается в меньшем разрешении, и
 * это почти незаметно глазу, зато экономит память и время видеокарты.
 */
final class Device {
    private static Device instance;

    /** Самая большая ширина картинки в пикселях. */
    final int maxWidth;
    /** Сколько штрихов друга проигрывать одновременно. */
    final int maxPlaying;
    final boolean highRefresh;
    /** Получать касания чаще кадра: линия отзывчивее, но процессору работы больше. */
    final boolean unbufferedTouch;

    private Device(int maxWidth, int maxPlaying, boolean strong) {
        this.maxWidth = maxWidth;
        this.maxPlaying = maxPlaying;
        this.highRefresh = strong;
        this.unbufferedTouch = strong;
    }

    static synchronized Device get(Context c) {
        if (instance == null) {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            double gb = info.totalMem / (1024.0 * 1024 * 1024);
            if (am.isLowRamDevice() || gb < 3.2) instance = new Device(720, 2, false);
            else if (gb < 6.2) instance = new Device(1080, 3, true);
            else instance = new Device(1440, 4, true);
        }
        return instance;
    }
}

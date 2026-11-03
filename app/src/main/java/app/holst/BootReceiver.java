package app.holst;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки и обновления приложения холст включается сам. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (App.get().prefs.enabled()) CanvasService.start(context);
    }
}

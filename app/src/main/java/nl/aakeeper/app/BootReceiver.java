package nl.aakeeper.app;

import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!LogStore.isEnabled(context)) return;
        try {
            Intent svc = new Intent(context, KeeperService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc); else context.startService(svc);
            LogStore.add(context, "Autostart na boot/update aangevraagd.");
        } catch (Throwable t) {
            LogStore.add(context, "Autostart door Android/MagicOS geblokkeerd: " + t.getClass().getSimpleName());
        }
    }
}

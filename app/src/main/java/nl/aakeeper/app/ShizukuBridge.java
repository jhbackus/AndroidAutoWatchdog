package nl.aakeeper.app;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.IBinder;
import rikka.shizuku.Shizuku;

public final class ShizukuBridge {
    public interface Callback { void done(String result); }
    public static final int REQ = 4101;
    private static IPrivilegedService service;
    private static boolean binding;

    private static final Shizuku.UserServiceArgs ARGS = new Shizuku.UserServiceArgs(
            new ComponentName("nl.aakeeper.app", PrivilegedService.class.getName()))
            .processNameSuffix("aa_keeper_shell")
            .daemon(false)
            .debuggable(false)
            .version(3);

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IPrivilegedService.Stub.asInterface(binder);
            binding = false;
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
        }
    };

    private ShizukuBridge() {}

    public static boolean binderAlive() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    public static boolean permissionGranted() {
        try { return binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    public static String status() {
        if (!binderAlive()) return "Shizuku niet actief";
        if (!permissionGranted()) return "Shizuku actief, toestemming ontbreekt";
        try {
            if (service != null && service.asBinder().isBinderAlive()) return "Shizuku klaar (shell uid " + service.uid() + ")";
        } catch (Throwable ignored) {}
        return "Shizuku toegestaan; service nog niet verbonden";
    }

    public static void requestPermission() {
        if (!binderAlive()) return;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQ);
            }
        } catch (Throwable ignored) {}
    }

    public static void bind() {
        if (!permissionGranted() || binding) return;
        try {
            if (service != null && service.asBinder().isBinderAlive()) return;
            binding = true;
            Shizuku.bindUserService(ARGS, CONNECTION);
        } catch (Throwable t) { binding = false; }
    }

    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    public static void runAsync(Context context, String command, Callback cb) {
        new Thread(() -> {
            String result;
            try {
                bind();
                for (int i = 0; i < 25; i++) {
                    if (service != null && service.asBinder().isBinderAlive()) break;
                    Thread.sleep(100);
                }
                if (service == null || !service.asBinder().isBinderAlive()) result = "ERROR: Shizuku user-service niet verbonden";
                else result = service.exec(command);
            } catch (Throwable t) {
                result = "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            String finalResult = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.done(finalResult));
        }, "AAK-Shizuku").start();
    }

    public static String boostCommand(String pkg) {
        String p = q(pkg);
        return "echo '[protect]'; am set-standby-bucket " + p + " active 2>&1; " +
                "cmd appops set " + p + " RUN_IN_BACKGROUND allow 2>&1; " +
                "cmd appops set " + p + " RUN_ANY_IN_BACKGROUND allow 2>&1; " +
                "dumpsys deviceidle whitelist +" + p + " 2>&1; " +
                "echo '[bucket]'; am get-standby-bucket " + p + " 2>&1";
    }

    public static String diagnosticCommand(String pkg) {
        String p = q(pkg);
        return "echo 'uid='$(id -u); " +
                "echo '[package state]'; dumpsys package " + p + " | grep -E 'User 0:|stopped=|enabled=|hidden=|suspended=' | head -25; " +
                "echo '[standby]'; am get-standby-bucket " + p + " 2>&1; " +
                "echo '[appops]'; cmd appops get " + p + " RUN_IN_BACKGROUND 2>&1; cmd appops get " + p + " RUN_ANY_IN_BACKGROUND 2>&1; " +
                "echo '[doze]'; dumpsys deviceidle whitelist | grep -F " + p + " || true; " +
                "echo '[process]'; ps -A | grep -F gearhead || true; " +
                "echo '[services]'; dumpsys activity services " + p + " | grep -E 'ServiceRecord|app=' | head -35; " +
                "echo '[bluetooth]'; dumpsys bluetooth_manager | grep -E 'ConnectionState|mConnectionState|Bonded devices|name:' | head -50; " +
                "echo '[wifi]'; dumpsys wifi | grep -E 'Wi-Fi is|mWifiInfo|WiFiEnabledState|ClientModeImpl' | head -35";
    }

    public static String repairCommand(String pkg) {
        String p = q(pkg);
        return boostCommand(pkg) + "; echo '[unstick]'; cmd package set-stopped-state " + p + " false 2>&1; " +
                "echo '[state]'; dumpsys package " + p + " | grep -E 'User 0:|stopped=' | head -8";
    }

    public static String handshakeCommand(String pkg) {
        String p = q(pkg);
        return "echo '[process]'; ps -A | grep -F gearhead || true; " +
                "echo '[services]'; dumpsys activity services " + p + " | grep -E 'ServiceRecord|app=' | head -24; " +
                "echo '[package]'; dumpsys package " + p + " | grep -E 'User 0:|stopped=' | head -8; " +
                "echo '[bluetooth]'; dumpsys bluetooth_manager | grep -E 'ConnectionState|mConnectionState|name:' | head -30; " +
                "echo '[wifi]'; dumpsys wifi | grep -E 'Wi-Fi is|mWifiInfo|ClientModeImpl' | head -22";
    }

    public static String hardRepairCommand(String pkg) {
        String p = q(pkg);
        return boostCommand(pkg) + "; echo '[hard restart]'; am force-stop " + p + " 2>&1; sleep 1; cmd package set-stopped-state " + p + " false 2>&1; " +
                "echo '[after]'; dumpsys package " + p + " | grep -E 'User 0:|stopped=' | head -8";
    }
}

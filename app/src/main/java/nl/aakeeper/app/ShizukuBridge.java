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
            .version(4);

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

    public static boolean serviceConnected() {
        try { return service != null && service.asBinder().isBinderAlive(); }
        catch (Throwable t) { service = null; return false; }
    }

    public static String status() {
        if (!binderAlive()) return "Shizuku-server niet actief — na een herstart opnieuw starten";
        if (!permissionGranted()) return "Shizuku actief, Watchdog-toestemming ontbreekt";
        if (serviceConnected()) {
            try { return "Shizuku klaar ✓ (shell uid " + service.uid() + ")"; }
            catch (Throwable ignored) {}
        }
        return binding ? "Shizuku toegestaan; shellservice wordt verbonden…" : "Shizuku toegestaan; shellservice niet verbonden";
    }

    public static void requestPermission() {
        if (!binderAlive()) return;
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQ);
            }
        } catch (Throwable ignored) {}
    }

    public static synchronized void bind() {
        if (!permissionGranted() || binding || serviceConnected()) return;
        try {
            binding = true;
            Shizuku.bindUserService(ARGS, CONNECTION);
        } catch (Throwable t) {
            binding = false;
            service = null;
        }
    }

    public static void runAsync(Context context, String command, Callback cb) {
        new Thread(() -> {
            String result;
            try {
                bind();
                for (int i = 0; i < 50 && !serviceConnected(); i++) Thread.sleep(100);
                if (!serviceConnected()) {
                    binding = false;
                    bind();
                    for (int i = 0; i < 30 && !serviceConnected(); i++) Thread.sleep(100);
                }
                if (!serviceConnected()) result = "ERROR: Shizuku actief, maar shellservice kon niet verbinden";
                else result = service.exec(command);
            } catch (Throwable t) {
                service = null;
                binding = false;
                result = "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            String finalResult = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> cb.done(finalResult));
        }, "AAK-Shizuku").start();
    }

    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    public static String boostCommand(String pkg) {
        String p = q(pkg);
        return "echo '[protect]'; am set-standby-bucket " + p + " active 2>&1; " +
                "am set-inactive " + p + " false 2>&1; " +
                "cmd package set-stopped-state " + p + " false 2>&1; " +
                "cmd appops set " + p + " RUN_IN_BACKGROUND allow 2>&1; " +
                "cmd appops set " + p + " RUN_ANY_IN_BACKGROUND allow 2>&1; " +
                "dumpsys deviceidle whitelist +" + p + " 2>&1; " +
                "echo '[verify]'; am get-standby-bucket " + p + " 2>&1; am get-inactive " + p + " 2>&1; " +
                "dumpsys package " + p + " | grep -E 'User 0:|stopped=' | head -4";
    }

    public static String diagnosticCommand(String pkg) {
        String p = q(pkg);
        return "echo '[identity]'; id; date; " +
                "echo '[shizuku]'; ps -A | grep -E 'shizuku_server|aa_keeper_shell' || true; " +
                "echo '[android-auto]'; dumpsys package " + p + " | grep -E 'versionName=|User 0:|stopped=' | head -12; " +
                "ps -A | grep -F gearhead || true; dumpsys activity services " + p + " | head -35; " +
                "echo '[bluetooth profiles]'; dumpsys bluetooth_manager | grep -E 'name:\"|mConnectionState: STATE_|curState=|STATE_CONNECTED' | tail -60; " +
                "echo '[wifi current]'; cmd wifi status 2>&1; " +
                "echo '[network route]'; dumpsys connectivity | grep -E 'WIFI CONNECTED|SSID:|192\\.168\\.|RequestorPkg:.*gearhead' | head -45";
    }

    public static String repairCommand(String pkg) {
        String p = q(pkg);
        return boostCommand(pkg) + "; echo '[unstick]'; cmd package set-stopped-state " + p + " false 2>&1; " +
                "echo '[state]'; dumpsys package " + p + " | grep -E 'User 0:|stopped=' | head -8";
    }

    public static String handshakeCommand(String pkg) {
        String p = q(pkg);
        return "echo '[time]'; date; " +
                "echo '[aa process]'; ps -A | grep -F gearhead || echo 'AA_PROCESS_ABSENT'; " +
                "echo '[aa services]'; dumpsys activity services " + p + " | grep -E 'ServiceRecord|app=' | head -24; " +
                "echo '[aa package]'; dumpsys package " + p + " | grep -E 'versionName=|User 0:|stopped=' | head -10; " +
                "echo '[bluetooth]'; dumpsys bluetooth_manager | grep -E 'name:\"|mConnectionState: STATE_|curState=|STATE_CONNECTED' | tail -45; " +
                "echo '[wifi]'; cmd wifi status 2>&1; " +
                "echo '[aa network request]'; dumpsys connectivity | grep -E 'RequestorPkg:.*gearhead|WIFI CONNECTED|SSID:' | head -35";
    }

    public static String hardRepairCommand(String pkg) {
        String p = q(pkg);
        return boostCommand(pkg) + "; echo '[hard restart]'; am force-stop " + p + " 2>&1; sleep 1; " +
                "cmd package set-stopped-state " + p + " false 2>&1; " +
                "echo '[after]'; dumpsys package " + p + " | grep -E 'User 0:|stopped=' | head -8";
    }
}

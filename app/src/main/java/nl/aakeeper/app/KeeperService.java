package nl.aakeeper.app;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;

public class KeeperService extends Service {
    public static final String CHANNEL_ID = "aa_watchdog";
    public static final String ACTION_STOP = "nl.aakeeper.app.STOP";
    private static final String AA = "com.google.android.projection.gearhead";

    private Handler handler;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver bluetoothReceiver;
    private long lastCarConnectMs = 0L;

    private final Runnable maintenance = new Runnable() {
        @Override public void run() {
            if (!LogStore.isEnabled(KeeperService.this)) return;
            if (ShizukuBridge.permissionGranted()) {
                ShizukuBridge.runAsync(KeeperService.this, ShizukuBridge.boostCommand(AA), result -> {
                    if (result.contains("ERROR:")) LogStore.add(KeeperService.this, "Periodieke Android Auto protection gaf een fout.");
                    else LogStore.add(KeeperService.this, "Periodieke Android Auto protection gecontroleerd.");
                });
            }
            handler.postDelayed(this, 6L * 60L * 60L * 1000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        registerNetworkMonitor();
        registerBluetoothMonitor();
        LogStore.add(this, "Android Auto Watchdog-service gestart. Geen permanente Wi-Fi-lock actief.");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            LogStore.add(this, "Watchdog door gebruiker gestopt.");
            LogStore.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        LogStore.setEnabled(this, true);
        startForeground(1001, notification("Wacht op verbinding met de auto"));
        handler.removeCallbacks(maintenance);
        handler.postDelayed(maintenance, 2500);
        return START_STICKY;
    }

    private void registerBluetoothMonitor() {
        bluetoothReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) return;

                BluetoothDevice device = null;
                try {
                    if (Build.VERSION.SDK_INT >= 33) device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    else device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                } catch (Throwable ignored) {}

                String name = "onbekend apparaat";
                try {
                    if (device != null && (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) {
                        String n = device.getName();
                        if (n != null && !n.trim().isEmpty()) name = n.trim();
                    }
                } catch (Throwable ignored) {}

                boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
                LogStore.add(KeeperService.this, "Bluetooth " + (connected ? "verbonden" : "verbroken") + ": " + name);
                if (connected && matchesConfiguredCar(name)) onCarConnected(name);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(bluetoothReceiver, f);
        } catch (Throwable t) {
            LogStore.add(this, "Bluetooth-monitor kon niet starten: " + t.getClass().getSimpleName() + ". Controleer Bluetooth-toestemming.");
        }
    }

    private boolean matchesConfiguredCar(String deviceName) {
        String wanted = LogStore.getCarName(this).trim();
        if (wanted.isEmpty()) return true;
        return deviceName != null && deviceName.toLowerCase().contains(wanted.toLowerCase());
    }

    private void onCarConnected(String name) {
        long now = System.currentTimeMillis();
        if (now - lastCarConnectMs < 15000L) return;
        lastCarConnectMs = now;
        LogStore.add(this, "Auto-trigger geactiveerd voor: " + name + ". Android Auto wordt voorbereid.");
        updateNotification("Auto verbonden – Android Auto controleren");

        if (ShizukuBridge.permissionGranted()) {
            ShizukuBridge.runAsync(this, ShizukuBridge.repairCommand(AA), result -> {
                LogStore.add(this, result.contains("ERROR:") ? "Soft repair mislukt." : "Soft repair toegepast bij Bluetooth-verbinding.");
            });
            handler.postDelayed(() -> runHandshakeSnapshot("+15 s"), 15000L);
            handler.postDelayed(() -> runHandshakeSnapshot("+45 s"), 45000L);
        } else {
            LogStore.add(this, "Shizuku niet beschikbaar; alleen Bluetooth/Wi-Fi gebeurtenis gelogd.");
        }
    }

    private void runHandshakeSnapshot(String phase) {
        if (!LogStore.isEnabled(this) || !ShizukuBridge.permissionGranted()) return;
        ShizukuBridge.runAsync(this, ShizukuBridge.handshakeCommand(AA), result -> {
            LogStore.add(this, "Handshake snapshot " + phase + ": " + compact(result));
            updateNotification("Android Auto handshake gecontroleerd");
        });
    }

    private String compact(String s) {
        if (s == null) return "geen resultaat";
        String x = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return x.length() > 700 ? x.substring(0, 700) + "…" : x;
    }

    private void registerNetworkMonitor() {
        try {
            cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(network);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        LogStore.add(KeeperService.this, "Wi-Fi netwerk beschikbaar (monitoring; geen lock). ");
                }
                @Override public void onLost(Network network) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(network);
                    LogStore.add(KeeperService.this, "Netwerk verloren: " + network + (nc != null ? " capabilities=" + nc : ""));
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback);
        } catch (Throwable t) {
            LogStore.add(this, "Netwerkmonitor kon niet starten: " + t.getClass().getSimpleName());
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, KeeperService.class).setAction(ACTION_STOP);
        PendingIntent psi = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Android Auto Watchdog actief")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "Stop", psi).build())
                .build();
    }

    private void updateNotification(String text) {
        try { getSystemService(NotificationManager.class).notify(1001, notification(text)); } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Android Auto Watchdog", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Bewaakt Bluetooth-trigger en Android Auto-status zonder Wi-Fi permanent wakker te houden.");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        try { if (networkCallback != null && cm != null) cm.unregisterNetworkCallback(networkCallback); } catch (Throwable ignored) {}
        try { if (bluetoothReceiver != null) unregisterReceiver(bluetoothReceiver); } catch (Throwable ignored) {}
        LogStore.add(this, "Android Auto Watchdog-service beëindigd.");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

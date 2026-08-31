package nl.aakeeper.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.*;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final String AA = "com.google.android.projection.gearhead";
    private static final String SHIZUKU = "moe.shizuku.privileged.api";
    private static final int REQ_BT = 11, REQ_NOTIF = 12;
    private TextView setupStatus, status, shizukuStatus, log;
    private EditText carName;
    private Button toggle, setupButton;
    private boolean setupStarted;
    private String lastStep = "";

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> { ShizukuBridge.bind(); lastStep = ""; refresh(); continueSoon(); };
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        LogStore.add(this, "Shizuku-server gestopt. Na een telefoonherstart moet Shizuku opnieuw worden gestart.");
        refresh();
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, grantResult) -> {
        if (requestCode == ShizukuBridge.REQ) {
            LogStore.add(this, grantResult == PackageManager.PERMISSION_GRANTED ? "Shizuku-toestemming verleend." : "Shizuku-toestemming geweigerd.");
            if (grantResult == PackageManager.PERMISSION_GRANTED) { ShizukuBridge.bind(); lastStep = ""; }
            refresh(); continueSoon();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived);
            Shizuku.addBinderDeadListener(binderDead);
            Shizuku.addRequestPermissionResultListener(permissionResult);
        } catch (Throwable ignored) {}
        ShizukuBridge.bind();
        refresh();
        if (!LogStore.isSetupComplete(this)) new Handler(Looper.getMainLooper()).postDelayed(() -> { setupStarted = true; continueSetup(); }, 700);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(32));
        root.setBackgroundColor(Color.rgb(16,17,20));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Android Auto Watchdog", 27, true); root.addView(title);
        root.addView(text("Honor / MagicOS • wireless Android Auto watchdog", 13, false));
        root.addView(space(14));
        setupStatus = text("Installatie controleren…", 15, true); root.addView(setupStatus);
        setupButton = button("GA VERDER MET INSTALLATIE"); root.addView(setupButton);
        setupButton.setOnClickListener(v -> { lastStep = ""; setupStarted = true; continueSetup(); });

        root.addView(section("Status"));
        status = text("", 14, false); root.addView(status);
        toggle = button("START WATCHDOG"); root.addView(toggle);
        toggle.setOnClickListener(v -> toggleKeeper());

        root.addView(section("Auto-trigger"));
        root.addView(text("Vul een herkenbaar deel van de Bluetooth-naam van je auto in. Leeg = iedere Bluetooth-verbinding.", 12, false));
        carName = new EditText(this); carName.setTextColor(Color.WHITE); carName.setHintTextColor(Color.GRAY); carName.setHint("Bijv. BMW, Audi MMI, Mercedes…"); carName.setSingleLine(); carName.setText(LogStore.getCarName(this)); root.addView(carName, full());
        Button save = button("SLA AUTO-FILTER OP"); root.addView(save); save.setOnClickListener(v -> { LogStore.setCarName(this, carName.getText().toString()); LogStore.add(this, "Bluetooth-filter opgeslagen: " + LogStore.getCarName(this)); refresh(); });

        root.addView(section("Android Auto bescherming"));
        shizukuStatus = text("", 14, false); root.addView(shizukuStatus);
        Button shizuku = button("SHIZUKU TOESTEMMING"); root.addView(shizuku); shizuku.setOnClickListener(v -> requestShizuku());
        Button boost = button("PAS BESCHERMING TOE"); root.addView(boost); boost.setOnClickListener(v -> runCommand(ShizukuBridge.boostCommand(AA), "Protection"));
        Button repair = button("SOFT REPAIR ANDROID AUTO"); root.addView(repair); repair.setOnClickListener(v -> runCommand(ShizukuBridge.repairCommand(AA), "Soft repair"));
        Button hard = button("HARDE RESTART BIJ STORING"); root.addView(hard); hard.setOnClickListener(v -> hardRepair());
        Button diag = button("LIVE HANDSHAKE-DIAGNOSTIEK"); root.addView(diag); diag.setOnClickListener(v -> runDiagnostics());

        root.addView(section("Honor / Android instellingen"));
        Button battery = button("BATTERIJOPTIMALISATIE UITSLUITEN"); root.addView(battery); battery.setOnClickListener(v -> requestBatteryExemption());
        if (isHonorDevice()) {
            Button honorDone = button("IK HEB HONOR APP LAUNCH INGESTELD");
            root.addView(honorDone);
            honorDone.setOnClickListener(v -> confirmHonorLaunch());
        }
        Button app = button("OPEN APP-INSTELLINGEN"); root.addView(app); app.setOnClickListener(v -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        Button aa = button("OPEN ANDROID AUTO"); root.addView(aa); aa.setOnClickListener(v -> openAndroidAuto());

        root.addView(section("Diagnostiek"));
        log = text("", 12, false); log.setTextIsSelectable(true); root.addView(log);
        Button refresh = button("VERVERS"); root.addView(refresh); refresh.setOnClickListener(v -> refresh());
        Button clear = button("WIS LOGBOEK"); root.addView(clear); clear.setOnClickListener(v -> { LogStore.clear(this); refresh(); });
        setContentView(scroll);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(Color.WHITE); t.setTextSize(sp); t.setPadding(0, dp(4), 0, dp(4)); if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD); return t;
    }
    private TextView section(String s) { TextView t = text(s, 19, true); t.setPadding(0, dp(20), 0, dp(8)); return t; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); LinearLayout.LayoutParams p = full(); p.topMargin = dp(7); b.setLayoutParams(p); return b; }
    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private Space space(int h) { Space s = new Space(this); s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return s; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() { super.onResume(); ShizukuBridge.bind(); refresh(); if (setupStarted && !LogStore.isSetupComplete(this)) continueSoon(); }
    @Override protected void onDestroy() { try { Shizuku.removeBinderReceivedListener(binderReceived); Shizuku.removeBinderDeadListener(binderDead); Shizuku.removeRequestPermissionResultListener(permissionResult); } catch (Throwable ignored) {} super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) { super.onRequestPermissionsResult(requestCode, permissions, results); if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) lastStep = ""; refresh(); continueSoon(); }

    private void continueSoon() { new Handler(Looper.getMainLooper()).postDelayed(() -> { if (!isFinishing() && setupStarted && !LogStore.isSetupComplete(this)) continueSetup(); }, 650); }
    private boolean already(String s) { if (s.equals(lastStep)) return true; lastStep = s; return false; }

    private void continueSetup() {
        refresh();
        if (!hasBluetoothPermission()) { setupStatus.setText("Stap 1/6 • Sta Bluetooth toe voor de auto-trigger."); if (!already("BT") && Build.VERSION.SDK_INT >= 31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT); return; }
        if (!hasNotificationPermission()) { setupStatus.setText("Stap 2/6 • Sta notificaties toe voor de watchdog-service."); if (!already("NOTIF") && Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF); return; }
        if (!isBatteryExempt()) { setupStatus.setText("Stap 3/6 • Sluit de Watchdog uit van batterijoptimalisatie."); if (!already("BATTERY")) requestBatteryExemption(); return; }
        if (isHonorDevice() && !LogStore.isHonorLaunchConfirmed(this)) { setupStatus.setText("Stap 4/6 • Stel Honor App launch handmatig in en bevestig daarna in deze app."); if (!already("HONOR")) showHonorGuide(); return; }
        if (!packageInstalled(SHIZUKU)) { setupStatus.setText("Stap 5/6 • Installeer Shizuku; dit mag Android niet stil door een andere app laten doen."); if (!already("INSTALL")) openShizukuStore(); return; }
        if (!ShizukuBridge.binderAlive()) { setupStatus.setText("Stap 5/6 • Start Shizuku via draadloos debuggen en keer terug."); if (!already("START")) openShizukuApp(); return; }
        if (!ShizukuBridge.permissionGranted()) { setupStatus.setText("Stap 6/6 • Geef deze Watchdog toestemming in Shizuku."); if (!already("PERM")) ShizukuBridge.requestPermission(); return; }
        finishSetup();
    }

    private void showHonorGuide() {
        new AlertDialog.Builder(this).setTitle("Honor App launch")
                .setMessage("Ga in MagicOS naar App launch / Automatisch starten. Zet voor Android Auto Watchdog 'Automatisch beheren' UIT en sta toe: Automatisch starten, Secundair starten en Uitvoeren op achtergrond. Vergrendel de app daarna ook in Recente apps indien MagicOS die optie toont.\n\nMagicOS geeft andere apps geen betrouwbare API om deze instelling uit te lezen. Daarom bevestig je deze stap handmatig.")
                .setPositiveButton("Ik heb dit ingesteld", (d,w) -> confirmHonorLaunch())
                .setNeutralButton("Open instellingen", (d,w) -> safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Later", null).show();
    }

    private void confirmHonorLaunch() {
        LogStore.setHonorLaunchConfirmed(this, true);
        LogStore.add(this, "Honor App launch handmatig bevestigd door gebruiker.");
        lastStep = "";
        setupStarted = true;
        refresh();
        continueSoon();
    }

    private void finishSetup() {
        if (!LogStore.isEnabled(this)) startKeeper();
        LogStore.setSetupComplete(this, true);
        setupStatus.setText("Installatie gereed ✓ Watchdog actief."); setupButton.setText("CONTROLEER INSTELLINGEN OPNIEUW");
        LogStore.add(this, "Onboarding voltooid; Watchdog automatisch geactiveerd.");
        if (ShizukuBridge.permissionGranted()) ShizukuBridge.runAsync(this, ShizukuBridge.boostCommand(AA), r -> { LogStore.add(this, r.contains("ERROR:") ? "Eerste protection gaf een fout." : "Eerste protection toegepast."); refresh(); });
        refresh();
    }

    private void startKeeper() { try { Intent i = new Intent(this, KeeperService.class); if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i); LogStore.setEnabled(this, true); } catch (Throwable t) { LogStore.add(this, "Startfout: " + t); } }
    private void toggleKeeper() { if (LogStore.isEnabled(this)) { try { startService(new Intent(this, KeeperService.class).setAction(KeeperService.ACTION_STOP)); } catch (Throwable ignored) {} LogStore.setEnabled(this, false); } else { if (!hasBluetoothPermission()) { setupStarted = true; continueSetup(); return; } startKeeper(); } new Handler(Looper.getMainLooper()).postDelayed(this::refresh, 300); }

    private void requestShizuku() { if (!ShizukuBridge.binderAlive()) { openShizukuApp(); return; } if (!ShizukuBridge.permissionGranted()) ShizukuBridge.requestPermission(); else { ShizukuBridge.bind(); Toast.makeText(this, "Shizuku is toegestaan.", Toast.LENGTH_SHORT).show(); } }
    private boolean ensureShizuku() { if (!ShizukuBridge.binderAlive()) { openShizukuApp(); return false; } if (!ShizukuBridge.permissionGranted()) { ShizukuBridge.requestPermission(); return false; } ShizukuBridge.bind(); return true; }
    private void runCommand(String command, String label) { if (!ensureShizuku()) return; ShizukuBridge.runAsync(this, command, r -> { LogStore.add(this, label + ": " + compact(r)); Toast.makeText(this, r.contains("ERROR:") ? label + " gaf een fout." : label + " uitgevoerd.", Toast.LENGTH_LONG).show(); refresh(); }); }
    private void hardRepair() { if (!ensureShizuku()) return; new AlertDialog.Builder(this).setTitle("Harde restart").setMessage("Alleen gebruiken wanneer Android Auto al vastgelopen is. Android Auto wordt force-stopped en daarna weer vrijgegeven.").setNegativeButton("Annuleren", null).setPositiveButton("Herstart", (d,w) -> runCommand(ShizukuBridge.hardRepairCommand(AA), "Harde repair")).show(); }
    private void runDiagnostics() { if (!ensureShizuku()) return; ShizukuBridge.runAsync(this, ShizukuBridge.diagnosticCommand(AA), r -> { LogStore.add(this, "Diagnostiek:\n" + r); log.setText(deviceInfo() + "\n\n--- LIVE ---\n" + r + "\n\n--- LOG ---\n" + LogStore.get(this)); }); }

    private void refresh() {
        if (status == null) return;
        WifiManager wm = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        String target = LogStore.getCarName(this);
        status.setText("Watchdog: " + (LogStore.isEnabled(this) ? "actief ✓" : "uit") +
                "\nWi-Fi: " + (wm != null && wm.isWifiEnabled() ? "aan ✓ (niet gelockt)" : "uit") +
                "\nBluetooth: " + (hasBluetoothPermission() ? "toegestaan ✓" : "toestemming ontbreekt") +
                "\nAuto-filter: " + (target.isEmpty() ? "alle apparaten" : target) +
                "\nBatterijoptimalisatie: " + (isBatteryExempt() ? "uitgesloten ✓" : "actief") +
                "\nHonor App launch: " + (!isHonorDevice() || LogStore.isHonorLaunchConfirmed(this) ? "bevestigd ✓" : "handmatige bevestiging nodig") +
                "\nAndroid Auto: " + (packageInstalled(AA) ? "gevonden ✓" : "niet gevonden"));
        toggle.setText(LogStore.isEnabled(this) ? "STOP WATCHDOG" : "START WATCHDOG");
        shizukuStatus.setText(ShizukuBridge.status());
        if (LogStore.isSetupComplete(this)) { setupStatus.setText(ShizukuBridge.binderAlive() ? "Installatie gereed ✓ Watchdog en Android Auto-bescherming zijn geconfigureerd." : "Installatie gereed • Start Shizuku opnieuw na een telefoonherstart."); setupButton.setText("CONTROLEER INSTELLINGEN OPNIEUW"); }
        log.setText(deviceInfo() + "\n\n--- LOG ---\n" + LogStore.get(this));
    }

    private String deviceInfo() { return "Android Auto Watchdog 1.7.0\n" + Build.MANUFACTURER + " " + Build.MODEL + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" + ShizukuBridge.status(); }
    private String compact(String s) { if (s == null) return "geen resultaat"; String x = s.replace('\n',' ').replaceAll("\\s+"," ").trim(); return x.length() > 900 ? x.substring(0,900) + "…" : x; }
    private boolean hasBluetoothPermission() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasNotificationPermission() { return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED; }
    private boolean isHonorDevice() { String m = String.valueOf(Build.MANUFACTURER).toLowerCase(), b = String.valueOf(Build.BRAND).toLowerCase(); return m.contains("honor") || b.contains("honor") || m.contains("huawei") || b.contains("huawei"); }
    private boolean packageInstalled(String p) { try { getPackageManager().getApplicationInfo(p,0); return true; } catch (Throwable t) { return false; } }
    private boolean isBatteryExempt() { try { PowerManager pm = getSystemService(PowerManager.class); return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()); } catch (Throwable t) { return false; } }
    private void requestBatteryExemption() { try { if (!isBatteryExempt()) safeStart(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); else { lastStep = ""; continueSoon(); } } catch (Throwable t) { safeStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } }
    private void openAndroidAuto() { try { Intent i = getPackageManager().getLaunchIntentForPackage(AA); if (i != null) startActivity(i); else safeStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + AA))); } catch (Throwable t) { Toast.makeText(this, "Android Auto kon niet worden geopend.", Toast.LENGTH_LONG).show(); } }
    private void openShizukuApp() { try { Intent i = getPackageManager().getLaunchIntentForPackage(SHIZUKU); if (i != null) startActivity(i); else openShizukuStore(); } catch (Throwable t) { openShizukuStore(); } }
    private void openShizukuStore() { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SHIZUKU))); } catch (Throwable t) { safeStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + SHIZUKU))); } }
    private void safeStart(Intent i) { try { startActivity(i); } catch (Throwable t) { Toast.makeText(this, "Deze instelling is op dit toestel niet rechtstreeks beschikbaar.", Toast.LENGTH_LONG).show(); } }
}

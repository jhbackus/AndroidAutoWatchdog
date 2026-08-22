package nl.aakeeper.app;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LogStore {
    private static final String PREF = "keeper";
    private static final String KEY_LOG = "log";
    private LogStore() {}

    public static synchronized void add(Context c, String line) {
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String old = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LOG, "");
        String next = now + "  " + line + "\n" + old;
        if (next.length() > 30000) next = next.substring(0, 30000);
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_LOG, next).apply();
    }
    public static String get(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LOG, "Nog geen gebeurtenissen.\n"); }
    public static void clear(Context c) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_LOG).apply(); }
    public static void setEnabled(Context c, boolean enabled) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply(); }
    public static boolean isEnabled(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("enabled", false); }
    public static void setCarName(Context c, String value) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("car_name", value == null ? "" : value.trim()).apply(); }
    public static String getCarName(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("car_name", ""); }
    public static void setHonorLaunchConfirmed(Context c, boolean value) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("honor_launch_confirmed", value).apply(); }
    public static boolean isHonorLaunchConfirmed(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("honor_launch_confirmed", false); }
    public static void setSetupComplete(Context c, boolean value) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("setup_complete", value).apply(); }
    public static boolean isSetupComplete(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("setup_complete", false); }
}

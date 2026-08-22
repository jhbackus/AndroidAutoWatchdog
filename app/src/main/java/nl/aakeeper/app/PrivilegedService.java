package nl.aakeeper.app;

import android.content.Context;
import android.os.Process;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Runs inside Shizuku's shell-privileged user-service process. */
public class PrivilegedService extends IPrivilegedService.Stub {
    public PrivilegedService() {}

    @Keep
    public PrivilegedService(Context context) {}

    @Override public String exec(String command) {
        if (command == null || command.length() > 2000) return "ERROR: invalid command";
        StringBuilder out = new StringBuilder();
        try {
            java.lang.Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null && out.length() < 12000) out.append(line).append('\n');
            if (!p.waitFor(12, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "ERROR: timeout\n" + out;
            }
            out.append("exit=").append(p.exitValue());
        } catch (Throwable t) {
            out.append("ERROR: ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return out.toString();
    }

    @Override public int uid() { return Process.myUid(); }

    @Override public void destroy() { System.exit(0); }
}

package io.github.qishui.automix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Local IPC with an explicit UID check. No network, file paths, or song data. */
public final class ConfigProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.qishui.automix.config");
    public static final String PREFS = "settings";

    private boolean readSeen, statusSeen;
    private long deniedAt;

    @Override public boolean onCreate() {
        LocalDiagnostics.event(getContext(), "provider_created", "module=" + HostDiagnostics.VERSION + " uid=" + Process.myUid());
        return true;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String name : packages) {
            if ("com.luna.music".equals(name)) return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (deniedAt == 0 || now - deniedAt > 15000) {
            deniedAt = now;
            LocalDiagnostics.event(getContext(), "provider_caller_denied", "uid=" + uid
                + " package_lookup=" + (packages == null ? "null" : "not_host"));
        }
        throw new SecurityException("Only the module and com.luna.music may access AutoMix");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        SharedPreferences p = prefs(getContext());
        Bundle result = new Bundle();
        if ("read".equals(method)) {
            if (!readSeen) {
                readSeen = true;
                LocalDiagnostics.event(getContext(), "provider_read_received", "caller_uid=" + Binder.getCallingUid());
            }
            for (SettingsSchema.Toggle toggle : SettingsSchema.TOGGLES)
                result.putBoolean(toggle.key, p.getBoolean(toggle.key, toggle.fallback));
            result.putInt(SettingsSchema.DURATION, p.getInt(SettingsSchema.DURATION, MixConfig.DEFAULT_DURATION_MS));
            result.putInt(SettingsSchema.EPOCH, p.getInt(SettingsSchema.EPOCH, 0));
        } else if ("status".equals(method) && extras != null) {
            if (!statusSeen) {
                statusSeen = true;
                LocalDiagnostics.event(getContext(), "provider_status_received", "caller_uid=" + Binder.getCallingUid());
            }
            String message = extras.getString("message", "");
            if (message == null) message = "";
            if (message.length() > 600) message = message.substring(0, 600);
            p.edit().putString("status", message).putLong("last_seen", System.currentTimeMillis())
                .putInt("completed", Math.max(0, extras.getInt("completed", 0)))
                .putInt("beat_completed", Math.max(0, extras.getInt("beat_completed", 0)))
                .putString("host_module_version", DiagnosticText.safe(extras.getString("module_version", "0.1.0 或旧进程"), 80))
                // Older builds send neither: empty means "unknown", and no restart hint is shown.
                .putString("host_loaded", DiagnosticText.safe(extras.getString("loaded", ""), 800))
                .putString("host_cached", DiagnosticText.safe(extras.getString("cached", ""), 800)).apply();
            java.util.ArrayList<String> logs = extras.getStringArrayList("logs");
            if (logs != null) for (int i = 0; i < Math.min(20, logs.size()); i++) {
                String line = logs.get(i);
                if (line != null) LocalDiagnostics.append(getContext(), line.substring(0, Math.min(6000, line.length())));
            }
            result.putBoolean("accepted", true);
            result.putString("module_version", HostDiagnostics.VERSION);
        } else {
            throw new IllegalArgumentException("Unsupported call");
        }
        return result;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { enforceCaller(); return null; }
    @Override public String getType(Uri uri) { enforceCaller(); return "vnd.android.cursor.item/vnd.qishui.automix"; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException("Read-only"); }
}

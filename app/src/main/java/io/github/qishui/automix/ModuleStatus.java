package io.github.qishui.automix;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the settings screens show about the host connection, read from the module's own prefs. */
public final class ModuleStatus {
    public static final String HOST = "com.luna.music", HOST_VERSION = "21.6.0 / 21.8.0";
    public static final long HOST_VERSION_CODE = 100216030L, HOST_VERSION_CODE_LATEST = 100218030L;
    public static final String MODULE_VERSION = HostDiagnostics.VERSION;
    /** The host reports every 2 s while it runs; older than this means it is not running. */
    public static final long LIVE_MS = 12000;

    public enum State { NOT_INSTALLED, DISABLED, CONNECTED, NOT_RUNNING, WAITING }

    public final State state;
    public final String hostVersion, detail, loadedVersion;
    public final long hostCode, lastSeen;
    public final int completed, beatCompleted;
    public final boolean versionMismatch, staleModule, live;
    /** Titles of "restart" switches the running (or last) host process does not have yet. */
    public final List<String> pending;
    /** Whether the host's cache already holds them, so its next start applies them. */
    public final boolean pendingCached;

    private ModuleStatus(Context context, SharedPreferences prefs, long now) {
        String version = null;
        long code = -1;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(HOST, 0);
            version = info.versionName;
            code = info.getLongVersionCode();
        } catch (Exception ignored) { }
        hostVersion = version;
        hostCode = code;
        lastSeen = prefs.getLong("last_seen", 0);
        live = lastSeen != 0 && now - lastSeen < LIVE_MS;
        if (version == null) state = State.NOT_INSTALLED;
        else if (!prefs.getBoolean("enabled", true)) state = State.DISABLED;
        else if (live) state = State.CONNECTED;
        else if (lastSeen != 0) state = State.NOT_RUNNING;
        else state = State.WAITING;
        detail = prefs.getString("status", "");
        loadedVersion = prefs.getString("host_module_version", "");
        completed = prefs.getInt("completed", 0);
        beatCompleted = prefs.getInt("beat_completed", 0);
        versionMismatch = version != null && code != HOST_VERSION_CODE && code != HOST_VERSION_CODE_LATEST;
        staleModule = lastSeen != 0 && !loadedVersion.isEmpty() && !HostDiagnostics.VERSION.equals(loadedVersion);
        List<String> waiting = new ArrayList<>();
        boolean inCache = true;
        if (version != null && !staleModule) {
            Map<String, Boolean> installed = values(prefs.getString("host_loaded", ""));
            Map<String, Boolean> next = values(prefs.getString("host_cached", ""));
            for (SettingsSchema.Toggle toggle : SettingsSchema.TOGGLES) {
                Boolean was = installed.get(toggle.key);
                if (!toggle.restart || was == null) continue;
                boolean want = prefs.getBoolean(toggle.key, toggle.fallback);
                if (was == want) continue;
                waiting.add(toggle.title);
                if (!Boolean.valueOf(want).equals(next.get(toggle.key))) inCache = false;
            }
        }
        pending = Collections.unmodifiableList(waiting);
        pendingCached = inCache;
    }

    private static Map<String, Boolean> values(String text) {
        Map<String, Boolean> out = new HashMap<>();
        for (String pair : text.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(pair.substring(0, eq), pair.endsWith("=1"));
        }
        return out;
    }

    /**
     * What it takes for the pending switches to apply, or "" when nothing is pending. The host
     * starts from its own cache and refreshes it about 2 s later, so a switch changed while it
     * was not running needs one start to be cached and another to be applied.
     */
    public String restartHint() {
        if (pending.isEmpty()) return "";
        String what = pending.size() == 1 ? "「" + pending.get(0) + "」" : pending.size() + " 项修改";
        if (live) return what + "重启汽水后生效";
        return what + (pendingCached ? "下次打开汽水生效" : "需打开汽水再重启一次");
    }

    /** The hint's action: restart (app info, to force stop) while the host runs, else open it. */
    public boolean restartOpensHost() { return !live; }

    public static ModuleStatus read(Context context) {
        return new ModuleStatus(context, ConfigProvider.prefs(context), System.currentTimeMillis());
    }

    /** The host has never reported in: show the first-use steps. */
    public boolean neverConnected() { return lastSeen == 0; }

    public String title() {
        switch (state) {
            case NOT_INSTALLED: return "未找到汽水音乐";
            case DISABLED: return "自动混音已关闭";
            case CONNECTED: return "工作中";
            case NOT_RUNNING: return "汽水未运行";
            default: return "等待汽水连接";
        }
    }

    public String warnings() {
        StringBuilder notes = new StringBuilder();
        if (versionMismatch) notes.append("当前汽水 ").append(hostVersion).append(" 未适配，仅支持官网 ")
            .append(HOST_VERSION).append("，混音不会生效。");
        if (staleModule) {
            if (notes.length() > 0) notes.append('\n');
            notes.append("汽水仍在运行旧版模块 ").append(loadedVersion).append("，强行停止汽水后重开即可。");
        }
        return notes.toString();
    }
}

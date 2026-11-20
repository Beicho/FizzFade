package io.github.qishui.automix;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import de.robv.android.xposed.XposedBridge;

/** LSPosed + logcat always; bounded retrying IPC once an application context exists. */
final class HostDiagnostics {
    static final String VERSION = "0.6.1";
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Map<String, Long> throttled = new HashMap<>();
    private Context context;
    private volatile Runnable monitor;
    private volatile String status = "启动回调已进入，正在检查适配器";
    private volatile int completed, beatCompleted;
    void beatCount(int count) { beatCompleted=count; }
    private boolean connected;
    private ProcessEvidence processEvidence;
    void checkpoint(String stage) { ProcessEvidence evidence=processEvidence; if(evidence!=null)evidence.mark(stage); }

    void event(String stage, String detail) { record(stage, detail, false); }

    void error(String stage, Throwable error) { record(stage, DiagnosticText.error(error), true); }

    void throttled(String stage, String detail, long intervalMs) {
        if (allow(stage, intervalMs)) event(stage, detail);
    }

    void errorThrottled(String stage, Throwable error, long intervalMs) {
        if (allow(stage + ":" + error.getClass().getName(), intervalMs)) error(stage, error);
    }

    private boolean allow(String key, long intervalMs) {
        synchronized (throttled) {
            long now = SystemClock.elapsedRealtime();
            Long previous = throttled.get(key);
            if (previous != null && now - previous < intervalMs) return false;
            throttled.put(key, now);
        }
        return true;
    }

    private void record(String stage, String detail, boolean failure) {
        String entry = LocalDiagnostics.line(stage, detail);
        if (failure) Log.e("FizzFade", entry); else Log.i("FizzFade", entry);
        try { XposedBridge.log("FizzFade " + entry); }
        catch (Throwable ignored) { /* logcat remains available if the framework logger fails. */ }
        synchronized (pending) {
            while (pending.size() >= 120) pending.removeFirst();
            pending.addLast(entry);
        }
    }

    synchronized void start(Context base) {
        if (context != null) return;
        context = base;
        Bundle startup = cachedConfig();
        ui = new Ui(startup);
        loaded = cached = restartValues(startup);
        HandlerThread thread = new HandlerThread("QishuiMixDiagnostics");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        handler.post(() -> { processEvidence = new ProcessEvidence(base, this); });
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    Runnable work = monitor;
                    if (work != null) work.run();
                    else if (pollConfig) readConfig();
                    flush();
                } catch (Throwable error) { ipcFailure("diagnostic_poll", error); }
                finally { handler.postDelayed(this, 2000); }
            }
        });
    }

    void monitor(Runnable work) { monitor = work; }
    void status(String message, int count) { status = message; completed = count; }

    private volatile long ipcSince;
    /** Milliseconds the current provider call has been blocked, or 0. Read from other threads. */
    long ipcBlockedMs() { long since = ipcSince; return since == 0 ? 0 : SystemClock.elapsedRealtime() - since; }

    // The last good settings live in the host's own private storage, so a frozen or killed
    // module process never decides what the host does, including at a cold start.
    private static final String CACHE = "qishui_automix_config";
    private static final String[] FLAGS = SettingsSchema.KEYS;

    /** Interface switches; each hook point reads its own flag. */
    static final class Ui {
        final boolean lyrics, word, seek, backdrop, player, playerCover, playerBg, trimTags, trimFollow, trimDots, trimPendant, trimCoin, tab, shortLyrics,
            tabFace, tabOrb, tabWelfare, refresh, blurThrottle;
        final int epoch;
        Ui(Bundle values) {
            lyrics = values == null || values.getBoolean("ui_lyrics", true);
            word = values == null || values.getBoolean("ui_lyrics_word", true);
            seek = values == null || values.getBoolean("ui_lyrics_seek", true);
            backdrop = values == null || values.getBoolean("ui_lyrics_bg", true);
            player = values == null || values.getBoolean("ui_player", true);
            playerCover = values == null || values.getBoolean("ui_player_cover", true);
            playerBg = values == null || values.getBoolean("ui_player_bg", true);
            trimTags = values == null || values.getBoolean("ui_trim_tags", true);
            trimFollow = values == null || values.getBoolean("ui_trim_follow", true);
            trimDots = values == null || values.getBoolean("ui_trim_dots", true);
            trimPendant = values == null || values.getBoolean("ui_trim_pendant", true);
            trimCoin = values == null || values.getBoolean("ui_trim_coin", true);
            tab = values == null || values.getBoolean("ui_tab", true);
            shortLyrics = values == null || values.getBoolean("ui_short_lyrics", true);
            tabFace = values == null || values.getBoolean("ui_tab_face", true);
            tabOrb = values == null || values.getBoolean("ui_tab_orb", true);
            tabWelfare = values == null || values.getBoolean("ui_tab_welfare", true);
            refresh = values == null || values.getBoolean("ui_refresh", true);
            blurThrottle = values == null || values.getBoolean("ui_blur_throttle", true);
            epoch = values == null ? 0 : values.getInt("ui_epoch", 0);
        }
    }
    private volatile Ui ui = new Ui(null);
    private volatile boolean pollConfig;
    Ui ui() { return ui; }
    /** The settings preview: the interface switches as the module app has them. */
    void preview(Bundle values) { ui = new Ui(values); }
    /** Lets the UI hooks keep their switches current when the mix manager (the usual reader) is absent. */
    void pollConfig() { pollConfig = true; }

    Bundle cachedConfig() {
        Context base = context;
        if (base == null) return null;
        android.content.SharedPreferences prefs = base.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        if (!prefs.contains("enabled")) return null;
        Bundle values = new Bundle();
        for (String key : FLAGS) values.putBoolean(key, prefs.getBoolean(key, true));
        values.putInt("duration", prefs.getInt("duration", MixConfig.DEFAULT_DURATION_MS));
        values.putInt("ui_epoch", prefs.getInt("ui_epoch", 0));
        return values;
    }

    /**
     * The "restart" switches as this process installed its hooks ({@link #loaded}) and as its cache
     * now holds them for the next start ({@link #cached}). The settings app compares both with its
     * own values to say whether a restart is needed, and whether one is enough: the host starts
     * from its cache, so a switch changed while it was not running needs a start to be cached first.
     */
    private volatile String loaded = "", cached = "";

    static String restartValues(Bundle values) {
        StringBuilder out = new StringBuilder();
        for (SettingsSchema.Toggle toggle : SettingsSchema.TOGGLES) {
            if (!toggle.restart) continue;
            boolean on = values == null ? toggle.fallback : values.getBoolean(toggle.key, toggle.fallback);
            if (out.length() > 0) out.append(',');
            out.append(toggle.key).append('=').append(on ? '1' : '0');
        }
        return out.toString();
    }

    private void remember(Bundle values) {
        cached = restartValues(values);
        android.content.SharedPreferences.Editor editor = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit();
        for (String key : FLAGS) editor.putBoolean(key, values.getBoolean(key, true));
        editor.putInt("duration", values.getInt("duration", MixConfig.DEFAULT_DURATION_MS))
            .putInt("ui_epoch", values.getInt("ui_epoch", 0)).apply();
    }

    Bundle readConfig() {
        try {
            ipcSince = SystemClock.elapsedRealtime();
            Bundle result = context.getContentResolver().call(ConfigProvider.URI, "read", null, null);
            if (result == null) throw new IllegalStateException("Empty provider response");
            remember(result);
            ui = new Ui(result);
            return result;
        } catch (Throwable error) {
            throttled("config_response_missing", "authority=io.github.qishui.automix.config read 无有效应答", 15000);
            ipcFailure("config_read", error);
            return null;
        } finally { ipcSince = 0; }
    }

    private void flush() {
        ArrayList<String> batch = new ArrayList<>();
        synchronized (pending) {
            for (String line : pending) { batch.add(line); if (batch.size() >= 20) break; }
        }
        Bundle values = new Bundle();
        values.putString("message", status);
        values.putInt("completed", completed);
        values.putInt("beat_completed", beatCompleted);
        values.putString("module_version", VERSION);
        values.putString("loaded", loaded);
        values.putString("cached", cached);
        values.putStringArrayList("logs", batch);
        try {
            ipcSince = SystemClock.elapsedRealtime();
            Bundle reply = context.getContentResolver().call(ConfigProvider.URI, "status", null, values);
            if (reply == null || !reply.getBoolean("accepted")) {
                throw new IllegalStateException("Diagnostic provider did not acknowledge");
            }
            synchronized (pending) {
                for (String line : batch) pending.removeFirstOccurrence(line);
            }
            if (!connected) {
                connected = true;
                event("ipc_connected", "配置 Provider 已应答；已缓存的启动日志将补传");
            }
        } catch (Throwable error) {
            throttled("status_response_missing", "authority=io.github.qishui.automix.config status 无有效应答；保留日志重试", 15000);
            ipcFailure("status_publish", error);
        } finally { ipcSince = 0; }
    }

    private synchronized void ipcFailure(String stage, Throwable error) {
        errorThrottled("ipc_" + stage + "_failed", error, 15000);
        connected = false;
    }
}

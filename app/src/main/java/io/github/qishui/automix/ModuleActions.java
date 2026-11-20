package io.github.qishui.automix;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Actions shared by the settings app and the plain fallback screen. */
public final class ModuleActions {
    /** Set by the crash recorder; the next launch opens the plain screen instead. */
    public static final String UI_FAILED = "ui_compose_failed";
    private static boolean crashRecorder;

    private ModuleActions() { }

    public static void setBoolean(Context context, String key, boolean value) {
        SharedPreferences prefs = ConfigProvider.prefs(context);
        SharedPreferences.Editor edit = prefs.edit().putBoolean(key, value);
        // A new epoch clears the host's crash guard for the lyric, play and tab pages.
        if (key.startsWith("ui_")) edit.putInt(SettingsSchema.EPOCH, prefs.getInt(SettingsSchema.EPOCH, 0) + 1);
        edit.apply();
    }

    public static void setDuration(Context context, int millis) {
        ConfigProvider.prefs(context).edit().putInt(SettingsSchema.DURATION, SettingsSchema.clampDuration(millis)).apply();
    }

    public static int duration(Context context) {
        return SettingsSchema.clampDuration(ConfigProvider.prefs(context).getInt(SettingsSchema.DURATION, MixConfig.DEFAULT_DURATION_MS));
    }

    /** A switch is usable when its parent is on (and, for mix switches, the master switch too). */
    public static boolean available(SharedPreferences prefs, SettingsSchema.Toggle toggle) {
        if (toggle.parent == null) return true;
        return (toggle.ui() || prefs.getBoolean("enabled", true)) && prefs.getBoolean(toggle.parent, true);
    }

    /** Lets the host read the provider; opening the app also starts the process that publishes it. */
    public static void grantHost(Context context) {
        try {
            context.grantUriPermission(ModuleStatus.HOST, ConfigProvider.URI, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException error) { LocalDiagnostics.error(context, "uri_grant_failed", error); }
    }

    public static int logChars(Context context) {
        return ConfigProvider.prefs(context).getString(LocalDiagnostics.KEY, "").length();
    }

    public static String summary(Context context) {
        SharedPreferences prefs = ConfigProvider.prefs(context);
        String target;
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(ModuleStatus.HOST, 0);
            target = info.versionName + " / " + info.getLongVersionCode();
        } catch (Exception error) { target = "未安装或不可见"; }
        long seen = prefs.getLong("last_seen", 0);
        StringBuilder settings = new StringBuilder();
        for (SettingsSchema.Toggle toggle : SettingsSchema.TOGGLES)
            settings.append(toggle.key).append('=').append(prefs.getBoolean(toggle.key, toggle.fallback)).append(' ');
        settings.append("duration=").append(prefs.getInt(SettingsSchema.DURATION, MixConfig.DEFAULT_DURATION_MS));
        return "叠拍 FizzFade 诊断  " + DateFormat.getDateTimeInstance().format(new Date())
            + "\n汽水版本：" + target + "\n适配版本：" + ModuleStatus.HOST_VERSION + " / " + ModuleStatus.HOST_VERSION_CODE
            + "\n模块：" + HostDiagnostics.VERSION + " · Legacy Xposed API 82"
            + "\n宿主已加载模块：" + prefs.getString("host_module_version", "未收到版本回报")
            + "\n设备：" + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
            + "\n最近连接：" + (seen == 0 ? "无" : DateFormat.getDateTimeInstance().format(new Date(seen)))
            + "\n状态：" + prefs.getString("status", "")
            + "\n本次进程混音成功：" + prefs.getInt("completed", 0) + " 次，对拍与变速确认：" + prefs.getInt("beat_completed", 0) + " 次"
            + "\n设置：" + settings
            + "\n\n--- 完整诊断日志 ---\n" + prefs.getString(LocalDiagnostics.KEY, "尚无日志");
    }

    public static Intent exportIntent() {
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "FizzFade-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt");
    }

    /** Writes the export to the document the user picked; returns false on failure. */
    public static boolean writeExport(Context context, Uri target) {
        try (OutputStream out = context.getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) throw new java.io.IOException("openOutputStream returned null");
            out.write(summary(context).getBytes(StandardCharsets.UTF_8));
            LocalDiagnostics.event(context, "log_exported", "chars=" + logChars(context));
            return true;
        } catch (Exception error) {
            LocalDiagnostics.error(context, "log_export_failed", error);
            return false;
        }
    }

    public static void share(Activity activity) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "叠拍 FizzFade 诊断日志").putExtra(Intent.EXTRA_TEXT, summary(activity));
        try { activity.startActivity(Intent.createChooser(send, "分享诊断日志")); }
        catch (ActivityNotFoundException error) { toast(activity, "没有可分享的应用"); }
    }

    public static void clearLog(Context context) {
        LocalDiagnostics.clear(context);
        LocalDiagnostics.event(context, "log_cleared", "已清空模块保存的日志；LSPosed 日志不受影响");
    }

    public static void openHost(Activity activity) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(ModuleStatus.HOST);
        if (launch == null) toast(activity, "未找到汽水音乐");
        else activity.startActivity(launch);
    }

    public static void openHostInfo(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ModuleStatus.HOST)));
        } catch (ActivityNotFoundException error) { toast(activity, "无法打开应用信息"); }
    }

    public static void toast(Context context, String text) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show(); }

    public static android.content.SharedPreferences prefs(Context context) { return ConfigProvider.prefs(context); }

    public static int logCapacityKb() { return DiagnosticText.MAX_LOG_CHARS / 1000; }

    public static void logOpen(Context context, String screen) {
        LocalDiagnostics.event(context, "settings_open", "module=" + HostDiagnostics.VERSION + " screen=" + screen
            + " Android=" + Build.VERSION.RELEASE + " api=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL);
    }

    /** The in-app previews; the views need Android 10. */
    public static void previewLyrics(Activity activity) { if (Build.VERSION.SDK_INT >= 29) LyricsDemo.show(activity); }
    public static void previewPlayer(Activity activity) { if (Build.VERSION.SDK_INT >= 29) PlayerDemo.show(activity); }

    /** Records the stack of any crash in this process and routes the next launch to the plain screen. */
    public static synchronized void installCrashRecorder(Context base) {
        if (crashRecorder) return;
        crashRecorder = true;
        Context app = base.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                SharedPreferences p = ConfigProvider.prefs(app);
                String trace = android.util.Log.getStackTraceString(error);
                String entry = LocalDiagnostics.line("ui_crash", "thread=" + thread.getName() + " "
                    + trace.substring(0, Math.min(6000, trace.length())));
                p.edit().putString(LocalDiagnostics.KEY, DiagnosticText.append(p.getString(LocalDiagnostics.KEY, ""), entry))
                    .putBoolean(UI_FAILED, true).commit();
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** Android's own record of how this module process last ended (covers native crashes and ANRs). */
    public static void recordOwnExits(Context context) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 3);
            for (ApplicationExitInfo exit : exits) {
                LocalDiagnostics.event(context, "ui_exit_history", "pid=" + exit.getPid() + " reason=" + exit.getReason()
                    + " status=" + exit.getStatus() + " at=" + exit.getTimestamp() + " description="
                    + DiagnosticText.safe(exit.getDescription(), 800));
            }
        } catch (Throwable error) { LocalDiagnostics.error(context, "ui_exit_history_unavailable", error); }
    }
}

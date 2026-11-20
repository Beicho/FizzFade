package io.github.qishui.automix;

import android.content.Context;
import android.os.Process;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Runs only in the module's own process. Does not depend on framework classes. */
final class LocalDiagnostics {
    static final String KEY = "diagnostic_log";
    private LocalDiagnostics() { }

    static String line(String stage, String detail) {
        return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date())
            + " pid=" + Process.myPid() + " [" + stage + "] " + detail;
    }

    static synchronized void append(Context context, String text) {
        android.content.SharedPreferences prefs = ConfigProvider.prefs(context);
        prefs.edit().putString(KEY, DiagnosticText.append(prefs.getString(KEY, ""), text)).apply();
    }

    static void event(Context context, String stage, String detail) {
        String entry = line(stage, detail);
        Log.i("FizzFade", entry);
        append(context, entry);
    }

    static void error(Context context, String stage, Throwable error) {
        String entry = line(stage, DiagnosticText.error(error));
        Log.e("FizzFade", entry);
        append(context, entry);
    }

    static synchronized void clear(Context context) {
        ConfigProvider.prefs(context).edit().remove(KEY).apply();
    }
}

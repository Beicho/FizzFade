package io.github.qishui.automix;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Crash strikes for the host UI features. A crash whose stack runs through a feature's classes
 * counts one strike against it; two strikes park the feature until the user flips any interface
 * switch (which bumps {@code ui_epoch}) or a new module build is installed. The backdrop is shared, so its crashes count for both.
 */
final class UiGuard {
    static final String LYRICS = "strikes", PLAYER = "player_strikes", TAB = "tab_strikes";
    private static final String PREFS = "qishui_automix_ui_guard";
    private static final String PKG = "io.github.qishui.automix.";
    private static boolean installed;

    static boolean allowed(Context context, String feature, int epoch) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // A new switch epoch or a new module build (which may carry the fix) starts with a clean slate.
        if (prefs.getInt("epoch", -1) != epoch || !HostDiagnostics.VERSION.equals(prefs.getString("version", ""))) {
            prefs.edit().putInt("epoch", epoch).putString("version", HostDiagnostics.VERSION)
                .putInt(LYRICS, 0).putInt(PLAYER, 0).putInt(TAB, 0).apply();
            return true;
        }
        return prefs.getInt(feature, 0) < 2;
    }

    static synchronized void install(Context app) {
        if (installed) return;
        installed = true;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                boolean lyrics = false, player = false, tab = false;
                for (Throwable e = error; e != null; e = e.getCause())
                    for (StackTraceElement frame : e.getStackTrace()) {
                        String name = frame.getClassName();
                        if (!name.startsWith(PKG)) continue;
                        if (name.startsWith(PKG + "LyricsBackdrop")) { lyrics = true; player = true; }
                        else if (name.startsWith(PKG + "Lyrics")) lyrics = true;
                        else if (name.startsWith(PKG + "Player")) player = true;
                        else if (name.startsWith(PKG + "Tab")) tab = true;
                    }
                if (lyrics || player || tab) {
                    SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    SharedPreferences.Editor edit = prefs.edit();
                    if (lyrics) edit.putInt(LYRICS, prefs.getInt(LYRICS, 0) + 1);
                    if (player) edit.putInt(PLAYER, prefs.getInt(PLAYER, 0) + 1);
                    if (tab) edit.putInt(TAB, prefs.getInt(TAB, 0) + 1);
                    edit.commit();
                }
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private UiGuard() { }
}

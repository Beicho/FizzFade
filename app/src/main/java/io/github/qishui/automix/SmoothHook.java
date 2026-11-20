package io.github.qishui.automix;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * App-wide smoothness: asks for the display's highest refresh rate at the current resolution on
 * every host window, instead of the system's default for the app. The real device ran the host at
 * 73fps on a 90Hz setting while capable of more; the request is a hint the system may still cap.
 *
 * <p>Hook point (behind {@code ui_refresh}): {@code Activity.onResume()} in the host process.
 */
final class SmoothHook {
    private static boolean logged;

    static void install(Context context, ClassLoader loader, HostDiagnostics diagnostics) {
        try { throttleBlur(loader, diagnostics); }
        catch (Throwable error) { diagnostics.error("blur_throttle_failed", error); }
        if (!diagnostics.ui().refresh) { diagnostics.event("refresh_off", "高刷新率请求已关闭"); return; }
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { request((Activity) p.thisObject, diagnostics); }
                    catch (Throwable error) { diagnostics.errorThrottled("refresh_failed", error, 60000); }
                }
            });
            diagnostics.event("refresh_ready", "hook=Activity.onResume");
        } catch (Throwable error) { diagnostics.error("refresh_hook_failed", error); }
    }

    static final String BLUR_LISTENER = "com.github.mmin18.widget.RealtimeBlurView$1";
    private static final long BLUR_INTERVAL_MS = 66;

    /**
     * The host's RealtimeBlurView (我的 header, profiles, search) draws the whole window into a
     * bitmap in software and blurs it on every frame of any animation on screen. Behind
     * {@code ui_blur_throttle}: at most ~15 copies a second; a skipped frame schedules a trailing
     * copy so the blur always settles on the final picture.
     */
    private static void throttleBlur(ClassLoader loader, HostDiagnostics diagnostics) throws Throwable {
        if (!diagnostics.ui().blurThrottle) { diagnostics.event("blur_throttle_off", "实时模糊保持每帧"); return; }
        Class<?> listener;
        try { listener = LyricsHost.load(loader, BLUR_LISTENER); }
        catch (Throwable missing) { diagnostics.event("blur_throttle_absent", BLUR_LISTENER); return; }
        if (!android.view.ViewTreeObserver.OnPreDrawListener.class.isAssignableFrom(listener)) throw new ClassCastException(BLUR_LISTENER);
        java.lang.reflect.Field outer = null;
        for (java.lang.reflect.Field f : listener.getDeclaredFields())
            if (android.view.View.class.isAssignableFrom(f.getType())) { f.setAccessible(true); outer = f; break; }
        if (outer == null) throw new NoSuchFieldException("RealtimeBlurView outer");
        final java.lang.reflect.Field view = outer;
        java.util.Map<Object, long[]> last = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        XposedHelpers.findAndHookMethod(listener, "onPreDraw", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (!diagnostics.ui().blurThrottle) return;
                    long now = android.os.SystemClock.uptimeMillis();
                    long[] at = last.get(p.thisObject);
                    if (at == null) { at = new long[2]; last.put(p.thisObject, at); }
                    long wait = BLUR_INTERVAL_MS - (now - at[0]);
                    if (at[0] != 0 && wait > 0) {
                        // Skip this copy; one trailing frame makes the next copy see the final picture.
                        if (at[1] < now) {
                            at[1] = now + wait;
                            android.view.View v = (android.view.View) view.get(p.thisObject);
                            if (v != null) v.postInvalidateDelayed(wait);
                        }
                        p.setResult(true);
                        return;
                    }
                    at[0] = now;
                } catch (Throwable error) { diagnostics.errorThrottled("blur_throttle_failed", error, 60000); }
            }
        });
        diagnostics.event("blur_throttle_ready", "interval_ms=" + BLUR_INTERVAL_MS);
    }

    private static void request(Activity activity, HostDiagnostics diagnostics) {
        if (!diagnostics.ui().refresh) return;
        Window window = activity.getWindow();
        Display display = Build.VERSION.SDK_INT >= 30 ? activity.getDisplay() : activity.getWindowManager().getDefaultDisplay();
        if (window == null || display == null) return;
        Display.Mode now = display.getMode(), best = now;
        StringBuilder modes = new StringBuilder();
        for (Display.Mode m : display.getSupportedModes()) {
            modes.append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight()).append('@').append(Math.round(m.getRefreshRate())).append(' ');
            if (m.getPhysicalWidth() == now.getPhysicalWidth() && m.getPhysicalHeight() == now.getPhysicalHeight()
                && m.getRefreshRate() > best.getRefreshRate() + 0.5f) best = m;
        }
        WindowManager.LayoutParams lp = window.getAttributes();
        if (lp.preferredDisplayModeId != best.getModeId()) {
            lp.preferredDisplayModeId = best.getModeId();
            window.setAttributes(lp);
        }
        if (!logged) {
            logged = true;
            diagnostics.event("refresh_request", "activity=" + activity.getClass().getSimpleName() + " current=" + Math.round(now.getRefreshRate())
                + " requested=" + Math.round(best.getRefreshRate()) + " modes=" + modes.toString().trim());
        }
    }
}

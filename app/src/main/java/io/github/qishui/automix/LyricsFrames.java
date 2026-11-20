package io.github.qishui.automix;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.view.FrameMetrics;
import android.view.Window;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Whole-window frame timing from {@link FrameMetrics} while one of our views is animating. Unlike the
 * view's own counters this includes the host's views, the render thread and (API 31+) the GPU, so
 * it shows where frames are actually lost. Callbacks and reports both run on the given handler.
 */
final class LyricsFrames implements Window.OnFrameMetricsAvailableListener {
    private static final int CAP = 8192;

    private final Window window;
    private final BooleanSupplier gate;
    private final float[] total = new float[CAP], gpu = new float[CAP];
    private int n, gaps, deadlines;
    private long over, dropped, lastVsync, gapNanos, deadlineNanos;

    private LyricsFrames(Window window, BooleanSupplier gate) { this.window = window; this.gate = gate; }

    /** {@code gate} says whether our view is animating; only those frames are counted. */
    static LyricsFrames start(android.view.View view, BooleanSupplier gate, Handler handler) {
        Activity activity = activity(view.getContext());
        Window window = activity == null ? null : activity.getWindow();
        if (window == null) return null;
        LyricsFrames frames = new LyricsFrames(window, gate);
        window.addOnFrameMetricsAvailableListener(frames, handler);
        return frames;
    }

    void stop() {
        try { window.removeOnFrameMetricsAvailableListener(this); } catch (Throwable ignored) { }
    }

    @Override public void onFrameMetricsAvailable(Window w, FrameMetrics metrics, int dropCount) {
        if (!gate.getAsBoolean()) { lastVsync = 0; return; }
        if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) return;
        dropped += dropCount;
        long totalNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
        long gpuNanos = Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.GPU_DURATION) : -1;
        if (n < CAP) { total[n] = totalNanos / 1e6f; gpu[n] = gpuNanos / 1e6f; n++; }
        if (Build.VERSION.SDK_INT >= 31) {
            long deadline = metrics.getMetric(FrameMetrics.DEADLINE);
            if (deadline > 0) {
                deadlines++;
                deadlineNanos += deadline;
                if (totalNanos > deadline || gpuNanos > deadline) over++;
            }
        }
        long vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
        if (lastVsync != 0 && vsync > lastVsync && vsync - lastVsync < 100_000_000L) { gaps++; gapNanos += vsync - lastVsync; }
        lastVsync = vsync;
    }

    /** Summary since the previous report, then resets; null when nothing was drawn. */
    String report() {
        if (n == 0) return null;
        float[] t = Arrays.copyOf(total, n), g = Arrays.copyOf(gpu, n);
        Arrays.sort(t);
        Arrays.sort(g);
        String out = String.format(Locale.ROOT,
            "frames=%d fps=%.1f budget_ms=%.2f over_budget=%d dropped_reports=%d total_p50=%.2f p95=%.2f p99=%.2f max=%.2f gpu_p50=%.2f p95=%.2f max=%.2f",
            n, gapNanos == 0 ? 0f : gaps * 1e9f / gapNanos, deadlines == 0 ? 0f : deadlineNanos / 1e6f / deadlines, over, dropped,
            pick(t, .50f), pick(t, .95f), pick(t, .99f), t[n - 1], pick(g, .50f), pick(g, .95f), g[n - 1]);
        n = gaps = deadlines = 0;
        over = dropped = lastVsync = gapNanos = deadlineNanos = 0;
        return out;
    }

    private static float pick(float[] sorted, float q) {
        return sorted[Math.min(sorted.length - 1, (int) (q * sorted.length))];
    }

    static Activity activity(Context context) {
        for (Context c = context; c instanceof ContextWrapper; c = ((ContextWrapper) c).getBaseContext())
            if (c instanceof Activity) return (Activity) c;
        return null;
    }
}

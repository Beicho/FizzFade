package io.github.qishui.automix;

/** Immutable settings, also usable by the JVM test harness. */
public final class MixConfig {
    public static final int DEFAULT_DURATION_MS = 6000;
    public final boolean enabled;
    public final int durationMs;
    public final boolean smoothCurve;
    public final boolean preRender;
    public final boolean adaptive;
    public final boolean beatMatch;

    public MixConfig(boolean enabled, int durationMs, boolean smoothCurve, boolean preRender) {
        this(enabled, durationMs, smoothCurve, preRender, false);
    }

    public MixConfig(boolean enabled, int durationMs, boolean smoothCurve, boolean preRender, boolean adaptive) {
        this(enabled, durationMs, smoothCurve, preRender, adaptive, false);
    }

    public MixConfig(boolean enabled, int durationMs, boolean smoothCurve, boolean preRender, boolean adaptive, boolean beatMatch) {
        this.beatMatch = beatMatch;
        this.enabled = enabled;
        this.durationMs = Math.max(2000, Math.min(12000, durationMs));
        this.smoothCurve = smoothCurve;
        this.preRender = preRender;
        this.adaptive = adaptive;
    }

    public static MixConfig unavailable() {
        return new MixConfig(false, DEFAULT_DURATION_MS, true, false);
    }
}

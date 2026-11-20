package io.github.qishui.automix;

/** Owns gain and cleanup only; queue changes and authorization stay in the host. */
public final class MixSession {
    public interface Output {
        void gains(float outgoing, float incoming) throws Exception;
        void finish(boolean completed, String reason);
    }

    public enum Phase { WAITING, BLENDING, CLOSED }
    private final Output output;
    private final MixConfig config;
    private final long absoluteDeadline;
    private final long startupDeadline;
    private long fadeStarted;
    private long fadeDuration;
    private Phase phase = Phase.WAITING;
    private boolean audibleSeen;

    public MixSession(Output output, MixConfig config, long now, long remainingMs) throws Exception {
        this.output = output;
        this.config = config;
        long startupBudget = Math.max(100, Math.min(config.adaptive ? 3500 : 1500, remainingMs - 500));
        this.startupDeadline = now + startupBudget;
        this.absoluteDeadline = now + Math.min(Math.max(0, remainingMs), startupBudget + config.durationMs) + 1000;
        output.gains(1f, 0f);
    }

    public Phase phase() { return phase; }

    public void tick(long now, boolean incomingAudible, boolean incomingPlaying, long remainingMs) {
        if (phase == Phase.CLOSED) return;
        try {
            if (now > absoluteDeadline
                || phase == Phase.BLENDING && now > fadeStarted + fadeDuration + 1000) {
                close(false, "watchdog");
                return;
            }
            if (phase == Phase.WAITING) {
                if (now >= startupDeadline || remainingMs < 350) {
                    close(false, "incoming_start_timeout");
                    return;
                }
                if (!incomingAudible || !incomingPlaying) return;
                audibleSeen = true;
                fadeStarted = now;
                fadeDuration = Math.max(1, Math.min(config.durationMs, remainingMs - 100));
                phase = Phase.BLENDING;
            }
            if (!incomingPlaying) {
                close(false, "incoming_stopped");
                return;
            }
            double t = (now - fadeStarted) / (double) fadeDuration;
            if (t >= 1 || remainingMs <= 0) {
                close(audibleSeen, "completed");
                return;
            }
            float[] gains = curve(t, config.smoothCurve);
            output.gains(gains[0], gains[1]);
        } catch (Exception error) {
            close(false, "gain_error");
        }
    }

    public void cancel(String reason) { close(false, reason); }

    private void close(boolean completed, String reason) {
        if (phase == Phase.CLOSED) return;
        phase = Phase.CLOSED;
        // The host adapter restores gains and disposes the deferred engine in finally.
        output.finish(completed, reason);
    }

    public static float[] curve(double position, boolean smooth) {
        double t = Double.isFinite(position) ? Math.max(0, Math.min(1, position)) : 0;
        if (!smooth) return new float[] {(float) (1 - t), (float) t};
        // Ease the edges to avoid an abrupt change in the gain slope.
        double eased = t * t * (3 - 2 * t);
        double outgoing = Math.cos(eased * Math.PI / 2);
        double incoming = Math.sin(eased * Math.PI / 2);
        // Headroom prevents correlated full-scale signals from exceeding unity in the sum.
        double headroom = Math.max(1, outgoing + incoming);
        return new float[] {(float) (outgoing / headroom), (float) (incoming / headroom)};
    }
}

package io.github.qishui.automix;

/**
 * Playback position between the host's coarse callbacks. Samples arrive on the player thread,
 * frames read on the main thread; each state is one immutable snapshot so a frame never sees a
 * half-written update.
 */
final class LyricsClock {
    private static final class State {
        final long ms, nanos, lastSample, cap;
        final float rate;
        State(long ms, long nanos, float rate, long lastSample, long cap) {
            this.ms = ms; this.nanos = nanos; this.rate = rate; this.lastSample = lastSample; this.cap = cap;
        }
    }

    private volatile State state = new State(0, 0, 0f, 0, 400_000_000L);
    private volatile boolean known, paused;
    private volatile float speed = 1f;
    private long interval = 250_000_000L;  // player thread only

    boolean known() { return known; }

    /** Position in ms at {@code now} (System.nanoTime base). */
    long at(long now) {
        State s = state;
        if (s.rate == 0f) return s.ms;
        long elapsed = Math.min(Math.max(0, now - s.nanos), Math.max(0, s.lastSample + s.cap - s.nanos));
        return s.ms + (long) (elapsed / 1e6 * s.rate);
    }

    boolean running() { return state.rate != 0f; }

    /** Reports stopped arriving long enough ago that extrapolation has hit its cap. */
    boolean stale(long now) { State s = state; return s.lastSample > 0 && now - s.lastSample > s.cap; }

    /** A position report from the host. Small disagreements are blended so the sweep never jitters. */
    synchronized void sample(long ms, long now) {
        State s = state;
        if (known && s.lastSample > 0) {
            long gap = now - s.lastSample;
            if (gap > 0 && gap < 3_000_000_000L) interval = (interval * 3 + gap) / 4;
        }
        boolean advancing = known && ms != s.ms && ms > s.ms - 50 && ms - s.ms < 5000;
        if (advancing) paused = false;
        float rate = paused ? 0f : (advancing || s.rate != 0f) ? speed : 0f;
        long base = ms;
        if (rate != 0f && s.rate != 0f) {
            long predicted = at(now), error = ms - predicted;
            if (Math.abs(error) < 120) base = predicted + error / 4;
        }
        long cap = Math.max(400_000_000L, Math.min(2_000_000_000L, interval * 3));
        state = new State(base, now, rate, now, cap);
        known = true;
    }

    /** Explicit transport state; time freezes exactly where it is. */
    synchronized void playing(boolean playing, long now) {
        State s = state;
        paused = !playing;
        long ms = at(now);
        state = new State(ms, now, playing ? speed : 0f, playing ? now : s.lastSample, s.cap);
    }

    synchronized void speed(float value, long now) {
        if (!(value > 0.2f && value < 4f)) return;
        long ms = at(now);
        speed = value;
        State s = state;
        state = new State(ms, now, s.rate == 0f ? 0f : value, s.lastSample, s.cap);
    }

    /** Immediate jump, used right after the user taps a line. */
    synchronized void jump(long ms, long now) {
        State s = state;
        state = new State(ms, now, s.rate, now, s.cap);
        known = true;
    }
}

package io.github.qishui.automix;

/**
 * Pure logic for the play page skin, kept free of Android types so it runs in JVM tests: whether
 * the song is playing (listened, never queried), and the springs.
 */
final class PlayerModel {
    /**
     * Playing state from listener callbacks only. An explicit state event wins; until the first one
     * arrives, fresh position reports mean playing and silence means paused.
     */
    static final class PlayState {
        private static final long FRESH_NANOS = 1_200_000_000L;
        private volatile int explicit = -1;      // -1 unknown, 0 paused, 1 playing
        private volatile long lastTime, explicitAt, lastPosition = Long.MIN_VALUE;

        void state(String name, long now) {
            if (name == null) return;
            if (name.endsWith("PLAYING")) { explicit = 1; explicitAt = now; }
            else if (name.endsWith("PAUSED") || name.endsWith("STOPPED") || name.endsWith("ERROR")) { explicit = 0; explicitAt = now; }
        }

        /** A position report; only a position that moved counts as evidence of playback. */
        void time(long position, long now) {
            long previous = lastPosition;
            lastPosition = position;
            if (previous == Long.MIN_VALUE || position == previous) return;
            lastTime = now;
            // Position moving well after a pause event means playback resumed without a state event.
            if (explicit == 0 && now - explicitAt > FRESH_NANOS / 2) explicit = -1;
        }

        boolean playing(long now) {
            int e = explicit;
            if (e >= 0) return e == 1;
            long t = lastTime;
            return t != 0 && now - t < FRESH_NANOS;
        }

        boolean known() { return explicit >= 0 || lastTime != 0; }
    }

    /** Damped spring integrated in small fixed steps; returns whether it is still moving. */
    static boolean spring(float[] x, float[] v, int i, float goal, float k, float zeta, float dt, float rest) {
        float c = 2f * zeta * (float) Math.sqrt(k);
        float remaining = dt;
        while (remaining > 0) {
            float h = Math.min(remaining, 1f / 240f);
            v[i] += (-k * (x[i] - goal) - c * v[i]) * h;
            x[i] += v[i] * h;
            remaining -= h;
        }
        if (Math.abs(x[i] - goal) < rest && Math.abs(v[i]) < rest * 4) { x[i] = goal; v[i] = 0; return false; }
        return true;
    }

    private PlayerModel() { }
}

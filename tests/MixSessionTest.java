package io.github.qishui.automix;

public final class MixSessionTest {
    private static int assertions;

    private static void check(boolean value, String description) {
        assertions++;
        if (!value) throw new AssertionError(description);
    }

    private static final class Sink implements MixSession.Output {
        float outgoing, incoming;
        int finished;
        boolean success;
        boolean failGain;
        String reason;
        @Override public void gains(float out, float in) throws Exception {
            if (failGain) throw new Exception("Simulated engine loss");
            outgoing = out; incoming = in;
        }
        @Override public void finish(boolean success, String reason) {
            this.finished++; this.success = success; this.reason = reason;
        }
    }

    public static void main(String[] args) throws Exception {
        for (boolean smooth : new boolean[] {false, true}) {
            float priorOut = 1, priorIn = 0;
            for (int step = 0; step <= 1000; step++) {
                float[] gain = MixSession.curve(step / 1000d, smooth);
                check(gain[0] >= 0 && gain[1] >= 0, "nonnegative gain");
                check(gain[0] + gain[1] <= 1.000001, "headroom for correlated peaks");
                check(gain[0] <= priorOut + 1e-6 && gain[1] >= priorIn - 1e-6, "monotonic transition");
                check(Math.abs(gain[0] - priorOut) < 0.002 && Math.abs(gain[1] - priorIn) < 0.002, "continuous gain");
                priorOut = gain[0]; priorIn = gain[1];
            }
            check(Math.abs(MixSession.curve(-5, smooth)[0] - 1) < 1e-6, "clamped start");
            check(Math.abs(MixSession.curve(5, smooth)[1] - 1) < 1e-6, "clamped end");
            check(Float.isFinite(MixSession.curve(Double.NaN, smooth)[0]), "NaN safe");
        }
        MixConfig config = new MixConfig(true, 6000, true, true);
        check(new MixConfig(true, 0, true, true).durationMs == 2000, "lower duration limit");
        check(new MixConfig(true, Integer.MAX_VALUE, true, true).durationMs == 12000, "upper duration limit");
        check(!MixConfig.unavailable().enabled, "provider failure disables mixing");

        Sink sink = new Sink();
        MixSession session = new MixSession(sink, config, 10000, 6900);
        check(sink.outgoing == 1 && sink.incoming == 0, "incoming muted until playback starts");
        session.tick(10200, false, false, 6700);
        check(session.phase() == MixSession.Phase.WAITING, "no fade into silence");
        session.tick(10400, true, true, 6500);
        session.tick(13400, true, true, 3500);
        check(Math.abs(sink.outgoing - 0.5) < 1e-6 && Math.abs(sink.incoming - 0.5) < 1e-6, "real overlap at midpoint");
        session.tick(16401, true, true, 499);
        check(sink.finished == 1 && sink.success, "complete exactly once");
        session.cancel("late pause"); session.tick(20000, true, true, 0);
        check(sink.finished == 1, "idempotent cleanup");

        sink = new Sink(); session = new MixSession(sink, config, 0, 6200);
        session.tick(1500, false, false, 4700);
        check(sink.finished == 1 && !sink.success && "incoming_start_timeout".equals(sink.reason), "startup deadline");

        sink = new Sink(); session = new MixSession(sink, new MixConfig(true, 6000, true, true, true), 0, 6200);
        session.tick(1600, false, true, 4600);
        check(session.phase() == MixSession.Phase.WAITING, "adaptive fade retains outgoing during a silent intro");
        session.tick(2400, true, true, 3800);
        check(session.phase() == MixSession.Phase.BLENDING, "adaptive fade waits for measured onset");
        session.tick(6200, true, true, 0);
        check(sink.success && sink.finished == 1, "late onset shortens fade within remaining tail");

        sink = new Sink(); session = new MixSession(sink, new MixConfig(true, 2200, true, true, true), 0, 8200);
        session.tick(3200, false, true, 5000);
        session.tick(3400, true, true, 4800);
        session.tick(4900, true, true, 3300);
        check(session.phase() == MixSession.Phase.BLENDING && sink.finished == 0,
            "a quiet intro does not consume the short fade's watchdog budget");
        session.tick(5600, true, true, 2600);
        check(sink.success && sink.finished == 1, "short fade completes after a long adaptive startup");

        sink = new Sink(); session = new MixSession(sink, new MixConfig(true, 2200, true, true, true), 0, 8200);
        session.tick(7200, true, true, 1000);
        check(sink.finished == 1 && "watchdog".equals(sink.reason),
            "absolute watchdog still closes a stalled scheduler with an overreported tail");

        sink = new Sink(); session = new MixSession(sink, config, 0, 6200);
        session.tick(100, true, true, 6100);
        session.tick(300, true, false, 5900);
        check(sink.finished == 1 && !sink.success, "pause cancels");

        sink = new Sink(); session = new MixSession(sink, config, 0, 2300);
        session.tick(100, true, true, 2200);
        session.tick(2200, true, true, 100);
        check(sink.finished == 1 && sink.success, "short remaining tail caps fade duration");

        sink = new Sink(); session = new MixSession(sink, config, 0, 6200);
        sink.failGain = true;
        session.tick(100, true, true, 6100);
        check(sink.finished == 1 && "gain_error".equals(sink.reason), "engine failure cleans up");

        sink = new Sink(); session = new MixSession(sink, config, 0, 6200);
        session.tick(9000, true, true, 3000);
        check(sink.finished == 1 && "watchdog".equals(sink.reason), "scheduler stall cannot leave overlapping engine");

        for (String reason : new String[] {"seek", "next", "focus_loss", "source_changed", "disabled"}) {
            sink = new Sink(); session = new MixSession(sink, config, 0, 6200);
            session.tick(100, true, true, 6100);
            session.cancel(reason); session.cancel(reason);
            check(sink.finished == 1 && !sink.success && reason.equals(sink.reason), "external interruption: " + reason);
        }
        System.out.println("PASS " + assertions + " assertions; curves, overlap, deadlines, cancellation and failures");
    }
}

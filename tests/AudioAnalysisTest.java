package io.github.qishui.automix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class AudioAnalysisTest {
    private static int assertions;
    private static void check(boolean condition, String name) {
        assertions++;
        if (!condition) throw new AssertionError(name);
    }

    private static ByteBuffer pcm(float amplitude, boolean floating, int channels, int samples) {
        ByteBuffer buffer = ByteBuffer.allocate(16 + samples * channels * (floating ? 4 : 2)).order(ByteOrder.nativeOrder());
        buffer.position(16);
        for (int i = 0; i < samples * channels; i++) {
            if (floating) buffer.putFloat(amplitude);
            else buffer.putShort((short) (amplitude * 32767));
        }
        buffer.position(16);
        return buffer;
    }

    interface Level { float at(int ms); }
    private static boolean fadeSeen(Level level) { return fadeSeen(level, 16000); }
    /** True if a fade-out is reported at any 250 ms check; steady inputs must never report one. */
    private static boolean fadeSeen(Level level, int millis) {
        PcmProbe probe = new PcmProbe();
        boolean seen = false;
        for (int ms = 0; ms < millis; ms += 10) {
            // Alternating sign keeps it audio-like; the probe measures energy, not waveform shape.
            ByteBuffer buffer = pcm(level.at(ms) * ((ms / 10) % 2 == 0 ? 1 : -1), true, 1, 480);
            probe.accept(new ByteBuffer[] {buffer}, 480, 48000, 1, 3, 1000 + ms);
            if (ms % 250 == 0) {
                PcmProbe.Snapshot snapshot = probe.snapshot(1000 + ms);
                if (snapshot.fadingOut()) { seen = true; check(snapshot.fadeRatio <= 0.5, "fade ratio bounded"); }
            }
        }
        return seen;
    }

    public static void main(String[] args) {
        check("full".equals(MediaEligibility.resource("", "FULL")), "prepared pre-render without real resource is full via source");
        check("full".equals(MediaEligibility.resource("FULL", "FULL")), "both resources agree");
        check(!"full".equals(MediaEligibility.resource("", "PREVIEW")), "preview source rejected");
        check(!"full".equals(MediaEligibility.resource("PREVIEW", "FULL")), "explicit preview never overridden");
        check(!"full".equals(MediaEligibility.resource("FULL", "PREVIEW")), "conflicting resource rejected");
        check(!"full".equals(MediaEligibility.resource(null, null)), "unknown resource fails closed");
        check(!MediaEligibility.same("", ""), "empty IDs cannot match");
        check(MediaEligibility.same("song-A", "song-A"), "same nonempty identity");

        for (int format : new int[] {1, 3, 6, 8}) {
            boolean floating = format == 3 || format == 8, planar = format >= 5;
            PcmProbe probe = new PcmProbe();
            ByteBuffer left = pcm(0.25f, floating, planar ? 1 : 2, 480);
            ByteBuffer right = pcm(-0.25f, floating, 1, 480);
            ByteBuffer[] buffers = planar ? new ByteBuffer[] {left, right} : new ByteBuffer[] {left};
            for (int i = 0; i < 100; i++) probe.accept(buffers, 480, 48000, 2, format, 1000 + i * 10);
            PcmProbe.Snapshot snapshot = probe.snapshot(2000);
            check(Math.abs(snapshot.rms - 0.25) < 0.001, "energy for PCM format " + format);
            check(left.position() == 16 && right.position() == 16, "host positions unchanged " + format);
            check(snapshot.bpm == 0 && !snapshot.rhythmic(), "constant audio does not invent beats");
            check(!probe.snapshot(3000).fresh, "stale audio is not fresh");
            probe.reset();
            check(probe.snapshot(3000).frames == 0, "seek resets analysis");
        }
        PcmProbe probe = new PcmProbe();
        ByteBuffer silence = pcm(0, true, 1, 480);
        for (int i = 0; i < 100; i++) probe.accept(new ByteBuffer[] {silence}, 480, 48000, 1, 3, 1000 + i * 10);
        check(probe.snapshot(2000).silenceMs == 1000, "silent tail duration");
        probe.accept(new ByteBuffer[] {pcm(Float.NaN, true, 1, 480)}, 480, 48000, 1, 3, 2000);
        check(probe.snapshot(2000).frames == 100, "invalid audio ignored");
        for (double tempo : new double[] {90, 120, 150}) {
            probe = new PcmProbe();
            ByteBuffer loud = pcm(0.6f, true, 1, 480);
            ByteBuffer quiet = pcm(0.015f, true, 1, 480);
            for (int ms = 0; ms < 14000; ms += 10) {
                double phase = ms % (60000d / tempo);
                probe.accept(new ByteBuffer[] {phase < 30 ? loud : quiet}, 480, 48000, 1, 3, 1000 + ms);
            }
            PcmProbe.Snapshot snapshot = probe.snapshot(15000);
            check(Math.abs(snapshot.bpm - tempo) < 5, "pulse tempo " + tempo + " observed=" + snapshot.bpm);
            check(snapshot.rhythmic(), "repeated pulse has sufficient confidence " + tempo);
        }
        // Outro fade: loud body, then a mastered fade-out; steady and settled-quiet endings are not fades.
        check(!fadeSeen(ms -> 0.3f), "steady level is not a fade");
        check(fadeSeen(ms -> ms < 10000 ? 0.3f : (float) (0.3 * Math.exp(-(ms - 10000) / 2500d)))
            , "exponential fade-out detected");
        check(!fadeSeen(ms -> ms < 10000 ? 0.3f : ms < 11000 ? 0.3f - (ms - 10000) * 0.0002f : 0.1f, 18000)
            , "settled quieter outro is not a fade");
        check(!fadeSeen(ms -> 0f), "silence is not a fade");
        System.out.println("PASS " + assertions + " audio/eligibility assertions; pre-render regression, PCM layouts, buffer ownership, silence, tempo and outro fade");
    }
}

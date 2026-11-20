package io.github.qishui.automix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded, read-only analysis of PCM already decoded by the host. Stores no audio. */
public final class PcmProbe {
    private final float[] envelope = new float[768];
    private int write, count;
    private double bucketMs, bucketPower;
    private long frames, lastAt, silenceMs;
    private float rms, peak, bassRms;
    private final double[] bassLow = new double[8], dcLow = new double[8];
    private long analyzedAt, fadeAt;
    private double bpm, confidence, fadeRatio = 1;
    private boolean fading;

    public synchronized void accept(ByteBuffer[] buffers, int samples, int rate, int channels, int format, long now) {
        if (buffers == null || samples < 1 || samples > 16384 || rate < 8000 || rate > 192000
            || channels < 1 || channels > 8) return;
        boolean planar = format >= 5;
        int type = planar ? format - 5 : format;
        int bytes = type == 0 ? 1 : type == 1 ? 2 : type == 2 || type == 3 ? 4 : type == 4 ? 8 : 0;
        if (bytes == 0 || buffers.length < (planar ? channels : 1)) return;
        ByteBuffer[] views = new ByteBuffer[planar ? channels : 1];
        for (int channel = 0; channel < views.length; channel++) {
            if (buffers[channel] == null) return;
            views[channel] = buffers[channel].duplicate().order(ByteOrder.nativeOrder());
            if ((long) samples * bytes * (planar ? 1 : channels) > views[channel].remaining()) return;
        }
        double energy = 0, bassEnergy = 0;
        float maximum = 0;
        int readings = 0;
        // At most 256 frames per callback, regardless of the decoder's frame size.
        int stride = Math.max(1, (samples + 255) / 256);
        double lowAlpha = 1 - Math.exp(-2 * Math.PI * 180 * stride / rate);
        double dcAlpha = 1 - Math.exp(-2 * Math.PI * 20 * stride / rate);
        for (int frame = 0; frame < samples; frame += stride) {
            for (int channel = 0; channel < channels; channel++) {
                ByteBuffer buffer = views[planar ? channel : 0];
                int index = buffer.position() + (planar ? frame : frame * channels + channel) * bytes;
                double value;
                switch (type) {
                    case 0: value = ((buffer.get(index) & 255) - 128) / 128d; break;
                    case 1: value = buffer.getShort(index) / 32768d; break;
                    case 2: value = buffer.getInt(index) / 2147483648d; break;
                    case 3: value = buffer.getFloat(index); break;
                    default: value = buffer.getDouble(index);
                }
                if (!Double.isFinite(value) || Math.abs(value) > 8) return;
                energy += value * value;
                dcLow[channel] += dcAlpha * (value - dcLow[channel]);
                bassLow[channel] += lowAlpha * (value - dcLow[channel] - bassLow[channel]);
                bassEnergy += bassLow[channel] * bassLow[channel];
                maximum = Math.max(maximum, (float) Math.abs(value));
                readings++;
            }
        }
        addPower(energy / Math.max(1, readings), bassEnergy / Math.max(1, readings), maximum, samples * 1000d / rate, now);
    }

    private void addPower(double power, double bassPower, float maximum, double millis, long now) {
        if (lastAt != 0 && now - lastAt > 1000) reset();
        lastAt = now;
        frames++;
        rms = (float) Math.sqrt(power);
        bassRms = (float) Math.sqrt(bassPower);
        peak = Math.max(maximum, peak * 0.92f);
        silenceMs = rms < 0.002f ? silenceMs + Math.round(millis) : 0;
        while (millis > 0) {
            double used = Math.min(millis, 20 - bucketMs);
            bucketMs += used;
            bucketPower += power * used;
            millis -= used;
            if (bucketMs >= 19.999) {
                envelope[write] = (float) Math.sqrt(bucketPower / bucketMs);
                write = (write + 1) % envelope.length;
                count = Math.min(envelope.length, count + 1);
                bucketMs = bucketPower = 0;
            }
        }
    }

    public synchronized void reset() {
        write = count = 0;
        bucketMs = bucketPower = 0;
        frames = lastAt = silenceMs = analyzedAt = 0;
        rms = peak = bassRms = 0;
        java.util.Arrays.fill(bassLow, 0); java.util.Arrays.fill(dcLow, 0);
        bpm = confidence = 0;
        fadeAt = 0; fadeRatio = 1; fading = false;
    }

    public synchronized Snapshot snapshot(long now) {
        if (count >= 400 && now - analyzedAt >= 2000) {
            analyzedAt = now;
            estimateTempo();
        }
        if (count >= 400 && now - fadeAt >= 250) {
            fadeAt = now;
            estimateFade();
        }
        boolean fresh = frames > 0 && now >= lastAt && now - lastAt < 400;
        return new Snapshot(fresh, frames, rms, peak, bassRms, silenceMs, bpm, confidence, fadeRatio, fading);
    }

    private void estimateTempo() {
        // Positive energy changes, not the carrier waveform; silence/tone never yield a tempo.
        float[] flux = new float[count];
        double power = 0;
        int attacks = 0;
        for (int i = 1; i < count; i++) {
            float current = envelope[(write - count + i + envelope.length) % envelope.length];
            float previous = envelope[(write - count + i - 1 + envelope.length) % envelope.length];
            flux[i] = Math.max(0, current - previous - 0.004f);
            power += flux[i] * flux[i];
            if (flux[i] > 0.025f) attacks++;
        }
        bpm = confidence = 0;
        if (attacks < 8 || power < 0.02) return;
        float[] smooth = new float[count];
        for (int i = 1; i < count - 1; i++) smooth[i] = flux[i - 1] * 0.25f + flux[i] * 0.5f + flux[i + 1] * 0.25f;
        double best = 0;
        int bestLag = 0;
        for (int lag = 17; lag <= 50; lag++) {
            double xy = 0, xx = 0, yy = 0;
            for (int i = lag + 1; i < count - 1; i++) {
                double x = smooth[i];
                double y = smooth[i - lag];
                xy += x * y; xx += x * x; yy += y * y;
            }
            double score = xy / Math.sqrt(Math.max(1e-12, xx * yy));
            if (score > best) { best = score; bestLag = lag; }
        }
        if (best >= 0.62 && bestLag > 0) { bpm = 3000d / bestLag; confidence = best; }
    }

    /**
     * A mastered fade-out: the last second is at least 6 dB under the loudest earlier
     * second and the last four seconds keep falling. Silence is left to silenceMs.
     */
    private void estimateFade() {
        fadeRatio = 1; fading = false;
        int blocks = count / 50;
        if (blocks < 8) return;
        double[] level = new double[blocks];
        for (int k = 0; k < blocks; k++) {
            double sum = 0;
            for (int j = 0; j < 50; j++) {
                float v = envelope[(write - (blocks - k) * 50 + j + 2 * envelope.length) % envelope.length];
                sum += v * v;
            }
            level[k] = Math.sqrt(sum / 50);
        }
        double reference = 0;
        for (int k = 0; k < blocks - 4; k++) reference = Math.max(reference, level[k]);
        double current = level[blocks - 1];
        if (reference < 0.02 || current < 0.002) return;
        fadeRatio = current / reference;
        // A fade falls for several seconds; a sudden step into a quiet outro, or a settled quiet
        // section, is not one: the decline must already be underway 3 s ago and still falling.
        fading = fadeRatio <= 0.5 && level[blocks - 4] <= reference * 0.85 && level[blocks - 3] >= current * 1.2
            && level[blocks - 4] >= level[blocks - 3] && level[blocks - 3] >= level[blocks - 2] && level[blocks - 2] >= current;
    }

    public static final class Snapshot {
        public final boolean fresh;
        public final long frames, silenceMs;
        public final float rms, peak, bassRms;
        public final double bpm, confidence, fadeRatio;
        private final boolean fading;
        Snapshot(boolean fresh, long frames, float rms, float peak, float bassRms, long silenceMs, double bpm, double confidence,
                 double fadeRatio, boolean fading) {
            this.fresh = fresh; this.frames = frames; this.rms = rms; this.peak = peak; this.bassRms = bassRms;
            this.silenceMs = silenceMs; this.bpm = bpm; this.confidence = confidence;
            this.fadeRatio = fadeRatio; this.fading = fading;
        }
        public boolean rhythmic() { return fresh && confidence >= 0.70 && bpm >= 60 && bpm <= 180; }
        public boolean fadingOut() { return fresh && fading; }
    }
}

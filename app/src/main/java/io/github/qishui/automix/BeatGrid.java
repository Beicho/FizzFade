package io.github.qishui.automix;

/** Small onset envelope, in the song's media time. No PCM is retained. */
public final class BeatGrid {
    private final float[] energy = new float[1600];
    private int count;
    private long origin = -1, lastEnd = -1;
    private double power, bucketMs;
    private Result result = Result.NONE;
    private long analyzedEnd;

    public synchronized void reset() {
        count = 0; origin = lastEnd = -1; power = bucketMs = 0;
        result = Result.NONE; analyzedEnd = 0;
    }

    public synchronized void add(double rms, double durationMs, long startMs) {
        if (!Double.isFinite(rms) || rms < 0 || durationMs <= 0 || durationMs > 300 || startMs < 0) return;
        if (lastEnd >= 0 && Math.abs(startMs - lastEnd) > 180) reset();
        if (origin < 0) origin = startMs;
        lastEnd = startMs + Math.round(durationMs);
        while (durationMs > 0.0001) {
            double used = Math.min(durationMs, 10 - bucketMs);
            power += rms * rms * used; bucketMs += used; durationMs -= used;
            if (bucketMs >= 9.999) {
                if (count == energy.length) {
                    System.arraycopy(energy, 400, energy, 0, energy.length - 400);
                    count -= 400; origin += 4000;
                }
                energy[count++] = (float) Math.sqrt(power / bucketMs);
                power = bucketMs = 0;
            }
        }
    }

    public synchronized Result result() {
        if (count >= 800 && lastEnd - analyzedEnd >= 1000) {
            analyzedEnd = lastEnd; result = analyze();
        }
        return result;
    }

    private Result analyze() {
        // Quiet intros and fading tails keep their rhythm but fall under fixed onset floors.
        // Boost only (never attenuate), bounded to 12 dB, from the loud end of this window.
        float[] sorted = java.util.Arrays.copyOf(energy, count);
        java.util.Arrays.sort(sorted);
        double reference = sorted[(int) (count * 0.95)];
        double gain = reference <= 0 ? 1 : Math.max(1, Math.min(4, 0.3 / reference));
        double[] flux = new double[count];
        double total = 0, sumSquares = 0; int attacks = 0;
        for (int i = 3; i < count - 1; i++) {
            double previous = (energy[i-1] + energy[i-2] + energy[i-3]) / 3d;
            flux[i] = Math.max(0, (energy[i] - previous) * gain - 0.006);
            total += flux[i]; sumSquares += flux[i] * flux[i];
            if (flux[i] > 0.035 && flux[i-1] <= 0.035) attacks++;
        }
        if (attacks < 10 || total < 0.4 || sumSquares < 0.02) return Result.NONE;
        double[] smooth = new double[count];
        for (int i=1;i<count-1;i++) smooth[i]=(flux[i-1]+2*flux[i]+flux[i+1])/4;
        double bestScore = 0;
        double[] scores = new double[88];
        for (int candidate = 35; candidate <= 86; candidate++) {
            double xy=0, xx=0, yy=0;
            for (int i=candidate+3; i<count-1; i++) {
                double x=smooth[i], y=smooth[i-candidate];
                xy+=x*y; xx+=x*x; yy+=y*y;
            }
            scores[candidate]=xy/Math.sqrt(Math.max(1e-12,xx*yy));
            bestScore=Math.max(bestScore,scores[candidate]);
        }
        if (bestScore < 0.60) return Result.NONE;
        // Phase is taken from attack power: kicks dominate, weaker off-beat bass/guitar
        // onsets (syncopation) no longer cancel the pulse they sit between.
        double[] weight = new double[count];
        double weightTotal = 0;
        for (int i = 3; i < count - 1; i++) { weight[i] = flux[i] * flux[i]; weightTotal += weight[i]; }
        double strength=0, period=0, phase=0, selectedScore=0;
        // Compare phase coherence as well as autocorrelation: half-tempo aliases have
        // opposite-phase attacks and must not displace the true pulse.
        for (int lag=35;lag<=86;lag++) {
            if (scores[lag]<Math.max(0.60,bestScore*0.75)
                || scores[lag]<scores[lag-1] || scores[lag]<scores[lag+1]) continue;
            for (double p=lag-1.2; p<=lag+1.2; p+=0.025) {
                double x=0,y=0,step=2*Math.PI/p;
                double cs=Math.cos(step),sn=Math.sin(step),cx=Math.cos(step*3),cy=Math.sin(step*3);
                for (int i=3;i<count-1;i++) {
                    x+=weight[i]*cx; y+=weight[i]*cy;
                    double nextX=cx*cs-cy*sn;
                    cy=cy*cs+cx*sn;cx=nextX;
                }
                double coherence=Math.hypot(x,y)/weightTotal;
                if (coherence>strength) {
                    strength=coherence;period=p*10;phase=Math.atan2(y,x)*p*10/(2*Math.PI);selectedScore=scores[lag];
                }
            }
        }
        double confidence=Math.min(selectedScore, strength);
        if (confidence < 0.70 || period <= 0) return Result.NONE;
        double bpm=60000/period;
        if (bpm < 70 || bpm > 170) return Result.NONE;
        return new Result(bpm, positive(origin + phase, period), confidence, count*10L, origin+count*10L);
    }

    static double positive(double value, double period) { return (value % period + period) % period; }

    public static final class Result {
        public static final Result NONE=new Result(0,0,0,0);
        public final double bpm, phaseMs, confidence;
        public final long analyzedMs, endPositionMs;
        public Result(double bpm,double phaseMs,double confidence,long analyzedMs) {
            this(bpm,phaseMs,confidence,analyzedMs,-1);
        }
        Result(double bpm,double phaseMs,double confidence,long analyzedMs,long endPositionMs) {
            this.bpm=bpm;this.phaseMs=phaseMs;this.confidence=confidence;this.analyzedMs=analyzedMs;this.endPositionMs=endPositionMs;
        }
        public boolean reliable() { return bpm>=70 && bpm<=170 && confidence>=0.70 && analyzedMs>=8000; }
        public boolean reliableAt(long position) { return reliable() && endPositionMs>=0 && Math.abs(endPositionMs-position)<3000; }
        public double period() { return 60000/bpm; }
        public double untilBeat(double position) { return positive(phaseMs-position,period()); }
    }
}

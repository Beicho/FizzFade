package io.github.qishui.automix;

/** Both grids are measured; no tempo is guessed when either song is unobservable. */
public final class BeatMatch {
    public final double rate, targetPositionMs, phaseErrorMs;
    private BeatMatch(double rate,double target,double error) { this.rate=rate;targetPositionMs=target;phaseErrorMs=error; }

    public static BeatMatch plan(BeatGrid.Result out, BeatGrid.Result in, double oldPosition, double newPosition) {
        if (!out.reliable() || !in.reliable()) return null;
        double rate=out.bpm/in.bpm;
        if (!Double.isFinite(rate) || rate<0.92 || rate>1.08) return null;
        double oldBeat=out.untilBeat(oldPosition);
        double nextBeat=newPosition+in.untilBeat(newPosition);
        double target=nextBeat-oldBeat*rate;
        if (target<0) target+=in.period();
        // Align within the measured intro, never jump into unanalysed song sections.
        if (target<0 || target>Math.min(6000,in.analyzedMs-1500)) return null;
        double error=BeatGrid.positive(in.untilBeat(newPosition)/rate-oldBeat+out.period()/2,out.period())-out.period()/2;
        return new BeatMatch(rate,target,error);
    }

    public static float recovery(double rate,long elapsed,long duration) {
        double t=Math.max(0,Math.min(1,elapsed/(double)Math.max(1,duration)));
        t=t*t*(3-2*t);
        return (float)(rate+(1-rate)*t);
    }
}

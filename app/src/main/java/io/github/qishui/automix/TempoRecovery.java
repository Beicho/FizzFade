package io.github.qishui.automix;

import android.os.Handler;
import android.os.SystemClock;

/** Serial rate restoration; external speed/lifecycle changes always take precedence. */
final class TempoRecovery {
    private final HostBridge bridge;
    private final HostDiagnostics diagnostics;
    private final AudioSignals audio;
    private Handler handler;
    private volatile Object engine;
    private long start,duration;
    private float from;
    private final Runnable frame=this::tick;
    TempoRecovery(HostBridge bridge,HostDiagnostics diagnostics) { this(bridge,diagnostics,null); }
    TempoRecovery(HostBridge bridge,HostDiagnostics diagnostics,AudioSignals audio) { this.bridge=bridge;this.diagnostics=diagnostics;this.audio=audio; }
    void begin(Object target,float rate,long millis,Handler owner) {
        cancel(true);engine=target;from=rate;duration=millis;handler=owner;start=SystemClock.elapsedRealtime();
        handler.post(frame);
    }
    void external(Object target) { if (engine==target) cancel(false); }
    boolean controls(Object target) { return engine!=null && engine==target; }
    void cancel(boolean restore) {
        Object target=engine;engine=null;
        if (audio!=null && target!=null) audio.grid(target).reset();
        if (handler!=null) handler.removeCallbacks(frame);
        if (restore && target!=null) try { bridge.tempo(target,1); }
        catch (Exception error) { diagnostics.error("tempo_restore_failed",error); }
    }
    private void tick() {
        Object target=engine;if(target==null)return;
        long elapsed=SystemClock.elapsedRealtime()-start;
        try {
            bridge.tempo(target,BeatMatch.recovery(from,elapsed,duration));
            if(elapsed>=duration) { if(audio!=null)audio.grid(target).reset();engine=null;diagnostics.event("tempo_restored","rate=1.0 smooth=true"); }
            else handler.postDelayed(frame,160);
        } catch(Exception error) { diagnostics.error("tempo_recovery_failed",error);cancel(true); }
    }
}

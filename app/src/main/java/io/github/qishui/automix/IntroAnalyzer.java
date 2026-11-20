package io.github.qishui.automix;

import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded silent pre-roll of an already entitled, prepared host player. */
final class IntroAnalyzer {
    private final HostBridge bridge;
    private final HostDiagnostics diagnostics;
    private final AudioSignals audio;
    private Object engine, wrapper;
    private Handler watchdog;
    private final Runnable timeout=()->cancel("analysis_watchdog",true);
    private long started, rewinding;
    private float volume=1;
    private volatile boolean seekDone, seekOk;
    private boolean cancelling;
    private BeatGrid.Result saved=BeatGrid.Result.NONE;
    private Object savedEngine, unsafeEngine;
    private String analysisId = "", savedId = "";
    private long analysisDuration, savedDuration, savedAt;
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    private final Map<String, CachedIntro> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true));

    static long minimumRemaining(int fadeMs) { return Math.max(24500, 12500L + 2500 + fadeMs + 3000); }

    // Cache metadata only. A different resource on a reused native engine invalidates its grid.
    void invalidate(Object object) {
        if (object == savedEngine) {
            cache.remove(savedId);
            savedEngine = null; savedId = ""; saved = BeatGrid.Result.NONE;
        }
    }
    private static boolean sameDuration(long a, long b) { return a >= 30000 && b >= 30000 && Math.abs(a-b) <= 250; }
    private static final class CachedIntro {
        final BeatGrid.Result grid;
        final long duration, at;
        CachedIntro(BeatGrid.Result grid, long duration, long at) { this.grid=grid;this.duration=duration;this.at=at; }
    }
    private void remember(long now) {
        if (!saved.reliable() || savedId.isEmpty()) return;
        // Source-change hooks may revoke an entry from another host thread. Hold this
        // lock only for metadata; native play/pause/seek/release never runs under it.
        synchronized (cache) {
            cache.put(savedId, new CachedIntro(saved, savedDuration, now));
            while (cache.size() > 16) { Iterator<String> keys=cache.keySet().iterator();keys.next();keys.remove(); }
        }
    }

    IntroAnalyzer(HostBridge bridge,HostDiagnostics diagnostics,AudioSignals audio) {
        this.bridge=bridge;this.diagnostics=diagnostics;this.audio=audio;
    }
    boolean controls(Object object) { return !cancelling && engine!=null && engine==object; }
    /** The engine this analyser last pre-rolled and rewound (it is left PAUSED at 0). */
    boolean analysed(Object object) { return object!=null && object==savedEngine; }
    boolean busy() { return engine!=null; }
    boolean unsafe(Object object) { return object!=null && object==unsafeEngine; }
    BeatGrid.Result result(Object object) {
        long age=SystemClock.elapsedRealtime()-savedAt;
        return savedEngine==object && age>=0 && age<=CACHE_TTL_MS?saved:BeatGrid.Result.NONE;
    }

    void tick(Object owner,Object nextEngine,long remaining,boolean allowed,String nextId,long duration,int fadeMs) throws Exception {
        long now=SystemClock.elapsedRealtime();
        if (engine!=null) {
            if (!allowed || owner!=wrapper || nextEngine!=engine || !analysisId.equals(nextId)
                || !sameDuration(analysisDuration,duration) || remaining<Math.max(8500,fadeMs+2500L)) { cancel("context_changed",true);return; }
            long position=HostBridge.number(HostBridge.call(engine,"getCurrentPlaybackTime"));
            audio.nativePcm.watch(engine,position);
            if (rewinding==0) {
                BeatGrid.Result grid=audio.grid(engine).result();
                if (grid.reliable() || now-started>=12500) {
                    saved=grid;savedEngine=engine;savedId=analysisId;savedDuration=analysisDuration;
                    HostBridge.original(bridge.pause,engine,new Object[0]);
                    rewinding=now;
                    bridge.seek(engine,0,ok->{seekOk=ok;seekDone=true;});
                    diagnostics.event("intro_analysis_rewind","measured_ms="+grid.analyzedMs+" bpm="+grid.bpm+" confidence="+grid.confidence);
                }
            } else if (seekDone) {
                boolean valid=seekOk && position>=0 && position<=250;
                if (!valid) { cancel("rewind_failed",true);return; }
                if (watchdog!=null) watchdog.removeCallbacks(timeout);
                unsafeEngine=null;
                if (!restore()) { cancel("volume_restore_failed",true);return; }
                savedAt=now;
                remember(now);
                diagnostics.event("intro_analysis_ready","bpm="+saved.bpm+" confidence="+saved.confidence+" position="+position);
                engine=wrapper=null; diagnostics.checkpoint("idle");
            } else if (now-rewinding>2500) cancel("rewind_timeout",true);
            return;
        }
        if (!allowed || nextEngine==null) return;
        if (nextEngine==savedEngine && savedId.equals(nextId) && sameDuration(savedDuration,duration)
            && now>=savedAt && now-savedAt<=CACHE_TTL_MS) return;
        if (nextEngine==savedEngine) invalidate(nextEngine);
        CachedIntro hit=cache.get(nextId);
        if (hit!=null && (now<hit.at || now-hit.at>CACHE_TTL_MS || !sameDuration(hit.duration,duration))) {
            cache.remove(nextId); hit=null;
        }
        long nextPosition=HostBridge.number(HostBridge.call(nextEngine,"getCurrentPlaybackTime"));
        if (hit!=null && nextPosition>=0 && nextPosition<=250) {
            savedEngine=nextEngine;saved=hit.grid;savedId=nextId;savedDuration=duration;savedAt=hit.at;
            diagnostics.event("intro_analysis_cached","bpm="+saved.bpm+" confidence="+saved.confidence+" age_ms="+(now-hit.at));
            return;
        }
        if (remaining<minimumRemaining(fadeMs)) return;
        if (nextPosition<0 || nextPosition>250) {
            savedEngine=nextEngine;saved=BeatGrid.Result.NONE;savedId=nextId;savedDuration=duration;savedAt=now;
            diagnostics.event("intro_analysis_skipped","reason=not_at_start position="+nextPosition);
            return;
        }
        engine=nextEngine;wrapper=owner;analysisId=nextId;analysisDuration=duration;
        started=now;rewinding=0;seekDone=seekOk=false;
        volume=HostBridge.decimal(HostBridge.call(engine,"getVolume"));
        if (!Float.isFinite(volume) || volume<=0) volume=1;
        audio.grid(engine).reset();audio.probe(engine).reset();
        audio.nativePcm.watch(engine,HostBridge.number(HostBridge.call(engine,"getCurrentPlaybackTime")));
        try {
            watchdog=new Handler(Looper.myLooper());watchdog.postDelayed(timeout,16000);
            bridge.gain(engine,0,0);
            HostBridge.original(bridge.play,engine,new Object[0]);
            diagnostics.checkpoint("intro_analysis");
            diagnostics.event("intro_analysis_begin","remaining="+remaining+" muted=true max_ms=12500 min_remaining="+minimumRemaining(fadeMs));
        } catch (Exception error) { cancel("start_failed",true);throw error; }
    }

    void cancel(String reason,boolean discard) {
        Object old=engine,owner=wrapper;
        if (old==null || cancelling) return;
        cancelling=true;unsafeEngine=old;saved=BeatGrid.Result.NONE;savedEngine=old;
        savedId=analysisId;savedDuration=analysisDuration;savedAt=SystemClock.elapsedRealtime();
        if (watchdog!=null) watchdog.removeCallbacks(timeout);
        // Keep mute ownership until the native player has stopped or the host has discarded it.
        try { HostBridge.original(bridge.pause,old,new Object[0]); }
        catch (Exception error) { diagnostics.error("intro_pause_failed",error); }
        if (discard) {
            saved=BeatGrid.Result.NONE;savedEngine=old;
            try { bridge.discardIntro(owner,old); }
            catch (Exception error) { diagnostics.error("intro_discard_failed",error); }
        }
        // A discarded player may already have released its native state. Never touch it again.
        engine=wrapper=null; cancelling=false;
        diagnostics.checkpoint("idle");
        diagnostics.event("intro_analysis_cancel","reason="+reason);
    }
    private boolean restore() {
        try { bridge.gain(engine,volume,volume);return true; }
        catch (Exception error) { diagnostics.error("intro_volume_restore_failed",error);return false; }
    }
}

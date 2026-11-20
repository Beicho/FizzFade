package io.github.qishui.automix;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class MixManager {
    /** Fade-outs are detected only in the last part of a song; earlier dips are breakdowns. */
    static final long OUTRO_WINDOW_MS = 20000;
    private final HostBridge bridge;
    private final HostDiagnostics diagnostics;
    final AudioSignals audio;
    final IntroAnalyzer intro;
    final TempoRecovery recovery;
    private final HandlerThread clockThread = new HandlerThread("QishuiMixClock");
    private volatile Handler clock;
    private final Map<Object, Binding> bindings = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, float[]> volumes = Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> switching = new ThreadLocal<>();
    private final Map<Object, Active> retainedSources = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile MixConfig config = MixConfig.unavailable();
    private volatile Active active;
    private volatile String status = "已接入汽水 21.6.0，等待音乐播放";
    private volatile int completed;
    private int beatCompleted;
    private volatile boolean stopped;
    private boolean configConnected;
    private volatile boolean configKnown;
    private String configSummary = "", lastReportedStatus = "";
    private volatile long progressCallbacks;
    private volatile long clockChecks;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            Handler handler = clock;
            if (stopped || handler == null) return;
            if (config.enabled) {
                ArrayList<Binding> snapshot;
                synchronized (bindings) {
                    bindings.values().removeIf(b -> b.wrapper.get() == null || b.inner.get() == null);
                    snapshot = new ArrayList<>(bindings.values());
                }
                for (Binding binding : snapshot) requestCheck(binding, true);
                if (!snapshot.isEmpty()) probeExecutor(snapshot.get(0));
            }
            long blocked = diagnostics.ipcBlockedMs();
            if (blocked > 5000) diagnostics.throttled("ipc_call_blocked", "ms=" + blocked
                + " 配置/状态调用阻塞在模块诊断线程；播放线程不受影响", 15000);
            if (!stopped) handler.postDelayed(this, config.enabled && !bindings.isEmpty() ? 250 : 2000);
        }
    };

    private volatile long probeSince, probeSent;

    /** Every 5 s, time a no-op on the host player executor: a stalled executor drops pause/play. */
    private void probeExecutor(Binding binding) {
        long now = SystemClock.elapsedRealtime();
        long since = probeSince;
        if (since != 0) {
            if (now - since > 3000) diagnostics.throttled("host_executor_stalled", "pending_ms=" + (now - since)
                + " mixing=" + (active != null) + " intro_busy=" + intro.busy(), 5000);
            return;
        }
        if (now - probeSent < 5000) return;
        Object inner = binding.inner.get();
        if (inner == null) return;
        probeSent = now; probeSince = now;
        try {
            bridge.dispatch(inner, () -> {
                long latency = SystemClock.elapsedRealtime() - probeSince;
                probeSince = 0;
                if (latency > 1000) diagnostics.event("host_executor_slow", "latency_ms=" + latency);
            });
        } catch (Throwable error) { probeSince = 0; diagnostics.errorThrottled("host_executor_probe_failed", error, 30000); }
    }

    /** Pause/play at the host's queue controller, one level above the player wrapper. */
    void innerTransport(Object inner, String action) {
        Binding binding = null;
        synchronized (bindings) { for (Binding b : bindings.values()) if (b.inner.get() == inner) { binding = b; break; } }
        Object wrapper = binding == null ? null : binding.wrapper.get();
        String before = "known=" + (binding != null) + " " + mediaState(wrapper)
            + " final_state=" + peek(inner, "getFinalPlaybackState") + " real_playing=" + peek(inner, "isRealPlaying")
            + " thread=" + Thread.currentThread().getName() + " intro_busy=" + intro.busy() + " mixing=" + (active != null);
        diagnostics.event("inner_" + action, before);
        // The host asserts its player thread on every state query: re-check there, never on main.
        later(inner, () -> {
            boolean playing = Boolean.TRUE.equals(peek(inner, "isRealPlaying"));
            if ("pause".equals(action) == playing) diagnostics.event("inner_" + action + "_ineffective", "before{" + before
                + "} after{final_state=" + peek(inner, "getFinalPlaybackState") + " real_playing=" + playing + " " + mediaState(wrapper) + "}");
        });
    }

    /** Runs a diagnostic re-check on the host player thread 600 ms from now. */
    private void later(Object inner, Runnable check) {
        Handler handler = clock;
        if (handler == null || inner == null) return;
        handler.postDelayed(() -> {
            if (stopped) return;
            try { bridge.dispatch(inner, () -> { if (!stopped) check.run(); }); }
            catch (Exception error) { diagnostics.errorThrottled("transport_recheck_failed", error, 30000); }
        }, 600);
    }

    /** The current main MediaPlayer's start flag next to its engine's own state. */
    private String mediaState(Object wrapper) {
        try {
            Object media = wrapper == null ? null : bridge.current(wrapper);
            if (media == null) return "media=none";
            Object engine = bridge.nativeEngine(media);
            return "media_started=" + bridge.engineStarted.getBoolean(media) + " engine_state=" + bridge.engineState(engine)
                + " engine=" + (engine == null ? 0 : System.identityHashCode(engine));
        } catch (Throwable error) { return "media=?"; }
    }

    /**
     * Before the host's MediaPlayer.pause. Its innerPause stops the engine only when MediaPlayer.C
     * is set, and only the host's fresh start sets it. After the intro analysis has rewound a
     * prepared engine (engine PAUSED), the host promotes it through the resume path instead, so C
     * stays unset for the whole track: every pause returned true and the music played on until a
     * track change created a fresh player. An engine that is actually playing is marked started,
     * so the host's own pause path runs unchanged.
     */
    /**
     * After the host's MediaPlayer.play. When it resumed an engine this module analysed (PAUSED at
     * its start) instead of starting it, its fresh start never ran: restore the two flags that
     * start would have set, so pause and a later data reload behave as for any other track.
     */
    void restoreStart(Object media) throws Exception {
        if (media == null || bridge.engineStarted.getBoolean(media)) return;
        Object engine = bridge.nativeEngine(media);
        if (!intro.analysed(engine) || bridge.engineState(engine) != HostBridge.ENGINE_PLAYING) return;
        bridge.engineStarted.setBoolean(media, true);
        bridge.playRequested.setBoolean(media, true);
        diagnostics.event("start_flags_restored", "engine=" + System.identityHashCode(engine) + " mixing=" + (active != null));
    }

    void repairPause(Object media) throws Exception {
        if (media == null || bridge.engineStarted.getBoolean(media)) return;
        Object engine = bridge.nativeEngine(media);
        if (bridge.engineState(engine) != HostBridge.ENGINE_PLAYING) return;
        bridge.engineStarted.setBoolean(media, true);
        diagnostics.event("pause_repaired", "engine=" + System.identityHashCode(engine)
            + " analysed=" + intro.analysed(engine) + " intro_busy=" + intro.busy() + " mixing=" + (active != null));
    }

    MixManager(HostBridge bridge, HostDiagnostics diagnostics) {
        this.bridge = bridge;
        this.diagnostics = diagnostics;
        audio = new AudioSignals(diagnostics);
        intro = new IntroAnalyzer(bridge, diagnostics, audio);
        recovery = new TempoRecovery(bridge, diagnostics, audio);
        // No IPC on the host's startup thread: start from the host-side cache; the diagnostics
        // thread reads the provider within 2 s and keeps the cache current.
        Bundle cached = diagnostics.cachedConfig();
        if (cached != null) {
            applyConfig(cached);
            configConnected = false;
            diagnostics.event("config_cache_loaded", configSummary);
        } else {
            config = MixConfig.unavailable();
            status = "等待首次读取设置";
        }
    }

    MixConfig config() { return config; }

    synchronized void start() {
        if (stopped || clock != null) return;
        clockThread.start();
        clock = new Handler(clockThread.getLooper());
        clock.postDelayed(clockTick, 250);
        diagnostics.event("playback_clock_ready", "interval=250 idle=1000 disabled=2000 dispatch=host_executor position=native_first");
        diagnostics.monitor(() -> {
            if (stopped) return;
            readConfig();
            if (!config.enabled || !config.beatMatch) control(() -> {
                intro.cancel("setting_disabled", true); recovery.cancel(true);
            });
            diagnostics.status(status, completed);
            if (!status.equals(lastReportedStatus)) {
                lastReportedStatus = status;
                diagnostics.event("playback_status", status);
            }
            diagnostics.throttled("playback_heartbeat", "bindings=" + bindings.size()
                + " callbacks=" + progressCallbacks + " mixing=" + (active != null)
                + " clock_checks=" + clockChecks + " config_cached=" + (configKnown && !configConnected)
                + " enabled=" + config.enabled + " completed=" + completed, 30000);
        });
        diagnostics.status(status, completed);
    }

    private void control(Runnable work) {
        Binding binding;
        synchronized (bindings) { binding=bindings.isEmpty()?null:bindings.values().iterator().next(); }
        if (binding == null) return;
        Object inner=binding.inner.get();
        if (inner == null) return;
        Handler handler = clock;
        if (handler != null) handler.post(() -> {
            if (stopped) return;
            try { bridge.dispatch(inner, () -> { if (!stopped) work.run(); }); }
            catch (Exception error) { reportError(error); }
        });
    }

    synchronized void shutdown() {
        stopped = true;
        Handler handler = clock;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            clockThread.quitSafely();
            clock = null;
        }
        config = MixConfig.unavailable();
        intro.cancel("disabled", true); recovery.cancel(true);
        Active current = active;
        if (current != null) current.cancel("disabled");
        diagnostics.monitor(null);
    }

    private void applyConfig(Bundle values) {
        config = new MixConfig(values.getBoolean("enabled"), values.getInt("duration", 6000),
            values.getBoolean("smooth", true), values.getBoolean("prerender", true), values.getBoolean("adaptive", true), values.getBoolean("beatmatch", true));
        configKnown = true;
        configSummary = "enabled=" + config.enabled + " duration=" + config.durationMs
            + " smooth=" + config.smoothCurve + " prerender=" + config.preRender + " adaptive=" + config.adaptive + " beatmatch=" + config.beatMatch;
    }

    private void readConfig() {
        Bundle values = diagnostics.readConfig();
        if (values != null) {
            String previous = configSummary;
            applyConfig(values);
            if (!configConnected) status = "已接入汽水 21.6.0，等待音乐播放";
            configConnected = true;
            if (!configSummary.equals(previous)) diagnostics.event("config_read_ok", configSummary);
        } else {
            configConnected = false;
            if (configKnown) {
                // Background provider availability must not silently overwrite a setting
                // already read in this process. A successful read still applies immediately.
                diagnostics.throttled("config_cached_in_use", "Provider 暂不可达，沿用本进程最后设置 enabled="
                    + config.enabled + " duration=" + config.durationMs, 15000);
            } else {
                config = MixConfig.unavailable();
                status = "已进入汽水进程，但配置不可读；混音已关闭，详见 IPC 日志";
            }
        }
    }

    void reportError(Throwable error) {
        status = "已跳过过渡：接口调用失败（" + error.getClass().getSimpleName() + "）";
        diagnostics.errorThrottled("player_call_failed", error, 10000);
    }

    void bind(Object wrapper, Object inner) {
        if (bindings.containsKey(wrapper)) return;
        bindings.put(wrapper, new Binding(wrapper, inner));
        diagnostics.event("player_bound", "main wrapper=" + wrapper.getClass().getName() + " bindings=" + bindings.size());
    }

    void progress(Object wrapper) {
        progressCallbacks++;
        Binding binding = bindings.get(wrapper);
        if (binding == null) return;
        binding.lastCallback = SystemClock.elapsedRealtime();
        requestCheck(binding, false);
    }

    private void requestCheck(Binding binding, boolean fromClock) {
        Handler handler = clock;
        long now = SystemClock.elapsedRealtime();
        if (handler == null || !config.enabled || stopped
            || now - binding.lastCheck < (fromClock && !binding.playing ? 1000 : 200)) return;
        if (!binding.queued.compareAndSet(false, true)) return;
        binding.lastCheck = now;
        // Leave listener dispatch without depending on the Android main/UI looper.
        boolean posted = handler.post(() -> {
            Object inner = binding.inner.get();
            if (stopped || inner == null) { binding.queued.set(false); return; }
            try {
                bridge.dispatch(inner, () -> {
                    try {
                        if (stopped || !config.enabled) return;
                        evaluate(binding);
                        if (fromClock) {
                            clockChecks++;
                            long at = SystemClock.elapsedRealtime();
                            diagnostics.throttled("playback_clock_check", "checks=" + clockChecks
                                + " callbacks=" + progressCallbacks + " callback_age="
                                + (binding.lastCallback == 0 ? -1 : at - binding.lastCallback)
                                + " playing=" + binding.playing + " mixing=" + (active != null)
                                + " position=" + binding.lastPosition, 15000);
                        }
                    }
                    catch (Exception error) { reportError(error); }
                    finally { binding.queued.set(false); }
                });
            } catch (Exception error) {
                binding.queued.set(false);
                reportError(error);
            }
        });
        if (!posted) binding.queued.set(false);
    }

    private void evaluate(Binding b) throws Exception {
        if (active != null || !config.enabled || stopped || Looper.myLooper() == null) return;
        Object wrapper = b.wrapper.get(), inner = b.inner.get();
        b.playing = false;
        if (wrapper == null || inner == null) return;
        if (!Boolean.TRUE.equals(HostBridge.call(wrapper, "isPlaying"))
            || !Boolean.TRUE.equals(HostBridge.call(inner, "isRealPlaying"))) return;
        b.playing = true;
        Object current = bridge.current(wrapper);
        if (current == null) return;
        Object playable = HostBridge.call(current, "getPlayable");
        HostBridge.MediaFacts currentFacts = bridge.facts(current, false);
        if (!bridge.music(playable) || !currentFacts.full()) {
            skip(b, "current_resource", "当前内容不是已确认的完整音乐", currentFacts.toString()); return;
        }
        if (Math.abs(HostBridge.decimal(HostBridge.call(wrapper, "getPlaybackSpeed")) - 1f) > 0.001f) {
            status = "变速播放期间沿用原播放"; return;
        }
        long duration = HostBridge.number(HostBridge.call(current, "getDuration"));
        Object oldEngine = bridge.nativeEngine(current);
        long position = -1;
        String positionSource = "media_fallback";
        if (oldEngine != null) {
            try {
                long actual = HostBridge.number(HostBridge.call(oldEngine, "getCurrentPlaybackTime"));
                if (actual >= 0 && actual <= duration) { position = actual; positionSource = "native"; }
            } catch (RuntimeException error) {
                diagnostics.errorThrottled("playback_clock_native_unavailable", error, 30000);
            }
        }
        if (position < 0) position = HostBridge.number(HostBridge.call(current, "getPlaybackTime"));
        long remaining = duration - position;
        diagnostics.throttled("playing_progress", "duration=" + duration + " position=" + position
            + " remaining=" + remaining + " duration_setting=" + config.durationMs + " source=" + positionSource, 15000);
        String currentId = HostBridge.playId(playable);
        long now = SystemClock.elapsedRealtime();
        if (!currentId.equals(b.currentId)) {
            b.currentId = currentId; b.lastAttempt = ""; b.lastPreRender = 0; b.requestedNext="";
            b.lastSkip = ""; b.lastSnapshot = ""; b.lastAnalysis="";
            status = "正在播放，等待曲尾自动过渡";
            diagnostics.event("track_changed", "duration=" + duration + " " + currentFacts);
        } else if (b.lastPosition >= 0 && Math.abs((position - b.lastPosition) - (now - b.lastPositionAt)) > 1800) {
            b.lastAttempt = ""; b.lastPreRender = 0;
            if (oldEngine != null) { audio.probe(oldEngine).reset(); audio.grid(oldEngine).reset(); }
            intro.cancel("seek", true);
            diagnostics.event("position_discontinuity", "from=" + b.lastPosition + " to=" + position);
        }
        b.lastPosition = position; b.lastPositionAt = now;
        if (oldEngine != null && config.beatMatch) audio.nativePcm.watch(oldEngine, position);
        if (duration < 30000 || position < 10000) return;
        // Prepare the next intro alongside current playback; its analysis does not require
        // a measured outgoing grid. Ordinary crossfades keep their late preparation window.
        if (!(config.beatMatch && config.adaptive) && remaining > Math.max(45000, config.durationMs * 4L)) return;
        Object next = HostBridge.call(inner, "getNextQueueItem");
        String nextId = HostBridge.playId(next);
        if (!bridge.music(next) || nextId.isEmpty() || currentId.isEmpty() || currentId.equals(nextId)
            || currentId.equals(b.lastAttempt)) return;
        if ("SINGLE".equals(HostBridge.enumName(HostBridge.call(inner, "getCurrentQueueLoopMode")))) return;
        Object upcoming = bridge.upcoming(wrapper);
        HostBridge.MediaFacts upcomingFacts = bridge.facts(upcoming, true);
        boolean matches = upcoming != null && MediaEligibility.same(nextId, HostBridge.playId(HostBridge.call(upcoming, "getPlayable")));
        // A new queue candidate must not inherit the previous candidate's retry cooldown.
        if (config.preRender && !(matches && upcomingFacts.prepared)
            && (now-b.lastPreRender>5000 || (!nextId.equals(b.requestedNext) && now-b.lastPreRender>=1000))) {
            b.lastPreRender = now;
            b.requestedNext=nextId;
            boolean accepted = bridge.request(wrapper, next);
            diagnostics.event("prerender_request", "accepted=" + accepted + " remaining=" + remaining
                +" previous_matches="+matches);
            upcoming = bridge.upcoming(wrapper);
            upcomingFacts = bridge.facts(upcoming, true);
            matches = upcoming != null && MediaEligibility.same(nextId, HostBridge.playId(HostBridge.call(upcoming, "getPlayable")));
        }
        String snapshot = "present=" + (upcoming != null) + " matches=" + matches + " " + upcomingFacts;
        if (!snapshot.equals(b.lastSnapshot)) {
            b.lastSnapshot = snapshot;
            diagnostics.event("prerender_snapshot", snapshot);
        }
        Object preparedEngine = upcoming == null ? null : bridge.nativeEngine(upcoming);
        if (intro.unsafe(preparedEngine)) {
            bridge.discardIntro(wrapper, preparedEngine);
            skip(b, "intro_dirty", "下一首分析中断，正在重新准备", "unsafe prepared engine discarded"); return;
        }
        BeatGrid.Result oldGrid = oldEngine == null ? BeatGrid.Result.NONE : audio.grid(oldEngine).result();
        boolean canAnalyze = config.beatMatch && config.adaptive && matches && upcomingFacts.prepared
            && oldEngine != null && preparedEngine != null && preparedEngine != oldEngine && upcomingFacts.duration >= 30000
            && !Boolean.TRUE.equals(HostBridge.call(oldEngine, "isMute")) && upcomingFacts.full() && bridge.automaticBlock(inner) == null;
        PcmProbe.Snapshot outgoingSignal = oldEngine == null ? null : audio.probe(oldEngine).snapshot(now);
        intro.tick(wrapper, preparedEngine, remaining, canAnalyze, nextId, upcomingFacts.duration, config.durationMs);
        boolean introReady=intro.result(preparedEngine).reliable();
        String analysis=!config.beatMatch || !config.adaptive?"disabled":!matches?"queue_mismatch"
            :!upcomingFacts.prepared?"next_preparing":!canAnalyze?"host_context_unavailable"
            :intro.busy()?"analyzing_intro"
            :introReady?(oldGrid.reliableAt(position)?"grids_ready":"intro_ready_outgoing_pending")
            :remaining<IntroAnalyzer.minimumRemaining(config.durationMs)?"analysis_window_short":"intro_unmeasured";
        if (!analysis.equals(b.lastAnalysis)) {
            b.lastAnalysis=analysis;
            diagnostics.event("beat_analysis_state","reason="+analysis+" remaining="+remaining
                +" out_bpm="+oldGrid.bpm+" confidence="+oldGrid.confidence+" intro_ready="+introReady
                +" pcm="+(outgoingSignal!=null && outgoingSignal.fresh));
        }
        if (intro.busy()) { status="正在播放，静音分析下一首节拍"; return; }
        if (bridge.upcoming(wrapper) != upcoming) return;
        if (canAnalyze) status=introReady?"正在播放，下一首节拍已准备":"正在播放，等待曲尾自动过渡";
        boolean quietTail = config.adaptive && outgoingSignal != null && outgoingSignal.fresh && outgoingSignal.silenceMs >= 300;
        // A mastered fade-out leaves the fixed tail window nearly inaudible; start where the fade begins.
        boolean outroFade = config.adaptive && !quietTail && outgoingSignal != null && outgoingSignal.fadingOut()
            && remaining <= OUTRO_WINDOW_MS && remaining > config.durationMs + 200L;
        if ((remaining > config.durationMs + (quietTail ? 2200L : 200L) && !outroFade) || remaining < 650) return;
        if (upcoming == null || !upcomingFacts.prepared) {
            skip(b, "not_prepared", "等待下一首预渲染就绪", snapshot); return;
        }
        if (!matches) {
            skip(b, "playable_mismatch", "预渲染曲目与队列下一首不匹配", snapshot); return;
        }
        if (!upcomingFacts.full()) {
            skip(b, upcomingFacts.eligibility, "下一首资源类型未确认完整，保留原播放", snapshot); return;
        }
        if (upcomingFacts.duration < 30000) return;
        Object newEngine = bridge.nativeEngine(upcoming);
        if (oldEngine == null || newEngine == null || oldEngine == newEngine) {
            status = "当前播放源不支持保留双轨，沿用原播放"; return;
        }
        if (Boolean.TRUE.equals(HostBridge.call(oldEngine, "isMute"))) return;
        String block = bridge.automaticBlock(inner);
        if (block != null) {
            skip(b, "automatic_block", "已保留宿主循环或睡眠设置", block); return;
        }
        int plannedDuration = config.durationMs;
        String strategy = "smooth_crossfade";
        if (quietTail) { plannedDuration = Math.min(plannedDuration, 2200); strategy = "quiet_tail"; }
        else if (config.adaptive && outgoingSignal != null && outgoingSignal.rhythmic()) {
            double beat = 60000d / outgoingSignal.bpm;
            plannedDuration = (int) Math.max(2000, Math.min(config.durationMs, Math.floor(config.durationMs / beat) * beat));
            strategy = outroFade ? "outro_fade_rhythmic" : "rhythmic_fade";
        } else if (outroFade) strategy = "outro_fade";
        MixConfig plan = new MixConfig(true, plannedDuration, config.smoothCurve, config.preRender, config.adaptive);
        b.lastAttempt = currentId;
        diagnostics.event("transition_plan", "strategy=" + strategy + " remaining=" + remaining + " duration=" + plan.durationMs
            + " pcm=" + (outgoingSignal != null && outgoingSignal.fresh)
            + " fade_ratio=" + (outgoingSignal == null ? 1 : Math.round(outgoingSignal.fadeRatio * 100) / 100d)
            + " bpm=" + (outgoingSignal == null ? 0 : Math.round(outgoingSignal.bpm))
            + " confidence=" + (outgoingSignal == null ? 0 : outgoingSignal.confidence));
        diagnostics.event("transition_begin", "remaining=" + remaining + " duration=" + plan.durationMs
            + " old_engine=" + System.identityHashCode(oldEngine) + " new_engine=" + System.identityHashCode(newEngine));
        BeatGrid.Result newGrid = intro.result(newEngine);
        Active a = new Active(b, oldEngine, newEngine, bridge.streamSource(current), nextId, remaining);
        a.oldGrid = oldGrid.reliableAt(position) ? oldGrid : BeatGrid.Result.NONE; a.newGrid = newGrid;
        diagnostics.event("beatmatch_eligibility", "out_bpm=" + oldGrid.bpm + " out_confidence=" + oldGrid.confidence
            + " in_bpm=" + newGrid.bpm + " in_confidence=" + newGrid.confidence
            + " reason=" + (!config.beatMatch ? "disabled" : !oldGrid.reliableAt(position) ? "outgoing_unmeasured_or_stale" : !newGrid.reliable() ? "incoming_unmeasured" : "grids_available"));
        active = a;
        diagnostics.checkpoint("transition_begin");
        if (a.source != null) retainedSources.put(a.source, a);
        try {
            a.session = new MixSession(a, plan, now, remaining);
            if (active != a || a.finished) return;
            boolean switched;
            switching.set(true);
            try {
                // Retention and incoming mute must exist before invoking any host interceptor.
                HostBridge.AutomaticResult decision = bridge.automaticAction(inner, playable);
                diagnostics.event("automatic_action_result", decision.toString());
                if (active != a || a.finished) return;
                Object afterAction = bridge.current(wrapper);
                boolean selectedNext = afterAction != null && bridge.nativeEngine(afterAction) == newEngine
                    && nextId.equals(HostBridge.playId(HostBridge.call(afterAction, "getPlayable")));
                if (selectedNext) {
                    // A host plugin already performed the handoff; never advance a second time.
                    switched = true;
                    diagnostics.event("automatic_handoff_observed", "expected_engine=true; 不再重复推进队列");
                } else if (decision.intercepted) {
                    a.cancel(decision.willPlay ? "host_handoff_unconfirmed" : "automatic_intercepted");
                    return;
                } else if (afterAction == current) {
                    switched = bridge.switchNext(inner);
                } else {
                    a.cancel("automatic_source_changed"); return;
                }
            }
            finally { switching.remove(); }
            if (active != a) return;
            Object selected = bridge.current(wrapper);
            if (!switched || bridge.nativeEngine(selected) != newEngine
                || !nextId.equals(HostBridge.playId(HostBridge.call(selected, "getPlayable")))
                || !a.outgoing.releaseDeferred()) {
                diagnostics.event("promotion_check_failed", "queue_switched=" + switched
                    + " engine_matches=" + (bridge.nativeEngine(selected) == newEngine)
                    + " release_deferred=" + a.outgoing.releaseDeferred());
                a.cancel("promotion_refused");
                return;
            }
            a.promoted = true;
            diagnostics.checkpoint("dual_playing");
            diagnostics.event("prerender_promoted", "新引擎接管成功，旧引擎 release 已暂缓");
            status = "正在等待下一首出声";
            a.handler.post(a.frame);
        } catch (Exception error) {
            a.cancel("host_error");
            throw error;
        }
    }

    Object redirectPageRouting() {
        Active a = active;
        if (!Boolean.TRUE.equals(switching.get()) || a == null || a.finished) return null;
        diagnostics.event("automatic_page_route_redirected", "完成事件只负责翻页；混音事务交由原生队列接管，其他拦截器继续执行");
        return bridge.unconsumedAutomaticResult;
    }

    void queueAdvance(Object inner, Object[] args) {
        Binding binding = null;
        synchronized (bindings) {
            for (Binding candidate : bindings.values()) if (candidate.inner.get() == inner) { binding = candidate; break; }
        }
        if (binding == null) return;
        String reason = args.length == 0 || args[0] == null ? "unset" : args[0].getClass().getSimpleName();
        diagnostics.event("queue_advance", "origin=" + (Boolean.TRUE.equals(switching.get()) ? "automix" : "host")
            + " reason=" + reason + " mixing=" + (active != null));
    }

    private void skip(Binding b, String reason, String message, String details) {
        status = message;
        if (!reason.equals(b.lastSkip)) {
            b.lastSkip = reason;
            diagnostics.event("transition_skipped", "reason=" + reason + " " + details);
        }
    }

    void preRenderEvent(Object wrapper, String event, Object[] args) {
        if (!bindings.containsKey(wrapper)) return;
        String detail = "event=" + event;
        if (event.equals("onPreRenderEnd")) {
            Throwable error = args.length > 1 && args[1] instanceof Throwable ? (Throwable) args[1] : null;
            detail += " success=" + (error == null);
            if (error != null) diagnostics.errorThrottled("prerender_failed", error, 10000);
        } else if (event.equals("onPreRenderDiscard") && args.length > 1 && args[1] != null) {
            detail += " reason=" + args[1].getClass().getSimpleName();
        }
        diagnostics.event("prerender_event", detail);
    }

    boolean deferSourceClose(Object source) {
        Active a = retainedSources.get(source);
        if (a == null) return false;
        synchronized (a) {
            if (!a.finished) { a.sourceCloseDeferred = true; return true; }
            if (a.sourceClosed) return true;
            a.sourceClosed = true;
            return false;
        }
    }

    /** Diagnoses "pause has no effect": host/engine/module state at the press, and again 600 ms later. */
    void transport(Object wrapper, String action) {
        // Unbound wrappers are logged too: a press routed to another player object is itself evidence.
        Binding binding = bindings.get(wrapper);
        String before = (binding != null ? "bound=true " : "bound=false bindings=" + bindings.size() + " ")
            + transportState(wrapper);
        diagnostics.event("user_" + action, before);
        later(binding == null ? null : binding.inner.get(), () -> {
            // The engine is what the user hears; the wrapper's flag can claim a pause that never happened.
            int engine = engineStateOf(wrapper);
            boolean stillPlaying = engine >= 0 ? engine == HostBridge.ENGINE_PLAYING : Boolean.TRUE.equals(peek(wrapper, "isPlaying"));
            if ("pause".equals(action) == stillPlaying) {
                diagnostics.event(action + "_ineffective", "before{" + before + "} after{" + transportState(wrapper) + "}");
            }
        });
    }

    private int engineStateOf(Object wrapper) {
        try { Object current = bridge.current(wrapper); return bridge.engineState(current == null ? null : bridge.nativeEngine(current)); }
        catch (Throwable error) { return -1; }
    }

    private static Object peek(Object target, String method) {
        try { return target == null ? null : HostBridge.call(target, method); } catch (Throwable error) { return "?"; }
    }

    private String transportState(Object wrapper) {
        Object engine = null;
        try { Object current = bridge.current(wrapper); engine = current == null ? null : bridge.nativeEngine(current); }
        catch (Throwable ignored) { }
        Active a = active;
        return "wrapper_playing=" + peek(wrapper, "isPlaying")
            + " state=" + peek(wrapper, "getPlaybackState")
            + " " + mediaState(wrapper)
            + " intro_busy=" + intro.busy()
            + " mixing=" + (a != null) + (a == null ? "" : " promoted=" + a.promoted + " phase=" + (a.session == null ? "-" : a.session.phase())
                + " outgoing=" + System.identityHashCode(a.outgoing.engine) + " incoming=" + System.identityHashCode(a.incoming.engine)
                + " deferred=" + (a.outgoing.deferred == null ? "none" : a.outgoing.deferred.getName()))
            + " tempo_recovery=" + (engine != null && recovery.controls(engine))
            + " switching=" + Boolean.TRUE.equals(switching.get());
    }

    void cancelFor(Object wrapper, String reason) {
        if (Boolean.TRUE.equals(switching.get()) || !bindings.containsKey(wrapper)) return;
        Active a = active;
        intro.cancel(reason, true); recovery.cancel(true);
        if (a != null && a.binding.wrapper.get() == wrapper) a.cancel(reason);
    }

    void cancelInner(Object inner, String reason) {
        if (Boolean.TRUE.equals(switching.get())) return;
        boolean known=false;
        synchronized(bindings) { for(Binding b:bindings.values()) if(b.inner.get()==inner) { known=true;break; } }
        if(!known)return;
        Active a = active;
        intro.cancel(reason, true); recovery.cancel(true);
        if (a != null && a.binding.inner.get() == inner) a.cancel(reason);
    }

    void sourceChange(Object engine) { sourceChange(engine,"source_mutation"); }

    void sourceChange(Object engine, String operation) {
        recovery.external(engine);
        audio.grid(engine).reset();
        intro.invalidate(engine);
        if (intro.controls(engine)) intro.cancel(operation, true);
        Active a = active;
        if (a != null && (engine == a.outgoing.engine || engine == a.incoming.engine)) {
            diagnostics.event("engine_mutation", "method="+operation+" engine="+System.identityHashCode(engine)
                +" incoming="+(engine==a.incoming.engine)+" promoted="+a.promoted
                +" switching="+Boolean.TRUE.equals(switching.get()));
            a.cancel(operation);
        }
    }

    /** Host promotion reapplies 1x; this is not a source change or a user speed gesture. */
    boolean playbackParams(Object engine, Object params) {
        if (params==null) return false;
        float speed=HostBridge.decimal(HostBridge.call(params,"getSpeed"));
        float pitch=HostBridge.decimal(HostBridge.call(params,"getPitch"));
        // TT PlaybackParams uses -1 for an unspecified value, unlike Android's default 1.
        boolean normal=Float.isFinite(speed) && Float.isFinite(pitch)
            && (speed==-1 || Math.abs(speed-1)<0.0001f) && (pitch==-1 || Math.abs(pitch-1)<0.0001f);
        Active a=active;
        if (normal) {
            // A user speed operation cancels at the wrapper hook BEFORE reaching this hook.
            // Only ignore default synchronization while this module owns a non-default rate.
            boolean owned=a!=null && !a.finished && engine==a.incoming.engine && a.tempoTouched;
            owned|=recovery.controls(engine);
            if ((a!=null && (engine==a.outgoing.engine || engine==a.incoming.engine)) || intro.controls(engine) || owned) {
                diagnostics.throttled("host_speed_sync", "engine="+System.identityHashCode(engine)
                    +" speed="+speed+" pitch="+pitch+" keep_module_rate="+owned,1000);
            }
            return owned;
        }
        diagnostics.event("external_playback_params", "engine="+System.identityHashCode(engine)
            +" speed="+speed+" pitch="+pitch);
        // The requested parameters run after this hook, taking priority over cleanup's restore.
        recovery.external(engine);
        audio.grid(engine).reset();
        if (intro.controls(engine)) intro.cancel("playback_params_changed",false);
        if (a!=null && (engine==a.outgoing.engine || engine==a.incoming.engine)) a.cancel("playback_params_changed");
        return false;
    }

    void volume(Object engine, Object[] args) {
        float left = (Float) args[0], right = (Float) args[1];
        if (!Float.isFinite(left) || !Float.isFinite(right) || left < 0 || right < 0) return;
        volumes.put(engine, new float[] {left, right});
        if (intro.controls(engine)) { args[0] = 0f; args[1] = 0f; return; }
        Active a = active;
        if (a == null) return;
        synchronized (a) {
            if (active != a) return;
            Port p = engine == a.outgoing.engine ? a.outgoing : engine == a.incoming.engine ? a.incoming : null;
            if (p == null) return;
            p.left = left;
            p.right = right;
            args[0] = left * p.gain;
            args[1] = right * p.gain;
        }
    }

    boolean defer(Object engine, Method method) {
        Active current = active;
        diagnostics.throttled("engine_" + method.getName(), "engine=" + System.identityHashCode(engine)
            + " intro=" + intro.controls(engine) + " mixing=" + (current != null)
            + (current == null ? "" : " outgoing=" + (engine == current.outgoing.engine) + " incoming=" + (engine == current.incoming.engine)), 1000);
        recovery.external(engine);
        if (intro.controls(engine)) intro.cancel("host_" + method.getName(), false);
        Active a = active;
        if (a == null) return false;
        synchronized (a) {
            if (active != a) return false;
            if (engine == a.outgoing.engine) {
                if (a.outgoing.deferred == null || priority(method) > priority(a.outgoing.deferred)) {
                    a.outgoing.deferred = method;
                    diagnostics.event("outgoing_cleanup_deferred", method.getName());
                }
                return true;
            }
        }
        if (engine == a.incoming.engine) a.cancel(method.getName());
        return false;
    }

    private static int priority(Method method) {
        return method.getName().startsWith("release") ? 3 : method.getName().equals("stop") ? 2 : 1;
    }

    private final class Port {
        final Object engine;
        float left, right, gain = 1;
        Method deferred;

        Port(Object engine, Port fallback) {
            this.engine = engine;
            float[] known = volumes.get(engine);
            float actual = HostBridge.decimal(HostBridge.call(engine, "getVolume"));
            if (!Float.isFinite(actual) || actual < 0) actual = 1;
            left = known != null ? known[0] : actual;
            right = known != null ? known[1] : actual;
            if (fallback != null && left == 0 && right == 0) { left = fallback.left; right = fallback.right; }
        }

        void gain(float value) throws Exception {
            gain = value;
            bridge.gain(engine, left * value, right * value);
        }

        boolean releaseDeferred() { return deferred != null && priority(deferred) == 3; }
    }

    private final class Active implements MixSession.Output {
        final Binding binding;
        final Port outgoing, incoming;
        final Handler handler = new Handler(Looper.myLooper());
        final String nextId;
        final Object source;
        final long baseline;
        final long outgoingBaseline, remainingAtStart, createdAt = SystemClock.elapsedRealtime();
        final Runnable frame = this::advance;
        MixSession session;
        volatile boolean promoted;
        BeatGrid.Result oldGrid = BeatGrid.Result.NONE, newGrid = BeatGrid.Result.NONE;
        boolean beatAttempted, beatSeeking, beatLocked, tempoConfirmed;
        volatile boolean tempoTouched;
        long phaseBadAt;
        volatile boolean beatSeekDone, beatSeekOk;
        int beatSeekAttempts;
        long beatSeekAt, tempoAt;
        float tempo = 1;
        double matchedRate = 1;
        long tempoCheckAt, tempoCheckPosition;
        long audibleBaseline;
        long lastPosition, lastProgressAt = SystemClock.elapsedRealtime();
        long outgoingLastPosition, outgoingProgressAt = SystemClock.elapsedRealtime();
        volatile boolean finished;
        boolean sourceCloseDeferred, sourceClosed, audioEvidence, overlapObserved;
        long overlapSince;

        Active(Binding binding, Object oldEngine, Object newEngine, Object source, String nextId, long remaining) {
            this.binding = binding;
            this.source = source;
            this.nextId = nextId;
            outgoing = new Port(oldEngine, null);
            incoming = new Port(newEngine, outgoing);
            baseline = HostBridge.number(HostBridge.call(newEngine, "getCurrentPlaybackTime"));
            lastPosition = baseline; audibleBaseline = baseline;
            remainingAtStart = remaining;
            outgoingBaseline = HostBridge.number(HostBridge.call(oldEngine, "getCurrentPlaybackTime"));
            outgoingLastPosition = outgoingBaseline;
        }

        void advance() {
            if (active != this || finished) return;
            try {
                if (!config.enabled || stopped) { cancel("disabled"); return; }
                Object wrapper = binding.wrapper.get();
                Object selected = wrapper == null ? null : bridge.current(wrapper);
                if (selected == null || bridge.nativeEngine(selected) != incoming.engine
                    || !nextId.equals(HostBridge.playId(HostBridge.call(selected, "getPlayable")))) {
                    cancel("source_changed"); return;
                }
                long now = SystemClock.elapsedRealtime();
                long position = HostBridge.number(HostBridge.call(incoming.engine, "getCurrentPlaybackTime"));
                if (config.beatMatch) audio.nativePcm.watch(incoming.engine, position);
                PcmProbe.Snapshot signal = audio.probe(incoming.engine).snapshot(now);
                if (signal.fresh && !audioEvidence) {
                    audioEvidence = true;
                    diagnostics.event("incoming_audio_observed", "rms=" + signal.rms + " frames=" + signal.frames);
                }
                if (position > lastPosition) { lastPosition = position; lastProgressAt = now; }
                if (session.phase() == MixSession.Phase.BLENDING && now - lastProgressAt > 1000) {
                    cancel("incoming_stalled"); return;
                }
                long outgoingPosition = HostBridge.number(HostBridge.call(outgoing.engine, "getCurrentPlaybackTime"));
                if (config.beatMatch) audio.nativePcm.watch(outgoing.engine, outgoingPosition);
                if (outgoingPosition > outgoingLastPosition) { outgoingLastPosition = outgoingPosition; outgoingProgressAt = now; }
                // Stream sources can report duration only through the Java wrapper. Keep its
                // known remaining tail, then measure native progress; do not re-read a zero duration.
                long remaining = Math.min(remainingAtStart - (outgoingPosition - outgoingBaseline),
                    remainingAtStart - (now - createdAt) + 150);
                if (now - outgoingProgressAt > 1200 && remaining > 500) {
                    cancel("outgoing_stalled"); return;
                }
                if (!config.beatMatch && tempoTouched) {
                    beatSeeking = beatLocked = false; bridge.tempo(incoming.engine,1); tempo=1;tempoTouched=false;
                }
                if (config.beatMatch && !beatAttempted && oldGrid.reliable() && newGrid.reliable()) {
                    beatAttempted = true;
                    BeatMatch match = BeatMatch.plan(oldGrid, newGrid, outgoingPosition, position);
                    if (match != null) {
                        matchedRate = match.rate; tempo = (float) match.rate; tempoTouched = true;
                        bridge.tempo(incoming.engine, tempo);
                        beatSeeking = true; beatSeekAt = now; beatSeekAttempts = 1;
                        bridge.seek(incoming.engine, (int) Math.round(match.targetPositionMs), ok -> { beatSeekOk = ok; beatSeekDone = true; });
                        diagnostics.event("beatmatch_align", "out_bpm=" + oldGrid.bpm + " in_bpm=" + newGrid.bpm
                            + " rate=" + tempo + " pitch=1.0 target_ms=" + Math.round(match.targetPositionMs));
                    } else diagnostics.event("beatmatch_fallback", "reason=tempo_difference_or_intro_range");
                }
                if (beatSeeking && (beatSeekDone || now - beatSeekAt > 1000)) {
                    position = HostBridge.number(HostBridge.call(incoming.engine, "getCurrentPlaybackTime"));
                    outgoingPosition = HostBridge.number(HostBridge.call(outgoing.engine, "getCurrentPlaybackTime"));
                    double error = phaseError(outgoingPosition, position);
                    if (beatSeekDone && beatSeekOk && Math.abs(error)>65 && beatSeekAttempts<2) {
                        long latency = Math.min(250, Math.max(0, now - beatSeekAt));
                        BeatMatch adjusted = BeatMatch.plan(oldGrid,newGrid,outgoingPosition+latency,position);
                        if (adjusted != null) {
                            beatSeekAttempts++;beatSeekDone=false;beatSeekAt=now;
                            bridge.seek(incoming.engine,(int)Math.round(adjusted.targetPositionMs),ok->{beatSeekOk=ok;beatSeekDone=true;});
                        } else beatSeekOk=false;
                    } else {
                        beatSeeking = false; audibleBaseline = position; lastPosition = position; lastProgressAt = now;
                        beatLocked = beatSeekDone && beatSeekOk && Math.abs(error)<=65;
                        if (!beatLocked) { bridge.tempo(incoming.engine, 1); tempo = 1; }
                        diagnostics.event("beatmatch_seek_result", "success=" + beatLocked + " position=" + position
                            + " error_ms=" + Math.round(error) + " attempts=" + beatSeekAttempts);
                    }
                }
                if (beatLocked && now - tempoAt >= 200) {
                    tempoAt = now;
                    if (tempoCheckAt == 0) { tempoCheckAt=now; tempoCheckPosition=position; }
                    else if (now-tempoCheckAt>=1500) {
                        double measured=(position-tempoCheckPosition)/(double)(now-tempoCheckAt);
                        diagnostics.event("tempo_observed", "requested="+tempo+" media_rate="+measured+" pitch_requested=1.0");
                        tempoCheckAt=now;tempoCheckPosition=position;
                        tempoConfirmed = Math.abs(measured-tempo)<=0.025;
                        if (!tempoConfirmed) {
                            beatLocked=false;bridge.tempo(incoming.engine,1);tempo=1;
                            diagnostics.event("beatmatch_fallback","reason=rate_not_confirmed");
                        }
                    }
                    double error = phaseError(outgoingPosition, position);
                    if (Math.abs(error)>90) {
                        if (phaseBadAt==0) phaseBadAt=now;
                        if (now-phaseBadAt>=600) {
                            beatLocked=false;bridge.tempo(incoming.engine,1);tempo=1;
                            diagnostics.event("beatmatch_fallback","reason=phase_drift error_ms="+Math.round(error));
                        }
                    } else phaseBadAt=0;
                    double correction = Math.max(-0.015, Math.min(0.015, error / 2500));
                    if (beatLocked) {
                        tempo = (float) Math.max(0.90, Math.min(1.10, matchedRate + correction));
                        bridge.tempo(incoming.engine, tempo);
                    }
                    diagnostics.throttled("beatmatch_phase", "error_ms=" + Math.round(error) + " rate=" + tempo, 1000);
                }
                MixSession.Phase previous = session.phase();
                // A connected PCM observer distinguishes a silent intro from audible audio.
                // When the host uses AudioX/offload, progression is the explicit fallback.
                boolean progressed = !beatSeeking && position > audibleBaseline + 20;
                boolean audible = progressed && (!audioEvidence || !config.adaptive || signal.fresh && signal.rms > 0.002f);
                session.tick(now, audible,
                    Boolean.TRUE.equals(HostBridge.call(selected, "isPlaying")), remaining);
                diagnostics.throttled("mix_progress", "phase=" + session.phase() + " old_position=" + outgoingPosition
                    + " new_position=" + position + " old_progress_age=" + (now - outgoingProgressAt)
                    + " new_progress_age=" + (now - lastProgressAt) + " out_gain=" + outgoing.gain
                    + " in_gain=" + incoming.gain + " remaining=" + remaining, 1000);
                if (active == this && session.phase() == MixSession.Phase.BLENDING
                    && position > baseline + 20 && outgoingPosition > outgoingBaseline + 20
                    && now - outgoingProgressAt < 400 && now - lastProgressAt < 400
                    && outgoing.gain > 0.05f && incoming.gain > 0.05f) {
                    if (overlapSince == 0) overlapSince = now;
                    if (!overlapObserved && now - overlapSince >= 200) {
                        overlapObserved = true;
                        diagnostics.event("dual_progress_observed", "old_position=" + outgoingPosition
                            + " new_position=" + position + " out_gain=" + outgoing.gain + " in_gain=" + incoming.gain);
                    }
                } else overlapSince = 0;
                if (session.phase() != previous) diagnostics.event("transition_phase", previous + " -> " + session.phase()
                    + " incoming_position=" + position + " remaining=" + remaining);
                if (active == this && !finished) handler.postDelayed(frame, 40);
            } catch (Exception error) {
                cancel("host_error");
                reportError(error);
            }
        }

        private double phaseError(long oldPosition, long newPosition) {
            return BeatGrid.positive(newGrid.untilBeat(newPosition) / matchedRate
                - oldGrid.untilBeat(oldPosition) + oldGrid.period()/2, oldGrid.period()) - oldGrid.period()/2;
        }

        void cancel(String reason) {
            if (session == null) finish(false, reason);
            else session.cancel(reason);
        }

        @Override public void gains(float out, float in) throws Exception {
            outgoing.gain(out);
            incoming.gain(in);
            if (in > 0) status = beatLocked ? "正在对拍与变速混音" : "正在交叉混音";
            diagnostics.throttled("mix_gains", "out=" + out + " in=" + in, 1000);
        }

        @Override public void finish(boolean success, String reason) {
            synchronized (this) {
                if (finished) return;
                finished = true;
                if (active == this) active = null;
            }
            handler.removeCallbacks(frame);
            diagnostics.checkpoint("transition_cleanup");
            boolean cleanupOk = true;
            if (tempoTouched) {
                try {
                    if (success && beatLocked) recovery.begin(incoming.engine, tempo,
                        (long) Math.max(3000, Math.min(8000, newGrid.period() * 8)), handler);
                    else { bridge.tempo(incoming.engine, 1); audio.grid(incoming.engine).reset(); }
                } catch (Exception error) { cleanupOk = false; diagnostics.error("tempo_restore_failed", error); }
            }
            try { incoming.gain(1); }
            catch (Exception error) { cleanupOk = false; reportError(error); }
            finally {
                try { outgoing.gain(outgoing.deferred == null ? 1 : 0); }
                catch (Exception error) { cleanupOk = false; reportError(error); }
                // Cleanup runs even if restoring volume fails, and is invoked only once.
                if (outgoing.deferred != null) {
                    try { HostBridge.original(outgoing.releaseDeferred() ? bridge.release : outgoing.deferred, outgoing.engine, new Object[0]); }
                    catch (Exception cleanupError) {
                        cleanupOk = false; reportError(cleanupError);
                        try { HostBridge.original(bridge.stop, outgoing.engine, new Object[0]); }
                        catch (Exception stopError) { diagnostics.error("outgoing_stop_failed", stopError); }
                    }
                }
                try {
                    if (source != null && sourceCloseDeferred && !sourceClosed) HostBridge.call(source, "close");
                } catch (Throwable cleanupError) { cleanupOk = false; diagnostics.error("source_cleanup_failed", cleanupError); }
                finally { if (source != null) retainedSources.remove(source); }
            }
            boolean verified = success && promoted && overlapObserved && cleanupOk;
            String finalReason = success && !overlapObserved ? "no_overlap_evidence" : reason;
            if (verified) {
                completed++;
                if (beatLocked && tempoConfirmed) { beatCompleted++; diagnostics.beatCount(beatCompleted); }
                status = "自动混音完成，累计 " + completed + " 次";
            } else {
                status = cleanupOk ? "已结束过渡：" + finalReason : "过渡清理异常，请查看日志";
            }
            diagnostics.checkpoint("idle");
            diagnostics.event("transition_finish", "success=" + verified + " reason=" + finalReason + " cleanup_ok=" + cleanupOk
                + " deferred=" + (outgoing.deferred == null ? "none" : outgoing.deferred.getName())
                + " source_close_deferred=" + sourceCloseDeferred + " audio_observed=" + audioEvidence
                + " dual_progress=" + overlapObserved
                + " beatmatched=" + (verified && beatLocked && tempoConfirmed)
                + " beat_completed=" + beatCompleted + " completed=" + completed);
        }
    }

    private static final class Binding {
        final WeakReference<Object> wrapper, inner;
        final AtomicBoolean queued = new AtomicBoolean();
        volatile long lastCheck, lastCallback;
        volatile boolean playing;
        long lastPreRender;
        String lastAttempt = "";
        String currentId = "", lastSkip = "", lastSnapshot = "", requestedNext="", lastAnalysis="";
        long lastPosition = -1, lastPositionAt;
        Binding(Object wrapper, Object inner) {
            this.wrapper = new WeakReference<>(wrapper);
            this.inner = new WeakReference<>(inner);
        }
    }
}

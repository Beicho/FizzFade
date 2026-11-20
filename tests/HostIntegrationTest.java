package io.github.qishui.automix;

import android.os.Handler;
import android.os.SystemClock;
import com.luna.biz.playing.player.bach.InnerPlayer;
import com.luna.biz.playing.player.bach.prerender.MediaPlayerWrapper;
import com.luna.common.player.datasource.StreamMediaDataSource;
import com.luna.common.player.kit.impl.TTMediaPlayer;
import com.luna.common.player.mediaplayer.MediaResType;
import com.luna.common.player.mediaplayer.impl.MediaPlayer;
import com.luna.common.player.queue.api.IPlayable;
import com.luna.common.player.queue.mode.QueueLoopMode;
import com.ss.ttvideoengine.TTVideoEngine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Executes the real HostBridge and MixManager against a deterministic host lifecycle. */
public final class HostIntegrationTest {
    private static int assertions;
    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }
    public static final class Item implements IPlayable {
        final String id;
        Item(String id) { this.id = id; }
        public String getPlayId() { return id; }
        public boolean isRewardAdPlayable() { return false; }
    }
    private static final class Scenario {
        final HostDiagnostics diagnostics = new HostDiagnostics();
        final MediaPlayerWrapper wrapper = new MediaPlayerWrapper();
        final InnerPlayer inner = new InnerPlayer(wrapper);
        final TTVideoEngine old = new TTVideoEngine(), next = new TTVideoEngine();
        final StreamMediaDataSource stream = new StreamMediaDataSource();
        final MixManager manager;
        Scenario(MediaResType reported, MediaResType source) throws Exception {this(reported,source,true);}
        Scenario(MediaResType reported, MediaResType source, boolean configAvailable) throws Exception {this(reported,source,configAvailable,null);}
        Scenario(MediaResType reported, MediaResType source, boolean configAvailable, Boolean cached) throws Exception {
            diagnostics.configAvailable=configAvailable; diagnostics.cached=cached;
            Handler.reset();
            TestRuntime.manager = null;
            wrapper.current = new MediaPlayer(new TTMediaPlayer(old, stream), new Item("A"), MediaResType.FULL, MediaResType.FULL);
            wrapper.current.C = true; // started by the host's fresh-start path
            wrapper.next = new MediaPlayer(new TTMediaPlayer(next, null), new Item("B"), reported, source);
            old.position = 174000; old.play();
            manager = new MixManager(new HostBridge(getClass().getClassLoader()), diagnostics);
            TestRuntime.manager = manager;
            manager.bind(wrapper, inner); manager.bind(wrapper, inner);
            manager.start();
        }
        void progress() { manager.progress(wrapper); Handler.drain(); }
        void advance(int millis) {
            for (int elapsed = 0; elapsed < millis; elapsed += 40) {
                SystemClock.now += 40; Handler.drain();
            }
        }
        void incomingPcm(int millis, short sample) {
            ByteBuffer buffer = ByteBuffer.allocate(960 * 2).order(ByteOrder.nativeOrder());
            while (buffer.hasRemaining()) buffer.putShort(sample);
            buffer.flip();
            for (int elapsed = 0; elapsed < millis; elapsed += 40) {
                manager.audio.probe(next).accept(new ByteBuffer[] {buffer}, 960, 48000, 1, 1, SystemClock.now);
                advance(40);
            }
        }
        void adaptive() { diagnostics.config.put("adaptive", true); diagnostics.monitor.run(); }
        boolean event(String text) { return diagnostics.events.stream().anyMatch(s -> s.contains(text)); }
    }
    static void pulse(BeatGrid grid, double bpm, long origin, int millis) {
        for (int t=0;t<millis;t+=10) {
            double phase=BeatGrid.positive(origin+t,60000/bpm);
            grid.add(0.04+(phase<30?0.6*Math.exp(-phase/12):0),10,origin+t);
        }
    }
    static void beatSetup(Scenario s, double incomingBpm) throws Exception {
        beatSetup(s,incomingBpm,145000,true);
    }
    static void beatSetup(Scenario s, double incomingBpm, int positionAtStart, boolean outgoingMeasured) throws Exception {
        beatSetup(s,incomingBpm,positionAtStart,outgoingMeasured,true);
    }
    static void beatSetup(Scenario s, double incomingBpm, int positionAtStart, boolean outgoingMeasured, boolean callbacks) throws Exception {
        s.old.position=positionAtStart;
        s.diagnostics.config.put("adaptive",true);s.diagnostics.config.put("beatmatch",true);
        s.diagnostics.monitor.run();
        if(outgoingMeasured) pulse(s.manager.audio.grid(s.old),120,positionAtStart-12000,12000);
        if(callbacks)s.progress();else s.advance(1000);
        check(s.event("intro_analysis_begin") && s.next.volume==0 && s.next.playing,"real prepared engine pre-roll is muted");
        for (int t=0;t<14000 && s.manager.intro.busy();t+=20) {
            long position=s.next.getCurrentPlaybackTime();
            double phase=BeatGrid.positive(position,60000/incomingBpm);
            short value=(short)(2000+(phase<30?18000*Math.exp(-phase/12):0));
            ByteBuffer buffer=ByteBuffer.allocate(960*2).order(ByteOrder.nativeOrder());
            while(buffer.hasRemaining())buffer.putShort(value);buffer.flip();
            s.manager.audio.nativeAudio(s.next,buffer,960,48000,1,1,position);
            long oldPos=s.old.getCurrentPlaybackTime();
            if(outgoingMeasured) for(int k=0;k<20;k+=10){double phaseOld=BeatGrid.positive(oldPos+k,500);s.manager.audio.grid(s.old).add(0.04+(phaseOld<30?0.6*Math.exp(-phaseOld/12):0),10,oldPos+k);}
            SystemClock.now+=20;Handler.drain();
            if(callbacks && t%200==0)s.progress();
        }
    }
    public static void main(String[] args) throws Exception {
        Scenario background = new Scenario(null, MediaResType.FULL);
        background.old.position=166000;
        background.advance(16000);
        check(background.inner.changes==1 && background.event("success=true")
            && background.old.releases==1 && background.stream.closes==1,
            "background playback completes a real overlap without a single progress callback");

        check(background.inner.realPlayingChecks>20 && background.event("callback_age=-1")
            && background.event("playback_clock_ready"),
            "clock keeps checking through the host executor with callback absence diagnosed");
        check(background.event("source=native"),"background scheduling reads the real native position");

        Scenario uiBlocked = new Scenario(null, MediaResType.FULL);
        uiBlocked.old.position=166000;Handler.suspendMain=true;uiBlocked.advance(16000);
        check(uiBlocked.inner.changes==1 && uiBlocked.event("success=true"),
            "a suspended Android main looper cannot block background clock or host-player mix frames");

        Scenario frozen = new Scenario(null, MediaResType.FULL);
        frozen.wrapper.current.cachedPosition=10000;frozen.old.position=166000;
        frozen.advance(16000);
        check(frozen.event("success=true") && frozen.inner.changes==1,
            "frozen MediaPlayer cached time cannot suppress a callback-free transition");

        Scenario backgroundPause = new Scenario(null, MediaResType.FULL);
        backgroundPause.old.position=166000;backgroundPause.advance(3000);
        backgroundPause.wrapper.pause();backgroundPause.advance(12000);
        check(backgroundPause.inner.changes==0 && !backgroundPause.old.playing && !backgroundPause.next.playing,
            "background clock never resumes user-paused playback");
        int pausedChecks=backgroundPause.inner.realPlayingChecks;
        backgroundPause.advance(5000);
        check(backgroundPause.inner.realPlayingChecks-pausedChecks<=6,
            "idle clock backs off instead of polling a paused player at playing frequency");
        backgroundPause.old.play();backgroundPause.advance(14000);
        check(backgroundPause.event("success=true") && backgroundPause.inner.changes==1,
            "explicit user resume restores background crossfade without a callback");

        Scenario disabledClock = new Scenario(null, MediaResType.FULL);
        disabledClock.old.position=166000;
        disabledClock.diagnostics.config.put("enabled",false);disabledClock.diagnostics.monitor.run();
        disabledClock.advance(2000);
        check(disabledClock.inner.changes==0 && disabledClock.inner.realPlayingChecks==0,
            "disabled settings suppress all background player evaluations");
        disabledClock.diagnostics.config.put("enabled",true);disabledClock.diagnostics.monitor.run();
        disabledClock.advance(14000);
        check(disabledClock.inner.changes==1 && disabledClock.event("success=true"),
            "re-enabling resumes the clock after disabled backoff");

        Scenario providerOffline = new Scenario(null, MediaResType.FULL);
        providerOffline.old.position=166000;providerOffline.diagnostics.configAvailable=false;
        providerOffline.diagnostics.monitor.run();providerOffline.advance(12000);
        check(providerOffline.event("config_cached_in_use") && providerOffline.manager.config().enabled
            && providerOffline.old.volume>0 && providerOffline.next.volume>0,
            "provider loss retains a successfully read setting and permits real background overlap");
        providerOffline.diagnostics.config.put("enabled",false);providerOffline.diagnostics.configAvailable=true;
        providerOffline.diagnostics.monitor.run();providerOffline.advance(1000);
        check(!providerOffline.manager.config().enabled && providerOffline.old.releases==1
            && providerOffline.event("reason=disabled") && !providerOffline.event("success=true"),
            "a newly readable disabled setting cancels cached-config overlap immediately");
        providerOffline.diagnostics.configAvailable=false;providerOffline.diagnostics.monitor.run();providerOffline.advance(3000);
        check(!providerOffline.manager.config().enabled && providerOffline.inner.changes==1,
            "provider loss after disabling never restores a formerly enabled setting");

        Scenario bootstrapOffline = new Scenario(null, MediaResType.FULL,false);
        bootstrapOffline.old.position=166000;bootstrapOffline.advance(4000);
        check(!bootstrapOffline.manager.config().enabled && bootstrapOffline.inner.changes==0
            && !bootstrapOffline.event("config_cached_in_use"),
            "no background mix is enabled when settings have never been read successfully");
        bootstrapOffline.diagnostics.configAvailable=true;bootstrapOffline.diagnostics.monitor.run();
        bootstrapOffline.advance(12000);
        check(bootstrapOffline.manager.config().enabled && bootstrapOffline.event("success=true")
            && bootstrapOffline.inner.changes==1,
            "first successful provider response starts callback-free playback clock after bootstrap failure");

        Scenario mixedClock = new Scenario(null, MediaResType.FULL);
        mixedClock.old.position=166000;
        for(int i=0;i<40;i++){mixedClock.progress();mixedClock.advance(200);}
        mixedClock.advance(8000);
        check(mixedClock.inner.changes==1 && mixedClock.inner.j.calls==1 && mixedClock.event("success=true"),
            "foreground callbacks followed by a silent listener do not duplicate the queue advance");

        Scenario twoBackground = new Scenario(null, MediaResType.FULL);
        twoBackground.advance(7000);
        TTVideoEngine third=new TTVideoEngine();
        twoBackground.wrapper.next=new MediaPlayer(new TTMediaPlayer(third,null),new Item("C"),null,MediaResType.FULL);
        twoBackground.next.seekTo(166000,ok->{});
        twoBackground.advance(16000);
        check(twoBackground.inner.changes==2 && twoBackground.event("completed=2")
            && twoBackground.old.releases==1 && twoBackground.next.releases==1 && third.playing && third.volume==1,
            "two successive background transitions work without any listener progress");

        Scenario blockedClock = new Scenario(null, MediaResType.FULL);
        java.util.List<Runnable> queuedChecks=new java.util.ArrayList<>();
        com.luna.common.player.executor.IPlayerThreadExecutor executor=blockedClock.inner.executor;
        blockedClock.inner.executor=queuedChecks::add;
        blockedClock.advance(7000);
        check(queuedChecks.size()==2 && blockedClock.inner.changes==0,
            "a blocked host executor receives one pending check and one liveness probe, not a growing queue");
        check(blockedClock.event("host_executor_stalled"), "a stalled host player executor is reported");
        blockedClock.inner.executor=executor;for(Runnable queued:new java.util.ArrayList<>(queuedChecks))executor.X(queued);queuedChecks.clear();Handler.drain();
        check(blockedClock.inner.realPlayingChecks==1,"a delayed check re-enters the host player looper");
        blockedClock.manager.shutdown();int beforeShutdown=blockedClock.inner.realPlayingChecks;
        blockedClock.advance(10000);blockedClock.manager.progress(blockedClock.wrapper);Handler.drain();
        check(blockedClock.inner.realPlayingChecks==beforeShutdown && blockedClock.inner.changes==0,
            "shutdown clears the clock and rejects subsequent callbacks");

        Scenario pendingShutdown = new Scenario(null, MediaResType.FULL);
        java.util.List<Runnable> pendingChecks=new java.util.ArrayList<>();
        com.luna.common.player.executor.IPlayerThreadExecutor pendingExecutor=pendingShutdown.inner.executor;
        pendingShutdown.inner.executor=pendingChecks::add;pendingShutdown.advance(1000);
        pendingShutdown.manager.shutdown();for(Runnable task:pendingChecks)pendingExecutor.X(task);Handler.drain();
        check(pendingShutdown.inner.changes==0 && pendingShutdown.inner.realPlayingChecks==0,
            "a host work item queued before shutdown cannot touch the player afterwards");

        Scenario parameters = new Scenario(null, MediaResType.FULL);
        parameters.inner.j.action=()->parameters.next.setPlaybackParams(new com.ss.ttm.player.PlaybackParams());
        parameters.progress();parameters.advance(7000);
        check(parameters.event("success=true") && parameters.old.releases==1,
            "host normal-rate synchronization during queue promotion must not cancel the mix");
        check(parameters.event("host_speed_sync") && !parameters.event("engine_mutation"),
            "default speed synchronization is diagnosed separately from actual source mutation");
        Scenario speedChange = new Scenario(null, MediaResType.FULL);
        speedChange.progress();speedChange.advance(2000);
        speedChange.next.setPlaybackParams(new com.ss.ttm.player.PlaybackParams().setSpeed(1.25f));
        speedChange.advance(7000);
        check(speedChange.event("reason=playback_params_changed") && !speedChange.event("success=true")
            && speedChange.next.tempo==1.25f && speedChange.old.releases==1,
            "external non-default speed wins and cancels overlap with exact operation recorded");
        Scenario sourceChange = new Scenario(null, MediaResType.FULL);
        sourceChange.progress();sourceChange.advance(1500);
        sourceChange.manager.sourceChange(sourceChange.next,"setDirectURL");
        sourceChange.advance(7000);
        check(sourceChange.event("method=setDirectURL") && sourceChange.event("reason=setDirectURL")
            && !sourceChange.event("success=true"),"real source replacement still cancels and identifies its method");
        Scenario routed = new Scenario(null, MediaResType.FULL);
        routed.inner.j.pageRouter = new com.luna.biz.playing.playpage.pager.delegate.viewmodel.livedatacontroller.AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1();
        routed.inner.j.pageRouter.dispatch = () -> {routed.inner.changeToNextQueueItem(null);routed.inner.play(null);};
        routed.progress();
        check(routed.inner.j.pageRouter.pageChanges == 0, "mix transaction must not publish the completion-only page event");
        check(routed.old.playing && routed.old.releases == 0 && routed.stream.closes == 0, "side-effect router cannot destroy old track before retention");
        check(routed.inner.j.policyCalls == 1 && routed.inner.changes == 1, "remaining policies run and queue advances once");
        routed.advance(3000);
        check(routed.old.volume > 0 && routed.next.volume > 0, "routed scenario produces simultaneous nonzero gains");
        routed.advance(4000);
        check(routed.event("success=true"), "routed transition completes with cleanup");
        check(routed.event("dual_progress_observed") && routed.event("dual_progress=true"), "success requires observed dual progress and nonzero gains");

        Scenario policy = new Scenario(null, MediaResType.FULL);
        policy.inner.j.pageRouter = new com.luna.biz.playing.playpage.pager.delegate.viewmodel.livedatacontroller.AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1();
        policy.inner.j.result.blocked = true; policy.inner.j.result.errorCode = 123456;
        policy.progress();
        check(policy.inner.j.pageRouter.pageChanges == 0 && policy.inner.j.policyCalls == 1, "UI redirect does not skip remaining policy interceptors");
        check(policy.inner.changes == 0 && policy.old.playing && policy.old.volume == 1 && policy.old.releases == 0, "real policy block preserves original playback");
        check(policy.event("code=123456") && policy.event("automatic_intercepted"), "actual policy decision is logged without conflating sleep");

        Scenario handoff = new Scenario(null, MediaResType.FULL);
        handoff.inner.j.action = () -> {handoff.inner.changeToNextQueueItem(null);handoff.inner.play(null);};
        handoff.inner.j.result.blocked = true; handoff.inner.j.result.willPlay = true;
        handoff.progress(); handoff.advance(7000);
        check(handoff.inner.changes == 1 && handoff.inner.j.calls == 1 && handoff.event("success=true"), "synchronous host handoff protected before action and never advances twice");

        Scenario pending = new Scenario(null, MediaResType.FULL);
        pending.inner.j.result.blocked = true; pending.inner.j.result.willPlay = true;
        pending.progress(); pending.advance(3000); pending.progress();
        check(pending.inner.changes == 0 && pending.inner.j.calls == 1 && pending.old.playing, "unconfirmed host handoff neither double dispatches nor retries side effects");
        check(pending.event("host_handoff_unconfirmed") && !pending.event("success=true"), "unconfirmed action cannot claim overlap");

        Scenario sleep = new Scenario(null, MediaResType.FULL); sleep.inner.sleepTime = 1800L; sleep.progress();
        check(sleep.inner.j.calls == 0 && sleep.inner.changes == 0 && sleep.event("sleep_timer=1800"), "sleep is checked without side effects and has its own reason");

        Scenario original = new Scenario(null, MediaResType.FULL);
        com.luna.biz.playing.playpage.pager.delegate.viewmodel.livedatacontroller.AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1 router = new com.luna.biz.playing.playpage.pager.delegate.viewmodel.livedatacontroller.AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1();
        router.dispatch = () -> {original.inner.changeToNextQueueItem(null);original.inner.play(null);};
        check(router.onInterceptAutoChangeToNextPlayable(null).isIntercepted() && router.pageChanges == 1 && original.inner.changes == 1, "outside a mix transaction original page routing remains active");
        Scenario s = new Scenario(null, MediaResType.FULL);
        s.progress();
        check(s.inner.changes == 1, "AVInfo FULL / reported null enters native queue promotion");
        check(s.wrapper.current.player.b == s.next, "prepared native engine promoted");
        check(s.old.releases == 0 && s.stream.closes == 0 && s.old.playing, "old engine and stream retained together");
        check(s.next.volume == 0, "incoming muted before its first progress");
        check(s.inner.j.calls == 1, "original auto-play interceptor runs");
        s.advance(3000);
        check(s.old.volume > 0 && s.old.volume < 1 && s.next.volume > 0 && s.next.volume < 1, "both tracks have real nonzero gains midway");
        s.advance(4000);
        check(s.event("success=true"), "transition completes");
        check(s.old.releases == 1 && s.stream.closes == 1, "deferred engine and stream cleaned once");
        check(s.next.playing && s.next.volume == 1, "incoming playback continues at original volume");
        check(s.diagnostics.events.stream().filter(e -> e.startsWith("player_bound ")).count() == 1, "duplicate constructor binding does not reset state");

        s = new Scenario(null, MediaResType.FULL); s.old.duration = 0; s.progress(); s.advance(7000);
        check(s.event("success=true") && s.old.releases == 1 && s.stream.closes == 1, "stream duration from wrapper survives native zero duration");

        s = new Scenario(null, MediaResType.FULL); s.adaptive(); s.progress(); s.incomingPcm(1000, (short) 0);
        check(s.next.volume == 0 && s.old.volume == 1 && s.event("incoming_audio_observed"), "PCM silent intro keeps outgoing audible and incoming muted");
        s.incomingPcm(6000, (short) 12000);
        check(s.event("success=true") && s.next.volume == 1 && s.old.releases == 1, "audible PCM starts a bounded fade after silent intro");
        s = new Scenario(null, MediaResType.FULL); s.adaptive(); s.old.position = 172000;
        ByteBuffer silence = ByteBuffer.allocate(960 * 2);
        for (int i = 0; i < 20; i++) s.manager.audio.probe(s.old).accept(new ByteBuffer[] {silence}, 960, 48000, 1, 1, SystemClock.now);
        s.progress(); s.advance(3000);
        check(s.event("strategy=quiet_tail") && s.event("duration=2200") && s.event("success=true"), "observed quiet tail triggers an earlier short transition");

        // Host-side settings cache: a frozen/killed module process at cold start does not disable mixing.
        Scenario frozenModule = new Scenario(null, MediaResType.FULL, false, true);
        frozenModule.old.position = 166000; frozenModule.advance(16000);
        check(frozenModule.event("config_cache_loaded") && frozenModule.event("success=true") && frozenModule.inner.changes == 1,
            "cached settings in the host process keep mixing working while the provider is unreachable");
        Scenario neverConfigured = new Scenario(null, MediaResType.FULL, false, false);
        neverConfigured.old.position = 166000; neverConfigured.advance(16000);
        check(neverConfigured.inner.changes == 0 && !neverConfigured.event("success=true"), "no cache and no provider still never mixes");
        Scenario innerPress = new Scenario(null, MediaResType.FULL); innerPress.progress();
        innerPress.manager.innerTransport(innerPress.inner, "pause"); innerPress.advance(1000); Handler.drain();
        check(innerPress.event("inner_pause") && innerPress.event("known=true"), "queue-controller pause is logged above the wrapper");

        // Pause after crossfading into an analysed intro (user report: pause stuck until a track change).
        for (boolean repair : new boolean[] {false, true}) {
            TestRuntime.pauseRepair = repair;
            Scenario rewound = new Scenario(null, MediaResType.FULL);
            beatSetup(rewound, 120);
            check(rewound.event("intro_analysis_ready") && rewound.next.getPlaybackState() == 2 && !rewound.next.playing,
                "intro analysis leaves the prepared engine paused at its start");
            for (int t = 0; t < 30000 && !rewound.event("transition_finish"); t += 200) { rewound.advance(200); rewound.progress(); }
            check(rewound.event("success=true") && rewound.wrapper.current.player.b == rewound.next && rewound.next.playing,
                "the analysed engine is promoted and plays on after the crossfade");
            if (!repair) check(!rewound.wrapper.current.C && !rewound.wrapper.current.A,
                "negative control: the host's resume path leaves the promoted player's start flags unset");
            else check(rewound.wrapper.current.C && rewound.wrapper.current.A && rewound.event("start_flags_restored"),
                "resuming an analysed engine restores the flags its skipped fresh start would have set");
            if (repair) rewound.wrapper.current.C = false; // exercise the pause-time safety net on its own
            rewound.manager.transport(rewound.wrapper, "pause");
            rewound.wrapper.pause(); rewound.advance(1000); Handler.drain();
            if (!repair) check(rewound.next.playing && rewound.event("pause_ineffective"),
                "negative control: without the repair the pause returns but the music keeps playing (0.5.x)");
            else check(!rewound.next.playing && rewound.next.getPlaybackState() == 2 && rewound.event("pause_repaired")
                && rewound.event("analysed=true") && !rewound.event("pause_ineffective"),
                "with the repair the host's own pause path stops the engine");
            if (repair) {
                rewound.wrapper.play(null);
                check(rewound.next.playing, "play after the repaired pause resumes");
                rewound.wrapper.pause();
                check(!rewound.next.playing, "a second pause on the same track works too");
            }
        }
        TestRuntime.pauseRepair = true;
        Scenario fresh = new Scenario(null, MediaResType.FULL); fresh.progress(); fresh.advance(7000);
        check(fresh.event("success=true") && fresh.wrapper.current.C, "a prepared but never analysed engine takes the fresh start path");
        fresh.wrapper.pause();
        check(!fresh.next.playing && !fresh.event("pause_repaired"), "a correctly started player is left alone");

        // Pause diagnostics: a press that leaves the host playing is reported with full state.
        Scenario stuck = new Scenario(null, MediaResType.FULL); stuck.progress();
        stuck.manager.transport(stuck.wrapper, "pause"); stuck.advance(1000); Handler.drain();
        check(stuck.event("user_pause") && stuck.event("pause_ineffective") && stuck.event("intro_busy="),
            "a pause press that leaves playback running is diagnosed with module state");
        Scenario paused = new Scenario(null, MediaResType.FULL); paused.progress();
        paused.manager.transport(paused.wrapper, "pause"); paused.wrapper.pause(); paused.advance(1000); Handler.drain();
        check(paused.event("user_pause") && !paused.event("pause_ineffective"), "an effective pause is not reported");
        Scenario other = new Scenario(null, MediaResType.FULL); other.progress();
        other.manager.transport(new MediaPlayerWrapper(), "pause"); other.advance(1000); Handler.drain();
        check(other.event("user_pause") && other.event("bound=false"), "a pause sent to an unbound player object is still logged");

        for (boolean fading : new boolean[] {true, false}) {
            // Mastered fade-out inside the outro window starts the transition at the fade, not in the last 6 s.
            Scenario outro = new Scenario(null, MediaResType.FULL); outro.adaptive(); outro.old.position = 150000;
            int startedAt = -1;
            for (int ms = 0; ms < 22000 && outro.old.getCurrentPlaybackTime() < 173000; ms += 20) {
                double level = !fading || ms < 10000 ? 0.3 : 0.3 * Math.exp(-(ms - 10000) / 2500d);
                short value = (short) (level * 32767 * ((ms / 20) % 2 == 0 ? 1 : -1));
                ByteBuffer pcm = ByteBuffer.allocate(960 * 2).order(ByteOrder.nativeOrder());
                while (pcm.hasRemaining()) pcm.putShort(value); pcm.flip();
                outro.manager.audio.probe(outro.old).accept(new ByteBuffer[] {pcm}, 960, 48000, 1, 1, SystemClock.now);
                SystemClock.now += 20; Handler.drain();
                if (startedAt < 0 && outro.event("transition_begin")) startedAt = outro.old.getCurrentPlaybackTime();
            }
            if (fading) {
                outro.advance(9000);
                check(outro.event("strategy=outro_fade") && startedAt > 160000 && startedAt < 173000
                    && outro.event("success=true") && outro.inner.changes == 1 && outro.old.releases == 1,
                    "outro fade starts one bounded transition before the fixed tail window (at " + startedAt + ")");
            } else {
                check(startedAt < 0 && outro.inner.changes == 0 && !outro.event("outro_fade"),
                    "steady loud tail waits for the configured tail window");
            }
        }

        Scenario quietStartup = new Scenario(null, MediaResType.FULL);quietStartup.adaptive();quietStartup.old.position=172000;
        for (int i=0;i<20;i++) quietStartup.manager.audio.probe(quietStartup.old).accept(new ByteBuffer[]{silence},960,48000,1,1,SystemClock.now);
        quietStartup.progress();quietStartup.incomingPcm(3200,(short)0);
        check(quietStartup.old.volume==1 && quietStartup.next.volume==0,
            "long silent intro keeps the old track audible even for an adaptive short fade");
        quietStartup.incomingPcm(3500,(short)12000);
        check(quietStartup.event("success=true") && !quietStartup.event("reason=watchdog") && quietStartup.old.releases==1,
            "short fade and delayed audible PCM complete through the actual adapter");

        s = new Scenario(null, MediaResType.PREVIEW); s.progress();
        check(s.inner.changes == 0 && s.old.playing, "real preview never promoted");
        check(s.event("reason=source_PREVIEW"), "preview rejection has exact reason");
        s = new Scenario(null, null); s.progress();
        check(s.inner.changes == 0 && s.event("resource_unknown"), "unknown entitlement never guessed");
        s = new Scenario(null, MediaResType.FULL); s.inner.queuedOverride = new Item("C"); s.progress();
        check(s.inner.changes == 0 && s.event("playable_mismatch"), "wrong prepared item never replaces actual next");
        s = new Scenario(null, MediaResType.FULL); s.wrapper.next.prepared = false; s.progress();
        check(s.inner.changes == 0 && s.wrapper.requests == 1, "unprepared next is requested, not promoted");
        s.advance(1000); s.progress();
        check(s.wrapper.requests == 1, "pre-render requests have cooldown");
        s.inner.queuedOverride=new Item("C");s.advance(300);s.progress();
        check(s.wrapper.requests==2,"a changed queue candidate is prepared immediately despite previous cooldown");
        s.inner.queuedOverride=new Item("D");s.advance(300);s.progress();
        check(s.wrapper.requests==2,"rapidly changing candidates cannot thrash native pre-render more than once per second");
        s.advance(800);s.progress();
        check(s.wrapper.requests==3,"latest candidate is retried after the one-second churn bound");
        s = new Scenario(null, MediaResType.FULL); s.inner.j.result.blocked = true; s.progress();
        check(s.inner.changes == 0 && s.old.playing, "host auto-play block preserved");
        s = new Scenario(null, MediaResType.FULL); s.inner.loop = QueueLoopMode.SINGLE; s.progress();
        check(s.inner.changes == 0, "single repeat preserved");

        s = new Scenario(null, MediaResType.FULL); s.progress(); s.advance(1000); s.wrapper.pause(); s.advance(10000);
        check(s.old.releases == 1 && s.stream.closes == 1, "pause immediately disposes old track once");
        check(!s.next.playing && s.next.volume == 1, "pause does not restart incoming track");
        check(s.event("success=false") && !s.event("success=true"), "cancel never increments success");
        s = new Scenario(null, MediaResType.FULL); s.inner.refuse = true; s.progress();
        check(s.old.playing && s.old.volume == 1 && s.old.releases == 0, "refused promotion preserves current track");
        s = new Scenario(null, MediaResType.FULL); s.progress(); s.next.playing = false; s.advance(1800);
        check(s.event("incoming_start_timeout") && s.old.releases == 1, "failed startup has bounded cleanup");
        s = new Scenario(null, MediaResType.FULL); s.progress(); s.old.playing = false; s.advance(1500);
        check(s.event("outgoing_stalled") && !s.event("success=true"), "stopped outgoing cannot count as successful overlap");
        for (String action : new String[] {"seekToTime", "playNext", "onAudioFocusChange"}) {
            s = new Scenario(null, MediaResType.FULL); s.progress(); s.advance(500);
            s.manager.cancelFor(s.wrapper, action); s.advance(10000);
            check(s.old.releases == 1 && s.stream.closes == 1 && !s.event("success=true"), "interrupt cleanup: " + action);
        }
        s = new Scenario(null, MediaResType.FULL); s.progress(); s.advance(500);
        s.diagnostics.config.put("enabled", false); s.diagnostics.monitor.run(); s.advance(100);
        check(s.old.releases == 1 && s.stream.closes == 1 && s.next.volume == 1, "configuration disable disposes tail and restores new track");
        Scenario callback = new Scenario(null, MediaResType.FULL);
        callback.progress();callback.old.releaseCallback=callback.stream::close;callback.advance(7000);
        check(callback.event("success=true") && callback.stream.closes==1,
            "native cleanup callback can acquire source lease while release waits, without monitor deadlock");

        Scenario shared = new Scenario(null, MediaResType.FULL);
        shared.old.position=145000;shared.wrapper.next.player.b=shared.old;
        shared.diagnostics.config.put("adaptive",true);shared.diagnostics.config.put("beatmatch",true);
        shared.diagnostics.monitor.run();pulse(shared.manager.audio.grid(shared.old),120,133000,12000);
        shared.progress();
        check(shared.old.playing && shared.old.volume==1 && !shared.event("intro_analysis_begin"),
            "shared native engine must never be muted for intro analysis");

        Scenario prerollCancel = new Scenario(null, MediaResType.FULL);
        prerollCancel.old.position=145000;
        prerollCancel.diagnostics.config.put("adaptive",true);prerollCancel.diagnostics.config.put("beatmatch",true);
        prerollCancel.diagnostics.monitor.run();pulse(prerollCancel.manager.audio.grid(prerollCancel.old),120,133000,12000);
        prerollCancel.progress();prerollCancel.wrapper.pause();prerollCancel.advance(20000);
        check(!prerollCancel.manager.intro.busy() && prerollCancel.next.releases==1 && prerollCancel.wrapper.next==null,
            "user pause stops and disposes silent intro immediately, once");

        Scenario early = new Scenario(null, MediaResType.FULL);
        beatSetup(early,116,10000,false);
        check(early.event("intro_analysis_ready") && early.manager.intro.result(early.next).reliable()
            && !early.manager.audio.grid(early.old).result().reliable(),
            "intro PCM is analyzed early while the outgoing grid is still unmeasured");
        check(early.old.playing && early.old.volume==1 && early.inner.changes==0 && !early.next.playing
            && early.event("intro_ready_outgoing_pending"),
            "early analysis keeps current audio and queue intact and exposes its actual readiness");
        TTVideoEngine cached = new TTVideoEngine();
        early.wrapper.next=new MediaPlayer(new TTMediaPlayer(cached,null),new Item("B"),null,MediaResType.FULL);
        early.advance(300);early.progress();
        check(early.manager.intro.result(cached).reliable() && early.event("intro_analysis_cached")
            && !cached.playing && cached.getCurrentPlaybackTime()==0 && cached.volume==1,
            "recreated pre-render reuses measured intro metadata without playing or muting the engine");
        early.manager.sourceChange(cached,"setDirectURL");
        check(!early.manager.intro.result(cached).reliable(),"real source replacement revokes a saved grid");
        early.advance(300);early.progress();
        check(early.manager.intro.busy(),"source replacement requires new measurement even for an identical ID");
        early.wrapper.pause();

        Scenario late = new Scenario(null, MediaResType.FULL);late.old.position=169000;late.adaptive();late.progress();
        check(!late.manager.intro.busy() && late.event("analysis_window_short") && late.inner.changes==0,
            "late seek does not start an unsafe analysis or manufacture a beat grid");
        Scenario longFade = new Scenario(null, MediaResType.FULL);longFade.old.position=153000;
        longFade.diagnostics.config.put("duration",12000);longFade.adaptive();longFade.progress();
        check(!longFade.manager.intro.busy() && longFade.event("analysis_window_short"),
            "long configured overlaps reserve enough analysis and rewind time");

        Scenario cacheChecks = new Scenario(null, MediaResType.FULL);beatSetup(cacheChecks,116,10000,false);
        IntroAnalyzer analyzer=cacheChecks.manager.intro;
        TTVideoEngine wrongSong=new TTVideoEngine();
        analyzer.tick(cacheChecks.wrapper,wrongSong,10000,true,"C",180000,6000);
        check(!analyzer.result(wrongSong).reliable(),"cache cannot cross song identities");
        TTVideoEngine wrongPosition=new TTVideoEngine();wrongPosition.position=1000;
        analyzer.tick(cacheChecks.wrapper,wrongPosition,10000,true,"B",180000,6000);
        check(!analyzer.result(wrongPosition).reliable() && !wrongPosition.playing,
            "an engine outside the measured intro start cannot take a cached phase");
        TTVideoEngine wrongDuration=new TTVideoEngine();
        analyzer.tick(cacheChecks.wrapper,wrongDuration,10000,true,"B",183000,6000);
        check(!analyzer.result(wrongDuration).reliable(),"different song duration invalidates the cached resource");
        // The previous rejection removed the B cache. Re-measure instead of reconstructing internals.
        Scenario expired = new Scenario(null, MediaResType.FULL);beatSetup(expired,116,10000,false);
        SystemClock.now+=30*60*1000+1;
        check(!expired.manager.intro.result(expired.next).reliable(),"saved same-engine grids also expire");
        TTVideoEngine expiredEngine=new TTVideoEngine();
        expired.manager.intro.tick(expired.wrapper,expiredEngine,10000,true,"B",180000,6000);
        check(!expired.manager.intro.result(expiredEngine).reliable() && !expiredEngine.playing,
            "expired cache cannot enable a late unmeasured transition");

        Scenario backgroundIntro = new Scenario(null, MediaResType.FULL);
        beatSetup(backgroundIntro,116,10000,false,false);
        check(backgroundIntro.event("intro_analysis_ready") && !backgroundIntro.next.playing
            && backgroundIntro.next.getCurrentPlaybackTime()==0 && backgroundIntro.old.playing
            && backgroundIntro.inner.changes==0,
            "background clock starts measured muted-intro analysis and rewinds without UI progress");

        Scenario parallelBeat = new Scenario(null, MediaResType.FULL);
        beatSetup(parallelBeat,116,10000,false);
        parallelBeat.manager.cancelFor(parallelBeat.wrapper,"seekToTime");
        parallelBeat.old.seekTo(150000,ok->{});parallelBeat.progress();
        check(parallelBeat.manager.intro.result(parallelBeat.next).reliable()
            && !parallelBeat.manager.audio.grid(parallelBeat.old).result().reliable(),
            "seeking after completed intro analysis keeps the intro but discards outgoing phase");
        while(parallelBeat.old.getCurrentPlaybackTime()<174000){
            long pos=parallelBeat.old.getCurrentPlaybackTime();
            ByteBuffer pcm=ByteBuffer.allocate(960*2).order(ByteOrder.nativeOrder());
            for(int frame=0;frame<960;frame++){
                double media=pos+frame/48d;
                double value=(0.02+0.65*Math.exp(-(media%500)/18))*30000*Math.sin(2*Math.PI*100*media/1000);
                pcm.putShort((short)value);
            }
            pcm.flip();parallelBeat.manager.audio.nativeAudio(parallelBeat.old,pcm,960,48000,1,1,pos);
            SystemClock.now+=20;Handler.drain();
            if(pos%200<20)parallelBeat.progress();
        }
        parallelBeat.progress();parallelBeat.advance(7000);
        check(parallelBeat.event("beatmatch_seek_result success=true") && parallelBeat.event("beatmatched=true"),
            "early measured intro combines with fresh outgoing PCM after seek for confirmed phase and tempo");

        Scenario beat = new Scenario(null, MediaResType.FULL);
        beatSetup(beat,116);
        check(beat.event("intro_analysis_ready") && !beat.next.playing && beat.next.getCurrentPlaybackTime()==0,
            "analyzed intro is paused and rewound before promotion");
        check(beat.manager.intro.result(beat.next).reliable(),"incoming grid comes from analyzed PCM");
        while(beat.old.getCurrentPlaybackTime()<174000){
            long pos=beat.old.getCurrentPlaybackTime();
            for(int t=0;t<200;t+=10){double phase=BeatGrid.positive(pos+t,500);beat.manager.audio.grid(beat.old).add(0.04+(phase<30?0.6*Math.exp(-phase/12):0),10,pos+t);}
            beat.advance(200);beat.progress();
        }
        beat.advance(1600);
        check(beat.event("beatmatch_align") && beat.event("beatmatch_seek_result success=true"),"measured grids align in real adapter flow");
        check(beat.next.tempo>1.01f && beat.next.tempo<1.08f,"bounded host playback speed is applied; native pitch quality requires device listening");
        float heldTempo=beat.next.tempo;
        beat.next.setPlaybackParams(new com.ss.ttm.player.PlaybackParams().setSpeed(1));
        check(beat.next.tempo==heldTempo && beat.event("keep_module_rate=true"),
            "asynchronous default synchronization cannot overwrite an owned beat-matched rate");
        beat.advance(5000);
        check(beat.event("success=true") && beat.old.releases==1,"beat transition retains and disposes outgoing normally");
        check(beat.event("beatmatched=true") && beat.event("tempo_observed"),"beat success requires phase lock and actual rate evidence");
        heldTempo=beat.next.tempo;beat.next.setPlaybackParams(new com.ss.ttm.player.PlaybackParams().setSpeed(1));
        check(beat.next.tempo==heldTempo,"default host sync preserves smooth tempo recovery after overlap");
        beat.advance(9000);
        check(beat.next.tempo==1 && beat.event("tempo_restored"),"new track smoothly returns to normal rate");

        Scenario seekFail = new Scenario(null, MediaResType.FULL);
        seekFail.next.seekSuccess=false;beatSetup(seekFail,116);
        check(seekFail.event("rewind_failed") && !seekFail.manager.intro.result(seekFail.next).reliable(),
            "failed intro rewind invalidates beat plan");
        check(seekFail.wrapper.next==null && seekFail.next.releases==1 && !seekFail.event("intro_volume_restore_failed"),
            "bad rewind discards native pre-render once, without touching released state");

        Scenario rate = new Scenario(null, MediaResType.FULL);
        TempoRecovery recovery=new TempoRecovery(new HostBridge(rate.getClass().getClassLoader()),rate.diagnostics);
        rate.next.tempo=1.05f;recovery.begin(rate.next,1.05f,4000,new Handler(android.os.Looper.getMainLooper()));
        rate.advance(800);recovery.external(rate.next);rate.next.tempo=1.25f;rate.advance(5000);
        check(rate.next.tempo==1.25f,"manual speed changes own the engine after recovery cancellation");

        System.out.println("PASS " + assertions + " host integration assertions; actual adapter/manager, background clock, host-thread affinity, promotion, overlap, stream retention, rights, pause and refusal");
    }
}

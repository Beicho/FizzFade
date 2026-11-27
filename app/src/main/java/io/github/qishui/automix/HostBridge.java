package io.github.qishui.automix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Exact adapter for the official 21.6.0 / 21.8.0 APKs. No heuristic hooks. */
final class HostBridge {
    static final String VERSION_LEGACY = "21.6.0", VERSION_LATEST = "21.8.0";
    static final long VERSION_CODE_LEGACY = 100216030L, VERSION_CODE_LATEST = 100218030L;

    static boolean supported(String versionName, long versionCode) {
        return (VERSION_LEGACY.equals(versionName) && versionCode == VERSION_CODE_LEGACY)
            || (VERSION_LATEST.equals(versionName) && versionCode == VERSION_CODE_LATEST);
    }

    final Class<?> wrapperClass, innerClass, factoryClass, engineClass;
    /** The host build behind this loader; 21.8.0 renamed the wrapper's player accessors once. */
    final String hostBuild;
    final Method factoryCreate, current, upcoming, kit, execute, setVolume, release, stop, pause, play, requestPreRender, automaticPageRoute;
    final Field engineField, streamField, interceptorField, factoryType;
    final Object reason, trigger, preRenderType, mainType, unconsumedAutomaticResult;
    final Method getMediaType;
    /** TTVideoEngine.getPlaybackState(): 0 stopped, 1 playing, 2 paused, 3 error. */
    static final int ENGINE_STOPPED = 0, ENGINE_PLAYING = 1, ENGINE_PAUSED = 2;
    /**
     * MediaPlayer.C is set only by the host's fresh start (g4) and gates pause: innerPause calls
     * the engine's pause only when it is true. Resuming an engine that is already paused takes
     * another path (e4) that never sets it.
     */
    final Field engineStarted;
    /** MediaPlayer.A: the play intent, set with the fresh start; a data reload starts playback only with it. */
    final Field playRequested;
    final Method mediaPlay, mediaPause, engineState;

    HostBridge(ClassLoader loader) throws ReflectiveOperationException {
        wrapperClass = cls(loader, "com.luna.biz.playing.player.bach.prerender.MediaPlayerWrapper");
        innerClass = cls(loader, "com.luna.biz.playing.player.bach.InnerPlayer");
        factoryClass = cls(loader, "com.luna.biz.playing.player.bach.MediaPlayerFactory");
        engineClass = cls(loader, "com.ss.ttvideoengine.TTVideoEngine");
        Class<?> media = cls(loader, "com.luna.common.player.mediaplayer.impl.MediaPlayer");
        Class<?> kitClass = cls(loader, "com.luna.common.player.kit.impl.TTMediaPlayer");
        Class<?> playable = cls(loader, "com.luna.common.player.queue.api.IPlayable");
        Class<?> result = cls(loader, "com.luna.common.player.mediaplayer.api.AutoChangeToNextInterceptResult");
        unconsumedAutomaticResult = result.getDeclaredConstructor(boolean.class, boolean.class, Integer.class)
            .newInstance(false, false, null);
        automaticPageRoute = method(cls(loader,
            "com.luna.biz.playing.playpage.pager.delegate.viewmodel.livedatacontroller.AutoChangeToNextLiveDataController$mPlayerInterceptor$2$1"),
            "onInterceptAutoChangeToNextPlayable", playable);
        Class<?> innerApi = cls(loader, "com.luna.common.player.bach.IInnerPlayerController");
        Class<?> config = cls(loader, "com.luna.common.player.mediaplayer.MediaPlayerConfig");
        Class<?> executor = cls(loader, "com.luna.common.player.executor.IPlayerThreadExecutor");
        factoryCreate = method(factoryClass, "a", innerApi, config);
        // 21.8.0 renamed this pair once: current/upcoming are G2/H2 there and H2/I2 in 21.6.0.
        // Only the new build has a zero-argument G2 (21.6.0's G2 takes arguments), so it tells them apart.
        boolean renamed = false;
        try { wrapperClass.getDeclaredMethod("G2"); renamed = true; }
        catch (NoSuchMethodException legacy) { }
        current = method(wrapperClass, renamed ? "G2" : "H2");
        upcoming = method(wrapperClass, renamed ? "H2" : "I2");
        if (current.getReturnType().isPrimitive() || upcoming.getReturnType().isPrimitive()
            || current.getReturnType() != upcoming.getReturnType()) {
            throw new NoSuchMethodException("Host player accessors changed");
        }
        hostBuild = renamed ? VERSION_LATEST : VERSION_LEGACY;
        requestPreRender = method(wrapperClass, "c3", cls(loader, "com.luna.common.player.prerender.PreRenderTrigger"), playable);
        kit = method(media, "k3");
        execute = method(executor, "X", Runnable.class);
        setVolume = method(engineClass, "setVolume", float.class, float.class);
        release = method(engineClass, "release");
        stop = method(engineClass, "stop");
        pause = method(engineClass, "pause");
        play = method(engineClass, "play");
        method(engineClass, "releaseAsync");
        method(engineClass, "getCurrentPlaybackTime");
        method(engineClass, "getDuration");
        method(engineClass, "getVolume");
        method(engineClass, "isMute");
        method(media, "isPrepared");
        method(media, "getPlayable");
        method(wrapperClass, "onPlaybackTimeChanged", playable, long.class);
        method(wrapperClass, "onPlaybackTimeChangedFast", playable, long.class);
        method(wrapperClass, "play", cls(loader, "com.luna.common.player.mediaplayer.PlayReason"));
        method(innerClass, "getExecutor");
        method(innerClass, "changeToNextQueueItem", cls(loader, "com.luna.common.player.mediaplayer.PlayReason"));
        engineField = field(kitClass, "b");
        streamField = field(kitClass, "e");
        interceptorField = field(innerClass, "j");
        factoryType = field(factoryClass, "a");
        if (engineField.getType() != engineClass || factoryType.getType().isPrimitive()) {
            throw new NoSuchFieldException("Host field type changed");
        }
        reason = field(cls(loader, "com.luna.common.player.mediaplayer.PlayReason$PlayComplete"), "a").get(null);
        trigger = field(cls(loader, "com.luna.common.player.prerender.PreRenderTrigger$RenderStart"), "a").get(null);
        Class<?> subtype = cls(loader, "com.luna.common.player.mediaplayer.PlayerSubType");
        preRenderType = field(subtype, "PRERENDER").get(null);
        mainType = field(subtype, "MAIN").get(null);
        method(media, "getAVInfo", subtype);
        getMediaType = method(cls(loader, "com.luna.common.arch.ext.PlayableExtKt"), "getMediaType", playable);
        engineStarted = field(media, "C");
        if (engineStarted.getType() != boolean.class) throw new NoSuchFieldException("MediaPlayer.C changed");
        playRequested = field(media, "A");
        if (playRequested.getType() != boolean.class) throw new NoSuchFieldException("MediaPlayer.A changed");
        mediaPlay = method(media, "play", cls(loader, "com.luna.common.player.mediaplayer.PlayReason"));
        mediaPause = method(media, "pause", cls(loader, "com.luna.common.player.mediaplayer.PauseReason"));
        engineState = method(engineClass, "getPlaybackState");
    }

    /** The engine's own playback state, or -1 when it cannot be read. */
    int engineState(Object engine) {
        if (engine == null) return -1;
        try { return ((Number) engineState.invoke(engine)).intValue(); }
        catch (Exception error) { return -1; }
    }

    private static Class<?> cls(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static Object call(Object object, String name, Object... args) {
        if (object == null) throw new IllegalStateException("Missing host object for " + name);
        try { return XposedHelpers.callMethod(object, name, args); }
        catch (Throwable error) { throw new IllegalStateException("Host call failed: " + name, error); }
    }

    boolean mainFactory(Object factory) throws Exception {
        return "main".equals(call(factoryType.get(factory), "getValue"));
    }

    Object current(Object wrapper) throws Exception { return current.invoke(wrapper); }
    Object upcoming(Object wrapper) throws Exception { return upcoming.invoke(wrapper); }
    Object nativeEngine(Object media) throws Exception {
        if (media == null || !kit.getDeclaringClass().isInstance(media)) return null;
        Object player = kit.invoke(media);
        if (player == null) return null;
        return engineField.get(player);
    }

    Object streamSource(Object media) throws Exception {
        return media != null && kit.getDeclaringClass().isInstance(media) ? streamField.get(kit.invoke(media)) : null;
    }

    boolean request(Object wrapper, Object next) throws Exception {
        // Use the original admission path and its real acknowledgement; do not alter its pending queue item.
        return Boolean.TRUE.equals(requestPreRender.invoke(wrapper, trigger, next));
    }

    MediaFacts facts(Object media, boolean preRender) {
        if (media == null) return new MediaFacts("", "", false, 0);
        String reported = enumName(call(media, "getRealMediaResType"));
        Object info = call(media, "getAVInfo", preRender ? preRenderType : mainType);
        String source = info == null ? "" : enumName(call(info, "getMediaResType"));
        return new MediaFacts(reported, source, Boolean.TRUE.equals(call(media, "isPrepared")),
            number(call(media, "getDuration")));
    }

    static final class MediaFacts {
        final String reported, source, eligibility;
        final boolean prepared;
        final long duration;
        MediaFacts(String reported, String source, boolean prepared, long duration) {
            this.reported = reported;
            this.source = source;
            this.prepared = prepared;
            this.duration = duration;
            eligibility = MediaEligibility.resource(reported, source);
        }
        boolean full() { return "full".equals(eligibility); }
        @Override public String toString() {
            return "prepared=" + prepared + " reported=" + (reported.isEmpty() ? "unset" : reported)
                + " source=" + (source.isEmpty() ? "unset" : source) + " duration=" + duration;
        }
    }

    void dispatch(Object inner, Runnable work) throws Exception {
        Object executor = call(inner, "getExecutor");
        if (executor == null) throw new IllegalStateException("No host player executor");
        execute.invoke(executor, work);
    }

    boolean music(Object item) throws Exception {
        if (item == null || Boolean.TRUE.equals(call(item, "isRewardAdPlayable"))) return false;
        Object type = getMediaType.invoke(null, item);
        return "track".equals(call(type, "getServerValue"));
    }

    static String playId(Object item) {
        Object id = item == null ? null : call(item, "getPlayId");
        return id instanceof String ? (String) id : "";
    }
    static String enumName(Object value) { return value instanceof Enum<?> ? ((Enum<?>) value).name() : ""; }
    static long number(Object value) { return ((Number) value).longValue(); }
    static float decimal(Object value) { return ((Number) value).floatValue(); }

    String automaticBlock(Object inner) {
        if ("SINGLE".equals(enumName(call(inner, "getCurrentQueueLoopMode")))) return "single_loop";
        Object selected = call(inner, "getSelectedSleepTimeS");
        if (selected != null) return "sleep_timer=" + number(selected);
        Object sleep = call(inner, "getLeftSleepTimeData");
        if (sleep != null && Boolean.TRUE.equals(call(sleep, "getWillSleepAfterPlay"))) return "sleep_after_play";
        return null;
    }

    AutomaticResult automaticAction(Object inner, Object playable) throws Exception {
        // This is an action, not a predicate: other host plugins may change playback here.
        Object result = call(interceptorField.get(inner), "onInterceptAutoChangeToNextPlayable", playable);
        return new AutomaticResult(Boolean.TRUE.equals(call(result, "isIntercepted")),
            Boolean.TRUE.equals(call(result, "getWillPlay")), call(result, "getErrorCode"));
    }

    static final class AutomaticResult {
        final boolean intercepted, willPlay;
        final Object code;
        AutomaticResult(boolean intercepted, boolean willPlay, Object code) {
            this.intercepted = intercepted; this.willPlay = willPlay; this.code = code;
        }
        @Override public String toString() {
            return "intercepted=" + intercepted + " will_play=" + willPlay + " code=" + code;
        }
    }

    boolean switchNext(Object inner) {
        // Same reason and order as onCompletion, with the automatic interceptor checked above.
        if (!Boolean.TRUE.equals(call(inner, "changeToNextQueueItem", reason))) return false;
        return Boolean.TRUE.equals(call(inner, "play", reason));
    }

    interface SeekReply { void complete(boolean success); }
    void seek(Object engine, int position, SeekReply reply) throws Exception {
        Class<?> listener = Class.forName("com.ss.ttvideoengine.SeekCompletionListener", false, engineClass.getClassLoader());
        Object callback = java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener}, (proxy, method, args) -> {
            if (method.getName().equals("onCompletion")) { reply.complete(Boolean.TRUE.equals(args[0])); return null; }
            if (method.getName().equals("toString")) return "AutoMixSeek";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
        original(method(engineClass, "seekTo", int.class, listener), engine, new Object[]{position, callback});
    }

    void tempo(Object engine, float speed) throws Exception {
        if (!Float.isFinite(speed) || speed < 0.90f || speed > 1.10f) throw new IllegalArgumentException("Tempo outside transition limit");
        Class<?> params = Class.forName("com.ss.ttm.player.PlaybackParams", false, engineClass.getClassLoader());
        Object value = params.getDeclaredConstructor().newInstance();
        call(value, "setSpeed", speed); call(value, "setPitch", 1f);
        original(method(engineClass,"setPlaybackParams", params), engine, new Object[]{value});
    }

    void discardIntro(Object wrapper, Object expectedEngine) throws Exception {
        Object next = upcoming(wrapper);
        if (next == null || nativeEngine(next) != expectedEngine) return;
        Object provider = field(wrapperClass, "z").get(wrapper);
        if (provider == null) throw new IllegalStateException("Missing pre-render provider");
        Object discard = field(cls(wrapperClass.getClassLoader(), "com.luna.common.player.mediaplayer.api.PreRenderDiscardReason$PreRenderUnprepared"), "a").get(null);
        call(provider, "A0", discard);
    }

    void gain(Object engine, float left, float right) throws Exception {
        original(setVolume, engine, new Object[] {left, right});
    }

    static void original(Method method, Object receiver, Object[] args) throws Exception {
        try {
            XposedBridge.invokeOriginalMethod(method, receiver, args);
        } catch (Throwable error) {
            throw new Exception("Host call failed: " + method.getName(), error);
        }
    }
}

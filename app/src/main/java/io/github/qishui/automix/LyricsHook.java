package io.github.qishui.automix;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Stage 1 UI: replaces the host's full-screen lyric list with {@link LyricsView}. Independent of
 * the mix hooks: its own contract, its own unhook list, and every failure ends in the native page.
 *
 * <p>Hook points (each behind its own switch):
 * <ul>
 * <li>{@code LyricsViewController.<update>(UpdateLyricsViewData)} — lyric data and the list view.</li>
 * <li>{@code LyricsLiveDataController.<init>} — which player feeds which page.</li>
 * <li>{@code IndicatorAndLyricsViewModel.<init>} — the host's own seek-and-play for taps.</li>
 * </ul>
 * Playback time comes from a listener added through the public {@code IPlayerController} interface.
 */
final class LyricsHook {
    private final HostDiagnostics diagnostics;
    private final LyricsHost host;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * Sessions by host controller. Values are weak: a session holds host objects that lead back to
     * its controller, so a strong value would keep every card ever shown alive. Each session is
     * pinned to its own host list view instead ({@link #SESSION_TAG}) and dies with that card.
     */
    private final Map<Object, WeakReference<Session>> sessions = new WeakHashMap<>();
    /** View tag key for that pin; outside any package id aapt assigns (0x01 framework, 0x7f apps). */
    private static final int SESSION_TAG = 0x51530001;
    private final Set<Object> lives = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Object> models = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private Handler worker;
    private int failures;
    private boolean disabled;

    private LyricsHook(Context app, LyricsHost host, HostDiagnostics diagnostics) {
        this.app = app; this.host = host; this.diagnostics = diagnostics;
    }

    static void install(Context context, ClassLoader loader, HostDiagnostics diagnostics) {
        HostDiagnostics.Ui ui = diagnostics.ui();
        if (!ui.lyrics) { diagnostics.event("lyrics_off", "逐字歌词页已关闭，未安装歌词 hook"); return; }
        if (Build.VERSION.SDK_INT < 29) { diagnostics.event("lyrics_unsupported", "api=" + Build.VERSION.SDK_INT + " 需要 Android 10+"); return; }
        if (!UiGuard.allowed(context, UiGuard.LYRICS, ui.epoch)) {
            diagnostics.event("lyrics_guarded", "歌词页曾两次导致汽水崩溃，已停用；在模块设置里切换一次歌词开关可重新启用");
            return;
        }
        LyricsHost contract;
        try { contract = new LyricsHost(loader); }
        catch (Throwable error) { diagnostics.error("lyrics_contract_failed", error); return; }
        LyricsHook hook = new LyricsHook(context.getApplicationContext() == null ? context : context.getApplicationContext(), contract, diagnostics);
        try {
            hook.hooks.add(XposedBridge.hookMethod(contract.controllerUpdate, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    try { hook.update(p.thisObject, p.args[0]); }
                    catch (Throwable error) { hook.fallback(p.thisObject, "update", error); }
                }
            }));
            hook.hooks.addAll(XposedBridge.hookAllConstructors(contract.liveController, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { hook.lives.add(p.thisObject); }
            }));
            if (ui.seek) hook.hooks.addAll(XposedBridge.hookAllConstructors(contract.viewModel, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { hook.models.add(p.thisObject); }
            }));
            diagnostics.event("lyrics_ready", "hook=" + hook.hooks.size() + " " + contract.describe()
                + " word=" + ui.word + " seek=" + ui.seek + " backdrop=" + ui.backdrop);
        } catch (Throwable error) {
            diagnostics.error("lyrics_hook_failed", error);
            for (XC_MethodHook.Unhook unhook : hook.hooks) try { unhook.unhook(); } catch (Throwable ignored) { }
        }
    }

    private Session session(Object controller) {
        WeakReference<Session> ref = sessions.get(controller);
        return ref == null ? null : ref.get();
    }

    private void update(Object controller, Object data) throws Exception {
        HostDiagnostics.Ui ui = diagnostics.ui();
        Session session = session(controller);
        if (disabled || !ui.lyrics) {
            if (session != null) { sessions.remove(controller); session.detach(disabled ? "disabled_after_errors" : "switched_off"); }
            return;
        }
        if (session == null) { session = new Session(controller); sessions.put(controller, new WeakReference<>(session)); }
        session.update(data, ui);
    }

    private void fallback(Object controller, String stage, Throwable error) {
        diagnostics.errorThrottled("lyrics_" + stage + "_failed", error, 15000);
        WeakReference<Session> ref = controller == null ? null : sessions.remove(controller);
        Session session = ref == null ? null : ref.get();
        if (session != null) session.detach("error_" + stage);
        if (++failures >= 3 && !disabled) {
            disabled = true;
            diagnostics.event("lyrics_disabled", "本次进程内歌词页连续出错 3 次，已回到原生歌词页");
        }
    }

    private Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("QishuiLyrics", android.os.Process.THREAD_PRIORITY_DISPLAY);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    /** One host lyric list view and the overlay drawn above it. */
    private final class Session implements LyricsView.Host {
        private final WeakReference<Object> controller;
        private final LyricsClock clock = new LyricsClock();
        private View recycler;
        private FrameLayout container;
        private ViewGroup pageRoot;
        private LyricsView view;
        private LyricsBackdrop backdrop;
        private LyricsFrames frames;
        private Object[] key;
        private LyricsModel model;
        private boolean word;
        private Object live, player, listener;
        private float hostAlpha = 1f;
        private int generation;
        private long lastWake;

        private final ViewTreeObserver.OnPreDrawListener sync = this::sync;
        private ViewTreeObserver syncing;
        private long probeIdleSince;
        // Player listener, pre-draw sync and frame probe exist only while our view is in a window:
        // the host makes a card (and a lyric list) per song and drops old ones without a callback we
        // hook, so anything registered for good would pile up (device logs: many lyrics_attach, no
        // lyrics_detach) — a pre-draw listener each on the UI thread and a FrameMetrics observer each
        // on the render thread, every frame.
        private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { attached(v); }
            @Override public void onViewDetachedFromWindow(View v) { detached(); }
        };
        private final View.OnLayoutChangeListener resize = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) relayout();
        };

        Session(Object controller) { this.controller = new WeakReference<>(controller); }

        void update(Object data, HostDiagnostics.Ui ui) throws Exception {
            Object controllerObject = controller.get();
            if (controllerObject == null) return;
            View rv = (View) host.controllerRecycler.get(controllerObject);
            if (rv == null) return;
            if (recycler != null && rv != recycler) { detach("recycler_replaced"); recycler.setTag(SESSION_TAG, null); }
            recycler = rv;
            if (rv.getTag(SESSION_TAG) != this) rv.setTag(SESSION_TAG, this);
            if (!(rv.getParent() instanceof FrameLayout)) { detach("container_not_frame"); return; }
            List<?> list = (List<?>) host.updateList.get(data);
            if (list == null || list.isEmpty()) return;
            bind(list);
            Object[] next = new Object[list.size() * 2];
            boolean sampled = false;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (!host.lyricViewData.isInstance(item)) continue;
                next[i * 2] = host.lineSentences[0].get(item);
                next[i * 2 + 1] = host.lineSentences[1].get(item);
                // Without a player listener the host's own per-line updates are the only clock.
                if (!sampled && (!clock.known() || listener == null)) { clock.sample(host.lineTime.getLong(item), System.nanoTime()); sampled = true; }
            }
            if (!same(next, key) || word != ui.word) {
                key = next;
                word = ui.word;
                List<LyricsModel.Source> sources = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    LyricsModel.Source source = host.source(list.get(i), i);
                    if (source == null) continue;
                    if (!ui.word) source.words = null;
                    sources.add(source);
                }
                LyricsModel built = LyricsModel.build(sources);
                if (!built.timed()) { model = null; detach("untimed_lyrics"); return; }
                model = built;
                attach(ui);
                relayout();
                diagnostics.event("lyrics_model", "lines=" + built.lines.length + " word_synced=" + built.wordSynced
                    + " translated=" + built.translated + " player=" + (player != null));
            } else if (model != null && attach(ui)) relayout();
        }

        private boolean same(Object[] a, Object[] b) {
            if (a == b) return true;
            if (a == null || b == null || a.length != b.length) return false;
            for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
            return true;
        }

        /** Finds the host data controller that produced this list, and through it the player. */
        private void bind(List<?> list) throws Exception {
            Object match = null;
            synchronized (lives) {
                for (Object candidate : lives) if (candidate != null && host.liveList.get(candidate) == list) { match = candidate; break; }
            }
            if (match == null) return;
            live = match;
            Object next = host.livePlayer.get(match);
            if (next != player) {
                listen(false);
                player = next;
                if (view != null && view.isAttachedToWindow()) listen(true);
            }
        }

        private void listen(boolean on) {
            try {
                if (on && player != null && listener == null) {
                    listener = Proxy.newProxyInstance(host.playerListener.getClassLoader(), new Class<?>[] {host.playerListener}, events());
                    host.addListener.invoke(player, listener);
                } else if (!on && player != null && listener != null) {
                    Object old = listener;
                    listener = null;
                    host.removeListener.invoke(player, old);
                }
            } catch (Throwable error) { diagnostics.errorThrottled("lyrics_listener_failed", error, 15000); }
        }

        /** Player callbacks arrive on the player thread: only clock writes and a throttled wake. */
        private InvocationHandler events() {
            return (proxy, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "QishuiLyricsListener";
                }
                try {
                    long now = System.nanoTime();
                    if (("onPlaybackTimeChanged".equals(name) || "onPlaybackTimeChangedFast".equals(name)) && args != null && args.length == 2) {
                        clock.sample(((Number) args[1]).longValue(), now);
                        if (now - lastWake > 200_000_000L) { lastWake = now; main.post(this::wake); }
                    } else if ("onPlaybackStateChanged".equals(name) && args != null && args.length == 2 && args[1] != null) {
                        String state = ((Enum<?>) args[1]).name();
                        if (state.endsWith("PLAYING")) clock.playing(true, now);
                        else if (state.endsWith("PAUSED") || state.endsWith("STOPPED") || state.endsWith("ERROR")) clock.playing(false, now);
                        main.post(this::wake);
                    } else if ("onSeekComplete".equals(name)) main.post(this::wake);
                } catch (Throwable error) { diagnostics.errorThrottled("lyrics_event_failed", error, 30000); }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == void.class || !type.isPrimitive()) return null;
                return type == long.class ? 0L : type == float.class ? 0f : type == double.class ? 0d : type == char.class ? (char) 0 : (Object) 0;
            };
        }

        private void wake() { if (view != null) view.wake(); }

        private boolean attach(HostDiagnostics.Ui ui) {
            if (view != null) return false;
            container = (FrameLayout) recycler.getParent();
            UiGuard.install(app);
            view = new LyricsView(container.getContext(), this, clock);
            container.addView(view, container.indexOfChild(recycler) + 1,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            hostAlpha = recycler.getAlpha();
            recycler.setAlpha(0f);
            view.setAlpha(hostAlpha);
            view.setVisibility(recycler.getVisibility());
            view.addOnAttachStateChangeListener(attachment);
            container.addOnLayoutChangeListener(resize);
            if (ui.backdrop) {
                pageRoot = page(container);
                if (pageRoot != null) {
                    backdrop = new LyricsBackdrop(pageRoot.getContext(), () -> main.post(() -> dropBackdrop("backdrop_draw_failed")));
                    pageRoot.addView(backdrop, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    backdrop.follow(pageRoot);
                }
            }
            if (view.isAttachedToWindow()) attached(view);
            diagnostics.event("lyrics_attach", "container=" + container.getClass().getSimpleName() + " backdrop=" + (backdrop != null)
                + " player=" + (player != null) + " seek_model=" + (findModel() != null));
            return true;
        }

        private void attached(View v) {
            listen(true);
            if (syncing == null) { syncing = v.getViewTreeObserver(); syncing.addOnPreDrawListener(sync); }
        }

        /**
         * The window frame probe exists only while our view animates: a FrameMetrics observer costs the
         * render thread a call and a message on every frame of the window, and every card on the pager
         * (three at a time) has a lyric view that is usually parked.
         */
        private void probe(boolean animating) {
            if (animating) {
                probeIdleSince = 0;
                LyricsView lv = view;
                if (frames == null && lv != null && syncing != null) {
                    try { frames = LyricsFrames.start(lv, lv::drawing, worker()); }
                    catch (Throwable error) { diagnostics.errorThrottled("lyrics_frame_probe_failed", error, 60000); }
                }
            } else if (frames != null) {
                long now = SystemClock.uptimeMillis();
                if (probeIdleSince == 0) probeIdleSince = now;
                else if (now - probeIdleSince > 3000) { frames.stop(); frames = null; probeIdleSince = 0; }
            }
        }

        private void detached() {
            listen(false);
            if (syncing != null && syncing.isAlive()) syncing.removeOnPreDrawListener(sync);
            syncing = null;
            if (frames != null) { frames.stop(); frames = null; }
        }

        /** The page root: the nearest FrameLayout ancestor painted with the host's cover gradient. */
        private ViewGroup page(View from) {
            for (ViewParent p = from.getParent(); p instanceof ViewGroup; p = p.getParent()) {
                ViewGroup group = (ViewGroup) p;
                if (group.getBackground() instanceof GradientDrawable && group instanceof FrameLayout) return group;
            }
            return null;
        }

        private boolean sync() {
            try {
                if (view == null || recycler == null) return true;
                if (!diagnostics.ui().lyrics) { main.post(() -> { Object c = controller.get(); if (c != null) sessions.remove(c); detach("switched_off"); }); return true; }
                float alpha = recycler.getAlpha();
                if (alpha != 0f) { hostAlpha = alpha; recycler.setAlpha(0f); }
                if (view.getAlpha() != hostAlpha) view.setAlpha(hostAlpha);
                int visibility = recycler.getVisibility();
                if (view.getVisibility() != visibility) view.setVisibility(visibility);
                if (backdrop != null) {
                    backdrop.follow(pageRoot);
                    if (backdrop.getVisibility() != visibility) backdrop.setVisibility(visibility);
                    backdrop.resume();
                }
                view.resume();
                probe(view.drawing());
            } catch (Throwable error) { main.post(() -> { Object c = controller.get(); fallback(c, "sync", error); }); }
            return true;
        }

        private void relayout() {
            LyricsModel target = model;
            if (target == null || view == null) return;
            int width = container != null && container.getWidth() > 0 ? container.getWidth() : app.getResources().getDisplayMetrics().widthPixels;
            LyricsView.Laid laid = view.laid();
            if (laid != null && laid.model == target && laid.width == width) return;
            int ticket = ++generation;
            Context context = view.getContext();
            worker().post(() -> {
                try {
                    long begin = SystemClock.elapsedRealtime();
                    LyricsView.Laid built = LyricsView.layout(context, target, width);
                    long cost = SystemClock.elapsedRealtime() - begin;
                    main.post(() -> {
                        if (ticket != generation || view == null || model != target) return;
                        view.show(built);
                        diagnostics.throttled("lyrics_layout", "lines=" + built.blocks.length + " width=" + width + " ms=" + cost, 5000);
                    });
                } catch (Throwable error) {
                    main.post(() -> { Object c = controller.get(); fallback(c, "layout", error); });
                }
            });
        }

        private Object findModel() {
            if (live == null) return null;
            synchronized (models) {
                for (Object candidate : models) {
                    try { if (candidate != null && host.modelLive.get(candidate) == live) return candidate; }
                    catch (Throwable ignored) { }
                }
            }
            return null;
        }

        @Override public void seek(LyricsModel.Line line) {
            if (!diagnostics.ui().seek) return;
            Object target = findModel();
            if (target == null || line.hostIndex < 0) {
                diagnostics.throttled("lyrics_seek_unavailable", "model=" + (target != null) + " index=" + line.hostIndex, 15000);
                return;
            }
            try {
                host.modelSeek.invoke(target, line.hostIndex);
                clock.jump(line.start, System.nanoTime());
                wake();
            } catch (Throwable error) { diagnostics.errorThrottled("lyrics_seek_failed", error, 15000); }
        }

        @Override public void failed(String stage, Throwable error) {
            main.post(() -> fallback(controller.get(), stage, error));
        }

        @Override public void stats(String detail) {
            diagnostics.event("lyrics_frames", detail);
            LyricsFrames probe = frames;
            if (probe != null) worker().post(() -> {
                String report = probe.report();
                if (report != null) diagnostics.event("lyrics_window", report);
            });
        }

        private void dropBackdrop(String reason) {
            if (backdrop == null) return;
            ViewParent parent = backdrop.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(backdrop);
            backdrop = null;
            diagnostics.event("lyrics_backdrop_removed", reason);
        }

        void detach(String reason) {
            detached();
            dropBackdrop(reason);
            if (view != null) {
                ViewParent parent = view.getParent();
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
                view.removeOnAttachStateChangeListener(attachment);
                view = null;
                if (recycler != null) recycler.setAlpha(hostAlpha);
                diagnostics.event("lyrics_detach", reason);
            }
            if (container != null) {
                container.removeOnLayoutChangeListener(resize);
                container = null;
            }
            generation++;
        }
    }
}

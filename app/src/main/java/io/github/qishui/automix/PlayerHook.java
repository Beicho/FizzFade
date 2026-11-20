package io.github.qishui.automix;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Stage 2 UI: re-skins the portrait play page card with {@link PlayerSkin}. Independent of the mix
 * and lyric hooks, and it adds no playback control of any kind: the host keeps every touch on its
 * own views, and the player is only listened to (for the cover's paused size).
 *
 * <p>Hook points:
 * <ul>
 * <li>{@code BaseTrackCardViewDelegate.onViewCreated(View, Bundle)}, acting only when the delegate
 * is exactly {@code PortraitTrackCardViewDelegate} (skin, and the card-level trims);</li>
 * <li>{@code CampaignPendantDelegate.onViewCreated(View, Bundle)} (behind {@code ui_trim_pendant});</li>
 * <li>{@code TreasureEntranceDelegate.initViews(View)} (behind {@code ui_trim_coin}).</li>
 * </ul>
 */
final class PlayerHook {
    /** Last playback state heard by a card's listener: 1 playing, 0 paused, -1 unknown (for the bar's round button). */
    static volatile int playingHint = -1;
    private final HostDiagnostics diagnostics;
    private final PlayerHost host;
    private final Context app;
    private ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * Cards by delegate. Values are weak: a card lives exactly as long as its view tree (its attach
     * listener sits on the card root), so neither this map nor a dead card pins the host's views.
     */
    private final Map<Object, WeakReference<Card>> cards = new WeakHashMap<>();
    private Handler worker;
    private int failures;
    private boolean disabled;

    private PlayerHook(Context app, PlayerHost host, HostDiagnostics diagnostics) {
        this.app = app; this.host = host; this.diagnostics = diagnostics;
    }

    static void install(Context context, ClassLoader loader, HostDiagnostics diagnostics) {
        HostDiagnostics.Ui ui = diagnostics.ui();
        boolean cardTrim = ui.trimTags || ui.trimFollow || ui.trimDots;
        if (!ui.player && !cardTrim && !ui.trimPendant && !ui.trimCoin) { diagnostics.event("player_off", "播放页换肤与精简均已关闭，未安装播放页 hook"); return; }
        if (Build.VERSION.SDK_INT < 29) { diagnostics.event("player_unsupported", "api=" + Build.VERSION.SDK_INT + " 需要 Android 10+"); return; }
        if (!UiGuard.allowed(context, UiGuard.PLAYER, ui.epoch)) {
            diagnostics.event("player_guarded", "播放页换肤曾两次导致汽水崩溃，已停用；在模块设置里切换一次界面开关可重新启用");
            return;
        }
        PlayerHost contract;
        try { contract = new PlayerHost(loader); }
        catch (Throwable error) { diagnostics.error("player_contract_failed", error); return; }
        PlayerHook hook = new PlayerHook(context.getApplicationContext() == null ? context : context.getApplicationContext(), contract, diagnostics);
        hook.loader = loader;
        try {
            if (ui.player || cardTrim) XposedBridge.hookMethod(contract.viewCreated, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !contract.portrait(p.thisObject)) return;
                    try { hook.created(p.thisObject, (View) p.args[0]); }
                    catch (Throwable error) { hook.fallback(p.thisObject, "attach", error); }
                }
            });
            String trims = "";
            if (ui.trimPendant) trims += hook.trimDelegate("com.luna.biz.playing.playpage.main.pendantview.CampaignPendantDelegate", true, "pendant");
            if (ui.trimCoin) trims += hook.trimDelegate("com.luna.biz.playing.playpage.title.main.treasure.TreasureEntranceDelegate", false, "coin");
            diagnostics.event("player_ready", contract.describe() + " skin=" + ui.player + " cover=" + ui.playerCover + " bg=" + ui.playerBg
                + " trim=" + (ui.trimTags ? "tags," : "") + (ui.trimFollow ? "follow," : "") + (ui.trimDots ? "dots," : "") + trims);
        } catch (Throwable error) {
            diagnostics.error("player_hook_failed", error);
        }
    }

    /**
     * Keeps a page delegate's root view GONE. The root is the View field containing the others;
     * it is resolved on every pre-draw because some delegates fill their fields lazily.
     */
    private String trimDelegate(String className, boolean viewCreated, String what) {
        try {
            Class<?> owner = LyricsHost.load(loader, className);
            java.lang.reflect.Method method = viewCreated
                ? owner.getDeclaredMethod("onViewCreated", View.class, android.os.Bundle.class)
                : owner.getDeclaredMethod("initViews", View.class);
            java.lang.reflect.Field[] fields = LyricsHost.many(owner, f -> f.getType() == View.class, 3, owner.getSimpleName() + ".views");
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !(p.args[0] instanceof View)) return;
                    Object delegate = p.thisObject;
                    View page = (View) p.args[0];
                    boolean[] logged = {false};
                    int[] fights = {0};
                    long[] since = {0};
                    View[] found = {null};
                    android.view.ViewTreeObserver.OnPreDrawListener keep = new android.view.ViewTreeObserver.OnPreDrawListener() {
                        @Override public boolean onPreDraw() {
                            try {
                                HostDiagnostics.Ui now = diagnostics.ui();
                                if (disabled || !(viewCreated ? now.trimPendant : now.trimCoin)) return true;
                                // Resolved once (reflection and a list per frame otherwise), again only if it left.
                                View root = found[0];
                                if (root == null || root.getParent() == null) root = found[0] = PlayerTrim.root(delegate, fields);
                                // INVISIBLE, not GONE: overlays need no layout pass when the host shows them again.
                                if (root != null && root.getVisibility() == View.VISIBLE) {
                                    root.setVisibility(View.INVISIBLE);
                                    fights[0]++;
                                    long at = android.os.SystemClock.uptimeMillis();
                                    if (!logged[0]) { logged[0] = true; since[0] = at; diagnostics.event("player_trim", what + " root=" + root.getClass().getSimpleName()); }
                                    else if (at - since[0] > 60000) {
                                        if (fights[0] > 30) diagnostics.event("player_trim_fight", what + " rehidden=" + fights[0] + "/min");
                                        fights[0] = 0; since[0] = at;
                                    }
                                }
                            } catch (Throwable error) { diagnostics.errorThrottled("player_trim_failed", error, 30000); }
                            return true;
                        }
                    };
                    // On the window's pre-draw list only while the page is in a window: a listener added
                    // straight to the window's observer outlives the page, runs every frame and pins it.
                    View.OnAttachStateChangeListener scope = new View.OnAttachStateChangeListener() {
                        private android.view.ViewTreeObserver observer;
                        @Override public void onViewAttachedToWindow(View v) {
                            if (observer == null) { observer = v.getViewTreeObserver(); observer.addOnPreDrawListener(keep); }
                        }
                        @Override public void onViewDetachedFromWindow(View v) {
                            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(keep);
                            observer = null;
                        }
                    };
                    page.addOnAttachStateChangeListener(scope);
                    if (page.isAttachedToWindow()) scope.onViewAttachedToWindow(page);
                }
            });
            return what + ",";
        } catch (Throwable error) {
            diagnostics.errorThrottled("player_trim_contract_failed", error, 60000);
            return what + "(failed),";
        }
    }

    private void created(Object delegate, View view) throws Exception {
        HostDiagnostics.Ui ui = diagnostics.ui();
        if (disabled || view == null) return;
        if (view.getResources().getConfiguration().smallestScreenWidthDp >= 600) { diagnostics.throttled("player_skip", "reason=tablet", 60000); return; }
        View seek = find(view, "playing_seek_container_view");
        if (seek == null || !(seek.getParent() instanceof ViewGroup)) { diagnostics.throttled("player_skip", "reason=no_seek_container", 60000); return; }
        ViewGroup root = (ViewGroup) seek.getParent();
        View first = root.getChildCount() > 0 ? root.getChildAt(0) : null;
        ViewGroup.LayoutParams lp = first == null ? null : first.getLayoutParams();
        View background = first instanceof ImageView && lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT
            && lp.height == ViewGroup.LayoutParams.MATCH_PARENT ? first : null;
        View cover = byClass(root, ".CoverViewPager", 4);
        Card old = take(delegate);
        if (old != null) old.detach("recreated");
        Card card = new Card(delegate, root);
        String layers = card.start(background, cover, ui);
        cards.put(delegate, new WeakReference<>(card));
        diagnostics.event("player_attach", layers + " background=" + (background != null) + " cover_found=" + (cover != null));
    }

    private View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    /** First descendant whose class name ends with {@code suffix}; class names are not obfuscated. */
    private static View byClass(View view, String suffix, int depth) {
        if (view.getClass().getName().endsWith(suffix)) return view;
        if (depth == 0 || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View hit = byClass(group.getChildAt(i), suffix, depth - 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private Card take(Object delegate) {
        WeakReference<Card> ref = cards.remove(delegate);
        return ref == null ? null : ref.get();
    }

    private void fallback(Object delegate, String stage, Throwable error) {
        diagnostics.errorThrottled("player_" + stage + "_failed", error, 15000);
        Card card = delegate == null ? null : take(delegate);
        if (card != null) card.detach("error_" + stage);
        if (++failures >= 3 && !disabled) {
            disabled = true;
            for (WeakReference<Card> ref : new java.util.ArrayList<>(cards.values())) { Card c = ref.get(); if (c != null) c.detach("disabled_after_errors"); }
            cards.clear();
            diagnostics.event("player_disabled", "本次进程内播放页换肤连续出错 3 次，已恢复原生播放页");
        }
    }

    private Handler worker() {
        if (worker == null) {
            HandlerThread thread = new HandlerThread("QishuiPlayer", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    /** One portrait card: its skin, its listener on the player, and its frame statistics. */
    private final class Card implements PlayerSkin.Host {
        private final WeakReference<Object> delegate;
        private final ViewGroup root;
        private PlayerSkin skin;
        private Object player, listener;
        private LyricsFrames frames;
        private final PlayerModel.PlayState state = new PlayerModel.PlayState();
        private boolean live;
        private android.view.ViewTreeObserver watching;

        // The player listener and the per-frame watch exist only while the card is in a window: the
        // host makes a card per song and drops old ones without a callback we hook (device logs: many
        // player_attach, no player_detach), so anything left registered would pile up and run forever.
        private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { attached(v); }
            @Override public void onViewDetachedFromWindow(View v) { detached(); }
        };
        private final Runnable report = new Runnable() {
            @Override public void run() {
                if (!live) return;
                LyricsFrames probe = frames;
                String line = probe == null ? null : probe.report();
                if (line != null) diagnostics.event("player_window", line);
                worker().postDelayed(this, 30000);
            }
        };
        private final Runnable refresh = () -> { if (skin != null) skin.playing(state.playing(System.nanoTime())); };

        Card(Object delegate, ViewGroup root) { this.delegate = new WeakReference<>(delegate); this.root = root; }

        String start(View background, View cover, HostDiagnostics.Ui ui) throws Exception {
            live = true;
            UiGuard.install(app);
            String layers = "skin=off";
            if (ui.player) {
                skin = new PlayerSkin(root, background, cover, this);
                layers = skin.attach(ui.playerBg, ui.playerCover);
            }
            if (ui.player && ui.playerCover) {
                Object d = delegate.get();
                player = d == null ? null : host.player(d);
            }
            root.addOnAttachStateChangeListener(attachment);
            if (root.isAttachedToWindow()) attached(root);
            return layers + " player=" + (player != null);
        }

        private void attached(View v) {
            if (!live) return;
            listen(true);
            if (watching == null) { watching = v.getViewTreeObserver(); watching.addOnPreDrawListener(watch); }
        }

        private void detached() {
            listen(false);
            if (watching != null && watching.isAlive()) watching.removeOnPreDrawListener(watch);
            watching = null;
        }

        // Turning the switch off restores the native card at the next frame.
        private final android.view.ViewTreeObserver.OnPreDrawListener watch = this::watch;

        private final java.util.List<View> trims = new java.util.ArrayList<>();
        private int scan;
        private boolean trimLogged;

        private boolean watch() {
            if (!live) return true;
            HostDiagnostics.Ui ui = diagnostics.ui();
            if (disabled || (!ui.player && !ui.trimTags && !ui.trimFollow && !ui.trimDots)) {
                main.post(() -> { Object d = delegate.get(); if (d != null) cards.remove(d); detach("switched_off"); });
                return true;
            }
            if (skin != null && !ui.player) { skin.detach(); skin = null; }
            try {
                // Targets appear when the host binds the song; look again now and then until found.
                boolean stale = trims.isEmpty();
                for (int i = 0; i < trims.size() && !stale; i++) stale = trims.get(i).getParent() == null;
                if (stale && (ui.trimTags || ui.trimFollow || ui.trimDots) && scan++ % 30 == 0) PlayerTrim.card(root, ui, trims);
                int hidden = PlayerTrim.enforce(trims);
                if (hidden > 0 && !trimLogged) { trimLogged = true; diagnostics.event("player_trim", "card hidden=" + hidden + " targets=" + trims.size()); }
            } catch (Throwable error) { diagnostics.errorThrottled("player_trim_failed", error, 30000); }
            return true;
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
            } catch (Throwable error) { diagnostics.errorThrottled("player_listener_failed", error, 15000); }
        }

        /** Player thread: record what was heard, hop to main only when the answer changes. */
        private InvocationHandler events() {
            boolean[] last = {true};
            return (proxy, method, args) -> {
                String name = method.getName();
                switch (name) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "QishuiPlayerSkinListener";
                }
                try {
                    long now = System.nanoTime();
                    if (("onPlaybackTimeChanged".equals(name) || "onPlaybackTimeChangedFast".equals(name)) && args != null && args.length == 2 && args[1] instanceof Number)
                        state.time(((Number) args[1]).longValue(), now);
                    else if ("onPlaybackStateChanged".equals(name) && args != null && args.length == 2 && args[1] instanceof Enum)
                        state.state(((Enum<?>) args[1]).name(), now);
                    boolean playing = state.playing(now);
                    playingHint = playing ? 1 : 0;
                    if (playing != last[0]) { last[0] = playing; main.post(refresh); }
                } catch (Throwable error) { diagnostics.errorThrottled("player_event_failed", error, 30000); }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) return false;
                if (type == void.class || !type.isPrimitive()) return null;
                return type == long.class ? 0L : type == float.class ? 0f : type == double.class ? 0d : type == char.class ? (char) 0 : (Object) 0;
            };
        }

        @Override public void failed(String stage, Throwable error) {
            main.post(() -> fallback(delegate.get(), stage, error));
        }

        void detach(String reason) {
            if (!live) return;
            live = false;
            root.removeOnAttachStateChangeListener(attachment);
            detached();
            if (frames != null) { frames.stop(); frames = null; }
            if (worker != null) worker.removeCallbacks(report);
            if (skin != null) { skin.detach(); skin = null; }
            diagnostics.event("player_detach", reason);
        }
    }
}

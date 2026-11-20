package io.github.qishui.automix;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The play page's one-line lyric redrawn by {@link LyricsShortView}. The host's container stays in
 * place with its own touch handling (tap opens the full lyric page); its children are only made
 * transparent while ours draws above them.
 *
 * <p>Hook points (behind {@code ui_short_lyrics}): {@code ShortLyricsContainerView.<update>(
 * ShortLyricViewData, UpdateLyricsViewData)} for the line and {@code <time>(long)} for the clock.
 */
final class LyricsShortHook {
    private final HostDiagnostics diagnostics;
    private final LyricsShortHost host;
    /**
     * Slots by host container. Values are weak: a slot's own view sits inside its container, so a
     * strong value would keep the key (and the whole card) reachable forever. The container keeps
     * the slot alive through its attach listener instead.
     */
    private final Map<View, java.lang.ref.WeakReference<Slot>> slots = new WeakHashMap<>();
    private int failures;
    private boolean disabled;

    private LyricsShortHook(LyricsShortHost host, HostDiagnostics diagnostics) { this.host = host; this.diagnostics = diagnostics; }

    private Slot slot(View container) {
        java.lang.ref.WeakReference<Slot> ref = slots.get(container);
        return ref == null ? null : ref.get();
    }

    private Slot take(View container) {
        java.lang.ref.WeakReference<Slot> ref = slots.remove(container);
        return ref == null ? null : ref.get();
    }

    static void install(Context context, ClassLoader loader, HostDiagnostics diagnostics) {
        HostDiagnostics.Ui ui = diagnostics.ui();
        if (!ui.shortLyrics) { diagnostics.event("short_lyrics_off", "播放页短歌词保持原生"); return; }
        if (Build.VERSION.SDK_INT < 29) return;
        if (!UiGuard.allowed(context, UiGuard.LYRICS, ui.epoch)) { diagnostics.event("short_lyrics_guarded", "歌词功能曾两次导致崩溃，已停用"); return; }
        LyricsShortHost contract;
        try { contract = new LyricsShortHost(loader); }
        catch (Throwable error) { diagnostics.error("short_lyrics_contract_failed", error); return; }
        LyricsShortHook hook = new LyricsShortHook(contract, diagnostics);
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        try {
            // Before, not after: once our line is showing, the host's own (hidden) update is skipped.
            // On every line change it runs a 300ms animator that calls setTextSize on two lines each
            // frame, re-laying out the whole play page underneath ours: the stutter at line changes.
            XposedBridge.hookMethod(contract.update, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { UiGuard.install(app); if (hook.line((View) p.thisObject, p.args[0])) p.setResult(null); }
                    catch (Throwable error) { hook.fail((View) p.thisObject, error); }
                }
            });
            XposedBridge.hookMethod(contract.time, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { if (hook.time((View) p.thisObject, (Long) p.args[0])) p.setResult(null); }
                    catch (Throwable error) { hook.fail((View) p.thisObject, error); }
                }
            });
            diagnostics.event("short_lyrics_ready", contract.describe());
        } catch (Throwable error) { diagnostics.error("short_lyrics_hook_failed", error); }
    }

    /** True when our view now shows this update, so the host's hidden copy need not run. */
    private boolean line(View container, Object data) throws Exception {
        if (disabled || !diagnostics.ui().shortLyrics || !(container instanceof ViewGroup)) return false;
        // The host re-sends the same sentence on every playback tick with a new time stamp:
        // parse a sentence once, and only feed the clock for the repeats.
        Object sentence = data == null || !host.lineData.isInstance(data) ? null : host.lineSentence.get(data);
        Slot known = slot(container);
        if (known != null && sentence != null && sentence == known.sentence) {
            known.lines++;
            long stamp = host.stamp(data);
            if (stamp >= 0) feed(known, stamp);
            return known.live();
        }
        LyricsModel.Source source = host.source(data);
        if (source == null || source.text.trim().isEmpty()) return false;
        if (!diagnostics.ui().word) source.words = null;
        LyricsModel model = LyricsModel.build(Collections.singletonList(source));
        LyricsModel.Line chosen = null;
        for (LyricsModel.Line l : model.lines) if (l.kind != LyricsModel.INTERLUDE) { chosen = l; break; }
        if (chosen == null) return false;
        Slot slot = known;
        if (slot == null) { slot = new Slot((ViewGroup) container); slots.put(container, new java.lang.ref.WeakReference<>(slot)); }
        slot.view.show(chosen);
        slot.sentence = sentence;
        slot.lines++;
        long stamp = host.stamp(data);
        if (stamp >= 0) feed(slot, stamp);
        return slot.live();
    }

    /** One clock sample from either source, with a throttled wake and a feed count for the log. */
    private void feed(Slot slot, long ms) {
        long now = System.nanoTime();
        slot.clock.sample(ms, now);
        slot.view.wake();
        if (slot.reportAt == 0) slot.reportAt = now;
        else if (now - slot.reportAt > 20_000_000_000L) {
            diagnostics.event("short_lyrics_feed", "line_updates=" + slot.lines + " time_calls=" + slot.times + " in 20s running=" + slot.clock.running());
            slot.lines = slot.times = 0;
            slot.reportAt = now;
        }
    }

    private boolean time(View container, long ms) {
        Slot slot = slot(container);
        if (slot == null || disabled || !diagnostics.ui().shortLyrics) return false;
        slot.times++;
        feed(slot, ms);
        return slot.live();
    }

    private void fail(View container, Throwable error) {
        diagnostics.errorThrottled("short_lyrics_failed", error, 15000);
        Slot slot = container == null ? null : take(container);
        if (slot != null) slot.detach();
        if (++failures >= 3 && !disabled) {
            disabled = true;
            for (java.lang.ref.WeakReference<Slot> ref : new java.util.ArrayList<>(slots.values())) { Slot s = ref.get(); if (s != null) s.detach(); }
            slots.clear();
            diagnostics.event("short_lyrics_disabled", "本次进程内短歌词连续出错 3 次，已恢复原生");
        }
    }

    /** Our view inside one host container; the host's own children kept transparent. */
    private final class Slot implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        final ViewGroup container;
        final LyricsClock clock = new LyricsClock();
        final LyricsShortView view;
        int lines, times;
        Object sentence;
        long reportAt;
        private ViewTreeObserver observer;

        Slot(ViewGroup container) {
            this.container = container;
            view = new LyricsShortView(container.getContext(), clock);
            view.setId(View.generateViewId());
            clock.playing(true, System.nanoTime());
            container.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // On the window's pre-draw list only while the card is in a window: the host makes a card
            // per song and drops old ones silently, so a listener added once would pile up per song.
            container.addOnAttachStateChangeListener(this);
            if (container.isAttachedToWindow()) onViewAttachedToWindow(container);
            diagnostics.event("short_lyrics_attach", "container=" + container.getWidth() + "x" + container.getHeight() + " children=" + container.getChildCount());
        }

        @Override public void onViewAttachedToWindow(View v) {
            if (observer != null) return;
            observer = v.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }

        @Override public void onViewDetachedFromWindow(View v) { drop(); }

        private void drop() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }

        @Override public boolean onPreDraw() {
            try {
                if (disabled || !diagnostics.ui().shortLyrics) { container.post(() -> { slots.remove(container); detach(); }); return true; }
                for (int i = 0; i < container.getChildCount(); i++) {
                    View c = container.getChildAt(i);
                    if (c != view && c.getAlpha() != 0f) c.setAlpha(0f);
                }
                view.resume();
            } catch (Throwable error) { fail(container, error); }
            return true;
        }

        /** Ours is in the container and drawing; otherwise the host keeps its own line current. */
        boolean live() { return view.getParent() == container && container.isAttachedToWindow(); }

        void detach() {
            container.removeOnAttachStateChangeListener(this);
            drop();
            container.removeView(view);
            for (int i = 0; i < container.getChildCount(); i++) container.getChildAt(i).setAlpha(1f);
        }
    }
}

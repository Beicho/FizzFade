package io.github.qishui.automix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The host's bottom bar as an iOS 26 Liquid Glass tab bar: a 62dp glass capsule 21dp from the
 * screen edges (measured values; Apple publishes none), the 汽水 play tab split into a 62dp glass
 * circle beside it like iOS's search tab, filled glyphs over short labels, and the selection as a
 * platter that lifts into a draggable lens bubble on touch ({@link TabMotion}).
 *
 * <p>The host's {@code BottomNavigationView} keeps every tab, listener and animation; its own
 * drawing is made transparent and a touch layer over it forwards taps to the host's tab views with
 * {@code performClick()}, so selection, reselection (scroll to top, play/pause) and the host's
 * click interceptors behave as before.
 *
 * <p>Hook point (behind {@code ui_tab}): constructors of
 * {@code com.luna.biz.main.main.bottombar.BottomNavigationView}, to learn each instance. Tabs are
 * named by the host's {@code com.luna.biz.main.BottomTab} enum (nav id → Play, Discovery, …).
 */
final class TabHook {
    static final String BAR = "com.luna.biz.main.main.bottombar.BottomNavigationView";
    static final String TABS = "com.luna.biz.main.BottomTab";

    /** iOS 26 tab bar, as measured by developers: 62pt tall, 21pt from the screen edges. */
    static final float HEIGHT_DP = 62f, SIDE_DP = 21f, GAP_DP = 10f, INSET_DP = 4.5f;
    /**
     * How far above the capsule the Surface reaches: the fade starts 24dp up, a lifted bubble and its
     * shadow about 25dp, the attribution line about 20dp (28dp at 2x font scale).
     */
    static final float BAND_DP = 72f;

    /** True while another tab covers the play page; our play-page animations park meanwhile. */
    static volatile boolean playCovered;
    private static volatile int underId;

    static void install(Context context, ClassLoader loader, HostDiagnostics diagnostics) {
        HostDiagnostics.Ui ui = diagnostics.ui();
        if (!ui.tab) { diagnostics.event("tab_off", "悬浮玻璃底栏已关闭"); return; }
        if (Build.VERSION.SDK_INT < 29) { diagnostics.event("tab_unsupported", "api=" + Build.VERSION.SDK_INT); return; }
        if (!UiGuard.allowed(context, UiGuard.TAB, ui.epoch)) {
            diagnostics.event("tab_guarded", "悬浮底栏曾两次导致汽水崩溃，已停用；在模块设置里切换一次界面开关可重新启用");
            return;
        }
        Class<?> bar;
        try {
            bar = LyricsHost.load(loader, BAR);
            if (!ViewGroup.class.isAssignableFrom(bar)) throw new ClassCastException(BAR + " is not a ViewGroup");
        } catch (Throwable error) { diagnostics.error("tab_contract_failed", error); return; }
        Map<Integer, String> names = names(loader, diagnostics);
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        try {
            XposedBridge.hookAllConstructors(bar, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { new Skin((ViewGroup) p.thisObject, diagnostics, app, names); }
                    catch (Throwable error) { diagnostics.errorThrottled("tab_attach_failed", error, 15000); }
                }
            });
            diagnostics.event("tab_ready", "class=" + bar.getSimpleName() + " lens=" + TabGlass.lensSupported()
                + " blur=" + (Build.VERSION.SDK_INT >= 31) + " tabs=" + names);
        } catch (Throwable error) { diagnostics.error("tab_hook_failed", error); }
    }

    /** nav id → BottomTab constant name, from the host's enum ({@code getNavId()}). */
    static Map<Integer, String> names(ClassLoader loader, HostDiagnostics diagnostics) {
        Map<Integer, String> out = new HashMap<>();
        try {
            Class<?> type = LyricsHost.load(loader, TABS);
            java.lang.reflect.Method navId = type.getMethod("getNavId");
            Object[] values = type.getEnumConstants();
            if (values != null) for (Object v : values) out.put((Integer) navId.invoke(v), ((Enum<?>) v).name());
        } catch (Throwable error) { diagnostics.errorThrottled("tab_names_failed", error, 60000); }
        return out;
    }

    /** The settings preview: the real skin on a stand-in bar shaped like the host's. */
    static void demo(ViewGroup bar, HostDiagnostics diagnostics, Context app, Map<Integer, String> names) { new Skin(bar, diagnostics, app, names); }

    /** Whether {@code view} lies in the play page while another tab covers it. */
    static boolean covers(View view) {
        if (!playCovered || underId == 0) return false;
        for (Object p = view; p instanceof View; p = ((View) p).getParent())
            if (((View) p).getId() == underId) return true;
        return false;
    }

    /**
     * The bar's background while skinned: draws nothing, but remembers the colour the host paints.
     * The host recolours its bar per tab with {@code setBackgroundColor}, which mutates whatever
     * ColorDrawable is installed instead of replacing it (0.5.3's black block on every tab but play).
     */
    static final class Clear extends ColorDrawable {
        int host = Color.TRANSPARENT;
        @Override public void setColor(int color) { host = color; invalidateSelf(); }
        @Override public void draw(Canvas canvas) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSPARENT; }
    }

    /** One bottom bar, skinned until switched off or broken (then restored). */
    private static final class Skin implements ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnDrawListener,
            View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        private final ViewGroup bar;
        private final HostDiagnostics diagnostics;
        private final Context app;
        private final Map<Integer, String> names;
        private final float dp;
        private final Clear clear = new Clear();
        final TabMotion motion = new TabMotion();
        final TabGlass glass = new TabGlass(), orbGlass = new TabGlass(), bubble = new TabGlass();
        private TabFace face;
        private Surface surface;
        private Pad pad;
        private Orb orb;
        private ViewGroup root;
        private View above, under, over;
        private Drawable hostBackground;
        private int failures, aboveMargin = -1, savedHeight, savedLeft, savedRight, savedBottom, lastHost = 1, attributionColor;
        private int[] savedPadding;
        private boolean dead, logged, saved, layoutDirty = true, faceOn, orbOn, dark = true, attributionHidden;
        private java.lang.reflect.Method itemExtra, selectedId;
        private TextPaint attribution;
        private java.lang.reflect.Field attributionText;
        private Integer selected, lastSelected;
        private TabFace.Tab orbTab;
        private String lastTitles = "";
        String note;
        private long lastScan, lastFrame, pendingUntil, costNanos, costMax, lastCostReport, lastRecord;
        private int pendingIndex = -1, costFrames, lastHaptic = -1, playingHint = -1;
        private final List<View> sources = new ArrayList<>();
        private final List<TabFace.Tab> scanned = new ArrayList<>();
        private final java.util.Set<View> goneByUs = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
        private final java.util.Set<View> clearedByUs = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
        final RectF rest = new RectF(), capsule = new RectF(), orbRest = new RectF(), orbRect = new RectF(), bubbleRect = new RectF();
        private final RectF last = new RectF();
        float faceScale = 1f, faceDx, faceDy, orbPress, orbPressV, recordedMid = Float.NaN;
        /** Round button: selection (platter and tint, 0..1), and the glyph swap (old glyph out, new in). */
        float orbSel, glyphMix = 1f;
        int glyphNow = -1, glyphWas = -1;
        private final float[] orbX = new float[3], orbV = new float[3];
        private boolean recordedDark;
        private boolean orbHeld;
        // The glass is recorded where the capsule and the round button rest (plus the host's own
        // translation) and only moved by node properties per frame; it is re-recorded when what it
        // shows changes: the page under it, the host's bar colour, or its resting place.
        final RectF glassRest = new RectF(), orbBase = new RectF();
        private final RectF recordedRest = new RectF(), recordedOrb = new RectF(), recordedBubble = new RectF();
        private boolean shown, glassStale = true, pagePending = true, recordedBubbleDark;
        private float recordedPress = -1f;
        private int facesVersion, recordedFaces = -1;
        // What the glass last saw of each page container: left, top, scroll x/y, translation x/y,
        // scale x/y, rotation, alpha (a container moved as a whole marks only its parent dirty).
        private View[] seenViews = new View[0];
        private float[] seen = new float[0];
        private Drawable seenBackground;
        private long lookNanos, lookMax;
        private int looks, glassRecords;
        private boolean safetyPosted;
        /**
         * Dirty flags are a hint, not proof: the host's RealtimeBlurView draws the whole window in
         * software from its own pre-draw listener, which clears every view's dirty flag. So the glass
         * also looks again at least this often while frames are drawn, and once after the last one.
         */
        private static final long SAFETY_MS = 100;
        private final Runnable safety = () -> {
            safetyPosted = false;
            if (!dead && shown && SystemClock.uptimeMillis() - lastRecord >= SAFETY_MS) { glassStale = true; tick(); }
        };
        private SwitchFrames frames;
        private Handler worker;
        private HandlerThread workerThread;
        private int pendingReports;
        private final Handler main = new Handler(Looper.getMainLooper());

        Skin(ViewGroup bar, HostDiagnostics diagnostics, Context app, Map<Integer, String> names) {
            this.bar = bar; this.diagnostics = diagnostics; this.app = app; this.names = names;
            dp = bar.getResources().getDisplayMetrics().density;
            Class<?> c = bar.getClass();
            try { itemExtra = c.getMethod("setItemPaddingBottomExtra", int.class); } catch (Throwable ignored) { }
            try { selectedId = c.getMethod("getSelectedItemId"); } catch (Throwable ignored) { }
            // The bottom attribution line the bar paints itself (a per-tab notice from the host's
            // config): its only TextPaint and only String field. Kept, but drawn above the capsule.
            java.lang.reflect.Field[] own = c.getDeclaredFields();
            for (java.lang.reflect.Field f : own) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    if (f.getType() == TextPaint.class && attribution == null) { f.setAccessible(true); attribution = (TextPaint) f.get(bar); }
                    else if (f.getType() == String.class && attributionText == null) { f.setAccessible(true); attributionText = f; }
                } catch (Throwable ignored) { }
            }
            bar.addOnAttachStateChangeListener(this);
            bar.addOnLayoutChangeListener(this);
            if (bar.isAttachedToWindow()) onViewAttachedToWindow(bar);
        }

        @Override public void onViewAttachedToWindow(View v) {
            if (dead) return;
            UiGuard.install(app);
            ViewTreeObserver o = bar.getViewTreeObserver();
            o.addOnPreDrawListener(this);
            o.addOnDrawListener(this);
            glassStale = true;
            // The switch probe's thread starts here, not on the first tab switch: HandlerThread.getLooper()
            // waits for a new background-priority thread, which is the last thing a switch frame needs.
            if (workerThread == null && Build.VERSION.SDK_INT >= 26) {
                workerThread = new HandlerThread("QishuiTab", android.os.Process.THREAD_PRIORITY_BACKGROUND);
                workerThread.start();
            }
        }

        @Override public void onViewDetachedFromWindow(View v) {
            ViewTreeObserver o = bar.getViewTreeObserver();
            if (o.isAlive()) { o.removeOnPreDrawListener(this); o.removeOnDrawListener(this); }
            if (frames != null) { frames.stop(); frames = null; }
            pendingReports = 0;
            if (workerThread != null) { workerThread.quitSafely(); workerThread = null; worker = null; }
            playCovered = false;
        }

        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { layoutDirty = true; }

        @Override public boolean onPreDraw() {
            long start = System.nanoTime();
            try { step(); }
            catch (Throwable error) {
                diagnostics.errorThrottled("tab_frame_failed", error, 15000);
                if (++failures >= 3) restore("errors");
            }
            long spent = System.nanoTime() - start;
            costNanos += spent; costFrames++;
            if (spent > costMax) costMax = spent;
            return true;
        }

        private void step() {
            if (dead) return;
            HostDiagnostics.Ui ui = diagnostics.ui();
            if (!ui.tab) { restore("switched_off"); return; }
            if (!(bar.getParent() instanceof ViewGroup) || !(bar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            root = (ViewGroup) bar.getParent();
            boolean frameLayout = root instanceof FrameLayout;
            faceOn = ui.tabFace && frameLayout && Build.VERSION.SDK_INT >= 29;
            if (surface == null) {
                surface = new Surface(bar.getContext(), this);
                face = new TabFace(dp, bar.getResources().getDisplayMetrics().scaledDensity);
                java.util.function.Consumer<Throwable> sink = error -> diagnostics.errorThrottled("tab_glass_failed", error, 60000);
                glass.onError(sink); orbGlass.onError(sink); bubble.onError(sink);
                if (underId == 0) underId = bar.getResources().getIdentifier("navigation_container_under_bottom_bar", "id", bar.getContext().getPackageName());
            }
            if (surface.getParent() != root || root.indexOfChild(surface) != root.indexOfChild(bar) - 1) {
                if (surface.getParent() instanceof ViewGroup) ((ViewGroup) surface.getParent()).removeView(surface);
                ViewGroup.LayoutParams sl = frameLayout
                    ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.BOTTOM)
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                root.addView(surface, root.indexOfChild(bar), sl);
            }

            boolean dirty = false;
            // The host paints the bar per tab; keep its colour for the glass, the bar itself clear.
            Drawable current = bar.getBackground();
            if (current != clear) {
                hostBackground = current;
                clear.host = current instanceof ColorDrawable ? ((ColorDrawable) current).getColor() : Color.TRANSPARENT;
                bar.setBackground(clear);
            }
            if (clear.host != lastHost) {
                lastHost = clear.host;
                dark = Color.alpha(lastHost) < 128 || luminance(lastHost) < 0.5f;
                dirty = true;
                glassStale = true;   // the glass is painted over the host's colour and tinted by it
            }
            String note = attribution();
            if (!java.util.Objects.equals(note, this.note)) { this.note = note; dirty = true; }
            float mid = bar.getTop() + bar.getHeight() / 2f;
            if (face.tabs.size() > 0 && (mid != recordedMid || dark != recordedDark)) { recordFaces(); dirty = true; }
            hideAttribution(true);
            selected = selected();
            long now = AnimationUtils.currentAnimationTimeMillis();

            boolean scanNow = layoutDirty || now - lastScan > 1000 || !java.util.Objects.equals(selected, lastSelected);
            boolean wantOrb = faceOn && ui.tabOrb && frameLayout;
            if (scanNow) {
                layoutDirty = false;
                lastScan = now;
                dirty |= scan(ui, wantOrb);
            }
            orbOn = wantOrb && orbTab != null;
            layout(orbOn);
            orb(orbOn);
            pad(faceOn);
            if (!java.util.Objects.equals(selected, lastSelected)) selectionChanged(now);
            // A tap the host turned down (its click interceptor, or a tab that opens a page of its own
            // instead of switching) leaves the selection where it was: the bubble glides back there
            // instead of resting on a tab that is not selected.
            if (pendingIndex >= 0 && now > pendingUntil) {
                pendingIndex = -1;
                int index = selectedIndex();
                if (faceOn && index >= 0 && !motion.dragging() && motion.settling() != index) motion.select(index);
            }

            // Where the capsule rests, and where it is drawn this frame (press growth, rubber band).
            float dt = lastFrame == 0 ? 1f / 120f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            boolean moving = faceOn && motion.step(dt);
            if (!moving) lastFrame = 0;
            boolean orbMoving = stepOrb(dt);
            moving |= orbMoving;
            // Springs that changed something this frame; a held, motionless bubble only keeps time.
            boolean animating = (faceOn && motion.springing) || orbMoving;
            rest.set(bar.getLeft(), bar.getTop(), bar.getRight(), bar.getBottom());
            float tx = bar.getTranslationX(), ty = bar.getTranslationY();
            float grow = rest.width() > 0 ? 1f + 16f * dp / rest.width() * motion.press : 1f;
            faceScale = grow;
            faceDx = tx + motion.panel;
            faceDy = ty;
            capsule.set(rest);
            capsule.offset(faceDx, faceDy);
            scale(capsule, grow, grow);
            glassRest.set(rest);
            glassRest.offset(tx, ty);
            if (orbOn && orb.getWidth() > 0) {
                orbRest.set(orb.getLeft(), orb.getTop(), orb.getRight(), orb.getBottom());
                orbBase.set(orbRest);
                orbBase.offset(tx, ty);
                orbRect.set(orbBase);
                float os = 1f + 0.08f * orbPress;
                scale(orbRect, os, os);
            } else { orbRest.setEmpty(); orbBase.setEmpty(); orbRect.setEmpty(); }
            bubble(faceOn);
            band(frameLayout, ty);
            boolean shown = bar.getVisibility() == View.VISIBLE && bar.getAlpha() > 0f && rest.width() > 0;
            int vis = shown ? View.VISIBLE : View.INVISIBLE;
            if (surface.getVisibility() != vis) surface.setVisibility(vis);
            if (surface.getAlpha() != bar.getAlpha()) surface.setAlpha(bar.getAlpha());
            // Back from hidden: HWUI may have dropped the glass nodes that were left out of frames.
            if (shown && !this.shown) glassStale = true;
            this.shown = shown;
            // Page changes made before the pre-draw pass (layout, animators, input, scrolling): seen here,
            // ahead of most of the host's pre-draw listeners; onDraw() looks once more after all of them.
            if (shown) { collectSources(); if (pageMoved()) pagePending = true; }
            if (!capsule.equals(last)) { last.set(capsule); dirty = true; }

            // Press growth, the rubber band and the round button's press move the glass recorded at rest
            // by node properties: no re-recording, no new blur/lens effect, no re-render of its layer.
            glass.transform(capsule);
            orbGlass.transform(orbRect);
            // The Surface repaints only for what it draws itself; the glass follows the page in onDraw(),
            // after every pre-draw listener (the host's included) has had its say.
            if (dirty || animating) surface.invalidate();
            if (animating) surface.postInvalidateOnAnimation();
            else if (moving) tick();   // a held bubble keeps its clock running without repainting anything
            boolean covered = selected != null && names.containsValue("Play") && !"Play".equals(names.get(selected));
            if (covered != playCovered) {
                playCovered = covered;
                // Parked play-page animations resume from their own pre-draw: give them one.
                if (!covered) tick();
            }
            report(now);
            if (!logged && rest.width() > 0) {
                logged = true;
                diagnostics.event("tab_attach", "bar=" + Math.round(rest.width()) + "x" + Math.round(rest.height()) + " at=" + Math.round(rest.left) + "," + Math.round(rest.top)
                    + " root=" + root.getWidth() + "x" + root.getHeight() + " sources=" + sources.size() + " above=" + (above != null)
                    + " host_bg=" + (hostBackground == null ? "none" : hostBackground.getClass().getSimpleName())
                    + " items=" + bar.getChildCount() + " named=" + names.size() + " selected=" + (selectedId != null)
                    + " attribution=" + (attribution != null) + " faces=" + faceOn + " orb=" + orbOn);
            }
        }

        /**
         * The Surface spans only the bottom band its drawings can reach, not the whole screen: HWUI
         * damages a repainted view's full bounds, so a full-screen Surface made every frame of the
         * bubble's motion redraw the entire page underneath (with partial updates, the band only).
         */
        private void band(boolean frameLayout, float ty) {
            ViewGroup.LayoutParams lp = surface.getLayoutParams();
            if (!frameLayout || rest.height() <= 0 || root.getHeight() <= 0 || !(lp instanceof FrameLayout.LayoutParams)) return;
            int need = Math.max(0, Math.round(rest.top + Math.min(0f, ty) - BAND_DP * dp));
            int have = lp.height > 0 ? root.getHeight() - lp.height : -1;
            // Some slack, so a host animating the bar's position does not cost a layout pass per frame.
            if (have >= 0 && have <= need && have >= need - Math.round(48 * dp) && ((FrameLayout.LayoutParams) lp).gravity == android.view.Gravity.BOTTOM) return;
            lp.height = root.getHeight() - need;
            ((FrameLayout.LayoutParams) lp).gravity = android.view.Gravity.BOTTOM;
            surface.setLayoutParams(lp);
        }

        private Integer selected() {
            try { return selectedId == null ? null : (Integer) selectedId.invoke(bar); } catch (Throwable e) { return null; }
        }

        private int selectedIndex() {
            if (selected == null) return -1;
            List<TabFace.Tab> tabs = face.tabs;
            for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).id == selected) return i;
            return -1;
        }

        /** The host moved the selection (our tap, a deep link, a back press): glide the bubble there. */
        private void selectionChanged(long now) {
            lastSelected = selected;
            int index = selectedIndex();
            motion.visible(index >= 0);
            if (index >= 0) {
                if (!motion.placed() || motion.shown < 0.05f) { motion.jump(index); motion.select(index); }
                else if (pendingIndex < 0 || now > pendingUntil || index == pendingIndex) { if (motion.settling() != index && !motion.dragging()) motion.select(index); }
            }
            pendingIndex = -1;
            // Measure the host's page switch, which is where most of a tab change's frames go: the
            // frames whose vsync falls in the next 800ms, wherever their reports arrive. The window's
            // frame observer costs the render thread a call and a message on every frame, so it is
            // registered for the switch and removed with its report (0.5.5 kept it for good).
            if (frames == null && workerThread != null && Build.VERSION.SDK_INT >= 26) {
                if (worker == null) worker = new Handler(workerThread.getLooper());
                frames = SwitchFrames.start(bar, worker);
            }
            final boolean counted = frames != null;
            if (counted) { frames.open(System.nanoTime(), 800_000_000L); pendingReports++; }
            final String to = selected == null ? "?" : names.containsKey(selected) ? names.get(selected) : String.valueOf(selected);
            final String layers = layers();
            main.postDelayed(() -> {
                SwitchFrames probe = frames;
                String line = probe == null ? null : probe.report();
                diagnostics.event("tab_switch", "to=" + to + " " + layers + (line == null ? "" : " " + line));
                if (counted && pendingReports > 0 && --pendingReports == 0 && frames != null) { frames.stop(); frames = null; }
            }, 1500);
        }

        /** Which page containers are drawn: the play page under another tab is wasted work. */
        private String layers() {
            if (under == null) {
                int id = bar.getResources().getIdentifier("navigation_container_under_bottom_bar", "id", bar.getContext().getPackageName());
                under = id == 0 ? null : root.findViewById(id);
            }
            if (over == null) {
                int id = bar.getResources().getIdentifier("navigation_container_over_bottom_bar", "id", bar.getContext().getPackageName());
                over = id == 0 ? null : root.findViewById(id);
            }
            // over: pages opened above the bar (and above the play page, whose animations only park
            // for other tabs, see covers()).
            return "under=" + describe(under) + " above=" + describe(above) + " over=" + describe(over);
        }

        private static String describe(View v) {
            if (v == null) return "none";
            int shown = 0, total = 0;
            if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { total++; if (((ViewGroup) v).getChildAt(i).getVisibility() == View.VISIBLE) shown++; }
            return (v.getVisibility() == View.VISIBLE ? "visible" : v.getVisibility() == View.GONE ? "gone" : "invisible") + "(" + shown + "/" + total + ")";
        }

        /**
         * Reads the host's tabs (name, title, badges, place), hides 福利 and moves the play tab into
         * the round button, and makes the host's own tab drawing transparent. Returns true when what
         * the bar shows changed.
         */
        private boolean scan(HostDiagnostics.Ui ui, boolean wantOrb) {
            if (itemExtra != null) try { itemExtra.invoke(bar, 0); } catch (Throwable ignored) { }
            scanned.clear();
            TabFace.Tab play = null;
            StringBuilder titles = new StringBuilder();
            for (int i = 0; i < bar.getChildCount(); i++) {
                View item = bar.getChildAt(i);
                int id = item.getId();
                String name = names.get(id);
                TextView title = title(item);
                String text = title == null ? "" : title.getText().toString().trim();
                boolean welfare = ui.tabWelfare && (name != null ? name.startsWith("Commerce") && !"CommerceVip".equals(name) : text.contains("福利"));
                boolean isPlay = wantOrb && ("Play".equals(name) || name == null && text.contains("汽水"));
                titles.append(name == null ? "?" : name).append(':').append(text).append(isPlay ? "*" : "").append(welfare ? "-" : "").append(',');
                boolean hide = welfare || isPlay;
                if (hide && item.getVisibility() != View.GONE) { item.setVisibility(View.GONE); goneByUs.add(item); }
                else if (!hide && goneByUs.remove(item)) item.setVisibility(View.VISIBLE);
                if (item instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) item;
                    for (int c = 0; c < g.getChildCount(); c++) {
                        View child = g.getChildAt(c);
                        if (faceOn) { if (child.getAlpha() != 0f) { child.setAlpha(0f); clearedByUs.add(child); } }
                        else if (clearedByUs.remove(child)) child.setAlpha(1f);
                    }
                }
                if (!faceOn || (item.getVisibility() != View.VISIBLE && !isPlay)) continue;
                TabFace.Tab tab = new TabFace.Tab();
                tab.id = id;
                tab.view = item;
                tab.title = text;
                tab.kind = TabIcons.kind(name, text);
                tab.left = bar.getLeft() + item.getLeft();
                tab.right = bar.getLeft() + item.getRight();
                tab.cx = (tab.left + tab.right) / 2f;
                tab.selected = selected != null && selected == id;
                badges(item, title, tab);
                if (isPlay) play = tab; else scanned.add(tab);
            }
            if (!titles.toString().equals(lastTitles)) {
                lastTitles = titles.toString();
                diagnostics.event("tab_items", "tabs=" + lastTitles + " selected=" + selected + " faces=" + faceOn);
            }
            if (!faceOn) { center(); scanned.clear(); }
            else for (int i = 0; i < bar.getChildCount(); i++) if (bar.getChildAt(i).getTranslationY() != 0f) bar.getChildAt(i).setTranslationY(0f);
            boolean changed = scanned.size() != face.tabs.size() || (play == null) != (orbTab == null) || (play != null && !play.same(orbTab));
            for (int i = 0; i < scanned.size() && !changed; i++) changed = !scanned.get(i).same(face.tabs.get(i));
            orbTab = play;
            if (changed) {
                face.tabs.clear();
                face.tabs.addAll(scanned);
                motion.layout(face.tabs.size(), Math.max(1f, bar.getWidth()), 4f * dp);
                int index = selectedIndex();
                if (!motion.placed() && index >= 0) motion.jump(index);
                recordFaces();
            }
            return changed;
        }

        void recordFaces() {
            if (root == null || face == null) return;
            recordedMid = bar.getTop() + bar.getHeight() / 2f;
            recordedDark = dark;
            face.record(root.getWidth(), root.getHeight(), recordedMid, off(), accent());
            facesVersion++;
        }

        int off() { return dark ? Color.argb(235, 255, 255, 255) : Color.rgb(29, 29, 31); }

        /** Apple Music's tint for the selected tab (light) and its lighter dark-mode variant. */
        int accent() { return dark ? Color.rgb(255, 69, 96) : Color.rgb(250, 45, 72); }

        /** The tab's title: its largest visible text. */
        private static TextView title(View v) {
            TextView[] best = new TextView[1];
            find(v, best, 0);
            return best[0];
        }

        private static void find(View v, TextView[] best, int depth) {
            if ((depth > 0 && v.getVisibility() != View.VISIBLE) || depth > 7) return;
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                if (t.getText() != null && t.getText().toString().trim().length() > 0 && (best[0] == null || t.getTextSize() > best[0].getTextSize())) best[0] = t;
                return;
            }
            if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) find(((ViewGroup) v).getChildAt(i), best, depth + 1);
        }

        /** The host's badges inside a tab: any other visible text is a corner badge, a small plain view a dot. */
        private void badges(View item, TextView title, TabFace.Tab tab) {
            tab.dot = false; tab.badge = null;
            scanBadges(item, title, tab, 0);
        }

        private void scanBadges(View v, TextView title, TabFace.Tab tab, int depth) {
            if (v.getVisibility() != View.VISIBLE || depth > 7) return;
            // Alpha below the item's direct children is the host's own (those we zero ourselves).
            if (depth > 1 && v.getAlpha() < 0.05f) return;
            if (v instanceof TextView) {
                if (v != title && v.getWidth() > 0) {
                    CharSequence text = ((TextView) v).getText();
                    if (text != null && text.toString().trim().length() > 0) tab.badge = text.toString().trim();
                }
                return;
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) scanBadges(g.getChildAt(i), title, tab, depth + 1);
                return;
            }
            if (depth > 0 && !(v instanceof android.widget.ImageView) && v.getBackground() != null
                && v.getWidth() > 0 && v.getHeight() > 0 && v.getWidth() <= 14 * dp && v.getHeight() <= 14 * dp) tab.dot = true;
        }

        /** The host's attribution line, if any, for the surface to draw above the capsule. */
        String attribution() {
            try { Object v = attributionText == null ? null : attributionText.get(bar); return v instanceof String && !((String) v).trim().isEmpty() ? (String) v : null; }
            catch (Throwable e) { return null; }
        }

        /** The host's bottom attribution line would sit inside the glass; its paint goes transparent while skinned. */
        private void hideAttribution(boolean hide) {
            if (attribution == null || hide == attributionHidden) return;
            if (hide) { attributionColor = attribution.getColor(); attribution.setColor(Color.TRANSPARENT); }
            else attribution.setColor(attributionColor);
            attributionHidden = hide;
            bar.invalidate();
        }

        private int nav() {
            android.view.WindowInsets insets = bar.getRootWindowInsets();
            if (insets == null) return 0;
            return Build.VERSION.SDK_INT >= 30 ? insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom : insets.getSystemWindowInsetBottom();
        }

        /** 21dp like iOS, but never on the gesture bar; with three-button navigation a gap above it. */
        private int bottom() {
            int nav = nav();
            return nav > Math.round(32 * dp) ? nav + Math.round(8 * dp) : Math.max(Math.round(SIDE_DP * dp), nav);
        }

        /**
         * iOS 26 geometry: 62dp tall, 21dp from the screen's sides and bottom (or just above the
         * navigation bar when that is taller), the page running underneath. Re-applied whenever the
         * host lays the bar out its own way.
         */
        private void layout(boolean orbOn) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
            if (!saved) {
                saved = true;
                savedHeight = lp.height; savedLeft = lp.leftMargin; savedRight = lp.rightMargin; savedBottom = lp.bottomMargin;
                savedPadding = new int[] {bar.getPaddingLeft(), bar.getPaddingTop(), bar.getPaddingRight(), bar.getPaddingBottom()};
            }
            int height = Math.round(HEIGHT_DP * dp), side = Math.round(SIDE_DP * dp), bottom = bottom();
            int right = orbOn ? side + height + Math.round(GAP_DP * dp) : side;
            if (lp.height != height || lp.leftMargin != side || lp.rightMargin != right || lp.bottomMargin != bottom) {
                lp.height = height; lp.leftMargin = side; lp.rightMargin = right; lp.bottomMargin = bottom;
                bar.setLayoutParams(lp);
            }
            int inner = Math.round(INSET_DP * dp);
            if (bar.getPaddingLeft() != inner || bar.getPaddingTop() != 0 || bar.getPaddingRight() != inner || bar.getPaddingBottom() != 0)
                bar.setPadding(inner, 0, inner, 0);
            if (above == null) {
                int id = bar.getResources().getIdentifier("navigation_container_above_bottom_bar", "id", bar.getContext().getPackageName());
                above = id == 0 ? null : root.findViewById(id);
            }
            if (above != null && above.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams a = (ViewGroup.MarginLayoutParams) above.getLayoutParams();
                if (a.bottomMargin != 0) { aboveMargin = a.bottomMargin; a.bottomMargin = 0; above.setLayoutParams(a); }
            }
        }

        /** Our touch layer over the capsule (faces on): the host's tabs get taps by performClick. */
        private void pad(boolean on) {
            if (!on) {
                if (pad != null && pad.getParent() instanceof ViewGroup) ((ViewGroup) pad.getParent()).removeView(pad);
                return;
            }
            if (pad == null) pad = new Pad(bar.getContext(), this);
            ViewGroup.MarginLayoutParams b = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
            FrameLayout.LayoutParams lp = pad.getParent() == root ? (FrameLayout.LayoutParams) pad.getLayoutParams()
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, b.height, android.view.Gravity.BOTTOM);
            if (pad.getParent() != root) {
                if (pad.getParent() instanceof ViewGroup) ((ViewGroup) pad.getParent()).removeView(pad);
                lp.leftMargin = b.leftMargin; lp.rightMargin = b.rightMargin; lp.bottomMargin = b.bottomMargin;
                root.addView(pad, root.indexOfChild(bar) + 1, lp);
            } else if (lp.height != b.height || lp.leftMargin != b.leftMargin || lp.rightMargin != b.rightMargin || lp.bottomMargin != b.bottomMargin) {
                lp.height = b.height; lp.leftMargin = b.leftMargin; lp.rightMargin = b.rightMargin; lp.bottomMargin = b.bottomMargin;
                pad.setLayoutParams(lp);
            }
            if (pad.getTranslationY() != bar.getTranslationY()) pad.setTranslationY(bar.getTranslationY());
            int vis = bar.getVisibility() == View.VISIBLE && bar.getAlpha() > 0.05f ? View.VISIBLE : View.GONE;
            if (pad.getVisibility() != vis) pad.setVisibility(vis);
        }

        /** The round button: forwards taps to the play tab, drawn by the {@link Surface}. */
        private void orb(boolean on) {
            if (!on) {
                if (orb != null && orb.getParent() instanceof ViewGroup) ((ViewGroup) orb.getParent()).removeView(orb);
                return;
            }
            if (orb == null) orb = new Orb(bar.getContext(), this);
            int size = Math.round(HEIGHT_DP * dp), side = Math.round(SIDE_DP * dp), bottom = bottom();
            if (orb.getParent() != root) {
                if (orb.getParent() instanceof ViewGroup) ((ViewGroup) orb.getParent()).removeView(orb);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, android.view.Gravity.BOTTOM | android.view.Gravity.END);
                lp.rightMargin = side; lp.bottomMargin = bottom;
                root.addView(orb, root.indexOfChild(bar) + 1, lp);
            } else {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) orb.getLayoutParams();
                if (lp.bottomMargin != bottom || lp.rightMargin != side || lp.width != size) { lp.width = lp.height = size; lp.bottomMargin = bottom; lp.rightMargin = side; orb.setLayoutParams(lp); }
            }
            if (orb.getTranslationY() != bar.getTranslationY()) orb.setTranslationY(bar.getTranslationY());
            int vis = bar.getVisibility() == View.VISIBLE && bar.getAlpha() > 0.05f ? View.VISIBLE : View.INVISIBLE;
            if (orb.getVisibility() != vis) orb.setVisibility(vis);
            int hint = PlayerHook.playingHint;
            if (hint != playingHint) { playingHint = hint; surface.invalidate(); }
        }

        private boolean stepOrb(float dt) {
            float[] x = orbX, v = orbV;
            x[0] = orbPress; v[0] = orbPressV; x[1] = orbSel; x[2] = glyphMix;
            boolean moving = PlayerModel.spring(x, v, 0, orbHeld ? 1f : 0f, 300f, 0.5f, dt, 0.002f);
            // The capsule's selection glides and fades; the round button's used to pop on and off, and
            // its glyph (wave / play / pause) to swap in one frame. Both ease now, like the capsule's.
            moving |= PlayerModel.spring(x, v, 1, orbSelected() ? 1f : 0f, 500f, 1f, dt, 0.004f);
            int glyph = orbGlyph();
            if (glyph != glyphNow) {
                if (glyphNow >= 0) { glyphWas = glyphNow; x[2] = 0f; v[2] = 0f; }
                glyphNow = glyph;
            }
            moving |= PlayerModel.spring(x, v, 2, 1f, 420f, 1f, dt, 0.004f);
            orbPress = x[0]; orbPressV = v[0]; orbSel = x[1]; glyphMix = x[2];
            return moving;
        }

        /** The bubble's rectangle this frame: tab slot, lifted and stretched, on the moving capsule. */
        private void bubble(boolean on) {
            if (!on || face.tabs.isEmpty() || motion.shown <= 0.01f) { bubbleRect.setEmpty(); return; }
            float x0 = face.centerAt(motion.value), slot = face.slotAt(motion.value);
            float cx = rest.centerX() + faceDx + (x0 - rest.centerX()) * faceScale, cy = rest.centerY() + faceDy;
            float w = slot * faceScale * motion.stretchX(), h = (rest.height() - 2f * INSET_DP * dp) * faceScale * motion.stretchY();
            bubbleRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }

        /**
         * After every pre-draw listener (the host's included) and right before the frame is recorded:
         * brings the glass up to date. Only page containers below the bar that changed since the glass
         * last looked (or a new bar colour or resting place) make it record again, at most ~60 times
         * a second while the page keeps moving (a skipped frame is caught up on the next one). Our own
         * motion and pages opened over the bar cost no glass work.
         */
        @Override public void onDraw() {
            if (dead || !shown || surface == null || root == null) return;
            long start = System.nanoTime();
            try { look(); }
            catch (Throwable error) {
                diagnostics.errorThrottled("tab_frame_failed", error, 15000);
                // Listeners cannot be removed while the tree dispatches onDraw.
                if (++failures >= 3) main.post(() -> restore("errors"));
            }
            long spent = System.nanoTime() - start;
            lookNanos += spent; looks++;
            if (spent > lookMax) lookMax = spent;
        }

        private void look() {
            collectSources();
            if (pageMoved()) pagePending = true;
            // HWUI drops the display list of a node that is left out of a frame.
            if (!glass.failed() && !glass.node.hasDisplayList()) glassStale = true;
            if (!orbBase.isEmpty() && !orbGlass.failed() && !orbGlass.node.hasDisplayList()) glassStale = true;
            if (face.tabs.size() > 0 && !facesIntact()) recordFaces();
            long tick = SystemClock.uptimeMillis(), since = tick - lastRecord;
            boolean now = glassStale || !glassRest.equals(recordedRest) || !orbBase.equals(recordedOrb)
                || ((pagePending || since >= SAFETY_MS) && since >= 15);
            if (now) {
                lastRecord = tick;
                recordGlass();
                snapshot();
                recordedRest.set(glassRest);
                recordedOrb.set(orbBase);
                glassStale = pagePending = false;
                glassRecords++;
            } else if (pagePending) tick();
            else if (!safetyPosted) { safetyPosted = true; main.postDelayed(safety, SAFETY_MS); }
            recordBubble(now);
        }

        /** Re-records the capsule's and the round button's glass where they rest; they draw the page by reference. */
        private void recordGlass() {
            final Drawable background = root.getBackground();
            final int base = Color.alpha(lastHost) >= 128 ? (lastHost | 0xFF000000) : Color.rgb(16, 16, 18);
            // Regular glass keeps its glyphs legible over bright, busy pages: a half dimming in dark
            // mode (Apple's dark tab bars read about that much darker than what is under them).
            final int tint = dark ? Color.argb(128, 18, 18, 22) : Color.argb(140, 250, 250, 252);
            TabGlass.Content page = c -> {
                c.drawColor(base);
                TabGlass.page(c, root, background, sources);
                c.drawColor(tint);
            };
            float blur = 10f * dp, lensH = 12f * dp, lensA = 16f * dp;
            glass.record(glassRest, page, glass.barEffect(glassRest.width(), glassRest.height(), blur, lensH, lensA));
            if (!orbBase.isEmpty()) orbGlass.record(orbBase, page, orbGlass.barEffect(orbBase.width(), orbBase.height(), blur, lensH, lensA));
            else orbGlass.discard();
        }

        /**
         * The selection bubble. Flat, it only points at the glass and the faces, so it is re-recorded
         * when it moves or changes; lifted, it is a lens layer holding the page and the glass, so it
         * also follows them ({@code glassRecorded}).
         */
        private void recordBubble(boolean glassRecorded) {
            if (bubbleRect.isEmpty() || !faceOn) {
                if (!recordedBubble.isEmpty()) { bubble.discard(); recordedBubble.setEmpty(); }
                return;
            }
            final float press = motion.press, magnify = 1f + 0.2f * press;
            boolean due = !bubbleRect.equals(recordedBubble) || press != recordedPress || dark != recordedBubbleDark
                || facesVersion != recordedFaces || !bubble.node.hasDisplayList() || (press > 0.01f && glassRecorded);
            if (!due) return;
            recordedBubble.set(bubbleRect);
            recordedPress = press;
            recordedBubbleDark = dark;
            recordedFaces = facesVersion;
            final Drawable background = root.getBackground();
            final int base = Color.alpha(lastHost) >= 128 ? (lastHost | 0xFF000000) : Color.rgb(16, 16, 18);
            final int platter = dark ? Color.argb(Math.round(46 * (1f - press)), 255, 255, 255) : Color.argb(Math.round(20 * (1f - press)), 0, 0, 0);
            face.magnify(magnify);
            final boolean overhang = press > 0.01f;
            bubble.record(bubbleRect, c -> {
                // Only a lifted bubble reaches past the capsule and needs the bare page around it.
                if (overhang) { c.drawColor(base); TabGlass.page(c, root, background, sources); }
                glass.draw(c);
                if (platter != 0) c.drawColor(platter);
                if (press > 0.01f) c.drawColor(Color.argb(Math.round(8 * press), 0, 0, 0));
                int saved = c.save();
                face(c);
                for (int i = 0; i < face.tabs.size() && i < face.tinted.size(); i++) c.drawRenderNode(face.tinted.get(i));
                c.restoreToCount(saved);
            }, press > 0.01f ? TabGlass.lens(bubbleRect.width(), bubbleRect.height(), 10f * dp * press, 14f * dp * press, 1f) : null);
        }

        /** The faces' transform this frame: the capsule's press growth and rubber band. */
        void face(Canvas c) {
            float cx = rest.centerX(), cy = rest.centerY();
            c.translate(cx + faceDx, cy + faceDy);
            c.scale(faceScale, faceScale);
            c.translate(-cx, -cy);
        }

        private boolean facesIntact() {
            if (!face.normal.hasDisplayList()) return false;
            for (int i = 0; i < face.tabs.size() && i < face.tinted.size(); i++) if (!face.tinted.get(i).hasDisplayList()) return false;
            return true;
        }

        /** Whether a page container under the glass changed since the glass last recorded it. */
        private boolean pageMoved() {
            if (sources.size() != seenViews.length || root.getBackground() != seenBackground) return true;
            for (int i = 0; i < seenViews.length; i++) {
                View s = sources.get(i);
                // isDirty: something inside was invalidated or moved since its last draw (our own
                // recording draws it too, which clears the flag, so check before recording).
                if (s != seenViews[i] || s.isDirty()) return true;
                int o = i * 10;
                if (seen[o] != s.getLeft() || seen[o + 1] != s.getTop() || seen[o + 2] != s.getScrollX() || seen[o + 3] != s.getScrollY()
                    || seen[o + 4] != s.getTranslationX() || seen[o + 5] != s.getTranslationY() || seen[o + 6] != s.getScaleX()
                    || seen[o + 7] != s.getScaleY() || seen[o + 8] != s.getRotation() || seen[o + 9] != s.getAlpha()) return true;
            }
            return false;
        }

        private void snapshot() {
            int n = sources.size();
            if (seenViews.length != n) { seenViews = new View[n]; seen = new float[n * 10]; }
            for (int i = 0; i < n; i++) {
                View s = sources.get(i);
                seenViews[i] = s;
                int o = i * 10;
                seen[o] = s.getLeft(); seen[o + 1] = s.getTop(); seen[o + 2] = s.getScrollX(); seen[o + 3] = s.getScrollY();
                seen[o + 4] = s.getTranslationX(); seen[o + 5] = s.getTranslationY(); seen[o + 6] = s.getScaleX();
                seen[o + 7] = s.getScaleY(); seen[o + 8] = s.getRotation(); seen[o + 9] = s.getAlpha();
            }
            seenBackground = root.getBackground();
        }

        /** One more frame without repainting anything of ours: invalidates a view that draws nothing. */
        private void tick() {
            View t = pad != null && pad.getParent() == root && pad.getVisibility() == View.VISIBLE ? pad
                : orb != null && orb.getParent() == root && orb.getVisibility() == View.VISIBLE ? orb : surface;
            t.postInvalidateOnAnimation();
        }

        /** Page containers below our surface, the covered play page left out. */
        private void collectSources() {
            sources.clear();
            int index = root.indexOfChild(surface);
            boolean onPlay = selected == null || "Play".equals(names.get(selected)) || !names.containsValue("Play");
            for (int i = 0; i < index; i++) {
                View child = root.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (!onPlay && child.getId() == underId && underId != 0 && above != null && above.getVisibility() == View.VISIBLE) continue;
                sources.add(child);
            }
        }

        /** With the host's own tab drawing: each tab's content centred in the capsule. */
        private void center() {
            float mid = bar.getHeight() / 2f;
            for (int i = 0; i < bar.getChildCount(); i++) {
                View item = bar.getChildAt(i);
                if (item.getVisibility() != View.VISIBLE || item.getHeight() == 0) continue;
                float[] box = {Float.MAX_VALUE, -Float.MAX_VALUE};
                content(item, 0f, box, 0);
                if (box[0] == Float.MAX_VALUE) continue;
                float offset = mid - (item.getTop() + (box[0] + box[1]) / 2f);
                offset = Math.max(-24 * dp, Math.min(24 * dp, offset));
                if (Math.abs(item.getTranslationY() - offset) > 0.5f) item.setTranslationY(offset);
            }
        }

        /** Vertical extent of what a tab visibly shows: text by its glyph lines, other leaves whole. */
        private static void content(View v, float top, float[] box, int depth) {
            if (v.getVisibility() != View.VISIBLE || v.getAlpha() <= 0f || depth > 6) return;
            if (v instanceof TextView) {
                TextView t = (TextView) v;
                android.text.Layout layout = t.getLayout();
                if (layout == null || t.getText().length() == 0) return;
                float y = top + t.getTotalPaddingTop();
                box[0] = Math.min(box[0], y + layout.getLineTop(0));
                box[1] = Math.max(box[1], y + layout.getLineBottom(layout.getLineCount() - 1));
                return;
            }
            if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) { View c = g.getChildAt(i); content(c, top + c.getTop(), box, depth + 1); }
                return;
            }
            if (v.getWidth() > 0 && v.getHeight() > 0 && depth > 0) {
                box[0] = Math.min(box[0], top);
                box[1] = Math.max(box[1], top + v.getHeight());
            }
        }

        // Touch, from the Pad over the capsule (x in the surface's coordinates).
        void touchDown(float x) {
            motion.down(face.indexAt(x));
            lastHaptic = motion.settling();
            surface.postInvalidateOnAnimation();
        }

        void touchMove(float x, float dx) {
            motion.drag(face.indexAt(x), dx);
            int at = motion.settling();
            if (at != lastHaptic) { lastHaptic = at; pad.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
            surface.postInvalidateOnAnimation();
        }

        void touchUp() {
            int index = motion.up();
            surface.postInvalidateOnAnimation();
            if (index < 0 || index >= face.tabs.size()) return;
            pendingIndex = index;
            pendingUntil = AnimationUtils.currentAnimationTimeMillis() + 600;
            // The bubble may be at rest by then: one frame after the window to check the host's answer.
            main.postDelayed(() -> { if (!dead && pendingIndex >= 0) tick(); }, 650);
            View target = face.tabs.get(index).view;
            if (target != null) target.performClick();
        }

        void touchCancel() {
            motion.cancel(selectedIndex());
            surface.postInvalidateOnAnimation();
        }

        void orbPressed(boolean down) {
            orbHeld = down;
            surface.postInvalidateOnAnimation();
        }

        void orbClicked() {
            View target = orbTab == null ? null : orbTab.view;
            if (target != null) target.performClick();
        }

        int orbGlyph() {
            if (orbTab == null) return TabIcons.WAVE;
            boolean on = selected != null && selected == orbTab.id;
            if (!on || playingHint < 0) return TabIcons.WAVE;
            return playingHint == 1 ? TabIcons.PAUSE : TabIcons.PLAY;
        }

        boolean orbSelected() { return orbTab != null && selected != null && selected == orbTab.id; }

        TabFace face() { return face; }

        boolean dark() { return dark; }

        private void report(long now) {
            if (lastCostReport == 0) lastCostReport = now;
            if (now - lastCostReport < 30000 || costFrames == 0) return;
            diagnostics.event("tab_cost", String.format(java.util.Locale.ROOT, "pre_draws=%d avg_us=%.0f max_us=%.0f looks=%d look_avg_us=%.0f look_max_us=%.0f glass_records=%d surface_draws=%d surface_avg_us=%.0f surface_max_us=%.0f",
                costFrames, costNanos / 1000f / costFrames, costMax / 1000f, looks, looks == 0 ? 0f : lookNanos / 1000f / looks, lookMax / 1000f, glassRecords,
                surface.draws, surface.draws == 0 ? 0f : surface.drawNanos / 1000f / surface.draws, surface.drawMax / 1000f));
            lastCostReport = now;
            costNanos = costMax = 0; costFrames = 0;
            lookNanos = lookMax = 0; looks = glassRecords = 0;
            surface.draws = 0; surface.drawMax = 0; surface.drawNanos = 0;
        }

        private void restore(String reason) {
            if (dead) return;
            dead = true;
            playCovered = false;
            main.removeCallbacks(safety);
            ViewTreeObserver o = bar.getViewTreeObserver();
            if (o.isAlive()) { o.removeOnPreDrawListener(this); o.removeOnDrawListener(this); }
            bar.removeOnLayoutChangeListener(this);
            if (frames != null) { frames.stop(); frames = null; }
            pendingReports = 0;
            if (workerThread != null) { workerThread.quitSafely(); workerThread = null; worker = null; }
            for (View v : new View[] {surface, pad, orb})
                if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            glass.discard(); orbGlass.discard(); bubble.discard();
            hideAttribution(false);
            if (bar.getBackground() == clear) bar.setBackground(new ColorDrawable(clear.host));
            if (saved && bar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
                lp.height = savedHeight; lp.leftMargin = savedLeft; lp.rightMargin = savedRight; lp.bottomMargin = savedBottom;
                bar.setLayoutParams(lp);
                bar.setPadding(savedPadding[0], savedPadding[1], savedPadding[2], savedPadding[3]);
            }
            for (int i = 0; i < bar.getChildCount(); i++) bar.getChildAt(i).setTranslationY(0f);
            for (View v : goneByUs.toArray(new View[0])) v.setVisibility(View.VISIBLE);
            for (View v : clearedByUs.toArray(new View[0])) v.setAlpha(1f);
            goneByUs.clear(); clearedByUs.clear();
            if (above != null && aboveMargin >= 0 && above.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams a = (ViewGroup.MarginLayoutParams) above.getLayoutParams();
                a.bottomMargin = aboveMargin;
                above.setLayoutParams(a);
            }
            diagnostics.event("tab_restored", reason + "；底栏布局将在汽水下次重排时完全恢复");
        }

        private static float luminance(int c) {
            return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
        }

        private static void scale(RectF r, float sx, float sy) {
            float cx = r.centerX(), cy = r.centerY(), hw = r.width() * sx / 2f, hh = r.height() * sy / 2f;
            r.set(cx - hw, cy - hh, cx + hw, cy + hh);
        }
    }

    /**
     * Frame timing around a tab change from {@link android.view.FrameMetrics}: whole-window total
     * and GPU time of the frames whose intended vsync falls in the window, against their deadline.
     */
    static final class SwitchFrames implements android.view.Window.OnFrameMetricsAvailableListener {
        private final android.view.Window window;
        private final float[] total = new float[256], gpu = new float[256];
        private volatile long from, to;
        private int n, over;
        private float deadline;

        private SwitchFrames(android.view.Window window) { this.window = window; }

        static SwitchFrames start(View view, Handler handler) {
            android.app.Activity activity = LyricsFrames.activity(view.getContext());
            android.view.Window window = activity == null ? null : activity.getWindow();
            if (window == null) return null;
            SwitchFrames frames = new SwitchFrames(window);
            window.addOnFrameMetricsAvailableListener(frames, handler);
            return frames;
        }

        synchronized void open(long startNanos, long lengthNanos) { from = startNanos; to = startNanos + lengthNanos; n = 0; over = 0; deadline = 0; }

        void stop() { try { window.removeOnFrameMetricsAvailableListener(this); } catch (Throwable ignored) { } }

        @Override public synchronized void onFrameMetricsAvailable(android.view.Window w, android.view.FrameMetrics m, int dropped) {
            long vsync = m.getMetric(android.view.FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
            if (vsync < from || vsync > to || n >= total.length) return;
            float t = m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1e6f;
            float g = Build.VERSION.SDK_INT >= 31 ? m.getMetric(android.view.FrameMetrics.GPU_DURATION) / 1e6f : -1f;
            if (Build.VERSION.SDK_INT >= 31) {
                float d = m.getMetric(android.view.FrameMetrics.DEADLINE) / 1e6f;
                if (d > 0) { deadline = d; if (t > d || g > d) over++; }
            }
            total[n] = t; gpu[n] = g; n++;
        }

        synchronized String report() {
            if (n == 0) return null;
            float[] t = java.util.Arrays.copyOf(total, n), g = java.util.Arrays.copyOf(gpu, n);
            java.util.Arrays.sort(t);
            java.util.Arrays.sort(g);
            String out = String.format(java.util.Locale.ROOT, "frames=%d budget_ms=%.2f over_budget=%d total_p50=%.1f max=%.1f gpu_p50=%.1f gpu_max=%.1f",
                n, deadline, over, t[n / 2], t[n - 1], g[n / 2], g[n - 1]);
            n = 0;
            return out;
        }
    }

    /**
     * Touch layer over the capsule: a press lifts the bubble under the finger, a drag carries it
     * across the tabs, the release selects the tab under it through the host's own tab view.
     */
    static final class Pad extends View {
        private final Skin skin;
        private float lastX;

        Pad(Context context, Skin skin) {
            super(context);
            this.skin = skin;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = getLeft() + e.getX();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = x;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    skin.touchDown(x);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    skin.touchMove(x, x - lastX);
                    lastX = x;
                    return true;
                case MotionEvent.ACTION_UP:
                    skin.touchUp();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    skin.touchCancel();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** Tap target of the round button; draws nothing (its glass and glyph are on the {@link Surface}). */
    static final class Orb extends View {
        private final Skin skin;

        Orb(Context context, Skin skin) {
            super(context);
            this.skin = skin;
            setClickable(true);
            setContentDescription("播放");
            setOnClickListener(v -> skin.orbClicked());
        }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            skin.orbPressed(pressed);
        }
    }

    /**
     * Full-size, untouchable layer under the bar: the faint bottom fade, shadows, the capsule's and
     * the round button's glass, the faces, the selection bubble and the touch glow.
     */
    static final class Surface extends View {
        private final Skin skin;
        private final float dp;
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG), fade = new Paint(), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
            fill = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF scratch = new RectF();
        private final Matrix local = new Matrix();
        private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RadialGradient light = new RadialGradient(0f, 0f, 1f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        private int fadeTop = -1, fadeBottom = -1;
        private boolean fadeDark;
        int draws;
        long drawMax, drawNanos;

        Surface(Context context, Skin skin) {
            super(context);
            this.skin = skin;
            dp = context.getResources().getDisplayMetrics().density;
            shadow.setMaskFilter(new android.graphics.BlurMaskFilter(12 * dp, android.graphics.BlurMaskFilter.Blur.NORMAL));
            glow.setShader(light);
            if (Build.VERSION.SDK_INT >= 29) glow.setBlendMode(android.graphics.BlendMode.PLUS);
            note.setTextSize(10f * context.getResources().getDisplayMetrics().scaledDensity);
            note.setTextAlign(Paint.Align.CENTER);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Android 15+ votes a frame rate per invalidated view by its size and update pattern, which
            // can settle an animation on 60 Hz; this view repaints only while the bar itself moves.
            if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
        }

        @Override public boolean onTouchEvent(MotionEvent event) { return false; }

        @Override protected void onDraw(Canvas canvas) {
            long start = System.nanoTime();
            try { paint(canvas); }
            finally {
                long spent = System.nanoTime() - start;
                draws++;
                drawNanos += spent;
                if (spent > drawMax) drawMax = spent;
            }
        }

        private void paint(Canvas canvas) {
            RectF capsule = skin.capsule;
            if (capsule.isEmpty()) return;
            // Everything below is in the screen root's coordinates; this view is only the bottom band.
            canvas.translate(0, -getTop());
            boolean dark = skin.dark();
            TabMotion m = skin.motion;
            // Bottom fade under the glass: keeps the page from clashing with the gesture bar.
            int top = Math.round(skin.rest.top - 24 * dp), bottom = getTop() + getHeight();
            if (top != fadeTop || bottom != fadeBottom || dark != fadeDark) {
                fadeTop = top; fadeBottom = bottom; fadeDark = dark;
                int end = dark ? Color.argb(84, 0, 0, 0) : Color.argb(110, 255, 255, 255);
                fade.setShader(new LinearGradient(0, top, 0, bottom, Color.TRANSPARENT, end, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, top, getWidth(), bottom, fade);
            if (skin.note != null) {
                note.setColor(dark ? Color.argb(150, 255, 255, 255) : Color.argb(150, 0, 0, 0));
                canvas.drawText(skin.note, getWidth() / 2f, skin.rest.top + skin.faceDy - 9 * dp, note);
            }
            boolean hardware = canvas.isHardwareAccelerated() && !LyricsBackdrop.capture(canvas, this);
            // Soft shadows (Kyant Shadow.Default: 24dp, offset radius/6, 10% black; stronger on dark pages).
            shadow.setColor(Color.argb(dark ? 46 : 26, 0, 0, 0));
            shadow(canvas, capsule, 4 * dp);
            if (!skin.orbRect.isEmpty()) shadow(canvas, skin.orbRect, 4 * dp);

            // The capsule's glass and its rim.
            if (hardware && !skin.glass.failed()) skin.glass.draw(canvas);
            else plain(canvas, capsule, dark);
            glowAt(canvas, capsule, m.glow, skin.bubbleRect.isEmpty() ? capsule.centerX() : skin.bubbleRect.centerX());
            TabGlass.rim(canvas, capsule, 0.75f * dp, dark ? 0.5f : 0.65f, rim);

            // Faces, then the selection bubble over the selected one.
            TabFace face = skin.face();
            if (face != null) {
                int saved = canvas.save();
                skin.face(canvas);
                if (hardware && face.normal.hasDisplayList()) canvas.drawRenderNode(face.normal);
                else for (TabFace.Tab t : face.tabs) face.tab(canvas, t, t.cx, skin.rest.centerY(), t.selected ? skin.accent() : skin.off());
                canvas.restoreToCount(saved);
            }
            RectF b = skin.bubbleRect;
            if (!b.isEmpty()) {
                float a = Math.max(0f, Math.min(1f, m.shown));
                int layer = a < 0.99f ? canvas.saveLayerAlpha(b.left - 30 * dp, b.top - 30 * dp, b.right + 30 * dp, b.bottom + 30 * dp, Math.round(255 * a)) : -1;
                if (m.press > 0.01f) {
                    shadow.setColor(Color.argb(Math.round(40 * m.press), 0, 0, 0));
                    shadow(canvas, b, 4 * dp);
                }
                if (hardware && !skin.bubble.failed()) skin.bubble.draw(canvas);
                else {
                    fill.setColor(dark ? Color.argb(46, 255, 255, 255) : Color.argb(20, 0, 0, 0));
                    canvas.drawRoundRect(b, b.height() / 2f, b.height() / 2f, fill);
                }
                if (m.press > 0.01f) {
                    // Inner shade along the top edge and Kyant's rim, both growing with the lift.
                    int saved = canvas.save();
                    canvas.clipPath(TabGlass.capsule(b));
                    inner.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.45f, Color.argb(Math.round(34 * m.press), 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                    canvas.drawRect(b, inner);
                    canvas.restoreToCount(saved);
                    TabGlass.rim(canvas, b, 0.75f * dp, 0.9f * m.press, rim);
                }
                if (layer >= 0) canvas.restoreToCount(layer);
            }

            // The round button.
            RectF o = skin.orbRect;
            if (!o.isEmpty() && face != null) {
                if (hardware && !skin.orbGlass.failed()) skin.orbGlass.draw(canvas);
                else plain(canvas, o, dark);
                float sel = Math.max(0f, Math.min(1f, skin.orbSel));
                if (sel > 0.004f) {
                    fill.setColor(dark ? Color.argb(Math.round(46 * sel), 255, 255, 255) : Color.argb(Math.round(20 * sel), 0, 0, 0));
                    canvas.drawCircle(o.centerX(), o.centerY(), o.height() / 2f - INSET_DP * dp, fill);
                }
                glowAt(canvas, o, skin.orbPress, o.centerX());
                TabGlass.rim(canvas, o, 0.75f * dp, dark ? 0.5f : 0.65f, rim);
                float size = 28 * dp * (o.height() / Math.max(1f, skin.orbRest.height()));
                int color = mix(skin.off(), skin.accent(), sel);
                int now = skin.glyphNow >= 0 ? skin.glyphNow : skin.orbGlyph();
                float swap = Math.max(0f, Math.min(1f, skin.glyphMix));
                // SF Symbols' "replace": the old glyph shrinks away as the new one grows in.
                if (skin.glyphWas >= 0 && swap < 0.996f)
                    face.glyph(canvas, skin.glyphWas, o.centerX(), o.centerY(), size * (1f - 0.3f * swap), fade(color, 1f - swap));
                face.glyph(canvas, now, o.centerX(), o.centerY(), size * (0.7f + 0.3f * swap), fade(color, swap));
            }
        }

        private static int mix(int a, int b, float t) {
            return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t), Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t), Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }

        private static int fade(int color, float alpha) {
            return (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * Math.max(0f, Math.min(1f, alpha))) << 24);
        }

        /** Software screen copies (the host's RealtimeBlurView) and old systems: a translucent capsule. */
        private void plain(Canvas canvas, RectF r, boolean dark) {
            fill.setColor(dark ? Color.argb(150, 28, 28, 32) : Color.argb(170, 248, 248, 250));
            canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill);
        }

        private void shadow(Canvas canvas, RectF r, float dy) {
            scratch.set(r);
            scratch.offset(0, dy);
            canvas.drawRoundRect(scratch, scratch.height() / 2f, scratch.height() / 2f, shadow);
        }

        /** Kyant's interactive highlight: a faint lift over the glass and a glow around the touch. */
        private void glowAt(Canvas canvas, RectF r, float strength, float x) {
            if (strength <= 0.01f) return;
            int saved = canvas.save();
            canvas.clipPath(TabGlass.capsule(r));
            fill.setColor(Color.argb(Math.round(20 * Math.min(1f, strength)), 255, 255, 255));
            if (Build.VERSION.SDK_INT >= 29) fill.setBlendMode(android.graphics.BlendMode.PLUS);
            canvas.drawRect(r, fill);
            if (Build.VERSION.SDK_INT >= 29) fill.setBlendMode(null);
            float radius = r.height() * 1.5f;
            local.setScale(radius, radius);
            local.postTranslate(x, r.centerY());
            light.setLocalMatrix(local);
            glow.setAlpha(Math.round(38 * Math.min(1f, strength)));
            canvas.drawCircle(x, r.centerY(), radius, glow);
            canvas.restoreToCount(saved);
        }
    }
}

package io.github.qishui.automix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

/**
 * Apple Music style lyrics. Every line is laid out once (on a worker thread) and recorded once into
 * its own RenderNode; per frame only node properties change (position, scale, alpha, blur level),
 * and only the line being sung is re-recorded for the word sweep. Needs API 29 (RenderNode).
 */
final class LyricsView extends View implements Choreographer.FrameCallback {
    interface Host {
        void seek(LyricsModel.Line line);
        void failed(String stage, Throwable error);
        void stats(String detail);
    }

    static final float INACTIVE_ALPHA = 0.30f, UNSUNG_ALPHA = 0.40f, MANUAL_ALPHA = 0.46f, INACTIVE_SCALE = 0.93f;

    /** Text measured for one width. Immutable once built; safe to hand across threads. */
    static final class Laid {
        final LyricsModel model;
        final int width;
        final Block[] blocks;
        Laid(LyricsModel model, int width, Block[] blocks) { this.model = model; this.width = width; this.blocks = blocks; }
    }

    static final class Block {
        StaticLayout main, trans;
        float mainH, h, transTop;
        float[] cx;          // x of every char offset in main, line-relative
        int[] row;           // visual line of every char
        RenderNode node;
        int blur = -1;
        boolean recorded;
    }

    private final Host host;
    private final LyricsClock clock;
    private final float dp;
    private final int pad, padLeft, gap, interludeH;
    private final TextPaint sung = new TextPaint(Paint.ANTI_ALIAS_FLAG), unsung = new TextPaint(Paint.ANTI_ALIAS_FLAG),
        sweep = new TextPaint(Paint.ANTI_ALIAS_FLAG), glow = new TextPaint(Paint.ANTI_ALIAS_FLAG), transPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), press = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient edge = new LinearGradient(0, 0, 1, 0, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);
    private final Matrix edgeMatrix = new Matrix();
    private final RenderNode live = new RenderNode("qishui-lyric-live");
    /** Blur in half steps of 1.1dp (0..3.3dp, as before), so a line can ease between levels. */
    private final RenderEffect[] blurs = new RenderEffect[7];

    private Laid laid;
    private int active = -1;
    private float[] pos = new float[0], vel = new float[0], target = new float[0], act = new float[0], actVel = new float[0], expand = new float[0], top = new float[0],
        blur = new float[0], blurVel = new float[0], expandVel = new float[0];
    /** 0 following the song, 1 scrolled by hand: dims less and drops the blur, eased both ways. */
    private final float[] mix = {0f}, mixVel = {0f};
    private long[] release = new long[0];
    private boolean frameQueued, attached, failed;
    private long lastFrame;

    // Manual scrolling.
    private final OverScroller scroller;
    private final int slop, minFling, maxFling;
    private VelocityTracker velocity;
    private boolean dragging, manual;
    private float manualScroll, downY, lastY;
    private long resumeAt;
    private int pressed = -1;

    // Frame statistics over continuous runs only (idle and parked gaps are not counted): our own
    // work per frame, and the intervals between our frames bucketed around 120/90/60 Hz periods.
    private long statFrames, statGaps, statGapNanos, statWorkNanos, statMaxWork, statSince, lastFrameSeen;
    private final long[] statBuckets = new long[5];
    // Parked: not on screen (offscreen pager card, covered window, alpha 0); woken by resume().
    private boolean parked;
    private volatile boolean drawing;
    private final Rect visible = new Rect();
    /** Uptime of the last frame any lyric view drew; play page backdrops underneath yield to it. */
    static volatile long lastFrameAt;

    LyricsView(Context context, Host host, LyricsClock clock) {
        super(context);
        this.host = host;
        this.clock = clock;
        dp = context.getResources().getDisplayMetrics().density;
        pad = Math.round(24 * dp);
        padLeft = Math.round(26 * dp);
        gap = Math.round(30 * dp);
        interludeH = Math.round(34 * dp);
        Typeface bold = Typeface.create(Typeface.DEFAULT, 700, false);
        for (TextPaint paint : new TextPaint[] {sung, unsung, sweep, glow}) { paint.setTypeface(bold); paint.setColor(Color.WHITE); }
        unsung.setAlpha(Math.round(UNSUNG_ALPHA * 255));
        sweep.setShader(edge);
        transPaint.setColor(Color.WHITE);
        dot.setColor(Color.WHITE);
        press.setColor(Color.argb(22, 255, 255, 255));
        live.setClipToBounds(false);
        ViewConfiguration config = ViewConfiguration.get(context);
        slop = config.getScaledTouchSlop();
        minFling = config.getScaledMinimumFlingVelocity();
        maxFling = config.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(context);
        setWillNotDraw(false);
        setClickable(true);
        if (Build.VERSION.SDK_INT >= 31) for (int i = 1; i < blurs.length; i++) blurs[i] = RenderEffect.createBlurEffect(i * 0.55f * dp, i * 0.55f * dp, Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
    }

    static float sp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.getResources().getDisplayMetrics());
    }

    /** Builds the text layout off the main thread. {@code width} is the view width in px. */
    static Laid layout(Context context, LyricsModel model, int width) {
        float dp = context.getResources().getDisplayMetrics().density;
        int textWidth = Math.max(1, width - Math.round(26 * dp) - Math.round(40 * dp));
        Typeface bold = Typeface.create(Typeface.DEFAULT, 700, false);
        TextPaint main = new TextPaint(Paint.ANTI_ALIAS_FLAG), credit = new TextPaint(Paint.ANTI_ALIAS_FLAG), trans = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        main.setTypeface(bold); main.setColor(Color.WHITE); main.setTextSize(sp(context, 27));
        credit.setColor(Color.WHITE); credit.setTextSize(sp(context, 13));
        trans.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false)); trans.setColor(Color.argb(160, 255, 255, 255)); trans.setTextSize(sp(context, 16));
        Block[] blocks = new Block[model.lines.length];
        for (int i = 0; i < blocks.length; i++) {
            LyricsModel.Line line = model.lines[i];
            Block block = new Block();
            blocks[i] = block;
            if (line.kind == LyricsModel.INTERLUDE) continue;
            TextPaint paint = line.kind == LyricsModel.CREDIT ? credit : main;
            block.main = StaticLayout.Builder.obtain(line.text, 0, line.text.length(), paint, textWidth)
                .setLineSpacing(0, line.kind == LyricsModel.CREDIT ? 1.1f : 1.08f).setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build();
            block.mainH = block.main.getHeight();
            block.h = block.mainH;
            if (line.translation != null) {
                block.trans = StaticLayout.Builder.obtain(line.translation, 0, line.translation.length(), trans, textWidth)
                    .setLineSpacing(0, 1.1f).setIncludePad(false).build();
                block.transTop = block.mainH + 6 * dp;
                block.h = block.transTop + block.trans.getHeight();
            }
            if (line.units != null) {
                int n = line.text.length();
                block.cx = new float[n + 1];
                block.row = new int[n + 1];
                for (int r = 0; r < block.main.getLineCount(); r++) {
                    int s = block.main.getLineStart(r), e = block.main.getLineEnd(r);
                    float left = block.main.getLineLeft(r);
                    for (int k = s; k <= e && k <= n; k++) {
                        if (k == e && r + 1 < block.main.getLineCount()) break;
                        block.cx[k] = left + main.getRunAdvance(line.text, s, e, s, e, false, k);
                        block.row[k] = r;
                    }
                }
            }
        }
        return new Laid(model, width, blocks);
    }

    void show(Laid next) {
        Laid previous = laid;
        laid = next;
        int n = next.blocks.length;
        boolean fresh = previous == null || previous.model != next.model || pos.length != n;
        if (!fresh) {
            for (int i = 0; i < n; i++) next.blocks[i].node = previous.blocks[i].node;
        } else {
            pos = new float[n]; vel = new float[n]; target = new float[n]; act = new float[n]; actVel = new float[n];
            expand = new float[n]; top = new float[n + 1]; release = new long[n]; blur = new float[n]; blurVel = new float[n]; expandVel = new float[n];
            active = -2; manual = false; scroller.abortAnimation();
        }
        for (Block block : next.blocks) { if (block.node == null) block.node = new RenderNode("qishui-lyric"); block.node.setClipToBounds(false); block.recorded = false; block.blur = -1; }
        if (fresh) snapToClock();
        wake();
    }

    Laid laid() { return laid; }

    /** Places every line at its resting position for the current clock, without animation. */
    private void snapToClock() {
        Laid current = laid;
        if (current == null) return;
        long t = clock.at(System.nanoTime());
        active = current.model.activeAt(t);
        for (int i = 0; i < expand.length; i++) {
            expand[i] = current.model.lines[i].kind == LyricsModel.INTERLUDE ? (i == active ? 1 : 0) : 1;
            act[i] = i == active ? 1 : 0; actVel[i] = 0;
            blur[i] = blurGoal(i); blurVel[i] = 0;
        }
        tops(current);
        float rest = rest(current);
        for (int i = 0; i < pos.length; i++) { pos[i] = rest; target[i] = rest; vel[i] = 0; release[i] = 0; }
    }

    private void tops(Laid current) {
        float y = 0;
        for (int i = 0; i < current.blocks.length; i++) {
            top[i] = y;
            LyricsModel.Line line = current.model.lines[i];
            float h = line.kind == LyricsModel.INTERLUDE ? interludeH : current.blocks[i].h;
            y += (h + gap) * expand[i];
        }
        top[current.blocks.length] = y;
    }

    private float rest(Laid current) {
        int index = active >= 0 ? active : current.model.firstTimed();
        return index < 0 ? 0 : top[index];
    }

    private float anchor() { return getHeight() * 0.28f; }

    void wake() {
        if (!attached || frameQueued || failed) return;
        frameQueued = true;
        parked = false;
        lastFrame = 0;
        lastFrameSeen = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Restarts a parked view once it is back on screen; cheap enough for every pre-draw. */
    void resume() { if (parked && onScreen()) wake(); }

    /** Whether frames are being produced right now (read by the window frame probe). */
    boolean drawing() { return drawing; }

    private boolean onScreen() {
        return attached && getWindowVisibility() == VISIBLE && isShown() && getAlpha() > 0f && getGlobalVisibleRect(visible) && !TabHook.covers(this);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) wake();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        statSince = SystemClock.elapsedRealtime();
        wake();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        drawing = false;
        if (frameQueued) Choreographer.getInstance().removeFrameCallback(this);
        frameQueued = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        wake();
    }

    @Override public void doFrame(long now) {
        frameQueued = false;
        if (failed || !attached) return;
        if (!onScreen()) { parked = true; drawing = false; lastFrameSeen = 0; return; }
        long begin = System.nanoTime();
        boolean more = false;
        try {
            more = step(now);
            invalidate();
            if (more) { frameQueued = true; Choreographer.getInstance().postFrameCallback(this); }
        } catch (Throwable error) { fail("frame", error); }
        drawing = more;
        if (more) lastFrameAt = SystemClock.uptimeMillis();
        account(now, System.nanoTime() - begin);
        if (!more) lastFrameSeen = 0;
    }

    /** Advances springs and the clock; returns whether another frame is needed. */
    private boolean step(long now) {
        Laid current = laid;
        if (current == null || getHeight() == 0) return false;
        float dt = lastFrame == 0 ? 1f / 120f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        long t = clock.at(now);
        int next = current.model.activeAt(t);
        if (next != active) {
            int previous = active;
            active = next;
            // Apple Music cascade: the sung line moves first, each following line a beat later.
            for (int i = 0; i < release.length; i++)
                release[i] = previous < -1 || next < previous || i <= next ? now : now + Math.min(7, i - next) * 42_000_000L;
        }
        boolean moving = false;
        for (int i = 0; i < expand.length; i++) {
            if (current.model.lines[i].kind != LyricsModel.INTERLUDE) continue;
            // An interlude row opens and closes as a critically damped spring: the exponential step
            // it replaces started at full speed, jerking every line below it into motion at once.
            moving |= spring(i, expand, expandVel, i == active ? 1f : 0f, 200f, 1f, dt, 0.002f);
        }
        tops(current);
        float rest = rest(current);
        if (manual && !dragging && scroller.computeScrollOffset()) { manualScroll = scroller.getCurrY(); moving = true; }
        if (manual && !dragging && scroller.isFinished() && resumeAt != 0 && SystemClock.uptimeMillis() >= resumeAt) {
            manual = false; resumeAt = 0;
            for (int i = 0; i < pos.length; i++) { pos[i] = manualScroll; vel[i] = 0; release[i] = now + Math.max(0, i - Math.max(0, active)) * 30_000_000L; }
        }
        // Entering and leaving the hand-scrolled view eases instead of snapping every line's
        // brightness (0.30 ↔ 0.46) and blur (up to 3.3dp ↔ none) in one frame.
        moving |= spring(0, mix, mixVel, manual ? 1f : 0f, 260f, 1f, dt, 0.004f);
        for (int i = 0; i < pos.length; i++) {
            // Blur follows each line's distance from the sung one as a spring (in half steps), so a new
            // active line sharpens while it grows and the others soften one step at a time.
            moving |= spring(i, blur, blurVel, blurGoal(i), 190f, 1f, dt, 0.02f);
            if (manual) { pos[i] = manualScroll; vel[i] = 0; target[i] = rest; continue; }
            if (now >= release[i]) target[i] = rest;
            moving |= spring(i, pos, vel, target[i], 135f, 0.86f, dt, 0.4f);
            float goal = i == active ? 1f : 0f;
            moving |= spring(i, act, actVel, goal, 190f, 1f, dt, 0.002f);
        }
        if (manual) moving = true;
        return moving || clock.running() || (manual && resumeAt != 0);
    }

    /** Blur level (half steps, 0..6) a line is heading to: by distance from the sung line, none while scrolled by hand. */
    private float blurGoal(int i) {
        if (manual || i == active) return 0f;
        return 2f * Math.min(3, Math.abs(i - Math.max(0, active)));
    }

    private static boolean spring(int i, float[] x, float[] v, float goal, float k, float zeta, float dt, float rest) {
        float c = 2f * zeta * (float) Math.sqrt(k);
        float remaining = dt;
        while (remaining > 0) {
            float h = Math.min(remaining, 1f / 240f);
            v[i] += (-k * (x[i] - goal) - c * v[i]) * h;
            x[i] += v[i] * h;
            remaining -= h;
        }
        if (Math.abs(x[i] - goal) < rest && Math.abs(v[i]) < rest * 4) { x[i] = goal; v[i] = 0; return false; }
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (failed || LyricsBackdrop.capture(canvas, this)) return;
        long begin = System.nanoTime();
        try { draw(canvas, laid); }
        catch (Throwable error) { fail("draw", error); }
        statWorkNanos += System.nanoTime() - begin;
    }

    private void draw(Canvas canvas, Laid current) {
        if (current == null || pos.length != current.blocks.length) return;
        int w = getWidth(), h = getHeight();
        float anchor = anchor();
        long t = clock.at(lastFrame == 0 ? System.nanoTime() : lastFrame);
        boolean hw = canvas.isHardwareAccelerated();
        for (int i = 0; i < current.blocks.length; i++) {
            LyricsModel.Line line = current.model.lines[i];
            Block block = current.blocks[i];
            float y = anchor + top[i] - pos[i];
            float bh = line.kind == LyricsModel.INTERLUDE ? interludeH * expand[i] : block.h;
            if (y + bh < -pad * 2 || y > h + pad * 2) continue;
            if (line.kind == LyricsModel.INTERLUDE) { if (expand[i] > 0.01f) dots(canvas, line, y, t, expand[i]); continue; }
            if (i == pressed) canvas.drawRoundRect(padLeft - 12 * dp, y - 8 * dp, w - 16 * dp, y + block.h + 8 * dp, 14 * dp, 14 * dp, press);
            float a = act[i];
            float scale = INACTIVE_SCALE + (1f - INACTIVE_SCALE) * a;
            float dim = INACTIVE_ALPHA + (MANUAL_ALPHA - INACTIVE_ALPHA) * mix[0];
            float alpha = dim + (1f - dim) * a;
            if (line.kind == LyricsModel.CREDIT) { alpha *= 0.8f; scale = 1f; }
            if (!hw) { soft(canvas, block, y, alpha, scale); continue; }
            boolean sweeping = line.units != null && i == active;
            RenderNode node = sweeping ? live : block.node;
            if (sweeping) {
                record(live, current, i, t, w);
                node.setAlpha(1f);
            } else {
                // HWUI drops a node's display list once it is left out of a frame (scrolled away, or
                // replaced by the live node while sung), so re-record on demand.
                if (!block.recorded || !node.hasDisplayList()) recordStatic(block, w);
                node.setAlpha(alpha);
                int level = Math.max(0, Math.min(blurs.length - 1, Math.round(blur[i])));
                if (Build.VERSION.SDK_INT >= 31 && level != block.blur) { node.setRenderEffect(blurs[level]); block.blur = level; }
            }
            node.setPivotX(padLeft);
            node.setPivotY(pad + block.mainH / 2f);
            node.setScaleX(scale); node.setScaleY(scale);
            node.setTranslationY(y - pad);
            canvas.drawRenderNode(node);
        }
    }

    private void recordStatic(Block block, int w) {
        RenderNode node = block.node;
        node.setPosition(0, 0, w, Math.round(block.h + pad * 2));
        RecordingCanvas c = node.beginRecording();
        try {
            c.translate(padLeft, pad);
            block.main.draw(c);
            if (block.trans != null) { c.translate(0, block.transTop); block.trans.draw(c); }
        } finally { node.endRecording(); }
        block.recorded = true;
    }

    /** The sung line: per unit dim text, the sweep edge, lift and glow on long notes. */
    private void record(RenderNode node, Laid current, int index, long t, int w) {
        LyricsModel.Line line = current.model.lines[index];
        Block block = current.blocks[index];
        node.setPosition(0, 0, w, Math.round(block.h + pad * 2));
        if (Build.VERSION.SDK_INT >= 31) node.setRenderEffect(null);
        float a = act[index];
        unsung.setAlpha(Math.round(255 * (INACTIVE_ALPHA + (UNSUNG_ALPHA - INACTIVE_ALPHA) * a)));
        float size = block.main.getPaint().getTextSize();
        sung.setTextSize(size); unsung.setTextSize(size); sweep.setTextSize(size); glow.setTextSize(size);
        float lift = 2.2f * dp, feather = 18 * dp;
        String text = line.text;
        StaticLayout layout = block.main;
        RecordingCanvas c = node.beginRecording();
        try {
            c.translate(padLeft, pad);
            LyricsModel.Unit[] units = line.units;
            int u = 0;
            while (u < units.length) {
                LyricsModel.Unit unit = units[u];
                float p = unitProgress(unit, t);
                // Fully sung or untouched neighbours on one visual line are drawn as one run.
                if ((p <= 0f || p >= 1f) && !emphasised(unit, t)) {
                    boolean done = p >= 1f;
                    int row = block.row[unit.start], v = u + 1;
                    while (v < units.length) {
                        LyricsModel.Unit other = units[v];
                        float q = unitProgress(other, t);
                        if (block.row[other.start] != row || block.row[other.end - 1] != row || (q >= 1f) != done
                            || (q > 0f && q < 1f) || emphasised(other, t)) break;
                        v++;
                    }
                    run(c, layout, block, text, unit.start, units[v - 1].end, done ? sung : unsung, done ? -lift : 0);
                    u = v;
                    continue;
                }
                float rise = -lift * ease(p);
                float g = emphasis(unit, t);
                if (g > 0.01f) {
                    float cxm = (block.cx[unit.start] + block.cx[unit.end]) / 2f;
                    float base = layout.getLineBaseline(block.row[unit.start]);
                    c.save();
                    c.scale(1f + 0.06f * g, 1f + 0.06f * g, cxm, base - size * 0.35f);
                    glow.setAlpha(Math.round(70 * g));
                    glow.setShadowLayer(9 * dp * g + 1, 0, 0, Color.argb(Math.round(190 * g), 255, 255, 255));
                    run(c, layout, block, text, unit.start, unit.end, glow, rise);
                }
                if (p < 1f) run(c, layout, block, text, unit.start, unit.end, unsung, rise);
                if (p >= 1f) run(c, layout, block, text, unit.start, unit.end, sung, rise);
                else if (p > 0f) {
                    float left = block.cx[unit.start], right = block.cx[unit.end];
                    if (block.row[unit.end - 1] != block.row[unit.start]) right = layout.getLineRight(block.row[unit.start]);
                    float x = sweepX(unit, block, t, left, right);
                    float edgeX = left + (x - left) / Math.max(1f, right - left) * (right - left + feather) - feather / 2f;
                    edgeMatrix.setScale(feather, 1f);
                    edgeMatrix.postTranslate(edgeX - feather / 2f, 0);
                    edge.setLocalMatrix(edgeMatrix);
                    run(c, layout, block, text, unit.start, unit.end, sweep, rise);
                }
                if (g > 0.01f) c.restore();
                u++;
            }
            if (block.trans != null) {
                c.translate(0, block.transTop);
                block.trans.getPaint().setAlpha(Math.round(160 * (0.55f + 0.45f * a)));
                block.trans.draw(c);
                block.trans.getPaint().setAlpha(160);
            }
        } finally { node.endRecording(); }
    }

    /** Draws [start, end) split at visual line breaks, with shaping context of each line. */
    private void run(Canvas c, StaticLayout layout, Block block, String text, int start, int end, Paint paint, float dy) {
        int s = start;
        while (s < end) {
            int row = block.row[s];
            int lineEnd = layout.getLineEnd(row), lineStart = layout.getLineStart(row);
            int e = Math.min(end, lineEnd);
            if (e <= s) break;
            c.drawTextRun(text, s, e, lineStart, lineEnd, block.cx[s], layout.getLineBaseline(row) + dy, false, paint);
            s = e;
        }
    }

    private static float unitProgress(LyricsModel.Unit unit, long t) {
        if (t >= unit.t1) return 1f;
        if (t <= unit.t0) return unit.t1 <= unit.t0 && t >= unit.t0 ? 1f : 0f;
        return LyricsModel.progress(unit.t0, unit.t1, t);
    }

    /** Sweep x following the original per-character timing inside the unit. */
    private static float sweepX(LyricsModel.Unit unit, Block block, long t, float left, float right) {
        int n = unit.c0.length;
        for (int k = 0; k < n; k++) {
            if (t < unit.c0[k]) return block.cx[unit.start + k];
            if (t < unit.c1[k]) {
                float x0 = block.cx[unit.start + k], x1 = k + 1 < n ? block.cx[unit.start + k + 1] : right;
                return x0 + (x1 - x0) * LyricsModel.progress(unit.c0[k], unit.c1[k], t);
            }
        }
        return right;
    }

    private static boolean emphasised(LyricsModel.Unit unit, long t) { return emphasis(unit, t) > 0.01f; }

    /** Long held notes glow and swell slightly while sung, then settle. */
    private static float emphasis(LyricsModel.Unit unit, long t) {
        long d = unit.duration();
        if (d < 1000 || t <= unit.t0 || t > unit.t1 + 450) return 0f;
        if (t <= unit.t1) return (float) Math.sin(Math.min(1f, (t - unit.t0) / (float) Math.min(d, 900)) * Math.PI / 2);
        return 1f - (t - unit.t1) / 450f;
    }

    private static float ease(float p) { float q = 1f - Math.max(0f, Math.min(1f, p)); return 1f - q * q * q; }

    private void dots(Canvas canvas, LyricsModel.Line line, float y, long t, float e) {
        long d = Math.max(1, line.end - line.start);
        float breathe = 1f + 0.07f * (float) Math.sin((t % 2400) / 2400f * Math.PI * 2);
        // The dots shrink and fade over the last half second on an S-curve instead of a straight ramp.
        float tail = LyricsModel.progress(line.end - 500, line.end, t);
        tail = tail * tail * (3f - 2f * tail);
        float scale = breathe * (1f - tail) * e;
        float cy = y + interludeH / 2f, r = 4.6f * dp;
        for (int k = 0; k < 3; k++) {
            float p = LyricsModel.progress(line.start + d * k / 3, line.start + d * (k + 1) / 3, t);
            dot.setAlpha(Math.round(255 * (0.32f + 0.68f * p) * (1f - tail) * e));
            canvas.drawCircle(padLeft + r + k * 15 * dp * scale, cy, r * scale, dot);
        }
    }

    private void soft(Canvas canvas, Block block, float y, float alpha, float scale) {
        int save = canvas.saveLayerAlpha(0, y - pad, getWidth(), y + block.h + pad, Math.round(alpha * 255));
        canvas.translate(padLeft, y);
        canvas.scale(scale, scale, 0, block.mainH / 2f);
        block.main.draw(canvas);
        if (block.trans != null) { canvas.translate(0, block.transTop); block.trans.draw(canvas); }
        canvas.restoreToCount(save);
    }

    // ---- touch ----

    @Override public boolean onTouchEvent(MotionEvent event) {
        try { return touch(event); }
        catch (Throwable error) { fail("touch", error); return false; }
    }

    private boolean touch(MotionEvent event) {
        Laid current = laid;
        if (current == null) return false;
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = lastY = y; dragging = false;
                if (!scroller.isFinished()) { scroller.abortAnimation(); dragging = true; }
                pressed = dragging ? -1 : hit(y);
                resumeAt = 0;
                wake();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(y - downY) > slop) {
                    dragging = true; pressed = -1;
                    if (!manual) { manual = true; manualScroll = pos.length > 0 ? pos[Math.max(0, Math.min(pos.length - 1, active))] : 0; }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    lastY = y;
                }
                if (dragging) {
                    float max = Math.max(0, top[top.length - 1] - gap), dy = lastY - y;
                    if (manualScroll < 0 || manualScroll > max) dy *= 0.4f;
                    manualScroll += dy;
                    lastY = y;
                    wake();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    velocity.computeCurrentVelocity(1000, maxFling);
                    float vy = velocity.getYVelocity();
                    int max = Math.round(Math.max(0, top[top.length - 1] - gap));
                    if (Math.abs(vy) > minFling) scroller.fling(0, Math.round(manualScroll), 0, Math.round(-vy), 0, 0, 0, max, 0, Math.round(80 * dp));
                    else scroller.springBack(0, Math.round(manualScroll), 0, 0, 0, max);
                    resumeAt = SystemClock.uptimeMillis() + 2600;
                } else {
                    int index = hit(y);
                    if (index >= 0) {
                        LyricsModel.Line line = current.model.lines[index];
                        if (line.kind == LyricsModel.LYRIC) {
                            playSoundEffect(android.view.SoundEffectConstants.CLICK);
                            manual = false; resumeAt = 0;
                            for (int i = 0; i < pos.length; i++) { release[i] = 0; }
                            host.seek(line);
                        }
                    }
                }
                end();
                wake();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) resumeAt = SystemClock.uptimeMillis() + 2600;
                end();
                wake();
                return true;
        }
        return true;
    }

    private void end() {
        dragging = false; pressed = -1;
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }

    private int hit(float y) {
        Laid current = laid;
        if (current == null) return -1;
        float anchor = anchor();
        for (int i = 0; i < current.blocks.length; i++) {
            LyricsModel.Line line = current.model.lines[i];
            if (line.kind == LyricsModel.INTERLUDE) continue;
            float top = anchor + this.top[i] - pos[i];
            if (y >= top - gap / 2f && y <= top + current.blocks[i].h + gap / 2f) return i;
        }
        return -1;
    }

    // ---- health ----

    private void fail(String stage, Throwable error) {
        if (failed) return;
        failed = true;
        host.failed(stage, error);
    }

    boolean failed() { return failed; }

    private void account(long frameTime, long work) {
        statFrames++;
        statWorkNanos += work;
        statMaxWork = Math.max(statMaxWork, work);
        if (lastFrameSeen != 0) {
            long gap = frameTime - lastFrameSeen;
            statGaps++;
            statGapNanos += gap;
            statBuckets[gap <= 9_500_000L ? 0 : gap <= 12_500_000L ? 1 : gap <= 18_000_000L ? 2 : gap <= 26_000_000L ? 3 : 4]++;
        }
        lastFrameSeen = frameTime;
        long now = SystemClock.elapsedRealtime();
        if (now - statSince >= 30000 && statFrames > 0) {
            Display display = getDisplay();
            host.stats(String.format(java.util.Locale.ROOT,
                "frames=%d running_fps=%.1f display_hz=%.0f max_hz=%.0f gap_8ms=%d gap_11ms=%d gap_16ms=%d gap_25ms=%d gap_more=%d work_avg_ms=%.3f work_max_ms=%.3f lines=%d",
                statFrames, statGapNanos == 0 ? 0f : statGaps * 1e9f / statGapNanos,
                display == null ? 0f : display.getRefreshRate(), maxRefresh(display),
                statBuckets[0], statBuckets[1], statBuckets[2], statBuckets[3], statBuckets[4],
                statWorkNanos / 1e6 / statFrames, statMaxWork / 1e6, laid == null ? 0 : laid.blocks.length));
            statFrames = statGaps = statGapNanos = statWorkNanos = statMaxWork = 0;
            java.util.Arrays.fill(statBuckets, 0);
            statSince = now;
        }
    }

    /** Highest refresh rate the panel offers at the current resolution. */
    private static float maxRefresh(Display display) {
        if (display == null) return 0f;
        Display.Mode current = display.getMode();
        float max = current.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes())
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() && mode.getPhysicalHeight() == current.getPhysicalHeight())
                max = Math.max(max, mode.getRefreshRate());
        return max;
    }
}

package io.github.qishui.automix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.Choreographer;
import android.view.View;

/**
 * The play page's one-line lyric in the style of the full lyric page: the sung part lights up word
 * by word behind a soft moving edge, the rest stays dim, the translation sits
 * small underneath, and a new line rises in while the old one lifts away. Lines too wide for the
 * view glide sideways to keep the sweep in sight. Draws only; touches go to the host's container.
 */
final class LyricsShortView extends View implements Choreographer.FrameCallback {
    private static final long CHANGE_MS = 340;

    /** One laid-out line. */
    private static final class Shown {
        final LyricsModel.Line line;
        final float[] cx;
        final float width;
        String fitted;
        float fittedFor = -1;
        Shown(LyricsModel.Line line, TextPaint paint) {
            this.line = line;
            int n = line.text.length();
            cx = new float[n + 1];
            for (int k = 0; k <= n; k++) cx[k] = paint.getRunAdvance(line.text, 0, n, 0, n, false, k);
            width = cx[n];
        }
    }

    private final LyricsClock clock;
    private final float dp;
    private final TextPaint dim = new TextPaint(Paint.ANTI_ALIAS_FLAG), lit = new TextPaint(Paint.ANTI_ALIAS_FLAG),
        trans = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix edgeMatrix = new Matrix();
    private final LinearGradient edge = new LinearGradient(0, 0, 1, 0, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);
    private final Rect visible = new Rect();
    private Shown current, previous;
    private long changedAt;
    private float scroll, scrollV, previousScroll;
    private boolean attached, queued, parked;
    private long lastFrame;
    /** Sweep edge and scroll of the last repaint; frames that would draw the same picture are skipped. */
    private float paintedEdge = Float.NaN, paintedScroll = Float.NaN;

    LyricsShortView(Context context, LyricsClock clock) {
        super(context);
        this.clock = clock;
        dp = context.getResources().getDisplayMetrics().density;
        Typeface bold = Typeface.create(Typeface.DEFAULT, 700, false);
        for (TextPaint p : new TextPaint[] {dim, lit}) { p.setTypeface(bold); p.setTextSize(LyricsView.sp(context, 20)); p.setColor(Color.WHITE); }
        dim.setAlpha(Math.round(0.38f * 255));
        lit.setShader(edge);
        trans.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        trans.setTextSize(LyricsView.sp(context, 14));
        trans.setColor(Color.argb(150, 255, 255, 255));
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Like LyricsView: the sweep should move at the panel's rate, not at whatever Android 15+'s
        // per-view vote picks from size and update pattern (NORMAL, i.e. 60 Hz, or lower).
        if (android.os.Build.VERSION.SDK_INT >= 35) setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
    }

    /** A new current line from the host; the same text again keeps the running sweep. */
    void show(LyricsModel.Line line) {
        if (line == null) return;
        if (current != null && current.line.text.equals(line.text) && current.line.start == line.start) return;
        previous = current;
        current = new Shown(line, lit);
        changedAt = SystemClock.uptimeMillis();
        // The outgoing line lifts away from where it was scrolled to, not from its start: a long line
        // used to jump sideways by its whole scroll in the first frame of the change.
        previousScroll = scroll;
        scroll = 0; scrollV = 0;
        paintedEdge = Float.NaN;
        wake();
    }

    void wake() {
        if (!attached || queued) return;
        queued = true;
        parked = false;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    void resume() { if (parked && onScreen()) wake(); }

    private boolean onScreen() {
        return attached && getWindowVisibility() == VISIBLE && isShown() && getGlobalVisibleRect(visible) && !TabHook.covers(this);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; wake(); }
    @Override protected void onDetachedFromWindow() {
        attached = false;
        if (queued) Choreographer.getInstance().removeFrameCallback(this);
        queued = false;
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); if (v == VISIBLE) wake(); }
    @Override public boolean onTouchEvent(android.view.MotionEvent event) { return false; }

    @Override public void doFrame(long now) {
        queued = false;
        if (!attached) return;
        if (!onScreen()) { parked = true; return; }
        float dt = lastFrame == 0 ? 1f / 120f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        boolean moving = SystemClock.uptimeMillis() - changedAt < CHANGE_MS;
        Shown s = current;
        float edge = -1f;
        if (s != null) {
            edge = sweep(s, clock.at(now));
            float avail = getWidth() - getPaddingLeft() - getPaddingRight();
            float goal = 0;
            if (s.width > avail && avail > 0) goal = Math.max(0, Math.min(s.width - avail, edge - avail * 0.62f));
            float[] x = {scroll}, v = {scrollV};
            moving |= PlayerModel.spring(x, v, 0, goal, 120f, 1f, dt, 0.3f);
            scroll = x[0]; scrollV = v[0];
        }
        // Repaint only when the picture changes. Between words, after a line is fully lit and in
        // instrumental gaps nothing moves, yet every repaint re-records the play page and drives a
        // whole-window frame (and the bar's glass) at the display's full rate.
        if (moving || !(Math.abs(edge - paintedEdge) < 0.25f) || scroll != paintedScroll) {
            paintedEdge = edge;
            paintedScroll = scroll;
            invalidate();
        }
        // Paused (no more time reports) or idle: stop; the next report wakes the view.
        if (moving || (clock.running() && !clock.stale(System.nanoTime()))) { queued = true; Choreographer.getInstance().postFrameCallback(this); }
    }

    /** x of the sweep edge at time t: per character inside the sung unit, whole units before it. */
    private static float sweep(Shown s, long t) {
        LyricsModel.Line line = s.line;
        if (line.units == null) return t >= line.start ? s.width : 0;
        float x = 0;
        for (LyricsModel.Unit u : line.units) {
            if (t >= u.t1) { x = s.cx[u.end]; continue; }
            if (t < u.t0) break;
            for (int k = 0; k < u.c0.length; k++) {
                int i = u.start + k;
                if (t >= u.c1[k]) { x = s.cx[i + 1]; continue; }
                if (t >= u.c0[k]) x = s.cx[i] + (s.cx[i + 1] - s.cx[i]) * LyricsModel.progress(u.c0[k], u.c1[k], t);
                break;
            }
            break;
        }
        return x;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (LyricsBackdrop.capture(canvas, this)) return;
        long nowMs = SystemClock.uptimeMillis();
        float k = Math.min(1f, (nowMs - changedAt) / (float) CHANGE_MS);
        float ease = 1f - (1f - k) * (1f - k) * (1f - k);
        long t = clock.at(System.nanoTime());
        if (previous != null && k < 1f) draw(canvas, previous, -14 * dp * ease, 1f - ease, Long.MAX_VALUE, previousScroll);
        if (current != null) draw(canvas, current, 14 * dp * (1f - ease), previous == null ? 1f : ease, t, scroll);
    }

    private void draw(Canvas canvas, Shown s, float dy, float alpha, long t, float scrollX) {
        if (alpha <= 0.01f) return;
        Paint.FontMetrics fm = lit.getFontMetrics();
        float mainH = fm.descent - fm.ascent;
        boolean translated = s.line.translation != null && !s.line.translation.isEmpty();
        Paint.FontMetrics tm = trans.getFontMetrics();
        float block = mainH + (translated ? 4 * dp + (tm.descent - tm.ascent) : 0);
        float top = (getHeight() - block) / 2f + dy;
        float base = top - fm.ascent;
        float left = getPaddingLeft();
        int saved = canvas.save();
        canvas.clipRect(left, 0, getWidth() - getPaddingRight(), getHeight());
        canvas.translate(left - scrollX, 0);
        int a = Math.round(alpha * 255);
        dim.setAlpha(Math.round(0.38f * a));
        canvas.drawText(s.line.text, 0, base, dim);
        float x = sweep(s, t);
        if (x > 0) {
            float feather = 18 * dp;
            edgeMatrix.setScale(feather, 1);
            edgeMatrix.postTranslate(x - feather / 2f, 0);
            edge.setLocalMatrix(edgeMatrix);
            lit.setAlpha(a);
            canvas.save();
            canvas.clipRect(-1, top - 8 * dp, x + feather / 2f, top + mainH + 8 * dp);
            canvas.drawText(s.line.text, 0, base, lit);
            canvas.restore();
        }
        canvas.restoreToCount(saved);
        if (translated) {
            trans.setAlpha(Math.round(alpha * 150));
            float avail = getWidth() - getPaddingLeft() - getPaddingRight();
            if (s.fittedFor != avail) {
                String tr = s.line.translation;
                s.fitted = trans.measureText(tr) > avail ? android.text.TextUtils.ellipsize(tr, trans, avail, android.text.TextUtils.TruncateAt.END).toString() : tr;
                s.fittedFor = avail;
            }
            canvas.drawText(s.fitted, left, top + mainH + 4 * dp - tm.ascent, trans);
        }
    }
}

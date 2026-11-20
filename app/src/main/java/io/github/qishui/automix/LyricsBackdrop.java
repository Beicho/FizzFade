package io.github.qishui.automix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

/**
 * Slowly flowing blobs in the cover colours the host already computed for this page. The shader
 * runs at a quarter of the resolution into a cached layer at 30 fps and is scaled up; the lyric
 * frames in between only composite the texture.
 */
final class LyricsBackdrop extends View implements Choreographer.FrameCallback {
    /**
     * The colour field, shared with the glass panels so they refract exactly what is behind them.
     * {@code p} is in units of the backdrop height; blob centres come from {@link #centers}.
     */
    static final String FIELD =
        "uniform float time;\n"
        + "uniform float4 b01; uniform float4 b23;\n"
        + "uniform half4 c0; uniform half4 c1; uniform half4 c2; uniform half4 c3; uniform half4 base;\n"
        + "float w(float2 p, float2 c, float r) { float2 d = (p - c) / r; return exp(-dot(d, d) * 1.5); }\n"
        + "float3 field(float2 p) {\n"
        + "  float t = time;\n"
        + "  p += 0.035 * float2(sin(p.y * 5.0 + t * 0.31), cos(p.x * 4.0 - t * 0.27));\n"
        + "  float3 col = base.rgb;\n"
        + "  col = mix(col, c0.rgb, w(p, b01.xy, 0.62));\n"
        + "  col = mix(col, c1.rgb, w(p, b01.zw, 0.58));\n"
        + "  col = mix(col, c2.rgb, w(p, b23.xy, 0.55));\n"
        + "  col = mix(col, c3.rgb, w(p, b23.zw, 0.60));\n"
        + "  return col * 0.86;\n"
        + "}\n";
    private static final String SHADER = "uniform float2 size;\n" + FIELD
        + "half4 main(float2 xy) {\n"
        + "  float3 col = field(xy / size.y);\n"
        + "  float n = fract(sin(dot(xy, float2(12.9898, 78.233))) * 43758.5453);\n"
        + "  return half4(half3(col + (n - 0.5) / 255.0), 1.0);\n"
        + "}\n";
    private static final int SCALE = 4;

    private final Object shader;           // RuntimeShader on API 33+, otherwise null
    private final Paint paint = new Paint();
    private final RenderNode node = Build.VERSION.SDK_INT >= 29 ? new RenderNode("qishui-backdrop") : null;
    private final float[][] from = new float[5][3], to = new float[5][3], now = new float[5][3];
    private long blendStart;
    private boolean attached, queued, failed;
    private long lastDraw;
    private Drawable source;
    private final Runnable failure;
    private final java.util.List<Runnable> ticks = new java.util.ArrayList<>();
    private final android.graphics.Rect visible = new android.graphics.Rect();
    private final float[] centers = new float[8];
    private float time;
    private boolean parked, yields;

    LyricsBackdrop(Context context, Runnable failure) {
        super(context);
        this.failure = failure;
        shader = shared();
        palette(new int[] {Color.rgb(40, 44, 60), Color.rgb(18, 20, 28)}, true);
        if (node != null) node.setUseCompositingLayer(true, null);
    }

    private static Object compiled;
    private static boolean compileFailed;

    /**
     * One compiled program per process: compiling AGSL costs milliseconds on the main thread and
     * every play page card makes a backdrop. Sharing is safe because uniforms are snapshotted into
     * the display list when a draw is recorded, and all recording happens on the UI thread.
     */
    static Object shared() {
        if (compiled == null && !compileFailed && Build.VERSION.SDK_INT >= 33) {
            try { compiled = new RuntimeShader(SHADER); } catch (Throwable error) { compileFailed = true; }
        }
        return compiled;
    }

    /**
     * A software canvas while our window is hardware accelerated means someone is copying the
     * screen into a bitmap (the host's RealtimeBlurView does, every frame). Runtime shaders and
     * render nodes are not allowed there, so draw a cheap stand-in.
     */
    static boolean capture(Canvas canvas, View view) {
        return !canvas.isHardwareAccelerated() && view.isHardwareAccelerated();
    }

    /** Re-reads the host page background; the host swaps in a new GradientDrawable per song. */
    void follow(View pageRoot) { follow(pageRoot == null ? null : pageRoot.getBackground()); }

    /** Same, for a host view that carries the cover gradient as its image rather than background. */
    void follow(Drawable background) {
        if (background == source) return;
        source = background;
        if (background instanceof GradientDrawable) {
            int[] colors = ((GradientDrawable) background).getColors();
            if (colors != null && colors.length > 0) palette(colors, false);
        }
    }

    private void palette(int[] colors, boolean immediate) {
        int a = colors[0], b = colors[colors.length - 1], mid = colors[colors.length / 2];
        int[] picks = {vivid(a, 0f), vivid(b, 0f), vivid(mid, 28f), vivid(blend(a, b), -24f), deep(blend(a, b))};
        for (int i = 0; i < 5; i++) {
            from[i] = now[i].clone();
            to[i][0] = Color.red(picks[i]) / 255f; to[i][1] = Color.green(picks[i]) / 255f; to[i][2] = Color.blue(picks[i]) / 255f;
            if (immediate) { from[i] = to[i].clone(); now[i] = to[i].clone(); }
        }
        blendStart = immediate ? 0 : SystemClock.uptimeMillis();
        wake();
    }

    private static int blend(int a, int b) {
        return Color.rgb((Color.red(a) + Color.red(b)) / 2, (Color.green(a) + Color.green(b)) / 2, (Color.blue(a) + Color.blue(b)) / 2);
    }

    /** Cover colours made rich enough to flow, and dark enough for white text. */
    private static int vivid(int color, float hueShift) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + hueShift + 360f) % 360f;
        hsv[1] = Math.min(1f, Math.max(0.35f, hsv[1] * 1.25f));
        hsv[2] = Math.min(0.62f, Math.max(0.30f, hsv[2]));
        return Color.HSVToColor(hsv);
    }

    private static int deep(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.min(0.22f, hsv[2] * 0.6f);
        return Color.HSVToColor(hsv);
    }

    private void wake() {
        if (!attached || queued || failed) return;
        queued = true;
        parked = false;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Restarts a backdrop parked off screen; cheap enough for every pre-draw. */
    void resume() { if (parked && onScreen()) wake(); }

    /** Runs after every repaint of the field (about 30 fps), for panels that sample it. */
    void onTick(Runnable tick) { ticks.add(tick); }

    /** For backdrops under the lyric page: stop while a lyric view is animating on top. */
    void yieldToLyrics() { yields = true; }

    private boolean onScreen() {
        if (yields && SystemClock.uptimeMillis() - LyricsView.lastFrameAt < 250) return false;
        return attached && getWindowVisibility() == VISIBLE && isShown() && getGlobalVisibleRect(visible) && !TabHook.covers(this);
    }

    @Override public void doFrame(long frameTimeNanos) {
        queued = false;
        if (!attached || failed) return;
        // Off-screen pager cards and covered windows stop here; the owner's pre-draw resumes them.
        if (!onScreen()) { parked = true; return; }
        long t = SystemClock.uptimeMillis();
        if (t - lastDraw >= 33) {
            lastDraw = t;
            invalidate();
            for (int i = 0; i < ticks.size(); i++) ticks.get(i).run();
        }
        boolean moving = shader != null && areAnimatorsEnabled();
        if (moving || blendStart != 0) {
            queued = true;
            // Wake for the next repaint, not on every vsync in between: at 120 Hz three of every four
            // callbacks only looked at the clock (and walked the view tree for onScreen()).
            long wait = 33 - (SystemClock.uptimeMillis() - lastDraw);
            if (wait > 1) Choreographer.getInstance().postFrameCallbackDelayed(this, wait);
            else Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) wake();
    }

    boolean shaderActive() { return shader != null && !failed; }

    /** Whether the field is currently repainting (on screen, flowing). */
    boolean animating() { return queued && !parked; }

    /** Blob centres in units of the height, from the same clock as {@link #FIELD}'s warp. */
    private void centers(float t, float ar) {
        centers[0] = ar * (0.25f + 0.20f * (float) Math.sin(t * 0.13f)); centers[1] = 0.18f + 0.10f * (float) Math.cos(t * 0.11f);
        centers[2] = ar * (0.80f + 0.18f * (float) Math.cos(t * 0.09f)); centers[3] = 0.42f + 0.16f * (float) Math.sin(t * 0.12f);
        centers[4] = ar * (0.30f + 0.22f * (float) Math.cos(t * 0.15f + 1.7f)); centers[5] = 0.70f + 0.12f * (float) Math.sin(t * 0.10f);
        centers[6] = ar * (0.70f + 0.20f * (float) Math.sin(t * 0.07f + 2.1f)); centers[7] = 0.92f + 0.10f * (float) Math.cos(t * 0.14f);
    }

    /** Copies this frame's field state into another shader built on {@link #FIELD}. */
    void bindField(RuntimeShader s) {
        s.setFloatUniform("time", time);
        s.setFloatUniform("b01", centers[0], centers[1], centers[2], centers[3]);
        s.setFloatUniform("b23", centers[4], centers[5], centers[6], centers[7]);
        String[] names = {"c0", "c1", "c2", "c3", "base"};
        for (int i = 0; i < 5; i++) s.setFloatUniform(names[i], now[i][0], now[i][1], now[i][2], 1f);
    }

    /** Current palette, for panels drawn without a shader. */
    int color(int index) { return rgb(now[Math.max(0, Math.min(4, index))]); }

    private static boolean areAnimatorsEnabled() {
        return Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; wake(); }
    @Override protected void onDetachedFromWindow() {
        attached = false;
        if (queued) Choreographer.getInstance().removeFrameCallback(this);
        queued = false;
        super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); wake(); }

    @Override protected void onDraw(Canvas canvas) {
        if (failed) return;
        if (capture(canvas, this)) { canvas.drawColor(rgb(now[4])); return; }
        try { paint(canvas); }
        catch (Throwable error) { failed = true; failure.run(); }
    }

    private void paint(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float k = 1f;
        if (blendStart != 0) {
            k = Math.min(1f, (SystemClock.uptimeMillis() - blendStart) / 900f);
            if (k >= 1f) blendStart = 0;
            k = k * k * (3 - 2 * k);
        }
        for (int i = 0; i < 5; i++) for (int j = 0; j < 3; j++) now[i][j] = from[i][j] + (to[i][j] - from[i][j]) * k;
        if (shader == null || node == null || !canvas.isHardwareAccelerated()) {
            paint.setShader(new LinearGradient(0, 0, 0, h, rgb(now[0]), rgb(now[4]), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            return;
        }
        int sw = Math.max(1, w / SCALE), sh = Math.max(1, h / SCALE);
        RuntimeShader s = (RuntimeShader) shader;
        paint.setShader(s);
        time = (SystemClock.uptimeMillis() % 3_600_000L) / 1000f;
        centers(time, w / (float) h);
        s.setFloatUniform("size", sw, sh);
        bindField(s);
        node.setPosition(0, 0, sw, sh);
        RecordingCanvas c = node.beginRecording();
        try { c.drawRect(0, 0, sw, sh, paint); } finally { node.endRecording(); }
        canvas.save();
        canvas.scale(w / (float) sw, h / (float) sh);
        canvas.drawRenderNode(node);
        canvas.restore();
    }

    private static int rgb(float[] c) { return Color.rgb(Math.round(c[0] * 255), Math.round(c[1] * 255), Math.round(c[2] * 255)); }
}

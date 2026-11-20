package io.github.qishui.automix;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import java.util.List;

/**
 * One piece of Liquid Glass for the floating bar: a render node that looks through the page under
 * it (vibrancy, blur, lens refraction, optionally the chromatic dispersion of a lifted bubble),
 * clipped to a capsule. Pipeline, shaders and default rim after Kyant's Backdrop
 * (https://github.com/Kyant0/AndroidLiquidGlass, Apache-2.0, Copyright 2025 Kyant).
 *
 * <p>The page is drawn into the node by reference to its own render nodes, so recording is cheap.
 * It must be re-recorded whenever the page under it changes: a render node drawn in two places only
 * reports damage to its first parent, so a glass layer that is not re-recorded keeps showing an old
 * picture when the page scrolls under it (what 0.5.1–0.5.4 did). Moving or growing the glass needs
 * no re-recording ({@link #transform}).
 */
final class TabGlass {
    // Kyant Backdrop: RoundedRectSDF.
    private static final String SDF = ""
        + "float radiusAt(float2 c, float4 r) { if (c.x >= 0.0) { return c.y <= 0.0 ? r.y : r.z; } return c.y <= 0.0 ? r.x : r.w; }\n"
        + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
        + "  float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
        + "  float outside = length(max(cornerCoord, 0.0)) - radius;\n"
        + "  float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
        + "  return outside + inside;\n"
        + "}\n"
        + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
        + "  float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
        + "  if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
        + "  float gradX = step(cornerCoord.y, cornerCoord.x);\n"
        + "  return sign(coord) * float2(gradX, 1.0 - gradX);\n"
        + "}\n"
        + "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n";
    // Kyant Backdrop: RoundedRectRefractionShaderString and ...WithDispersionShaderString in one.
    private static final String LENS = "uniform shader content;\n"
        + "uniform float2 size; uniform float2 offset; uniform float4 cornerRadii;\n"
        + "uniform float refractionHeight; uniform float refractionAmount; uniform float depthEffect; uniform float chromaticAberration;\n"
        + SDF
        + "half4 main(float2 coord) {\n"
        + "  float2 halfSize = size * 0.5;\n"
        + "  float2 centeredCoord = (coord + offset) - halfSize;\n"
        + "  float radius = radiusAt(centeredCoord, cornerRadii);\n"
        + "  float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
        + "  if (-sd >= refractionHeight) return content.eval(coord);\n"
        + "  sd = min(sd, 0.0);\n"
        + "  float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
        + "  float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
        + "  float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));\n"
        + "  float2 refractedCoord = coord + d * grad;\n"
        + "  if (chromaticAberration <= 0.0) return content.eval(refractedCoord);\n"
        + "  float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
        + "  float2 dispersedCoord = d * grad * dispersionIntensity;\n"
        + "  half4 color = half4(0.0);\n"
        + "  half4 red = content.eval(refractedCoord + dispersedCoord);\n"
        + "  color.r += red.r / 3.5; color.a += red.a / 7.0;\n"
        + "  half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
        + "  color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;\n"
        + "  half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
        + "  color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;\n"
        + "  half4 green = content.eval(refractedCoord);\n"
        + "  color.g += green.g / 3.5; color.a += green.a / 7.0;\n"
        + "  half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
        + "  color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;\n"
        + "  half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
        + "  color.b += blue.b / 3.0; color.a += blue.a / 7.0;\n"
        + "  half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
        + "  color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;\n"
        + "  return color;\n"
        + "}\n";
    // Kyant Backdrop: DefaultHighlightShaderString.
    private static final String RIM = "uniform float2 size; uniform float4 cornerRadii;\n"
        + "layout(color) uniform half4 color; uniform float angle; uniform float falloff;\n"
        + SDF
        + "half4 main(float2 coord) {\n"
        + "  float2 halfSize = size * 0.5;\n"
        + "  float2 centeredCoord = coord - halfSize;\n"
        + "  float radius = radiusAt(centeredCoord, cornerRadii);\n"
        + "  float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
        + "  float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
        + "  float2 normal = float2(cos(angle), sin(angle));\n"
        + "  return color * pow(abs(dot(grad, normal)), falloff);\n"
        + "}\n";

    private static final Object[] PROGRAMS = new Object[2];
    private static boolean compileFailed;

    /** Compiled once per process: compiling per element cost frames on every card in 0.5.1. */
    private static Object program(int which) {
        if (PROGRAMS[which] == null && !compileFailed && Build.VERSION.SDK_INT >= 33) {
            try { PROGRAMS[which] = new RuntimeShader(which == 0 ? LENS : RIM); }
            catch (Throwable error) { compileFailed = true; }
        }
        return PROGRAMS[which];
    }

    static boolean lensSupported() { return Build.VERSION.SDK_INT >= 33 && program(0) != null; }

    /** What the glass looks through, drawn in the owner's (the screen root's) coordinates. */
    interface Content { void draw(RecordingCanvas canvas); }

    final RenderNode node = new RenderNode("qishui-glass");
    private final RectF bounds = new RectF(), drawn = new RectF();
    private final Outline outline = new Outline();
    private boolean failed;
    private java.util.function.Consumer<Throwable> errors;

    private RenderEffect barEffect;
    private int barW, barH;

    /** {@link #bar} for this piece, rebuilt only when its size changes (a press grows it). */
    RenderEffect barEffect(float w, float h, float blur, float lensHeight, float lensAmount) {
        int iw = Math.round(w), ih = Math.round(h);
        if (barEffect == null || iw != barW || ih != barH) { barEffect = bar(iw, ih, blur, lensHeight, lensAmount); barW = iw; barH = ih; }
        return barEffect;
    }

    void onError(java.util.function.Consumer<Throwable> sink) { errors = sink; }

    boolean failed() { return failed; }

    RectF bounds() { return bounds; }

    /** Records the glass at {@code r} (a capsule), looking through {@code content}, under {@code effect}. */
    void record(RectF r, Content content, RenderEffect effect) {
        bounds.set(r);
        if (failed || r.isEmpty()) { node.discardDisplayList(); return; }
        try {
            int l = Math.round(r.left), t = Math.round(r.top), w = Math.max(1, Math.round(r.width())), h = Math.max(1, Math.round(r.height()));
            node.setPosition(l, t, l + w, t + h);
            outline.setRoundRect(0, 0, w, h, Math.min(w, h) / 2f);
            node.setOutline(outline);
            node.setClipToOutline(true);
            if (Build.VERSION.SDK_INT >= 31) node.setRenderEffect(effect);
            RecordingCanvas c = node.beginRecording(w, h);
            try {
                c.translate(-l, -t);
                content.draw(c);
            } finally {
                node.endRecording();
            }
            place();
        } catch (Throwable error) {
            failed = true;
            node.discardDisplayList();
            if (errors != null) errors.accept(error);
        }
    }

    void discard() { node.discardDisplayList(); bounds.setEmpty(); }

    /**
     * Shows the glass recorded at {@link #bounds} over {@code to} instead (the capsule grown by a
     * press or leaning into a drag): node properties only, so no re-recording, no new effect, and
     * the layer's blurred, refracted picture is reused. Empty {@code to}: where it was recorded.
     */
    void transform(RectF to) {
        if (to == null || to.isEmpty()) drawn.setEmpty(); else drawn.set(to);
        place();
    }

    private void place() {
        if (bounds.isEmpty() || drawn.isEmpty()) {
            node.setScaleX(1f); node.setScaleY(1f); node.setTranslationX(0f); node.setTranslationY(0f);
            return;
        }
        int l = Math.round(bounds.left), t = Math.round(bounds.top);
        int w = Math.max(1, Math.round(bounds.width())), h = Math.max(1, Math.round(bounds.height()));
        node.setPivotX(w / 2f);
        node.setPivotY(h / 2f);
        node.setScaleX(drawn.width() / w);
        node.setScaleY(drawn.height() / h);
        node.setTranslationX(drawn.centerX() - (l + w / 2f));
        node.setTranslationY(drawn.centerY() - (t + h / 2f));
    }

    void draw(Canvas canvas) {
        if (!failed && !bounds.isEmpty() && node.hasDisplayList()) canvas.drawRenderNode(node);
    }

    /**
     * The bar's material, Kyant's LiquidBottomTabs: vibrancy, blur, then a rounded-rect lens that
     * bends the rim inward. Android 12 has no lens; older versions get no effect (surface only).
     */
    static RenderEffect bar(float w, float h, float blur, float lensHeight, float lensAmount) {
        if (Build.VERSION.SDK_INT < 31) return null;
        ColorMatrix saturation = new ColorMatrix();
        saturation.setSaturation(1.5f);
        RenderEffect vibrancy = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(saturation));
        RenderEffect blurred = blur > 0 ? RenderEffect.createBlurEffect(blur, blur, vibrancy, Shader.TileMode.CLAMP) : vibrancy;
        RenderEffect lens = lens(w, h, lensHeight, lensAmount, 0f);
        return lens == null ? blurred : RenderEffect.createChainEffect(lens, blurred);
    }

    /** A capsule lens; with {@code dispersion} 1 the rim splits colours like a lifted iOS bubble. */
    static RenderEffect lens(float w, float h, float height, float amount, float dispersion) {
        if (Build.VERSION.SDK_INT < 33 || height <= 0.5f || amount <= 0.5f) return null;
        Object program = program(0);
        if (program == null) return null;
        RuntimeShader s = (RuntimeShader) program;
        float r = Math.min(w, h) / 2f;
        s.setFloatUniform("size", w, h);
        s.setFloatUniform("offset", 0f, 0f);
        s.setFloatUniform("cornerRadii", r, r, r, r);
        s.setFloatUniform("refractionHeight", Math.min(height, r));
        s.setFloatUniform("refractionAmount", -amount);
        s.setFloatUniform("depthEffect", 0f);
        s.setFloatUniform("chromaticAberration", dispersion);
        return RenderEffect.createRuntimeShaderEffect(s, "content");
    }

    /**
     * Kyant's default highlight on a capsule at {@code r}: a thin rim lit from 45°, added on top.
     * {@code paint} is the caller's scratch paint. Without runtime shaders (software copies, old
     * systems) a plain faint stroke is drawn instead.
     */
    static void rim(Canvas canvas, RectF r, float strokeWidth, float alpha, Paint paint) {
        if (r.isEmpty() || alpha <= 0.004f) return;
        float radius = Math.min(r.width(), r.height()) / 2f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth * 2f);
        Object program = canvas.isHardwareAccelerated() ? program(1) : null;
        int saved = canvas.save();
        canvas.clipPath(capsule(r));
        if (program != null) {
            RuntimeShader s = (RuntimeShader) program;
            s.setFloatUniform("size", r.width(), r.height());
            s.setFloatUniform("cornerRadii", radius, radius, radius, radius);
            s.setColorUniform("color", Color.WHITE);
            s.setFloatUniform("angle", (float) Math.toRadians(45));
            s.setFloatUniform("falloff", 1f);
            paint.setShader(s);
            paint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
            paint.setBlendMode(BlendMode.PLUS);
            canvas.translate(r.left, r.top);
            canvas.drawRoundRect(0, 0, r.width(), r.height(), radius, radius, paint);
        } else {
            paint.setShader(null);
            paint.setBlendMode(null);
            paint.setColor(Color.argb(Math.round(110 * Math.min(1f, alpha)), 255, 255, 255));
            canvas.drawRoundRect(r, radius, radius, paint);
        }
        canvas.restoreToCount(saved);
        paint.setShader(null);
        paint.setBlendMode(null);
    }

    private static final android.graphics.Path CAPSULE = new android.graphics.Path();

    /** A capsule path at {@code r} (shared scratch; main thread only). */
    static android.graphics.Path capsule(RectF r) {
        CAPSULE.reset();
        float radius = Math.min(r.width(), r.height()) / 2f;
        CAPSULE.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
        return CAPSULE;
    }

    /**
     * Draws the page under the bar the way the screen shows it: the root's background, then each
     * source (a sibling below the bar) at its place, by reference to its render nodes.
     */
    static void page(RecordingCanvas c, View root, Drawable background, List<View> sources) {
        if (background != null) background.draw(c);
        for (int i = 0; i < sources.size(); i++) {
            View source = sources.get(i);
            if (source.getVisibility() != View.VISIBLE || source.getAlpha() <= 0f || source.getWidth() == 0) continue;
            int saved = c.save();
            if (source.getParent() == root) c.translate(source.getLeft(), source.getTop());
            else c.translate(source.getLeft() - root.getLeft(), source.getTop() - root.getTop());
            if (!source.getMatrix().isIdentity()) c.concat(source.getMatrix());
            c.translate(-source.getScrollX(), -source.getScrollY());
            source.draw(c);
            c.restoreToCount(saved);
        }
    }
}

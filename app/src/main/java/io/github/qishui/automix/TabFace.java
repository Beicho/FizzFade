package io.github.qishui.automix;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Typeface;
import android.text.TextPaint;
import java.util.ArrayList;
import java.util.List;

/**
 * What the floating bar shows: per tab a filled glyph over a short label (iOS tab bar layout:
 * icon above label, label ~10pt semibold), the host's badges at the glyph's top right, and the
 * same faces once more in the accent colour, one render node per tab, for the selection bubble to
 * show (and magnify) through its lens. Recorded only when the tabs change; the bubble moves and
 * scales the nodes by their properties, so a frame of motion records nothing here.
 */
final class TabFace {
    /** One host tab as last seen. Positions are in the drawing surface's coordinates. */
    static final class Tab {
        int id;
        String title = "";
        int kind = TabIcons.DOT;
        float cx, left, right;
        boolean selected, dot;
        String badge;
        android.view.View view;

        boolean same(Tab o) {
            return o != null && id == o.id && kind == o.kind && selected == o.selected && dot == o.dot
                && title.equals(o.title) && java.util.Objects.equals(badge, o.badge)
                && Math.abs(cx - o.cx) < 0.5f && Math.abs(left - o.left) < 0.5f && Math.abs(right - o.right) < 0.5f;
        }
    }

    static final float ICON_DP = 26f, LABEL_SP = 10.5f, GAP_DP = 1.5f;

    final List<Tab> tabs = new ArrayList<>();
    final RenderNode normal = new RenderNode("qishui-tab-faces");
    final List<RenderNode> tinted = new ArrayList<>();
    private final float dp;
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG), badgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), red = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path[] glyphs = new Path[TabIcons.COUNT];
    private final Path scratch = new Path();
    private final Matrix matrix = new Matrix();
    private final RectF box = new RectF();

    TabFace(float dp, float sp) {
        this.dp = dp;
        label.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        label.setTextSize(LABEL_SP * sp);
        label.setTextAlign(Paint.Align.CENTER);
        badgeText.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        badgeText.setTextSize(10f * sp);
        badgeText.setTextAlign(Paint.Align.CENTER);
        badgeText.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        red.setColor(Color.rgb(255, 59, 48));
    }

    /** Records the plain faces and one accent node per tab; {@code midY} is the capsule's centre line. */
    void record(int width, int height, float midY, int off, int accent) {
        normal.setPosition(0, 0, Math.max(1, width), Math.max(1, height));
        normal.setClipToBounds(false);
        RecordingCanvas c = normal.beginRecording(Math.max(1, width), Math.max(1, height));
        try { for (Tab t : tabs) tab(c, t, t.cx, midY, off); }
        finally { normal.endRecording(); }
        while (tinted.size() < tabs.size()) tinted.add(new RenderNode("qishui-tab-tint"));
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            RenderNode n = tinted.get(i);
            float half = Math.max(t.right - t.left, ICON_DP * dp * 2f) / 2f, top = midY - 40 * dp;
            int l = Math.round(t.cx - half), tp = Math.round(top), w = Math.round(half * 2f), h = Math.round(80 * dp);
            n.setPosition(l, tp, l + w, tp + h);
            n.setClipToBounds(false);
            n.setPivotX(w / 2f);
            n.setPivotY(midY - tp);
            RecordingCanvas tc = n.beginRecording(w, h);
            try { tc.translate(-l, -tp); tab(tc, t, t.cx, midY, accent); }
            finally { n.endRecording(); }
        }
        for (int i = tabs.size(); i < tinted.size(); i++) tinted.get(i).discardDisplayList();
    }

    /** Magnification of the accent faces inside the lifted bubble (1 at rest, 1.2 lifted). */
    void magnify(float scale) {
        for (int i = 0; i < tabs.size() && i < tinted.size(); i++) { tinted.get(i).setScaleX(scale); tinted.get(i).setScaleY(scale); }
    }

    /** One tab: glyph above label, as a block centred on {@code midY}. */
    void tab(Canvas canvas, Tab t, float cx, float midY, int color) {
        float size = ICON_DP * dp;
        Paint.FontMetrics fm = label.getFontMetrics();
        float textH = fm.descent - fm.ascent;
        float block = size + GAP_DP * dp + textH;
        float top = midY - block / 2f;
        float iconY = top + size / 2f;
        glyph(canvas, t.kind, cx, iconY, size, color);
        label.setColor(color);
        String text = t.title.length() > 4 ? t.title.substring(0, 4) : t.title;
        canvas.drawText(text, cx, top + size + GAP_DP * dp - fm.ascent, label);
        badge(canvas, t, cx + size * 0.36f, iconY - size * 0.34f);
    }

    /** A filled glyph of {@code kind} in a {@code size} box centred on (cx, cy). */
    void glyph(Canvas canvas, int kind, float cx, float cy, float size, int color) {
        Path p = glyphs[kind];
        if (p == null) {
            Path built = new Path();
            TabIcons.glyph(kind, new TabIcons.Sink() {
                @Override public void moveTo(float x, float y) { built.moveTo(x, y); }
                @Override public void lineTo(float x, float y) { built.lineTo(x, y); }
                @Override public void quadTo(float x1, float y1, float x2, float y2) { built.quadTo(x1, y1, x2, y2); }
                @Override public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) { built.cubicTo(x1, y1, x2, y2, x3, y3); }
                @Override public void close() { built.close(); }
            });
            glyphs[kind] = p = built;
        }
        float s = size / 24f;
        matrix.setScale(s, s);
        matrix.postTranslate(cx - size / 2f, cy - size / 2f);
        scratch.reset();
        p.transform(matrix, scratch);
        fill.setColor(color);
        canvas.drawPath(scratch, fill);
    }

    /** The host's red dot, or its corner text in a red capsule (iOS badge). */
    void badge(Canvas canvas, Tab t, float x, float y) {
        if (t.badge != null && !t.badge.isEmpty()) {
            String text = t.badge.length() > 4 ? t.badge.substring(0, 4) : t.badge;
            float h = 17 * dp, w = Math.max(h, badgeText.measureText(text) + 10 * dp);
            box.set(x - h / 2f, y - h / 2f, x - h / 2f + w, y + h / 2f);
            canvas.drawRoundRect(box, h / 2f, h / 2f, red);
            Paint.FontMetrics fm = badgeText.getFontMetrics();
            canvas.drawText(text, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2f, badgeText);
        } else if (t.dot) {
            canvas.drawCircle(x, y, 4.5f * dp, red);
        }
    }

    /** Bounds of the tab nearest to fractional index {@code at} blended with its neighbour. */
    float centerAt(float at) {
        if (tabs.isEmpty()) return 0f;
        int i = (int) Math.floor(at);
        if (i < 0) return tabs.get(0).cx;
        if (i >= tabs.size() - 1) return tabs.get(tabs.size() - 1).cx;
        float f = at - i;
        return tabs.get(i).cx + (tabs.get(i + 1).cx - tabs.get(i).cx) * f;
    }

    /** Width of a tab slot around fractional index {@code at}. */
    float slotAt(float at) {
        if (tabs.isEmpty()) return 0f;
        int i = Math.max(0, Math.min(tabs.size() - 1, Math.round(at)));
        Tab t = tabs.get(i);
        return t.right - t.left;
    }

    /** Fractional index under x (between tab centres, clamped). */
    float indexAt(float x) {
        int n = tabs.size();
        if (n <= 1) return 0f;
        if (x <= tabs.get(0).cx) return 0f;
        for (int i = 0; i < n - 1; i++) {
            float a = tabs.get(i).cx, b = tabs.get(i + 1).cx;
            if (x <= b) return i + (b > a ? (x - a) / (b - a) : 0f);
        }
        return n - 1;
    }
}

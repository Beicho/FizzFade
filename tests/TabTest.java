package io.github.qishui.automix;

import java.util.ArrayList;
import java.util.List;

/** Floating bar: the glyph reader and the selection bubble's motion (pure Java parts). */
public final class TabTest {
    private static int passed, failed;

    public static void main(String[] args) {
        glyphsParseInsideTheirBox();
        readerFollowsSvgRules();
        tabsAreNamedByEnumFirst();
        tapGlidesLiftsStretchesAndDrops();
        motionIsFrameRateIndependent();
        tapOnSelectedTabStillPulses();
        dragCarriesBubbleAndSelectsNearest();
        cancelReturnsToSelection();
        rubberBandIsBoundedAndSettles();
        hiddenSelectionFadesBubble();
        heldBubbleStopsSpringing();
        System.out.println("TabTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Records commands and the bounds of every point. */
    static final class Box implements TabIcons.Sink {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        final List<String> ops = new ArrayList<>();
        private void p(float x, float y) { minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); }
        @Override public void moveTo(float x, float y) { p(x, y); ops.add("M" + r(x) + "," + r(y)); }
        @Override public void lineTo(float x, float y) { p(x, y); ops.add("L" + r(x) + "," + r(y)); }
        @Override public void quadTo(float x1, float y1, float x2, float y2) { p(x1, y1); p(x2, y2); ops.add("Q" + r(x1) + "," + r(y1) + " " + r(x2) + "," + r(y2)); }
        @Override public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) { p(x1, y1); p(x2, y2); p(x3, y3); ops.add("C" + r(x3) + "," + r(y3)); }
        @Override public void close() { ops.add("Z"); }
        private static String r(float v) { return String.valueOf(Math.round(v * 100) / 100f); }
    }

    static void glyphsParseInsideTheirBox() {
        for (int kind = 0; kind < TabIcons.COUNT; kind++) {
            Box box = new Box();
            TabIcons.glyph(kind, box);
            check("glyph " + kind + " has outline", box.ops.size() > 3);
            check("glyph " + kind + " inside 24-unit box", box.minX >= -0.01f && box.minY >= -0.01f && box.maxX <= 24.01f && box.maxY <= 24.01f);
            check("glyph " + kind + " uses the box", box.maxX - box.minX > 8f && box.maxY - box.minY > 8f);
        }
        Box play = new Box();
        TabIcons.glyph(TabIcons.PLAY, play);
        check("play starts at (8, 17.18)", play.ops.get(0).equals("M8.0,17.18"));
        Box pause = new Box();
        TabIcons.glyph(TabIcons.PAUSE, pause);
        int moves = 0, closes = 0;
        for (String op : pause.ops) { if (op.startsWith("M")) moves++; if (op.equals("Z")) closes++; }
        check("pause is two bars", moves == 2 && closes == 2);
    }

    static void readerFollowsSvgRules() {
        Box t = new Box();
        TabIcons.parse("M0 0Q10 10 20 0T40 0", 1f, 0f, t);
        check("T reflects the previous control point", t.ops.get(2).equals("Q30.0,-10.0 40.0,0.0"));
        Box rel = new Box();
        TabIcons.parse("M10 10h10v10z m5 5h1", 1f, 0f, rel);
        check("relative move after Z starts at the subpath start", rel.ops.get(4).equals("M15.0,15.0") && rel.ops.get(5).equals("L16.0,15.0"));
        Box implicit = new Box();
        TabIcons.parse("m1 1 2 2 3 3", 1f, 0f, implicit);
        check("pairs after a moveto are linetos", implicit.ops.size() == 3 && implicit.ops.get(1).equals("L3.0,3.0") && implicit.ops.get(2).equals("L6.0,6.0"));
        Box dense = new Box();
        TabIcons.parse("M.5-.5l.25.25-1e1 0", 1f, 0f, dense);
        check("dense numbers and exponents", dense.ops.get(0).equals("M0.5,-0.5") && dense.ops.get(1).equals("L0.75,-0.25") && dense.ops.get(2).equals("L-9.25,-0.25"));
        boolean threw = false;
        try { TabIcons.parse("M0 0A1 1 0 0 0 2 2", 1f, 0f, new Box()); } catch (IllegalArgumentException e) { threw = true; }
        check("arcs are rejected", threw);
    }

    static void tabsAreNamedByEnumFirst() {
        check("Discovery is explore", TabIcons.kind("Discovery", "随便") == TabIcons.EXPLORE);
        check("Home is the person tab (我的)", TabIcons.kind("Home", "我的") == TabIcons.PERSON);
        check("ListenVideo is the note", TabIcons.kind("ListenVideo", null) == TabIcons.NOTE);
        check("unknown enum falls back to title", TabIcons.kind("Brand-new", "会员") == TabIcons.DIAMOND);
        check("no enum, 我的", TabIcons.kind(null, "我的") == TabIcons.PERSON);
        check("unknown title is a dot", TabIcons.kind(null, "xyz") == TabIcons.DOT);
    }

    /** Runs {@code m} at {@code hz} until it rests; returns seconds, or -1 after 3 s. */
    static float settle(TabMotion m, float hz, float[] peaks) {
        float t = 0f;
        while (t < 3f) {
            boolean moving = m.step(1f / hz);
            t += 1f / hz;
            if (peaks != null) {
                peaks[0] = Math.max(peaks[0], m.press);
                peaks[1] = Math.max(peaks[1], m.stretchX());
                peaks[2] = Math.max(peaks[2], Math.abs(m.panel));
            }
            check0(!Float.isNaN(m.value) && !Float.isNaN(m.press) && !Float.isNaN(m.stretchX()) && !Float.isNaN(m.stretchY()));
            if (!moving) return t;
        }
        return -1f;
    }

    static void tapGlidesLiftsStretchesAndDrops() {
        TabMotion m = new TabMotion();
        m.layout(3, 900f, 12f);
        m.jump(0);
        m.select(2);
        float[] peaks = new float[3];
        float t = settle(m, 120f, peaks);
        check("tap settles within a second (" + t + "s)", t > 0f && t < 1f);
        check("tap ends on the target", m.value == 2f && m.target == 2f);
        check("bubble lifted during the glide (" + peaks[0] + ")", peaks[0] > 0.9f);
        check("bubble stretched with speed (" + peaks[1] + ")", peaks[1] > TabMotion.LIFT * 1.05f);
        check("drops back to a flat platter", m.press == 0f && m.scaleX == 1f && m.scaleY == 1f && !m.lifted());
        // The lift springs (damping 0.6/0.7) overshoot LIFT by under 12%.
        check("stretch bounded (" + peaks[1] + ")", peaks[1] <= TabMotion.LIFT * 1.12f / (1f - TabMotion.STRETCH));
        System.out.println("  tap 0→2: settle " + t + "s, press peak " + peaks[0] + ", stretchX peak " + peaks[1]);
    }

    static void motionIsFrameRateIndependent() {
        float[] times = new float[3];
        float[] hz = {60f, 90f, 120f};
        for (int i = 0; i < 3; i++) {
            TabMotion m = new TabMotion();
            m.layout(4, 1000f, 12f);
            m.jump(0);
            m.select(3);
            times[i] = settle(m, hz[i], null);
        }
        check("60/90/120 Hz settle alike (" + times[0] + ", " + times[1] + ", " + times[2] + ")",
            times[0] > 0 && Math.abs(times[0] - times[2]) < 0.06f && Math.abs(times[1] - times[2]) < 0.06f);
    }

    static void tapOnSelectedTabStillPulses() {
        TabMotion m = new TabMotion();
        m.layout(3, 900f, 12f);
        m.jump(1);
        m.select(1);
        float[] peaks = new float[3];
        float t = settle(m, 120f, peaks);
        check("tap on the selected tab lifts visibly (" + peaks[0] + ")", peaks[0] > 0.5f);
        check("…and settles (" + t + "s)", t > 0f && t < 0.8f && m.value == 1f && m.press == 0f);
    }

    static void dragCarriesBubbleAndSelectsNearest() {
        TabMotion m = new TabMotion();
        m.layout(4, 1000f, 12f);
        m.jump(0);
        m.down(0.1f);
        for (int i = 0; i < 30; i++) { m.drag(0.1f + i * 0.05f, 10f); m.step(1f / 120f); }
        // A finger moving at 6 tabs/s: the bubble trails it (critically damped, stiffness 1000)…
        check("bubble trails a fast finger (" + m.value + ")", m.value > 0.9f && m.value < 1.55f && m.lifted() && m.dragging());
        for (int i = 0; i < 40; i++) m.step(1f / 120f);
        // …and catches up once it rests.
        check("bubble reaches a resting finger (" + m.value + ")", Math.abs(m.value - 1.55f) < 0.01f);
        check("the capsule leans toward the drag", m.panel > 0f && m.panel <= 12f);
        int chosen = m.up();
        check("release picks the nearest tab", chosen == 2);
        float t = settle(m, 120f, null);
        check("then settles on it (" + t + "s)", t > 0f && m.value == 2f && m.press == 0f && m.panel == 0f);
    }

    static void cancelReturnsToSelection() {
        TabMotion m = new TabMotion();
        m.layout(3, 900f, 12f);
        m.jump(2);
        m.down(1.8f);
        m.drag(0.3f, -500f);
        for (int i = 0; i < 10; i++) m.step(1f / 120f);
        m.cancel(2);
        settle(m, 120f, null);
        check("cancel goes back to the selected tab", m.value == 2f && !m.dragging() && m.press == 0f);
    }

    static void rubberBandIsBoundedAndSettles() {
        TabMotion m = new TabMotion();
        m.layout(3, 600f, 12f);
        m.jump(0);
        m.down(0f);
        for (int i = 0; i < 100; i++) { m.drag(2f, 50f); m.step(1f / 120f); }
        check("rubber band never passes its maximum (" + m.panel + ")", m.panel <= 12f + 1e-4f && m.panel > 11f);
        m.up();
        settle(m, 120f, null);
        check("rubber band springs back", m.panel == 0f);
    }

    static void hiddenSelectionFadesBubble() {
        TabMotion m = new TabMotion();
        m.layout(3, 900f, 12f);
        m.jump(1);
        m.visible(false);
        settle(m, 120f, null);
        check("bubble fades when the round button owns the selection", m.shown == 0f);
        m.visible(true);
        settle(m, 120f, null);
        check("and returns", m.shown == 1f);
    }

    /** The bar repaints only while springs move: a finger held still keeps time but changes nothing. */
    static void heldBubbleStopsSpringing() {
        TabMotion m = new TabMotion();
        m.layout(3, 900f, 12f);
        m.jump(1);
        m.down(1f);
        boolean sprang = false;
        for (int i = 0; i < 12; i++) { m.step(1f / 120f); sprang |= m.springing; }
        check("a press springs the bubble up", sprang);
        boolean held = true;
        for (int i = 0; i < 240; i++) held &= m.step(1f / 120f);
        check("a held press still asks for frames", held);
        check("…but no spring moves once it is up (" + m.press + ")", !m.springing && m.press == 1f);
        m.up();
        float t = settle(m, 120f, null);
        check("released, it settles (" + t + "s)", t > 0f && !m.springing && m.press == 0f);
    }

    private static void check0(boolean ok) { if (!ok) { failed++; System.out.println("FAIL: NaN in motion"); } }

    private static void check(String what, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + what); }
    }
}

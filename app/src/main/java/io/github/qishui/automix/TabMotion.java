package io.github.qishui.automix;

/**
 * Motion of the floating bar's selection, after the iOS 26 tab bar as recreated in Kyant's
 * LiquidBottomTabs and DampedDragAnimation (https://github.com/Kyant0/AndroidLiquidGlass catalog,
 * Apache-2.0, Copyright 2025 Kyant). At rest the selection is a flat platter under one tab; a touch
 * lifts it into a lens bubble ({@link #press} 0 → 1, scale up to 78/56 with slightly different
 * X and Y springs, so it wobbles like gel), the bubble follows the finger or glides to a tapped tab
 * and stretches along its motion, and it drops back once it is within 2.5% of its target.
 *
 * <p>Positions are fractional tab indices. Pure Java and frame-rate independent, so it can be
 * tested on the JVM; the caller steps it once per frame with the frame's time.
 */
final class TabMotion {
    static final float LIFT = 78f / 56f;
    /** Shortest lift for a tap, so a tap on the selected tab still reads as a press. */
    static final float HOLD = 0.12f;
    /** Most the bubble stretches with speed (Kyant uses 0.2; a little calmer here). */
    static final float STRETCH = 0.15f;
    private static final float H = 1f / 240f;

    /** The bubble's centre as a fractional tab index, and the index it is heading to. */
    float value, target;
    /** 0 = flat platter, 1 = lifted lens. */
    float press;
    /** Lift scale before the motion stretch. */
    float scaleX = 1f, scaleY = 1f;
    /** Smoothed speed in tab ranges per second (signed). */
    float velocity;
    /** The capsule's rubber-band shift toward a drag, in px. */
    float panel;
    /** Interactive highlight: strength 0..1. */
    float glow;
    /** 1 while some capsule tab is selected, fades to 0 when the selection leaves the capsule. */
    float shown = 1f;
    /** Whether the last {@link #step} moved any spring (a held, motionless bubble only keeps time). */
    boolean springing;

    private float valueV, pressV, scaleXV, scaleYV, velocityV, panelV, glowV, shownV;
    private float drift, maxPanel = 1f, width = 1f, held;
    private int count = 1;
    private boolean lifted, dragging, releasing, placed, visible = true;

    /** Number of tabs in the capsule and the capsule width (px) for the rubber band (max 4dp). */
    void layout(int tabs, float capsuleWidth, float maxShift) {
        count = Math.max(1, tabs);
        width = Math.max(1f, capsuleWidth);
        maxPanel = maxShift;
        float max = count - 1;
        if (target > max) target = max;
        if (value > max) value = max;
    }

    int count() { return count; }

    /** First placement or a re-layout: no animation. */
    void jump(int index) {
        value = target = clamp(index);
        valueV = velocity = velocityV = 0f;
        placed = true;
    }

    boolean placed() { return placed; }

    /** Whether the capsule currently owns the selection (false while the round button is selected). */
    void visible(boolean on) { visible = on; }

    /** A tap or a selection made elsewhere: lift, glide to {@code index}, drop near it. */
    void select(int index) {
        if (!placed) { jump(index); return; }
        dragging = false;
        target = clamp(index);
        lift();
        releasing = true;
    }

    /** Finger down at fractional index {@code at}. */
    void down(float at) {
        if (!placed) jump(Math.round(clamp(at)));
        dragging = true;
        releasing = false;
        drift = 0f;
        target = clamp(at);
        lift();
    }

    /** Finger moved to fractional index {@code at}, {@code dx} px since the last event. */
    void drag(float at, float dx) {
        if (!dragging) return;
        target = clamp(at);
        drift += dx;
    }

    /** Finger up: settles on the nearest tab and returns it. */
    int up() {
        int index = Math.round(clamp(target));
        dragging = false;
        drift = 0f;
        target = index;
        releasing = true;
        return index;
    }

    /** Gesture cancelled: back to {@code selected}. */
    void cancel(int selected) {
        dragging = false;
        drift = 0f;
        if (selected >= 0) target = clamp(selected);
        releasing = true;
    }

    boolean dragging() { return dragging; }

    boolean lifted() { return lifted; }

    /** Index the bubble will settle on. */
    int settling() { return Math.round(clamp(target)); }

    private void lift() {
        if (!lifted) held = 0f;
        lifted = true;
    }

    private float clamp(float at) { return Math.max(0f, Math.min(count - 1, at)); }

    /** Advances by {@code dt} seconds; returns true while anything still moves. */
    boolean step(float dt) {
        dt = Math.max(0f, Math.min(dt, 0.05f));
        if (lifted) held += dt;
        // Drop once the bubble is close to where it is going (Kyant: 2.5% of the range), but show a
        // tap on the tab that is already selected for a moment instead of a one-frame flicker.
        if (releasing && lifted && !dragging) {
            float range = Math.max(1, count - 1);
            if (Math.abs(value - target) < range * 0.025f && held >= HOLD) { lifted = false; releasing = false; }
        }
        boolean moving = false;
        float before = value;
        moving |= spring(0, target, 1000f, 1f, dt, 0.001f);
        float range = Math.max(1, count - 1);
        float raw = dt > 0 ? (value - before) / dt / range : 0f;
        moving |= spring(1, dragging || lifted && Math.abs(value - target) > 0.001f ? raw : 0f, 300f, 0.5f, dt, 0.01f);
        // Rest thresholds stop the frames once the change is under ~0.3dp on a 90dp bubble.
        moving |= spring(2, lifted ? 1f : 0f, 1000f, 1f, dt, 0.003f);
        moving |= spring(3, lifted ? LIFT : 1f, 250f, 0.6f, dt, 0.004f);
        moving |= spring(4, lifted ? LIFT : 1f, 250f, 0.7f, dt, 0.004f);
        float fraction = Math.max(-1f, Math.min(1f, drift / width));
        float pull = maxPanel * Math.signum(fraction) * easeOut(Math.abs(fraction));
        moving |= dragging ? snap(5, pull) : spring(5, 0f, 300f, 1f, dt, 0.05f);
        moving |= spring(6, dragging || lifted ? 1f : 0f, 300f, 0.5f, dt, 0.004f);
        moving |= spring(7, visible ? 1f : 0f, 500f, 1f, dt, 0.002f);
        springing = moving;
        return moving || lifted || dragging;
    }

    /** Horizontal stretch from speed, any direction (Kyant's demo leaned only one way). */
    float stretchX() { return scaleX / (1f - Math.min(STRETCH, Math.abs(velocity) / 10f * 0.75f)); }

    float stretchY() { return scaleY * (1f - Math.min(STRETCH, Math.abs(velocity) / 10f * 0.25f)); }

    /** Ease-out (Compose EaseOut, cubic-bezier(0, 0, 0.58, 1)) approximated by its curve. */
    private static float easeOut(float t) {
        // Solve x(s) = t for the bezier parameter, then return y(s).
        float s = t;
        for (int i = 0; i < 6; i++) {
            float x = bezier(s, 0f, 0.58f) - t, dx = derivative(s, 0f, 0.58f);
            if (Math.abs(dx) < 1e-5f) break;
            s = Math.max(0f, Math.min(1f, s - x / dx));
        }
        return bezier(s, 0f, 1f);
    }

    private static float bezier(float s, float p1, float p2) {
        float u = 1f - s;
        return 3f * u * u * s * p1 + 3f * u * s * s * p2 + s * s * s;
    }

    private static float derivative(float s, float p1, float p2) {
        float u = 1f - s;
        return 3f * u * u * p1 + 6f * u * s * (p2 - p1) + 3f * s * s * (1f - p2);
    }

    private boolean snap(int which, float goal) {
        boolean moved = get(which) != goal;
        set(which, goal, 0f);
        return moved;
    }

    /** Damped spring of {@code which} toward {@code goal}: stiffness k, damping ratio zeta. */
    private boolean spring(int which, float goal, float k, float zeta, float dt, float rest) {
        float x = get(which), v = getV(which);
        float c = 2f * zeta * (float) Math.sqrt(k);
        for (float left = dt; left > 0f; left -= H) {
            float h = Math.min(left, H);
            v += (-k * (x - goal) - c * v) * h;
            x += v * h;
        }
        if (Math.abs(x - goal) < rest && Math.abs(v) < rest * 10f) { set(which, goal, 0f); return false; }
        set(which, x, v);
        return true;
    }

    private float get(int which) {
        switch (which) {
            case 0: return value;
            case 1: return velocity;
            case 2: return press;
            case 3: return scaleX;
            case 4: return scaleY;
            case 5: return panel;
            case 6: return glow;
            default: return shown;
        }
    }

    private float getV(int which) {
        switch (which) {
            case 0: return valueV;
            case 1: return velocityV;
            case 2: return pressV;
            case 3: return scaleXV;
            case 4: return scaleYV;
            case 5: return panelV;
            case 6: return glowV;
            default: return shownV;
        }
    }

    private void set(int which, float x, float v) {
        switch (which) {
            case 0: value = x; valueV = v; break;
            case 1: velocity = x; velocityV = v; break;
            case 2: press = x; pressV = v; break;
            case 3: scaleX = x; scaleXV = v; break;
            case 4: scaleY = x; scaleYV = v; break;
            case 5: panel = x; panelV = v; break;
            case 6: glow = x; glowV = v; break;
            default: shown = x; shownV = v; break;
        }
    }
}

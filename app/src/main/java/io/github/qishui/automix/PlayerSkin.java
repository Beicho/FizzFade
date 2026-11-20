package io.github.qishui.automix;

import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

/**
 * Re-skins one play page card without taking over any of it: a flowing backdrop above the host's
 * cover-colour background and a cover that rounds into an M3 Expressive shape and springs smaller
 * while paused. All host views stay
 * where they are and keep every touch; only drawing properties change, and detach() restores them.
 */
final class PlayerSkin implements Choreographer.FrameCallback {
    interface Host { void failed(String stage, Throwable error); }

    static final float PAUSED_SCALE = 0.86f;

    private final ViewGroup root;
    private final View background, cover;
    private final Host host;
    private final float dp;
    private LyricsBackdrop backdrop;

    // Cover springs: 0 scale, 1 corner radius (dp), 2 elevation (dp).
    private final float[] x = new float[3], v = new float[3];
    private boolean coverOn, playing = true, queued, attached;
    private long lastFrame;
    private ViewOutlineProvider savedOutline;
    private boolean savedClip;
    private float savedElevation, savedScaleX, savedScaleY;
    private final int[] at = new int[2];

    private final ViewTreeObserver.OnPreDrawListener sync = this::sync;
    private ViewTreeObserver syncing;
    // The sync runs from the window's pre-draw list only while the card is in a window; the host
    // drops cards without telling us, and a listener left on the window would run forever.
    private final View.OnAttachStateChangeListener scope = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View v) { startSync(v); }
        @Override public void onViewDetachedFromWindow(View v) { stopSync(); }
    };
    private final ViewOutlineProvider outline = new ViewOutlineProvider() {
        @Override public void getOutline(View view, Outline out) {
            out.setRoundRect(0, 0, view.getWidth(), view.getHeight(), x[1] * dp);
        }
    };

    PlayerSkin(ViewGroup root, View background, View cover, Host host) {
        this.root = root; this.background = background; this.cover = cover; this.host = host;
        dp = root.getResources().getDisplayMetrics().density;
    }

    /** Adds the requested layers; returns a short description for the log. */
    String attach(boolean flowing, boolean shape) {
        attached = true;
        int index = background != null && background.getParent() == root ? root.indexOfChild(background) + 1 : 0;
        if (flowing && background != null) {
            backdrop = new LyricsBackdrop(root.getContext(), () -> host.failed("backdrop", new IllegalStateException("backdrop draw failed")));
            backdrop.setId(View.generateViewId());
            backdrop.yieldToLyrics();
            backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(backdrop, index, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            backdrop.follow(source());
        }
        if (shape && cover != null) {
            coverOn = true;
            savedOutline = cover.getOutlineProvider();
            savedClip = cover.getClipToOutline();
            savedElevation = cover.getElevation();
            savedScaleX = cover.getScaleX();
            savedScaleY = cover.getScaleY();
            x[0] = playing ? 1f : PAUSED_SCALE; x[1] = radius(playing); x[2] = elevation(playing);
            cover.setOutlineProvider(outline);
            cover.setClipToOutline(true);
            if (Build.VERSION.SDK_INT >= 28) { cover.setOutlineSpotShadowColor(Color.BLACK); cover.setOutlineAmbientShadowColor(Color.BLACK); }
            apply();
        }
        root.addOnAttachStateChangeListener(scope);
        if (root.isAttachedToWindow()) startSync(root);
        return "backdrop=" + (backdrop != null) + " cover=" + coverOn;
    }

    private void startSync(View v) {
        if (!attached || syncing != null) return;
        syncing = v.getViewTreeObserver();
        syncing.addOnPreDrawListener(sync);
    }

    private void stopSync() {
        if (syncing != null && syncing.isAlive()) syncing.removeOnPreDrawListener(sync);
        syncing = null;
    }

    private Drawable source() {
        if (background instanceof ImageView) {
            Drawable image = ((ImageView) background).getDrawable();
            if (image != null) return image;
        }
        return background == null ? null : background.getBackground();
    }

    private static float radius(boolean playing) { return playing ? 28f : 40f; }
    private static float elevation(boolean playing) { return playing ? 18f : 6f; }

    /** From the player listener (host) or the demo; animates the cover toward the new state. */
    void playing(boolean value) {
        if (playing == value) return;
        playing = value;
        wake();
    }

    boolean animating() { return (backdrop != null && backdrop.animating()) || queued; }

    private boolean sync() {
        try {
            if (!attached) return true;
            if (backdrop != null) { backdrop.follow(source()); backdrop.resume(); }
        } catch (Throwable error) { host.failed("sync", error); }
        return true;
    }

    private void wake() {
        if (!attached || !coverOn || queued) return;
        queued = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long now) {
        queued = false;
        if (!attached || !coverOn) return;
        try {
            float dt = lastFrame == 0 ? 1f / 120f : Math.min(0.05f, (now - lastFrame) / 1e9f);
            lastFrame = now;
            // Expressive spatial spring for size, a stiffer one for the shape and a soft shadow.
            boolean moving = PlayerModel.spring(x, v, 0, playing ? 1f : PAUSED_SCALE, 170f, 0.78f, dt, 0.0008f);
            moving |= PlayerModel.spring(x, v, 1, radius(playing), 260f, 0.9f, dt, 0.05f);
            moving |= PlayerModel.spring(x, v, 2, elevation(playing), 120f, 1f, dt, 0.05f);
            apply();
            if (moving) { queued = true; Choreographer.getInstance().postFrameCallback(this); }
        } catch (Throwable error) { host.failed("cover", error); }
    }

    private void apply() {
        cover.setPivotX(cover.getWidth() / 2f);
        cover.setPivotY(cover.getHeight() / 2f);
        cover.setScaleX(x[0]);
        cover.setScaleY(x[0]);
        cover.setElevation(x[2] * dp);
        cover.invalidateOutline();
    }

    /** Removes every layer and gives the cover back its own outline, clip, shadow and scale. */
    void detach() {
        if (!attached) return;
        attached = false;
        if (queued) Choreographer.getInstance().removeFrameCallback(this);
        queued = false;
        root.removeOnAttachStateChangeListener(scope);
        stopSync();
        if (backdrop != null) { root.removeView(backdrop); backdrop = null; }
        if (coverOn) {
            coverOn = false;
            cover.setOutlineProvider(savedOutline);
            cover.setClipToOutline(savedClip);
            cover.setElevation(savedElevation);
            cover.setScaleX(savedScaleX);
            cover.setScaleY(savedScaleY);
        }
    }

    View backdropView() { return backdrop; }
}

package io.github.qishui.automix;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Preview of the play page skin in the module app: a stand-in card laid out like the host's (a
 * full-size gradient image first, a cover, a title block, an action row, a seek bar, a bottom bar)
 * with a generated cover and original text, skinned by the same {@link PlayerSkin} and
 * {@link TabGlass}. Tap the cover to pause and play; tap a tab to move the selection.
 */
final class PlayerDemo {
    static void show(Activity activity) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        float d = activity.getResources().getDisplayMetrics().density;
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        FrameLayout root = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);   // like the host's container under the bottom bar
        root.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView background = new ImageView(activity);
        background.setScaleType(ImageView.ScaleType.FIT_XY);
        background.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Color.rgb(196, 92, 70), Color.rgb(70, 52, 120), Color.rgb(24, 26, 44)}));
        card.addView(background, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int side = width - Math.round(48 * d);
        FrameLayout cover = new FrameLayout(activity);
        ImageView art = new ImageView(activity);
        art.setImageBitmap(art(Math.min(side, 900)));
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams coverLp = new FrameLayout.LayoutParams(side, side, Gravity.CENTER_HORIZONTAL);
        coverLp.topMargin = Math.round(96 * d);
        card.addView(cover, coverLp);

        // The one-line lyric under the cover, fed like the host feeds it: a line, then times.
        LyricsClock shortClock = new LyricsClock();
        shortClock.playing(true, System.nanoTime());
        LyricsShortView shortLyric = new LyricsShortView(activity, shortClock);
        FrameLayout.LayoutParams shortLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(64 * d), Gravity.TOP);
        shortLp.topMargin = Math.round(96 * d) + side + Math.round(18 * d);
        shortLyric.setPadding(Math.round(32 * d), 0, Math.round(32 * d), 0);
        card.addView(shortLyric, shortLp);
        LyricsModel demoLyrics = LyricsDemo.model();
        long demoTotal = demoLyrics.lines[demoLyrics.lines.length - 1].end + 2500;
        long[] demoOrigin = {android.os.SystemClock.elapsedRealtime()};

        LinearLayout title = new LinearLayout(activity);
        title.setOrientation(LinearLayout.VERTICAL);
        title.addView(text(activity, "夜航", 26, Typeface.BOLD, 255));
        title.addView(text(activity, "演示歌手 · 演示专辑", 15, Typeface.NORMAL, 170));
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        titleLp.leftMargin = titleLp.rightMargin = Math.round(32 * d);
        titleLp.bottomMargin = Math.round(214 * d);
        card.addView(title, titleLp);

        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        for (String glyph : new String[] {"♡ 2.4万", "评论 318", "分享", "···"}) {
            TextView t = text(activity, glyph, 14, Typeface.NORMAL, 220);
            t.setPadding(0, 0, Math.round(22 * d), 0);
            actions.addView(t);
        }
        FrameLayout.LayoutParams actionsLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        actionsLp.leftMargin = actionsLp.rightMargin = Math.round(32 * d);
        actionsLp.bottomMargin = Math.round(168 * d);
        card.addView(actions, actionsLp);

        SeekBar seek = new SeekBar(activity);
        seek.setMax(1000);
        FrameLayout.LayoutParams seekLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(40 * d), Gravity.BOTTOM);
        seekLp.leftMargin = seekLp.rightMargin = Math.round(20 * d);
        seekLp.bottomMargin = Math.round(120 * d);
        card.addView(seek, seekLp);

        // Another tab's page, over the play page like the host's container above the bar: rows
        // that run under the bar, on the host's dark root gradient.
        android.widget.ScrollView page = new android.widget.ScrollView(activity);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Math.round(16 * d), Math.round(48 * d), Math.round(16 * d), Math.round(120 * d));
        int[] hues = {Color.rgb(255, 120, 90), Color.rgb(90, 160, 255), Color.rgb(120, 220, 140), Color.rgb(230, 110, 220), Color.rgb(255, 200, 80)};
        for (int i = 0; i < 18; i++) {
            TextView row = text(activity, "演示歌单 " + (i + 1), 17, Typeface.BOLD, 240);
            row.setPadding(Math.round(18 * d), Math.round(22 * d), 0, Math.round(22 * d));
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {hues[i % hues.length], Color.argb(40, 255, 255, 255)});
            bg.setCornerRadius(16 * d);
            row.setBackground(bg);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = Math.round(12 * d);
            rows.addView(row, rowLp);
        }
        page.addView(rows);
        page.setVisibility(View.GONE);
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {Color.rgb(13, 0, 47), Color.rgb(0, 0, 20)}));

        // A stand-in for the host's BottomNavigationView, skinned by the real TabHook code: tab
        // wrappers whose id is the nav id, clicks through one listener that tells select from
        // reselect, getSelectedItemId(), the per-tab setBackgroundColor and the attribution line.
        DemoBar bar = new DemoBar(activity);
        String[] tabs = {"发现", "听抖音", "汽水", "福利", "我的"};
        String[] enums = {"Discovery", "ListenVideo", "Play", "CommerceCoin", "Home"};
        java.util.Map<Integer, String> names = new java.util.HashMap<>();
        for (int i = 0; i < tabs.length; i++) {
            FrameLayout wrapper = new FrameLayout(activity);
            LinearLayout content = new LinearLayout(activity);
            content.setGravity(Gravity.CENTER);
            TextView t = text(activity, tabs[i], 15, Typeface.BOLD, 150);
            content.addView(t);
            if (i == 0) { TextView corner = text(activity, "新", 9, Typeface.BOLD, 255); content.addView(corner); }
            if (i == 4) { View dot = new View(activity); GradientDrawable red = new GradientDrawable(); red.setColor(Color.RED); red.setCornerRadius(4 * d); dot.setBackground(red); content.addView(dot, new LinearLayout.LayoutParams(Math.round(8 * d), Math.round(8 * d))); }
            wrapper.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            int id = 100 + i;
            wrapper.setId(id);
            names.put(id, enums[i]);
            wrapper.setOnClickListener(v -> bar.click(v.getId(), card, page));
            bar.items.put(id, wrapper);
            bar.addView(wrapper, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(100 * d), Gravity.BOTTOM));
        bar.select(102, card, page);
        HostDiagnostics demoDiagnostics = new HostDiagnostics();
        android.os.Bundle switches = new android.os.Bundle();
        android.content.SharedPreferences prefs = ConfigProvider.prefs(activity);
        for (String key : new String[] {"ui_tab", "ui_tab_face", "ui_tab_orb", "ui_tab_welfare", "ui_player", "ui_player_cover", "ui_player_bg", "ui_short_lyrics"})
            switches.putBoolean(key, prefs.getBoolean(key, true));
        demoDiagnostics.preview(switches);
        TabHook.demo(bar, demoDiagnostics, activity.getApplicationContext(), names);
        PlayerSkin skin = new PlayerSkin(card, background, cover,
            (stage, error) -> { LocalDiagnostics.error(activity, "player_demo_" + stage, error); dialog.dismiss(); });
        boolean[] playing = {true};
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (playing[0]) seek.setProgress((seek.getProgress() + 2) % 1000);
                long t = android.os.SystemClock.elapsedRealtime() - demoOrigin[0];
                if (t > demoTotal) { demoOrigin[0] = android.os.SystemClock.elapsedRealtime(); t = 0; }
                int index = demoLyrics.activeAt(t);
                if (index >= 0 && demoLyrics.lines[index].kind != LyricsModel.INTERLUDE) shortLyric.show(demoLyrics.lines[index]);
                if (playing[0]) { shortClock.sample(t, System.nanoTime()); shortLyric.wake(); }
                handler.postDelayed(this, 50);
            }
        };
        // What the host's RealtimeBlurView does every frame: copy the screen in software.
        Runnable capture = () -> {
            try {
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(Math.max(1, root.getWidth() / 4), Math.max(1, root.getHeight() / 4), android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas soft = new android.graphics.Canvas(bitmap);
                soft.scale(0.25f, 0.25f);
                root.draw(soft);
                LocalDiagnostics.event(activity, "player_demo_software_capture", "ok");
            } catch (Throwable error) { LocalDiagnostics.error(activity, "player_demo_software_capture", error); }
        };
        cover.setOnClickListener(v -> {
            playing[0] = !playing[0];
            skin.playing(playing[0]);
            LocalDiagnostics.event(activity, "player_demo_toggle", "playing=" + playing[0]);
        });
        dialog.setContentView(root);
        dialog.setOnShowListener(x -> {
            String layers = skin.attach(true, true);
            LocalDiagnostics.event(activity, "player_demo", layers);
            seek.setProgress(380);
            handler.post(tick);
            handler.postDelayed(capture, 1500);
        });
        dialog.setOnDismissListener(x -> { handler.removeCallbacks(tick); skin.detach(); });
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.show();
    }

    /**
     * Shaped like the host's bar where the skin looks: an id map, the selected id, per-tab colour,
     * select vs reselect through one click listener, and the attribution line it paints itself
     * (its only TextPaint and String fields).
     */
    static final class DemoBar extends LinearLayout {
        final java.util.Map<Integer, View> items = new java.util.LinkedHashMap<>();
        private Integer selected;
        private final android.text.TextPaint note = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private String attribution;

        DemoBar(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            note.setColor(Color.argb(130, 255, 255, 255));
            note.setTextSize(10 * context.getResources().getDisplayMetrics().scaledDensity);
            note.setTextAlign(Paint.Align.CENTER);
        }

        public Integer getSelectedItemId() { return selected; }

        public void setItemPaddingBottomExtra(int extra) { }

        void click(int id, View play, View page) {
            if (selected != null && selected == id) {
                LocalDiagnostics.event(getContext(), "player_demo_reselect", "id=" + id);
                return;
            }
            select(id, play, page);
        }

        void select(int id, View play, View page) {
            selected = id;
            for (java.util.Map.Entry<Integer, View> e : items.entrySet()) {
                TextView t = (TextView) ((ViewGroup) ((ViewGroup) e.getValue()).getChildAt(0)).getChildAt(0);
                t.setTextColor(e.getKey() == id ? Color.WHITE : Color.argb(150, 255, 255, 255));
            }
            boolean onPlay = id == 102;
            play.setVisibility(onPlay ? View.VISIBLE : View.GONE);
            page.setVisibility(onPlay ? View.GONE : View.VISIBLE);
            setBackgroundColor(onPlay ? Color.TRANSPARENT : Color.rgb(18, 18, 18));
            attribution = id == 101 ? "以上内容由 AI 辅助生成" : null;
            invalidate();
            LocalDiagnostics.event(getContext(), "player_demo_select", "id=" + id);
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (attribution != null) canvas.drawText(attribution, getWidth() / 2f, getHeight() - 6 * getResources().getDisplayMetrics().density, note);
        }
    }

    private static TextView text(Activity activity, String value, int sp, int style, int alpha) {
        TextView t = new TextView(activity);
        t.setText(value);
        t.setTextSize(sp);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        t.setTextColor(Color.argb(alpha, 255, 255, 255));
        t.setSingleLine(true);
        return t;
    }

    /** An abstract generated cover: warm-to-violet field, a sun disc and soft rings. No real artwork. */
    private static Bitmap art(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, size, size, new int[] {Color.rgb(255, 150, 96), Color.rgb(214, 74, 110), Color.rgb(64, 48, 140)}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(new RadialGradient(size * 0.62f, size * 0.42f, size * 0.24f, Color.argb(255, 255, 236, 200), Color.argb(0, 255, 236, 200), Shader.TileMode.CLAMP));
        c.drawCircle(size * 0.62f, size * 0.42f, size * 0.24f, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        for (int i = 1; i <= 5; i++) {
            p.setStrokeWidth(size * 0.006f);
            p.setColor(Color.argb(70 - i * 10, 255, 255, 255));
            c.drawCircle(size * 0.3f, size * 0.78f, size * 0.09f * i, p);
        }
        return bitmap;
    }
}

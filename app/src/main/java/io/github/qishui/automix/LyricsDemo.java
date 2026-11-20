package io.github.qishui.automix;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

/** Full-screen preview of the lyric page in the module app, with demo lines and a looping clock. */
final class LyricsDemo {
    private static final String[][] LINES = {
        {"夜色把城市调成慢速", null},
        {"Every light is humming low", "每盏灯都在低声哼唱"},
        {"我们在副歌里靠近", null},
        {"Hold the note and let it glow", "把这个音停住，让它发光"},
        {"", null},
        {"下一首已经在路上", null},
        {"Fade in, fade out, never stop", "淡入，淡出，从不停下"},
    };

    static void show(Activity activity) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(activity);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Color.rgb(120, 52, 86), Color.rgb(38, 46, 104), Color.rgb(20, 22, 40)}));
        LyricsClock clock = new LyricsClock();
        LyricsModel model = model();
        long total = model.lines[model.lines.length - 1].end + 2500;
        Handler handler = new Handler(Looper.getMainLooper());
        long[] origin = {SystemClock.elapsedRealtime()};
        LyricsView[] holder = new LyricsView[1];
        LyricsView view = new LyricsView(activity, new LyricsView.Host() {
            @Override public void seek(LyricsModel.Line line) {
                LocalDiagnostics.event(activity, "lyrics_demo_seek", "line=" + line.hostIndex + " start=" + line.start);
                origin[0] = SystemClock.elapsedRealtime() - line.start;
                clock.jump(line.start, System.nanoTime());
                holder[0].wake();
            }
            @Override public void failed(String stage, Throwable error) {
                LocalDiagnostics.error(activity, "lyrics_demo_" + stage, error);
                dialog.dismiss();
            }
            @Override public void stats(String detail) { LocalDiagnostics.event(activity, "lyrics_demo_frames", detail); }
        }, clock);
        holder[0] = view;
        LyricsBackdrop backdrop = new LyricsBackdrop(activity, () -> { });
        root.addView(backdrop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams area = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        area.topMargin = Math.round(96 * activity.getResources().getDisplayMetrics().density);
        area.bottomMargin = Math.round(120 * activity.getResources().getDisplayMetrics().density);
        root.addView(view, area);
        backdrop.follow(root);
        // Feeds the clock like the host does: a coarse report every 250 ms, looping.
        Runnable tick = new Runnable() {
            @Override public void run() {
                long t = SystemClock.elapsedRealtime() - origin[0];
                if (t > total) { origin[0] = SystemClock.elapsedRealtime(); t = 0; }
                clock.sample(t, System.nanoTime());
                view.wake();
                handler.postDelayed(this, 250);
            }
        };
        dialog.setContentView(root);
        dialog.setOnShowListener(d -> {
            view.post(() -> view.show(LyricsView.layout(activity, model, view.getWidth())));
            clock.playing(true, System.nanoTime());
            handler.post(tick);
        });
        dialog.setOnDismissListener(d -> handler.removeCallbacks(tick));
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dialog.show();
    }

    static LyricsModel model() {
        List<LyricsModel.Source> sources = new ArrayList<>();
        long t = 1500;
        int index = 0;
        for (String[] line : LINES) {
            if (line[0].isEmpty()) { t += 5200; continue; }
            LyricsModel.Source s = new LyricsModel.Source();
            s.hostIndex = index++;
            s.text = line[0];
            s.translation = line[1];
            s.words = new ArrayList<>();
            List<long[]> spans = new ArrayList<>();
            boolean latin = !LyricsModel.cjk(line[0], 0);
            String[] parts = latin ? line[0].split("(?<= )") : line[0].split("");
            long start = t;
            for (String part : parts) {
                if (part.isEmpty()) continue;
                long d = latin ? 260L + 40L * part.trim().length() : 330L;
                if (part.trim().equals("glow")) d = 1800;
                // Split like the host: one entry per character, duration shared evenly.
                String word = part.trim();
                for (int i = 0; i < word.length(); i++) {
                    s.words.add(String.valueOf(word.charAt(i)));
                    spans.add(new long[] {t + d * i / word.length(), t + d * (i + 1) / word.length()});
                }
                if (part.endsWith(" ")) { s.words.add(" "); spans.add(new long[] {t + d, t + d}); }
                t += d;
            }
            s.wordStart = new long[spans.size()];
            s.wordEnd = new long[spans.size()];
            for (int i = 0; i < spans.size(); i++) { s.wordStart[i] = spans.get(i)[0]; s.wordEnd[i] = spans.get(i)[1]; }
            s.start = start; s.end = t;
            t += 700;
            sources.add(s);
        }
        return LyricsModel.build(sources);
    }
}

package io.github.qishui.automix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The plain fallback screen: platform widgets only, generated from {@link SettingsSchema}. The
 * settings app opens it instead of itself after the app crashed, so the switches stay reachable.
 */
public final class SettingsActivity extends Activity {
    private static final int REQUEST_EXPORT = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> dependents = new ArrayList<>();
    private SharedPreferences prefs;
    private LinearLayout content;
    private int ink, muted, accent, warn;
    private TextView statusTitle, statusDetail, statusMeta, warning, logSize;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 2000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = ConfigProvider.prefs(this);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ink = night ? Color.rgb(230, 232, 236) : Color.rgb(24, 26, 30);
        muted = night ? Color.rgb(150, 154, 162) : Color.rgb(110, 114, 122);
        accent = night ? Color.rgb(79, 156, 255) : Color.rgb(0, 112, 240);
        warn = night ? Color.rgb(255, 170, 110) : Color.rgb(200, 90, 20);
        LocalDiagnostics.event(this, "settings_open", "module=" + HostDiagnostics.VERSION + " plain=true api=" + Build.VERSION.SDK_INT);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(night ? Color.BLACK : Color.rgb(247, 247, 247));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, dp(24), pad, dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            content.setPadding(pad, dp(20) + insets.getSystemWindowInsetTop(), pad, dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        build(night);
    }

    private void build(boolean night) {
        int card = night ? Color.rgb(28, 28, 30) : Color.WHITE;
        TextView title = label("叠拍 FizzFade · 简易界面", 24, ink);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);
        // Opened in place of the app after a crash, or by choice from 更多 (the app is still below).
        boolean crashed = prefs.getBoolean(ModuleActions.UI_FAILED, false);
        TextView retry = label(crashed ? "新界面上次异常退出，原因已写入日志。点此重新打开新界面" : "点此返回新界面", 13, accent);
        retry.setPadding(0, dp(6), 0, dp(12));
        retry.setOnClickListener(v -> {
            if (crashed) {
                prefs.edit().putBoolean(ModuleActions.UI_FAILED, false).commit();
                startActivity(new Intent(this, io.github.qishui.automix.ui.MainActivity.class));
            }
            finish();
        });
        content.addView(retry);

        LinearLayout status = section(card);
        statusTitle = label("", 18, ink);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.addView(statusTitle);
        statusDetail = label("", 14, muted);
        status.addView(statusDetail);
        warning = label("", 13, warn);
        status.addView(warning);
        statusMeta = label("", 12, muted);
        status.addView(statusMeta);

        String[] names = {"混音", "歌词", "播放页", "底栏", "精简", "性能"};
        for (SettingsSchema.Group group : SettingsSchema.Group.values()) {
            LinearLayout box = section(card);
            TextView head = label(names[group.ordinal()], 13, accent);
            head.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            box.addView(head);
            for (SettingsSchema.Toggle toggle : SettingsSchema.group(group)) {
                toggle(box, toggle);
                if (toggle.key.equals("enabled")) duration(box);
            }
        }

        LinearLayout logs = section(card);
        logSize = label("", 13, muted);
        logs.addView(logSize);
        logs.addView(button("导出日志文件", this::exportLog));
        logs.addView(button("分享日志", () -> ModuleActions.share(this)));
        logs.addView(button("清空日志", () -> new AlertDialog.Builder(this).setTitle("清空诊断日志？")
            .setMessage("仅清空模块保存的日志，LSPosed 日志不受影响。").setNegativeButton("取消", null)
            .setPositiveButton("清空", (d, w) -> { ModuleActions.clearLog(this); updateStatus(); }).show()));
        if (Build.VERSION.SDK_INT >= 29) {
            logs.addView(button("预览歌词效果", () -> LyricsDemo.show(this)));
            logs.addView(button("预览播放页效果", () -> PlayerDemo.show(this)));
        }
        syncDependents();
    }

    @Override protected void onResume() {
        super.onResume();
        ModuleActions.grantHost(this);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void updateStatus() {
        if (statusTitle == null) return;
        ModuleStatus s = ModuleStatus.read(this);
        statusTitle.setText(s.title() + " · 混音 " + s.completed + " 次 · 对拍 " + s.beatCompleted + " 次");
        statusDetail.setText(s.detail.isEmpty() ? "请在 LSPosed 启用模块后重新打开汽水" : s.detail);
        String notes = s.warnings();
        String hint = s.restartHint();
        if (!hint.isEmpty()) notes = notes.isEmpty() ? hint : notes + "\n" + hint;
        warning.setText(notes);
        warning.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        statusMeta.setText("模块 " + HostDiagnostics.VERSION + " · 汽水 " + (s.hostVersion == null ? "—" : s.hostVersion)
            + (s.lastSeen == 0 ? "" : " · 最近 " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(s.lastSeen))));
        int chars = ModuleActions.logChars(this);
        logSize.setText(chars == 0 ? "暂无日志" : "已保存约 " + Math.max(1, chars / 1024) + " KB 日志");
    }

    private void toggle(LinearLayout box, SettingsSchema.Toggle toggle) {
        Switch view = new Switch(this);
        view.setText(toggle.title + "\n" + toggle.summary + (toggle.restart ? "（重启汽水后生效）" : ""));
        view.setTextColor(ink);
        view.setTextSize(15);
        view.setPadding(0, dp(10), 0, dp(10));
        view.setChecked(prefs.getBoolean(toggle.key, toggle.fallback));
        view.setOnCheckedChangeListener((button, checked) -> {
            ModuleActions.setBoolean(this, toggle.key, checked);
            syncDependents();
        });
        box.addView(view);
        dependents.add(() -> {
            boolean on = ModuleActions.available(prefs, toggle);
            view.setEnabled(on);
            view.setAlpha(on ? 1f : 0.4f);
        });
    }

    private void duration(LinearLayout box) {
        TextView value = label("", 15, ink);
        SeekBar seek = new SeekBar(this);
        seek.setMax(10);
        seek.setProgress(ModuleActions.duration(this) / 1000 - 2);
        seek.setProgressTintList(ColorStateList.valueOf(accent));
        value.setText("过渡时长 " + (seek.getProgress() + 2) + " 秒");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                value.setText("过渡时长 " + (progress + 2) + " 秒");
                if (user) ModuleActions.setDuration(SettingsActivity.this, (progress + 2) * 1000);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        box.addView(value);
        box.addView(seek);
        dependents.add(() -> {
            boolean on = prefs.getBoolean("enabled", true);
            seek.setEnabled(on);
            value.setAlpha(on ? 1f : 0.4f);
        });
    }

    private void syncDependents() { for (Runnable sync : dependents) sync.run(); }

    private void exportLog() {
        try { startActivityForResult(ModuleActions.exportIntent(), REQUEST_EXPORT); }
        catch (ActivityNotFoundException error) { ModuleActions.toast(this, "系统缺少文件选择器，请改用分享"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        ModuleActions.toast(this, ModuleActions.writeExport(this, data.getData()) ? "日志已导出" : "导出失败，请改用分享");
    }

    private LinearLayout section(int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(16));
        box.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        content.addView(box, params);
        return box;
    }

    private TextView button(String text, Runnable action) {
        TextView view = label(text, 15, accent);
        view.setPadding(0, dp(12), 0, dp(12));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

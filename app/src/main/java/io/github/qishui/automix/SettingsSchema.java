package io.github.qishui.automix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every switch the module has, in one place. The settings app, the plain fallback screen, the
 * provider and the host-side cache all read this list, so a new switch is added exactly once.
 * Keys starting with {@code ui_} bump {@code ui_epoch} when changed (clears the host crash guard)
 * and do not depend on the mix master switch.
 */
public final class SettingsSchema {
    public static final String DURATION = "duration", EPOCH = "ui_epoch";
    public static final int DURATION_MIN = 2000, DURATION_MAX = 12000;

    public enum Group { MIX, LYRICS, PLAYER, TAB, TRIM, PERF }

    public static final class Toggle {
        public final String key, title, summary, parent;
        public final Group group;
        public final boolean fallback, restart;
        Toggle(Group group, String key, String title, String summary, String parent, boolean restart) {
            this.group = group; this.key = key; this.title = title; this.summary = summary;
            this.parent = parent; this.restart = restart; fallback = true;
        }
        public boolean ui() { return key.startsWith("ui_"); }
    }

    public static final List<Toggle> TOGGLES = Collections.unmodifiableList(Arrays.asList(
        new Toggle(Group.MIX, "enabled", "自动交叉混音", "曲尾两首歌重叠，平滑淡出淡入", null, false),
        new Toggle(Group.MIX, "adaptive", "智能过渡时机", "淡出处提前接歌，等新歌起音再淡入", "enabled", false),
        new Toggle(Group.MIX, "beatmatch", "对拍与轻微变速", "实验 · 节奏相近时对齐拍点，过后回原速", "adaptive", false),
        new Toggle(Group.MIX, "smooth", "平滑曲线", "限制两轨音量之和；关闭为线性淡变", "enabled", false),
        new Toggle(Group.MIX, "prerender", "预渲染下一首", "提前准备下一首，略增流量与内存", "enabled", true),

        new Toggle(Group.LYRICS, "ui_lyrics", "逐字歌词页", "替换全屏歌词页，关闭即恢复原生", null, true),
        new Toggle(Group.LYRICS, "ui_lyrics_word", "逐字扫字", "有逐字时间的歌曲逐字点亮，否则逐行高亮", "ui_lyrics", false),
        new Toggle(Group.LYRICS, "ui_lyrics_seek", "点歌词跳转", "点一行歌词从这里播放", "ui_lyrics", true),
        new Toggle(Group.LYRICS, "ui_lyrics_bg", "封面流动背景", "用封面颜色铺满歌词页并缓慢流动", "ui_lyrics", false),
        new Toggle(Group.LYRICS, "ui_short_lyrics", "播放页短歌词", "封面下那行歌词逐字点亮，换行上浮", null, false),

        new Toggle(Group.PLAYER, "ui_player", "播放页换肤", "流动背景与新封面，按钮手势不变", null, true),
        new Toggle(Group.PLAYER, "ui_player_cover", "封面形状与暂停缩放", "大圆角封面，暂停时弹簧缩小", "ui_player", false),
        new Toggle(Group.PLAYER, "ui_player_bg", "封面流动背景", "用封面颜色铺满播放页并缓慢流动", "ui_player", false),

        new Toggle(Group.TAB, "ui_tab", "悬浮玻璃底栏", "玻璃胶囊悬浮在页面上，点按逻辑不变", null, true),
        new Toggle(Group.TAB, "ui_tab_face", "图标与选中气泡", "填充图标；按住鼓成可拖动的气泡", "ui_tab", false),
        new Toggle(Group.TAB, "ui_tab_orb", "播放 Tab 拆成圆钮", "「汽水」Tab 放到右侧单独的圆形按钮", "ui_tab", false),
        // Part of the floating bar's hook: with the bar off, the host's own bar shows 福利 again.
        new Toggle(Group.TAB, "ui_tab_welfare", "隐藏福利 Tab", "底栏不再显示「福利」", "ui_tab", true),

        new Toggle(Group.TRIM, "ui_trim_tags", "隐藏歌名旁标签", "VIP、无损、智能音效等小标签", null, true),
        new Toggle(Group.TRIM, "ui_trim_follow", "隐藏关注按钮", "歌手名旁的「关注」", null, true),
        new Toggle(Group.TRIM, "ui_trim_dots", "隐藏封面分页圆点", "左右滑封面仍可用", null, true),
        new Toggle(Group.TRIM, "ui_trim_pendant", "隐藏活动挂件", "封面旁的活动小人等挂件", null, true),
        new Toggle(Group.TRIM, "ui_trim_coin", "隐藏金币入口", "播放页顶部的金币、宝箱入口", null, true),

        new Toggle(Group.PERF, "ui_refresh", "高刷新率", "给汽水的每个窗口请求最高刷新率", null, false),
        new Toggle(Group.PERF, "ui_blur_throttle", "实时模糊限速", "页头实时模糊每秒最多 15 次，更省电", null, false)
    ));

    /** Boolean keys in provider/cache order: mix switches first, then interface switches. */
    static final String[] KEYS;
    static {
        List<String> keys = new ArrayList<>();
        for (Toggle toggle : TOGGLES) keys.add(toggle.key);
        KEYS = keys.toArray(new String[0]);
    }

    public static Toggle find(String key) {
        for (Toggle toggle : TOGGLES) if (toggle.key.equals(key)) return toggle;
        return null;
    }

    public static List<Toggle> group(Group group) {
        List<Toggle> result = new ArrayList<>();
        for (Toggle toggle : TOGGLES) if (toggle.group == group) result.add(toggle);
        return result;
    }

    public static int clampDuration(int value) { return Math.max(DURATION_MIN, Math.min(DURATION_MAX, value)); }

    private SettingsSchema() { }
}

package io.github.qishui.automix;

/**
 * Filled tab glyphs: Google's Material Symbols, Rounded style, FILL 1, weight 400
 * (https://github.com/google/material-design-icons, Apache-2.0), and a small reader for the SVG
 * path commands they use. Apple's HIG asks for filled symbols in tab bars ("Prefer filled symbols
 * or icons for consistency with the platform"); the host's tabs are text only, so these stand in.
 *
 * <p>Pure Java so the reader can be tested on the JVM; {@link TabFace} turns the output into a Path.
 */
final class TabIcons {
    /** Receives the outline in a 24-unit box, y down. */
    interface Sink {
        void moveTo(float x, float y);
        void lineTo(float x, float y);
        void quadTo(float x1, float y1, float x2, float y2);
        void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3);
        void close();
    }

    static final int EXPLORE = 0, NOTE = 1, PERSON = 2, WAVE = 3, PLAY = 4, PAUSE = 5, HOME = 6, DIAMOND = 7,
        GIFT = 8, SPARK = 9, LIVE = 10, VIDEO = 11, CHAT = 12, SEARCH = 13, DOT = 14, COUNT = 15;

    /** Material Symbols path data; all but SPARK use the 960-unit box "0 -960 960 960". */
    private static final String[] DATA = {
        // explore
        "M480-440q-17 0-28.5-11.5T440-480q0-17 11.5-28.5T480-520q17 0 28.5 11.5T520-480q0 17-11.5 28.5T480-440Zm0 360q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0 0q-134 0-227-93t-93-227q0-134 93-227t227-93q134 0 227 93t93 227q0 134-93 227t-227 93Zm67-234q6-3 11-8t8-11l117-250q5-10-2.5-17.5T663-683L413-566q-6 3-11 8t-8 11L277-297q-5 10 2.5 17.5T297-277l250-117Z",
        // music_note
        "M400-120q-66 0-113-47t-47-113q0-66 47-113t113-47q23 0 42.5 5.5T480-418v-382q0-17 11.5-28.5T520-840h160q17 0 28.5 11.5T720-800v80q0 17-11.5 28.5T680-680H560v400q0 66-47 113t-113 47Z",
        // person
        "M480-480q-66 0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47ZM160-240v-32q0-34 17.5-62.5T224-378q62-31 126-46.5T480-440q66 0 130 15.5T736-378q29 15 46.5 43.5T800-272v32q0 33-23.5 56.5T720-160H240q-33 0-56.5-23.5T160-240Z",
        // graphic_eq
        "M280-280v-400q0-17 11.5-28.5T320-720q17 0 28.5 11.5T360-680v400q0 17-11.5 28.5T320-240q-17 0-28.5-11.5T280-280Zm160 160v-720q0-17 11.5-28.5T480-880q17 0 28.5 11.5T520-840v720q0 17-11.5 28.5T480-80q-17 0-28.5-11.5T440-120ZM120-440v-80q0-17 11.5-28.5T160-560q17 0 28.5 11.5T200-520v80q0 17-11.5 28.5T160-400q-17 0-28.5-11.5T120-440Zm480 160v-400q0-17 11.5-28.5T640-720q17 0 28.5 11.5T680-680v400q0 17-11.5 28.5T640-240q-17 0-28.5-11.5T600-280Zm160-160v-80q0-17 11.5-28.5T800-560q17 0 28.5 11.5T840-520v80q0 17-11.5 28.5T800-400q-17 0-28.5-11.5T760-440Z",
        // play_arrow
        "M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Z",
        // pause
        "M640-200q-33 0-56.5-23.5T560-280v-400q0-33 23.5-56.5T640-760q33 0 56.5 23.5T720-680v400q0 33-23.5 56.5T640-200Zm-320 0q-33 0-56.5-23.5T240-280v-400q0-33 23.5-56.5T320-760q33 0 56.5 23.5T400-680v400q0 33-23.5 56.5T320-200Z",
        // home
        "M160-200v-360q0-19 8.5-36t23.5-28l240-180q21-16 48-16t48 16l240 180q15 11 23.5 28t8.5 36v360q0 33-23.5 56.5T720-120H600q-17 0-28.5-11.5T560-160v-200q0-17-11.5-28.5T520-400h-80q-17 0-28.5 11.5T400-360v200q0 17-11.5 28.5T360-120H240q-33 0-56.5-23.5T160-200Z",
        // diamond
        "M368-630h224L486-840h-12L368-630Zm82 474v-414H105l345 414Zm60 0 345-414H510v414Zm148-474h207l-83-166q-11-20-29.5-32T711-840H554l104 210Zm-563 0h207l104-210H249q-23 0-41.5 12T178-796L95-630Z",
        // redeem
        "M160-280v80h640v-80H160Zm0-440h88q-5-9-6.5-19t-1.5-21q0-50 35-85t85-35q30 0 55.5 15.5T460-826l20 26 20-26q18-24 44-39t56-15q50 0 85 35t35 85q0 11-1.5 21t-6.5 19h88q33 0 56.5 23.5T880-640v440q0 33-23.5 56.5T800-120H160q-33 0-56.5-23.5T80-200v-440q0-33 23.5-56.5T160-720Zm0 320h640v-240H596l60 82q10 14 8 29.5T648-503q-14 10-29.5 7.5T593-511L480-664 367-511q-10 13-25.5 15.5T312-503q-14-10-16.5-25.5T303-558l59-82H160v240Zm200-320q17 0 28.5-11.5T400-760q0-17-11.5-28.5T360-800q-17 0-28.5 11.5T320-760q0 17 11.5 28.5T360-720Zm240 0q17 0 28.5-11.5T640-760q0-17-11.5-28.5T600-800q-17 0-28.5 11.5T560-760q0 17 11.5 28.5T600-720Z",
        // auto_awesome (24-unit box)
        "M19 8.3q-.125 0-.262-.075Q18.6 8.15 18.55 8l-.8-1.75-1.75-.8q-.15-.05-.225-.188Q15.7 5.125 15.7 5t.075-.263Q15.85 4.6 16 4.55l1.75-.8.8-1.75q.05-.15.188-.225.137-.075.262-.075t.263.075q.137.075.187.225l.8 1.75 1.75.8q.15.05.225.187.075.138.075.263t-.075.262Q22.15 5.4 22 5.45l-1.75.8-.8 1.75q-.05.15-.187.225-.138.075-.263.075Zm0 14q-.125 0-.262-.075-.138-.075-.188-.225l-.8-1.75-1.75-.8q-.15-.05-.225-.188-.075-.137-.075-.262t.075-.262q.075-.138.225-.188l1.75-.8.8-1.75q.05-.15.188-.225.137-.075.262-.075t.263.075q.137.075.187.225l.8 1.75 1.75.8q.15.05.225.188.075.137.075.262t-.075.262q-.075.138-.225.188l-1.75.8-.8 1.75q-.05.15-.187.225-.138.075-.263.075ZM9 18.575q-.275 0-.525-.15T8.1 18l-1.6-3.5L3 12.9q-.275-.125-.425-.375-.15-.25-.15-.525t.15-.525q.15-.25.425-.375l3.5-1.6L8.1 6q.125-.275.375-.425.25-.15.525-.15t.525.15q.25.15.375.425l1.6 3.5 3.5 1.6q.275.125.425.375.15.25.15.525t-.15.525q-.15.25-.425.375l-3.5 1.6L9.9 18q-.125.275-.375.425-.25.15-.525.15Z",
        // live_tv
        "m442-380 166-106q18-12 18-34t-18-34L442-660q-20-13-41-2t-21 35v214q0 24 21 35t41-2ZM160-200q-33 0-56.5-23.5T80-280v-480q0-33 23.5-56.5T160-840h640q33 0 56.5 23.5T880-760v480q0 33-23.5 56.5T800-200H640v40q0 17-11.5 28.5T600-120H360q-17 0-28.5-11.5T320-160v-40H160Z",
        // smart_display
        "m411-320 223-143q9-6 9-17t-9-17L411-640q-10-7-20.5-1T380-623v286q0 12 10.5 18t20.5-1ZM160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Z",
        // chat_bubble
        "m240-240-92 92q-19 19-43.5 8.5T80-177v-623q0-33 23.5-56.5T160-880h640q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H240Z",
        // search
        "M380-320q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l224 224q11 11 11 28t-11 28q-11 11-28 11t-28-11L532-372q-30 24-69 38t-83 14Zm0-80q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z",
        // dot: a plain disc for tabs we cannot name
        "M480-300q-75 0-127.5-52.5T300-480q0-75 52.5-127.5T480-660q75 0 127.5 52.5T660-480q0 75-52.5 127.5T480-300Z",
    };

    /** Glyph for a host tab: its BottomTab enum name first, its title otherwise. */
    static int kind(String tabName, String title) {
        if (tabName != null) switch (tabName) {
            case "Discovery": return EXPLORE;
            case "ListenVideo": return NOTE;
            case "Home": return PERSON;     // the host's "mine" tab (我的)
            case "Play": return WAVE;
            case "Live": return LIVE;
            case "CommerceVip": return DIAMOND;
            case "CommerceReward": case "CommerceCoin": return GIFT;
            case "AICreate": return SPARK;
            default: break;
        }
        if (title == null) return DOT;
        if (has(title, "我的", "Me", "Mine")) return PERSON;
        if (has(title, "福利", "领时长", "任务", "金币")) return GIFT;
        if (has(title, "会员", "VIP")) return DIAMOND;
        if (has(title, "AI")) return SPARK;
        if (has(title, "发现", "探索", "广场")) return EXPLORE;
        if (has(title, "抖音", "听")) return NOTE;
        if (has(title, "直播")) return LIVE;
        if (has(title, "视频")) return VIDEO;
        if (has(title, "消息")) return CHAT;
        if (has(title, "搜索")) return SEARCH;
        if (has(title, "首页", "精选", "推荐")) return HOME;
        if (has(title, "汽水", "音乐", "播放")) return WAVE;
        return DOT;
    }

    private static boolean has(String s, String... keys) { for (String k : keys) if (s.contains(k)) return true; return false; }

    /** Writes glyph {@code kind} into {@code out}, scaled to a 24-unit box. */
    static void glyph(int kind, Sink out) {
        boolean small = kind == SPARK;
        parse(DATA[kind], small ? 1f : 1f / 40f, small ? 0f : 960f, out);
    }

    /**
     * Reads SVG path data (M L H V C S Q T Z, absolute and relative); each point is written as
     * {@code ((x) * scale, (y + offsetY) * scale)}. Arcs are not used by these glyphs and rejected.
     */
    static void parse(String d, float scale, float offsetY, Sink out) {
        Reader r = new Reader(d);
        float x = 0, y = 0, startX = 0, startY = 0, ctrlX = 0, ctrlY = 0;
        char cmd = 0, last = 0;
        while (r.more()) {
            if (r.isCommand()) cmd = r.command();
            else if (cmd == 0) throw new IllegalArgumentException("path starts without a command");
            else if (cmd == 'M') cmd = 'L';          // pairs after a moveto are linetos
            else if (cmd == 'm') cmd = 'l';
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = ox + r.number(); y = oy + r.number();
                    startX = x; startY = y;
                    out.moveTo(x * scale, (y + offsetY) * scale);
                    break;
                case 'L':
                    x = ox + r.number(); y = oy + r.number();
                    out.lineTo(x * scale, (y + offsetY) * scale);
                    break;
                case 'H':
                    x = ox + r.number();
                    out.lineTo(x * scale, (y + offsetY) * scale);
                    break;
                case 'V':
                    y = oy + r.number();
                    out.lineTo(x * scale, (y + offsetY) * scale);
                    break;
                case 'Q': {
                    float x1 = ox + r.number(), y1 = oy + r.number();
                    x = ox + r.number(); y = oy + r.number();
                    out.quadTo(x1 * scale, (y1 + offsetY) * scale, x * scale, (y + offsetY) * scale);
                    ctrlX = x1; ctrlY = y1;
                    break;
                }
                case 'T': {
                    char prev = Character.toUpperCase(last);
                    float x1 = prev == 'Q' || prev == 'T' ? 2 * x - ctrlX : x;
                    float y1 = prev == 'Q' || prev == 'T' ? 2 * y - ctrlY : y;
                    x = ox + r.number(); y = oy + r.number();
                    out.quadTo(x1 * scale, (y1 + offsetY) * scale, x * scale, (y + offsetY) * scale);
                    ctrlX = x1; ctrlY = y1;
                    break;
                }
                case 'C': {
                    float x1 = ox + r.number(), y1 = oy + r.number(), x2 = ox + r.number(), y2 = oy + r.number();
                    x = ox + r.number(); y = oy + r.number();
                    out.cubicTo(x1 * scale, (y1 + offsetY) * scale, x2 * scale, (y2 + offsetY) * scale, x * scale, (y + offsetY) * scale);
                    ctrlX = x2; ctrlY = y2;
                    break;
                }
                case 'S': {
                    char prev = Character.toUpperCase(last);
                    float x1 = prev == 'C' || prev == 'S' ? 2 * x - ctrlX : x;
                    float y1 = prev == 'C' || prev == 'S' ? 2 * y - ctrlY : y;
                    float x2 = ox + r.number(), y2 = oy + r.number();
                    x = ox + r.number(); y = oy + r.number();
                    out.cubicTo(x1 * scale, (y1 + offsetY) * scale, x2 * scale, (y2 + offsetY) * scale, x * scale, (y + offsetY) * scale);
                    ctrlX = x2; ctrlY = y2;
                    break;
                }
                case 'Z':
                    out.close();
                    x = startX; y = startY;
                    break;
                default:
                    throw new IllegalArgumentException("unsupported path command " + cmd);
            }
            last = cmd;
            if (Character.toUpperCase(cmd) == 'Z') cmd = 0;   // a number right after Z is malformed
        }
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) { this.s = s; }

        private void skip() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\n' || s.charAt(i) == '\t')) i++; }

        boolean more() { skip(); return i < s.length(); }

        boolean isCommand() { skip(); char c = s.charAt(i); return Character.isLetter(c) && c != 'e' && c != 'E'; }

        char command() { skip(); return s.charAt(i++); }

        float number() {
            skip();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false, digits = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { digits = true; i++; }
                else if (c == '.' && !dot) { dot = true; i++; }
                else if ((c == 'e' || c == 'E') && digits) {
                    i++;
                    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                } else break;
            }
            if (!digits) throw new IllegalArgumentException("number expected at " + start + " in path");
            return Float.parseFloat(s.substring(start, i));
        }
    }

    private TabIcons() { }
}

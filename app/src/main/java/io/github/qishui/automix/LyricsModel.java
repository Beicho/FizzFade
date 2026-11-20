package io.github.qishui.automix;

import java.util.ArrayList;
import java.util.List;

/**
 * Timed lyric lines split into sung units. Pure Java so the JVM tests can drive it.
 *
 * <p>The host stores KRC words with absolute times; multi-letter words arrive as one entry per
 * character with the duration split evenly. A unit regroups them the way they are read: one CJK
 * character, or one run of non-space characters. Each unit keeps per-character times so the sweep
 * follows the original syllable timing.
 */
final class LyricsModel {
    static final int LYRIC = 0, CREDIT = 1, INTERLUDE = 2;
    static final long INTERLUDE_MS = 4000;

    static final class Unit {
        final int start, end;          // [start, end) in the line text
        final long t0, t1;
        final long[] c0, c1;           // per character, index - start
        Unit(int start, int end, long[] c0, long[] c1) {
            this.start = start; this.end = end; this.c0 = c0; this.c1 = c1;
            long a = Long.MAX_VALUE, b = Long.MIN_VALUE;
            for (int i = 0; i < c0.length; i++) { a = Math.min(a, c0[i]); b = Math.max(b, c1[i]); }
            t0 = a; t1 = Math.max(a, b);
        }
        long duration() { return t1 - t0; }
    }

    static final class Line {
        final int kind, hostIndex;
        final String text, translation;
        final long start, end;
        final Unit[] units;            // null: line-synced
        Line(int kind, int hostIndex, String text, String translation, long start, long end, Unit[] units) {
            this.kind = kind; this.hostIndex = hostIndex; this.text = text; this.translation = translation;
            this.start = start; this.end = end; this.units = units;
        }
        boolean timed() { return kind != CREDIT; }
    }

    /** One host sentence, already read out of the host objects. */
    static final class Source {
        int hostIndex;
        boolean credit;
        String text, translation;
        long start, end;
        List<String> words;            // null when the sentence has no word timing
        long[] wordStart, wordEnd;
    }

    final Line[] lines;
    final boolean wordSynced, translated;
    private final int[] order;         // indexes of timed lines, ascending start

    private LyricsModel(Line[] lines) {
        this.lines = lines;
        boolean words = false, trans = false;
        List<Integer> timed = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Line line = lines[i];
            if (line.units != null) words = true;
            if (line.translation != null) trans = true;
            if (line.timed()) timed.add(i);
        }
        timed.sort((a, b) -> Long.compare(lines[a].start, lines[b].start));
        order = new int[timed.size()];
        for (int i = 0; i < order.length; i++) order[i] = timed.get(i);
        wordSynced = words; translated = trans;
    }

    /** At least one line can follow the playback clock. */
    boolean timed() {
        for (int index : order) if (lines[index].kind == LYRIC) return true;
        return false;
    }

    /** Line being sung at {@code t}: the last timed line already started, or -1 before the first. */
    int activeAt(long t) {
        int lo = 0, hi = order.length - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines[order[mid]].start <= t) { found = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return found < 0 ? -1 : order[found];
    }

    /** First timed line, the anchor before anything is sung. */
    int firstTimed() { return order.length == 0 ? -1 : order[0]; }

    static float progress(long t0, long t1, long t) {
        if (t <= t0) return t >= t1 ? 1f : 0f;
        if (t >= t1) return 1f;
        return (float) (t - t0) / (float) (t1 - t0);
    }

    static LyricsModel build(List<Source> sources) {
        List<Line> lyric = new ArrayList<>();
        for (Source source : sources) {
            String text = source.text == null ? "" : source.text;
            String translation = blank(source.translation) || source.translation.trim().equals(text.trim()) ? null : source.translation.trim();
            if (source.credit) {
                if (!blank(text)) lyric.add(new Line(CREDIT, source.hostIndex, text.trim(), null, source.start, source.end, null));
                continue;
            }
            boolean untimed = source.start < 0 || source.start >= Long.MAX_VALUE / 2;
            Unit[] units = untimed ? null : units(source);
            if (units != null) {
                StringBuilder joined = new StringBuilder();
                for (String word : source.words) joined.append(word);
                text = joined.toString();
            }
            if (blank(text)) continue;
            long start = source.start, end = source.end;
            if (units != null) { start = Math.min(start, units[0].t0); end = Math.max(end < Long.MAX_VALUE / 2 ? end : 0, units[units.length - 1].t1); }
            lyric.add(new Line(untimed ? CREDIT : LYRIC, source.hostIndex, text, translation, start, end, units));
        }
        // Long instrumental gaps get the breathing dots, like Apple Music.
        List<Line> out = new ArrayList<>();
        Line previous = null;
        for (Line line : lyric) {
            if (line.kind == LYRIC) {
                long from = previous == null ? 0 : previous.end;
                if (line.start - from >= INTERLUDE_MS && (previous != null || line.start >= INTERLUDE_MS))
                    out.add(new Line(INTERLUDE, -1, "", null, from, line.start, null));
                previous = line;
            }
            out.add(line);
        }
        return new LyricsModel(out.toArray(new Line[0]));
    }

    /** Groups host words into read units; null when the words do not carry usable timing. */
    static Unit[] units(Source source) {
        if (source.words == null || source.words.isEmpty() || source.wordStart == null) return null;
        StringBuilder text = new StringBuilder();
        List<long[]> times = new ArrayList<>();
        boolean moving = false;
        for (int w = 0; w < source.words.size(); w++) {
            String word = source.words.get(w);
            if (word == null || word.isEmpty()) continue;
            long a = source.wordStart[w], b = Math.max(a, source.wordEnd[w]);
            if (b > a) moving = true;
            // Multi-character entries (rare: the host already splits most) share their span evenly.
            int n = word.length();
            for (int i = 0; i < n; i++) {
                text.append(word.charAt(i));
                times.add(new long[] {a + (b - a) * i / n, a + (b - a) * (i + 1) / n});
            }
        }
        if (!moving || text.length() == 0) return null;
        List<Unit> units = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int end = i + 1;
            if (!cjk(text, i)) {
                while (end < n && !Character.isWhitespace(text.charAt(end)) && !cjk(text, end)) end++;
            } else if (Character.isHighSurrogate(c) && end < n) end++;
            long[] c0 = new long[end - i], c1 = new long[end - i];
            for (int k = i; k < end; k++) { c0[k - i] = times.get(k)[0]; c1[k - i] = times.get(k)[1]; }
            units.add(new Unit(i, end, c0, c1));
            i = end;
        }
        return units.isEmpty() ? null : units.toArray(new Unit[0]);
    }

    static boolean cjk(CharSequence text, int index) {
        int code = Character.codePointAt(text, index);
        Character.UnicodeScript script = Character.UnicodeScript.of(code);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
            || (code >= 0x3000 && code <= 0x303F) || (code >= 0xFF00 && code <= 0xFFEF);
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}

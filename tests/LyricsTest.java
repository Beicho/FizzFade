package io.github.qishui.automix;

import com.luna.biz.playing.lyric.longlyrics.indicatorlyrics.lyrics.data.LyricViewData;
import com.luna.common.arch.db.entity.lyrics.LyricType;
import com.luna.common.arch.db.entity.lyrics.NetLyricsLanguage;
import com.luna.common.arch.db.entity.lyrics.Sentence;
import com.luna.common.arch.db.entity.lyrics.Word;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class LyricsTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        cjkUnitsKeepCharacterTiming();
        latinWordsRegroupFromHostCharacters();
        lineSyncedAndUntimed();
        interludesAndActiveLine();
        clockExtrapolatesAndFreezes();
        clockBlendsSmallErrorsAndSnapsLargeOnes();
        hostContractReadsShapes();
        System.out.println("LyricsTest ok checks=" + checks);
    }

    static LyricsModel.Source words(int index, long start, String... parts) {
        LyricsModel.Source s = new LyricsModel.Source();
        s.hostIndex = index;
        s.words = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long t = start;
        s.wordStart = new long[parts.length]; s.wordEnd = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            s.words.add(parts[i]); text.append(parts[i]);
            s.wordStart[i] = t; s.wordEnd[i] = parts[i].trim().isEmpty() ? t : t + 300;
            t = s.wordEnd[i];
        }
        s.text = text.toString(); s.start = start; s.end = t;
        return s;
    }

    static void cjkUnitsKeepCharacterTiming() {
        LyricsModel model = LyricsModel.build(Collections.singletonList(words(0, 1000, "夜", "色", "，", "慢")));
        LyricsModel.Line line = model.lines[0];
        check(line.units.length == 4, "one unit per CJK character and full-width punctuation");
        check(line.units[1].t0 == 1300 && line.units[1].t1 == 1600, "unit time");
        check(model.wordSynced, "word synced flag");
    }

    static void latinWordsRegroupFromHostCharacters() {
        // The host splits "Hold" into four evenly timed characters, then a space, then "on".
        LyricsModel model = LyricsModel.build(Collections.singletonList(words(3, 0, "H", "o", "l", "d", " ", "o", "n")));
        LyricsModel.Line line = model.lines[0];
        check(line.text.equals("Hold on"), "text rebuilt from words");
        check(line.units.length == 2, "two words");
        check(line.units[0].start == 0 && line.units[0].end == 4 && line.units[0].t0 == 0 && line.units[0].t1 == 1200, "first word span");
        check(line.units[0].c0.length == 4 && line.units[0].c1[2] == 900, "per-character timing kept");
        check(line.units[1].start == 5 && line.hostIndex == 3, "second word after the space");
        check(Math.abs(LyricsModel.progress(0, 1200, 600) - 0.5f) < 1e-6, "progress midpoint");
    }

    static void lineSyncedAndUntimed() {
        LyricsModel.Source lrc = new LyricsModel.Source();
        lrc.text = "逐行歌词"; lrc.start = 5000; lrc.end = 9000;
        LyricsModel model = LyricsModel.build(Collections.singletonList(lrc));
        check(model.lines.length == 2 && model.lines[0].kind == LyricsModel.INTERLUDE, "intro dots before a late first line");
        check(model.lines[1].units == null && !model.wordSynced && model.timed(), "line synced fallback");

        LyricsModel.Source text = new LyricsModel.Source();
        text.text = "纯文本"; text.start = 0; text.end = Long.MAX_VALUE;
        LyricsModel.Source plain = new LyricsModel.Source();
        plain.text = "无时间"; plain.start = Long.MAX_VALUE; plain.end = Long.MAX_VALUE;
        check(!LyricsModel.build(Collections.singletonList(plain)).timed(), "untimed lyrics stay native");

        LyricsModel.Source credit = new LyricsModel.Source();
        credit.text = "作词：某人"; credit.credit = true; credit.start = 0; credit.end = 0;
        LyricsModel withCredit = LyricsModel.build(Arrays.asList(credit, words(1, 0, "a")));
        check(withCredit.lines[0].kind == LyricsModel.CREDIT && withCredit.activeAt(0) == 1, "credits never become active");
    }

    static void interludesAndActiveLine() {
        List<LyricsModel.Source> lines = Arrays.asList(words(0, 0, "a", "b"), words(1, 700, "c"), words(2, 6000, "d"));
        LyricsModel model = LyricsModel.build(lines);
        // a b (0..600), c (700..1000), dots (1000..6000), d.
        check(model.lines.length == 4 && model.lines[2].kind == LyricsModel.INTERLUDE, "gap of 5 s gets dots");
        check(model.activeAt(-1) == -1, "nothing before the first line");
        check(model.activeAt(0) == 0 && model.activeAt(699) == 0, "first line until the second starts");
        check(model.activeAt(1000) == 2 && model.activeAt(5999) == 2, "dots during the gap");
        check(model.activeAt(6000) == 3, "next line");
        LyricsModel.Source translated = words(0, 0, "x");
        translated.translation = " 译文 ";
        check("译文".equals(LyricsModel.build(Collections.singletonList(translated)).lines[0].translation), "translation trimmed");
    }

    static void clockExtrapolatesAndFreezes() {
        LyricsClock clock = new LyricsClock();
        long ms = 1_000_000L;
        clock.sample(10_000, 0);
        check(clock.at(100 * ms) == 10_000, "single sample does not run");
        clock.sample(10_250, 250 * ms);
        check(clock.running() && clock.at(350 * ms) == 10_350, "advancing samples run in real time");
        check(clock.at(5_000 * ms) <= 10_250 + 2000, "extrapolation is capped");
        clock.playing(false, 400 * ms);
        long frozen = clock.at(400 * ms);
        check(clock.at(900 * ms) == frozen && !clock.running(), "pause freezes");
        clock.playing(true, 1_000 * ms);
        check(clock.at(1_100 * ms) == frozen + 100, "resume continues from the frozen point");
    }

    static void clockBlendsSmallErrorsAndSnapsLargeOnes() {
        LyricsClock clock = new LyricsClock();
        long ms = 1_000_000L;
        clock.sample(0, 0); clock.sample(100, 100 * ms);
        clock.sample(240, 200 * ms);               // 40 ms ahead of the prediction
        long blended = clock.at(200 * ms);
        check(blended > 200 && blended < 240, "small error blended, got " + blended);
        clock.sample(60_000, 300 * ms);            // a seek
        check(clock.at(300 * ms) == 60_000, "large error snaps");
        clock.jump(5_000, 400 * ms);
        check(clock.at(400 * ms) == 5_000, "jump");
    }

    static void hostContractReadsShapes() throws Exception {
        LyricsHost host = new LyricsHost(LyricsTest.class.getClassLoader());
        check(host.controllerUpdate.getName().equals("h"), "public update entry, not the private overloads");
        check(host.controllerRecycler.getName().equals("f"), "recycler field among three views");
        check(host.modelSeek.getName().equals("p5"), "seek is the only public final (int)");
        check(host.lineTime.getName().equals("l"), "line time");

        Sentence zh = new Sentence(LyricType.ORIGIN, "你好", 0, 0, null, null);
        java.util.Map<NetLyricsLanguage, Sentence> map = new java.util.HashMap<>();
        map.put(NetLyricsLanguage.ZH, zh);
        List<Word> ws = Arrays.asList(new Word("H", 1000, 1100), new Word("i", 1100, 1200));
        Sentence origin = new Sentence(LyricType.ORIGIN, "Hi", 1000, 1200, ws, map);
        // Translation stored in the first sentence field: the shape test must still find the origin.
        LyricViewData swapped = new LyricViewData(zh, origin, 777);
        LyricsModel.Source source = host.source(swapped, 4);
        check(source.text.equals("Hi") && "你好".equals(source.translation), "origin told apart from translation");
        check(source.start == 1000 && source.end == 1200 && source.words.size() == 2 && source.wordEnd[1] == 1200, "times");
        check(source.hostIndex == 4 && !source.credit, "index and kind");
        check(host.lineTime.getLong(swapped) == 777, "host playback time on the line");

        Sentence maker = new Sentence(LyricType.SONG_MAKER, "作曲：某人", 0, 0, null, null);
        check(host.source(new LyricViewData(maker, null, 0), 0).credit, "credit type");
        check(host.source(new com.luna.biz.playing.lyric.longlyrics.indicatorlyrics.lyrics.data.PlaceholderViewData(), 0) == null, "placeholder skipped");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}

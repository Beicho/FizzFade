package io.github.qishui.automix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Contract with the play page's one-line lyric ({@code ShortLyricsContainerView}). Class names are
 * kept by the host; members are matched by shape, exactly one candidate each, like {@link LyricsHost}.
 */
final class LyricsShortHost {
    static final String PKG = "com.luna.biz.playing.lyric.shortlyrics.";

    final Class<?> container, lineData, updateData, sentence, word;
    final Method update, time;
    final Field lineSentence, lineTime, sentenceType, sentenceText, sentenceWords, sentenceTranslations, wordText;
    final Field[] sentenceTimes, wordTimes;

    LyricsShortHost(ClassLoader loader) throws Exception {
        container = LyricsHost.load(loader, PKG + "ShortLyricsContainerView");
        lineData = LyricsHost.load(loader, PKG + "data.ShortLyricViewData");
        updateData = LyricsHost.load(loader, PKG + "data.UpdateLyricsViewData");
        sentence = LyricsHost.load(loader, LyricsHost.ENTITY + "Sentence");
        word = LyricsHost.load(loader, LyricsHost.ENTITY + "Word");
        update = LyricsHost.oneMethod(container, m -> publicVoid(m) && m.getParameterTypes().length == 2
            && m.getParameterTypes()[0] == lineData && m.getParameterTypes()[1] == updateData, "ShortLyricsContainerView.update");
        time = LyricsHost.oneMethod(container, m -> publicVoid(m) && m.getParameterTypes().length == 1
            && m.getParameterTypes()[0] == long.class, "ShortLyricsContainerView.time");
        // The sentence lives on the base class, BaseLyricViewData.
        lineSentence = LyricsHost.one(lineData.getSuperclass(), f -> f.getType() == sentence, "BaseLyricViewData.sentence");
        // The host stamps each update with the playback time (it clones the line with the new time),
        // and by default this is the only clock the one-line lyric gets.
        lineTime = LyricsHost.one(lineData.getSuperclass(), f -> f.getType() == long.class, "BaseLyricViewData.time");
        sentenceType = LyricsHost.one(sentence, f -> f.getType().isEnum(), "Sentence.type");
        sentenceText = LyricsHost.one(sentence, f -> f.getType() == CharSequence.class, "Sentence.content");
        sentenceTimes = LyricsHost.many(sentence, f -> f.getType() == long.class, 2, "Sentence.times");
        sentenceWords = LyricsHost.one(sentence, f -> f.getType() == List.class, "Sentence.words");
        sentenceTranslations = LyricsHost.one(sentence, f -> f.getType() == Map.class, "Sentence.translations");
        wordText = LyricsHost.one(word, f -> f.getType() == CharSequence.class, "Word.content");
        wordTimes = LyricsHost.many(word, f -> f.getType() == long.class, 2, "Word.times");
    }

    private static boolean publicVoid(Method m) {
        return Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class;
    }

    String describe() { return "update=" + update.getName() + " time=" + time.getName() + " sentence=" + lineSentence.getName() + " stamp=" + lineTime.getName(); }

    /** Playback time stamped on a line update, or -1. */
    long stamp(Object data) throws IllegalAccessException {
        return data != null && lineData.isInstance(data) ? lineTime.getLong(data) : -1;
    }

    /** The current line as a model source with its translation, or null. */
    LyricsModel.Source source(Object data) throws Exception {
        if (data == null || !lineData.isInstance(data)) return null;
        Object s = lineSentence.get(data);
        if (s == null) return null;
        LyricsModel.Source source = new LyricsModel.Source();
        Object type = sentenceType.get(s);
        String kind = type == null ? "" : ((Enum<?>) type).name();
        source.credit = "SONG_MAKER".equals(kind) || "CONTRIBUTOR".equals(kind) || "PRELUDE_EFFECT".equals(kind);
        CharSequence text = (CharSequence) sentenceText.get(s);
        source.text = text == null ? "" : text.toString();
        long[] times = span(s, sentenceTimes);
        source.start = times[0]; source.end = times[1];
        source.translation = translation(s, source.text);
        List<?> words = (List<?>) sentenceWords.get(s);
        if (words != null && !words.isEmpty()) {
            int n = words.size();
            source.words = new ArrayList<>(n);
            source.wordStart = new long[n];
            source.wordEnd = new long[n];
            for (int i = 0; i < n; i++) {
                Object w = words.get(i);
                CharSequence content = w == null ? null : (CharSequence) wordText.get(w);
                source.words.add(content == null ? "" : content.toString());
                long[] wt = w == null ? new long[] {times[0], times[0]} : span(w, wordTimes);
                source.wordStart[i] = wt[0]; source.wordEnd[i] = wt[1];
            }
        }
        return source;
    }

    /** A Chinese translation when there is one (what the host shows), else the first different one. */
    private String translation(Object s, String origin) throws Exception {
        Map<?, ?> map = (Map<?, ?>) sentenceTranslations.get(s);
        if (map == null || map.isEmpty()) return null;
        String first = null;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object value = e.getValue();
            if (value == null || !sentence.isInstance(value)) continue;
            CharSequence t = (CharSequence) sentenceText.get(value);
            if (t == null || t.toString().trim().isEmpty() || t.toString().equals(origin)) continue;
            String key = e.getKey() == null ? "" : e.getKey().toString().toUpperCase(java.util.Locale.ROOT);
            if (key.contains("ZH") || key.contains("CN") || key.contains("CHINESE")) return t.toString();
            if (first == null) first = t.toString();
        }
        return first;
    }

    private static long[] span(Object target, Field[] pair) throws Exception {
        long x = pair[0].getLong(target), y = pair[1].getLong(target);
        return new long[] {Math.min(x, y), Math.max(x, y)};
    }
}

package io.github.qishui.automix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflection contract with the host's lyric page. Class names in the lyric module are not
 * obfuscated in 21.6.0; members are, so every field and method is found by its types and shape
 * rather than by name. Any member that does not resolve to exactly one candidate fails the contract
 * and the lyric page stays native.
 */
final class LyricsHost {
    static final String PKG = "com.luna.biz.playing.lyric.longlyrics.indicatorlyrics.";
    static final String ENTITY = "com.luna.common.arch.db.entity.lyrics.";

    final Class<?> updateData, viewController, liveController, viewModel, lyricViewData, sentence, word, playerController, playerListener;
    final Field updateList, updateIndex, controllerRecycler, livePlayer, liveList, modelLive, lineTime;
    final Field[] lineSentences;
    final Field sentenceType, sentenceText, sentenceWords, sentenceTranslations;
    final Field[] sentenceTimes, wordTimes;
    final Field wordText;
    final Method controllerUpdate, modelSeek, addListener, removeListener;

    LyricsHost(ClassLoader loader) throws Exception {
        updateData = load(loader, PKG + "lyrics.data.UpdateLyricsViewData");
        viewController = load(loader, PKG + "lyrics.LyricsViewController");
        liveController = load(loader, PKG + "lyrics.LyricsLiveDataController");
        viewModel = load(loader, PKG + "IndicatorAndLyricsViewModel");
        lyricViewData = load(loader, PKG + "lyrics.data.LyricViewData");
        sentence = load(loader, ENTITY + "Sentence");
        word = load(loader, ENTITY + "Word");
        playerController = load(loader, "com.luna.common.player.queue.api.IPlayerController");
        playerListener = load(loader, "com.luna.common.player.queue.api.IPlayerListener");

        updateList = one(updateData, f -> f.getType() == List.class, "UpdateLyricsViewData.list");
        updateIndex = one(updateData, f -> f.getType() == Integer.class, "UpdateLyricsViewData.index");
        controllerRecycler = one(viewController, f -> f.getType().getName().endsWith(".LongLyricsRecyclerView")
            || (android.view.ViewGroup.class.isAssignableFrom(f.getType()) && f.getType().getName().contains("RecyclerView")), "LyricsViewController.recycler");
        controllerUpdate = oneMethod(viewController, m -> !Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers())
            && m.getReturnType() == void.class && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == updateData, "LyricsViewController.update");
        livePlayer = one(liveController, f -> f.getType() == playerController, "LyricsLiveDataController.player");
        liveList = one(liveController, f -> f.getType() == java.util.concurrent.CopyOnWriteArrayList.class, "LyricsLiveDataController.list");
        modelLive = one(viewModel, f -> f.getType() == liveController, "IndicatorAndLyricsViewModel.controller");
        // seekPlayerToLyricAndPlay(index): the only public final (int) -> void.
        modelSeek = oneMethod(viewModel, m -> Modifier.isPublic(m.getModifiers()) && Modifier.isFinal(m.getModifiers())
            && !Modifier.isStatic(m.getModifiers()) && m.getReturnType() == void.class
            && m.getParameterTypes().length == 1 && m.getParameterTypes()[0] == int.class, "IndicatorAndLyricsViewModel.seek");

        lineSentences = many(lyricViewData, f -> f.getType() == sentence, 2, "LyricViewData.sentences");
        lineTime = one(lyricViewData, f -> f.getType() == long.class, "LyricViewData.time");
        sentenceType = one(sentence, f -> f.getType().isEnum(), "Sentence.type");
        sentenceText = one(sentence, f -> f.getType() == CharSequence.class, "Sentence.content");
        sentenceTimes = many(sentence, f -> f.getType() == long.class, 2, "Sentence.times");
        sentenceWords = one(sentence, f -> f.getType() == List.class, "Sentence.words");
        sentenceTranslations = one(sentence, f -> f.getType() == java.util.Map.class, "Sentence.translations");
        wordText = one(word, f -> f.getType() == CharSequence.class, "Word.content");
        wordTimes = many(word, f -> f.getType() == long.class, 2, "Word.times");

        addListener = playerController.getMethod("addPlayerListener", playerListener);
        removeListener = playerController.getMethod("removePlayerListener", playerListener);
    }

    String describe() {
        return "update=" + controllerUpdate.getName() + " recycler=" + controllerRecycler.getName() + " seek=" + modelSeek.getName()
            + " live=" + livePlayer.getName() + "/" + liveList.getName() + " sentence=" + sentenceText.getName() + "/" + sentenceWords.getName();
    }

    /** Reads one LyricViewData into a model source, or null for placeholders. */
    LyricsModel.Source source(Object item, int index) throws Exception {
        if (item == null || !lyricViewData.isInstance(item)) return null;
        Object a = lineSentences[0].get(item), b = lineSentences[1].get(item);
        Object origin = a, translation = b;
        // The translation is a value of the original sentence's translation map.
        if (a == null || (b != null && !translates(b, a) && translates(a, b))) { origin = b; translation = a; }
        if (origin == null) return null;
        LyricsModel.Source source = new LyricsModel.Source();
        source.hostIndex = index;
        Object type = sentenceType.get(origin);
        String kind = type == null ? "" : ((Enum<?>) type).name();
        source.credit = "SONG_MAKER".equals(kind) || "CONTRIBUTOR".equals(kind) || "PRELUDE_EFFECT".equals(kind);
        CharSequence text = (CharSequence) sentenceText.get(origin);
        source.text = text == null ? "" : text.toString();
        long[] times = span(origin, sentenceTimes);
        source.start = times[0]; source.end = times[1];
        if (translation != null && translation != origin) {
            CharSequence t = (CharSequence) sentenceText.get(translation);
            source.translation = t == null ? null : t.toString();
        }
        List<?> words = (List<?>) sentenceWords.get(origin);
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

    private boolean translates(Object candidate, Object origin) throws Exception {
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) sentenceTranslations.get(origin);
        return map != null && map.containsValue(candidate);
    }

    /** Start and end; the two long fields are told apart by order of value, not by name. */
    private static long[] span(Object target, Field[] pair) throws Exception {
        long x = pair[0].getLong(target), y = pair[1].getLong(target);
        return new long[] {Math.min(x, y), Math.max(x, y)};
    }

    static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    interface FieldTest { boolean test(Field field); }
    interface MethodTest { boolean test(Method method); }

    static Field one(Class<?> owner, FieldTest test, String what) throws NoSuchFieldException {
        Field[] found = many(owner, test, 1, what);
        return found[0];
    }

    static Field[] many(Class<?> owner, FieldTest test, int expected, String what) throws NoSuchFieldException {
        List<Field> hits = new ArrayList<>();
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (test.test(field)) hits.add(field);
        }
        if (hits.size() != expected) throw new NoSuchFieldException(what + " candidates=" + hits.size() + " expected=" + expected);
        hits.sort((a, b) -> a.getName().compareTo(b.getName()));
        for (Field field : hits) field.setAccessible(true);
        return hits.toArray(new Field[0]);
    }

    static Method oneMethod(Class<?> owner, MethodTest test, String what) throws NoSuchMethodException {
        Method hit = null;
        int count = 0;
        for (Method method : owner.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) continue;
            if (test.test(method)) { hit = method; count++; }
        }
        if (count != 1) throw new NoSuchMethodException(what + " candidates=" + count);
        hit.setAccessible(true);
        return hit;
    }
}

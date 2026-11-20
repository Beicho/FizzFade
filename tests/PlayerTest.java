package io.github.qishui.automix;

import com.luna.biz.playing.playpage.track.BaseCompleteTrackCardViewDelegate;
import com.luna.biz.playing.playpage.track.PhoneLandscapeTrackCardViewDelegate;
import com.luna.biz.playing.playpage.track.PortraitTrackCardViewDelegate;
import com.luna.common.player.queue.api.IPlayerController;
import com.luna.common.player.queue.api.IPlayerListener;

public final class PlayerTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        playStateFromListenerOnly();
        springSettles();
        hostContract();
        shortLyricsContract();
        System.out.println("PlayerTest ok checks=" + checks);
    }

    static void playStateFromListenerOnly() {
        long s = 1_000_000_000L;
        PlayerModel.PlayState state = new PlayerModel.PlayState();
        check(!state.known() && !state.playing(0), "unknown until something is heard");
        state.time(1000, 1 * s);
        check(!state.playing(1 * s), "a single position is not movement");
        state.time(1250, 1 * s + s / 4);
        check(state.playing(2 * s), "moving position means playing");
        check(!state.playing(3 * s), "silence means paused");
        state.state("PAUSED", 4 * s);
        state.time(1500, 4 * s + s / 10);
        check(!state.playing(4 * s + s / 5), "a trailing report right after pause does not resume");
        state.state("PLAYING", 5 * s);
        check(state.playing(9 * s), "explicit playing holds without reports");
        state.state("PLAYBACK_STATE_PAUSED", 10 * s);
        check(!state.playing(10 * s), "prefixed names");
        state.time(1500, 11 * s); state.time(1750, 11 * s + s / 4);
        check(state.playing(11 * s + s / 2), "position moving long after a pause means resumed");
    }

    static void springSettles() {
        float[] x = {0.86f}, v = {0f};
        boolean moving = true;
        int frames = 0;
        while (moving && frames < 600) { moving = PlayerModel.spring(x, v, 0, 1f, 170f, 0.78f, 1f / 120f, 0.0008f); frames++; }
        check(!moving && x[0] == 1f, "spring reaches its goal");
        check(frames > 20 && frames < 240, "settles in a fraction of a second, frames=" + frames);
    }

    static void hostContract() throws Exception {
        PlayerHost host = new PlayerHost(PlayerTest.class.getClassLoader());
        check(host.viewCreated.getName().equals("onViewCreated"), "lifecycle hook");
        check(host.provider.getName().equals("z"), "provider found by interface, not by its synthetic type");
        check(host.portrait(new PortraitTrackCardViewDelegate()) && !host.portrait(new PhoneLandscapeTrackCardViewDelegate()), "portrait only");
        IPlayerController player = new IPlayerController() {
            @Override public void addPlayerListener(IPlayerListener l) { }
            @Override public void removePlayerListener(IPlayerListener l) { }
        };
        BaseCompleteTrackCardViewDelegate.shared = player;
        check(host.player(new PortraitTrackCardViewDelegate()) == player, "player through the provider");
    }

    static void shortLyricsContract() throws Exception {
        LyricsShortHost host = new LyricsShortHost(PlayerTest.class.getClassLoader());
        check(host.update.getName().equals("k") && host.time.getName().equals("u"), "line and time entries by shape");
        java.util.Map<com.luna.common.arch.db.entity.lyrics.NetLyricsLanguage, com.luna.common.arch.db.entity.lyrics.Sentence> map = new java.util.HashMap<>();
        map.put(com.luna.common.arch.db.entity.lyrics.NetLyricsLanguage.ZH,
            new com.luna.common.arch.db.entity.lyrics.Sentence(com.luna.common.arch.db.entity.lyrics.LyricType.ORIGIN, "我们", 0, 0, null, null));
        java.util.List<com.luna.common.arch.db.entity.lyrics.Word> ws = java.util.Arrays.asList(
            new com.luna.common.arch.db.entity.lyrics.Word("W", 2000, 2100), new com.luna.common.arch.db.entity.lyrics.Word("e", 2100, 2200));
        com.luna.common.arch.db.entity.lyrics.Sentence s = new com.luna.common.arch.db.entity.lyrics.Sentence(
            com.luna.common.arch.db.entity.lyrics.LyricType.ORIGIN, "We", 2000, 2200, ws, map);
        LyricsModel.Source src = host.source(new com.luna.biz.playing.lyric.shortlyrics.data.ShortLyricViewData(3, s, 0, 0, 2050));
        check(src.text.equals("We") && "我们".equals(src.translation) && src.words.size() == 2 && src.wordEnd[1] == 2200, "short line with translation and words");
        check(host.stamp(new com.luna.biz.playing.lyric.shortlyrics.data.ShortLyricViewData(3, s, 0, 0, 2050)) == 2050, "playback time stamped on the update");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}

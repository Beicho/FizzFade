package com.luna.biz.playing.lyric;
import com.luna.common.arch.db.entity.lyrics.Sentence;
// Mirrors 21.6.0: index, the sentence, two colours, a time.
public class BaseLyricViewData {
    private final int a; private final Sentence b; private int c; private int d; private long e;
    public BaseLyricViewData(int a, Sentence b, int c, int d, long e) { this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; }
}

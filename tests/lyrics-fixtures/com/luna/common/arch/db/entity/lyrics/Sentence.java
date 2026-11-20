package com.luna.common.arch.db.entity.lyrics;
public final class Sentence { private final LyricType a; private final CharSequence b; private long c; private long d;
    private java.util.List<Word> e; private java.util.Map<NetLyricsLanguage, Sentence> f;
    public Sentence(LyricType a, CharSequence b, long c, long d, java.util.List<Word> e, java.util.Map<NetLyricsLanguage, Sentence> f) {
        this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f; } }

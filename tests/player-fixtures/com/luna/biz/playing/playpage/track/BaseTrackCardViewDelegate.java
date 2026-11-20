package com.luna.biz.playing.playpage.track;
// Mirrors 21.6.0: lifecycle method by name; unrelated fields of many types, including a ViewGroup.
public abstract class BaseTrackCardViewDelegate {
    private final Runnable a = null;
    private boolean q;
    private android.view.ViewGroup u;
    public static int calls;
    public void onViewCreated(android.view.View view, android.os.Bundle state) { calls++; }
    public void onDestroy() { }
}

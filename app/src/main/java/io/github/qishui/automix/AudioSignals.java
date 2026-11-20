package io.github.qishui.automix;

import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Observes the host's existing effect processor; never replaces or enables it. */
final class AudioSignals {
    private final Map<Object, Slot> processors = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, PcmProbe> engines = Collections.synchronizedMap(new WeakHashMap<>());
    private final HostDiagnostics diagnostics;
    final NativePcm nativePcm;
    private final Map<Object, BeatGrid> grids = Collections.synchronizedMap(new WeakHashMap<>());
    AudioSignals(HostDiagnostics diagnostics) {
        this.diagnostics = diagnostics; nativePcm = new NativePcm(this, diagnostics);
    }
    BeatGrid grid(Object engine) {
        synchronized (grids) { return grids.computeIfAbsent(engine, e -> new BeatGrid()); }
    }
    void nativeAudio(Object engine, ByteBuffer buffer, int frames, int rate, int channels, int format, long pts) {
        PcmProbe probe = probe(engine);
        long now = SystemClock.elapsedRealtime();
        long before = probe.snapshot(now).frames;
        probe.accept(new ByteBuffer[]{buffer}, frames, rate, channels, format, now);
        PcmProbe.Snapshot snapshot = probe.snapshot(now);
        if (snapshot.fresh && snapshot.frames != before) grid(engine).add(snapshot.bassRms * 1.4 + snapshot.rms * 0.25, frames*1000d/rate, pts);
        diagnostics.throttled("native_pcm_observed", "engine=" + System.identityHashCode(engine)
            + " rate=" + rate + " channels=" + channels + " pts=" + pts, 15000);
    }

    PcmProbe probe(Object engine) {
        synchronized (engines) {
            PcmProbe probe = engines.get(engine);
            if (probe == null) { probe = new PcmProbe(); engines.put(engine, probe); }
            return probe;
        }
    }

    private Slot slot(Object processor) {
        synchronized (processors) {
            Slot slot = processors.get(processor);
            if (slot == null) { slot = new Slot(); processors.put(processor, slot); }
            return slot;
        }
    }

    void bind(Object engine, Object processor) {
        if (processor == null) return;
        slot(processor).engine = new WeakReference<>(engine);
        probe(engine);
        diagnostics.event("pcm_observer_bound", "engine=" + System.identityHashCode(engine)
            + " processor=" + processor.getClass().getName());
    }

    void open(Object processor, Object[] args) {
        Slot slot = slot(processor);
        slot.rate = (Integer) args[0]; slot.channels = (Integer) args[1]; slot.format = (Integer) args[3];
        Object engine = slot.engine.get();
        if (engine != null) probe(engine).reset();
        diagnostics.event("pcm_format", "rate=" + slot.rate + " channels=" + slot.channels + " format=" + slot.format);
    }

    void audio(Object processor, Object[] args) {
        Slot slot = processors.get(processor);
        if (slot == null) return;
        Object engine = slot.engine.get();
        if (engine == null) return;
        probe(engine).accept((ByteBuffer[]) args[0], (Integer) args[1], slot.rate, slot.channels,
            slot.format, SystemClock.elapsedRealtime());
    }

    private static final class Slot {
        volatile WeakReference<Object> engine = new WeakReference<>(null);
        volatile int rate, channels, format = -1;
    }
}

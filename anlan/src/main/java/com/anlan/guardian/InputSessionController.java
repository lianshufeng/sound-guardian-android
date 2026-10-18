package com.anlan.guardian;

import android.os.Handler;
import android.os.Looper;

/** 唤醒与关怀共用准备、提示音、尾音和输入所有权。调用均在主线程。 */
final class InputSessionController implements AutoCloseable {
    interface Host {
        UnderstandingWorker worker();
        void cue(Runnable complete, java.util.function.Consumer<String> error);
        boolean available();
        boolean cueEnabled();
        default void readyForInput() { }
    }
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final long tailMs;
    private final long wakeTailMs;
    private long generation;
    private InteractionPorts.SpeechInput.Result owner;
    private long preparingAt;
    private final Runnable timeout = () -> { InteractionPorts.SpeechInput.Result callback = owner; cancel(); if (callback != null) callback.onError("INPUT_PREPARE_TIMEOUT"); };
    InputSessionController(Host host, long tailMs) { this(host, tailMs, tailMs); }
    InputSessionController(Host host, long tailMs, long wakeTailMs) {
        this.host = host; this.tailMs = tailMs; this.wakeTailMs = wakeTailMs;
    }
    void start(InteractionPorts.SpeechInput.Result callback, long timeoutMs) {
        start(callback, timeoutMs, false);
    }
    void start(InteractionPorts.SpeechInput.Result callback, long timeoutMs, boolean immediate) {
        cancel(); owner = callback; long lease = ++generation;
        if (!host.available() || host.worker() == null) { callback.onError("INPUT_UNAVAILABLE"); return; }
        GuardianRuntime.publishInput("PREPARING");
        preparingAt=System.nanoTime();
        main.postDelayed(timeout, 30000);
        host.worker().prepare(() -> {
            if (lease != generation) return;
            Runnable after = () -> main.postDelayed(() -> {
                if (lease != generation || !host.available() || host.worker() == null) return;
                try {
                    host.readyForInput();
                    host.worker().start(callback, timeoutMs);
                    GuardianRuntime.publishInput("LISTENING"); main.removeCallbacks(timeout);
                    InteractionTrace.record(InteractionTrace.Stage.INPUT_READY,lease,preparingAt,0);
                    callback.onReady();
                }
                catch (RuntimeException error) { callback.onError("INPUT_UNAVAILABLE"); }
            }, immediate ? wakeTailMs : tailMs);
            if (!immediate && host.cueEnabled()) {
                GuardianRuntime.publishInput("CUE");
                host.cue(() -> { if (lease == generation) after.run(); }, reason -> { if (lease == generation) callback.onError(reason); });
            } else after.run();
        }, reason -> { if (lease == generation) callback.onError(reason); });
    }
    void cancel() {
        generation++; main.removeCallbacksAndMessages(null);
        if (owner != null && host.worker() != null) host.worker().cancel(owner);
        owner = null; GuardianRuntime.publishInput("IDLE");
    }
    @Override public void close() { cancel(); }
}

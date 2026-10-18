package com.anlan.guardian;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 有界性能元数据；不接受文字、地址、凭据或录音内容。时间使用单调纳秒。 */
final class InteractionTrace {
    enum Stage { CAPTURE, GUARD, GUARD_GAP, STORE, STORE_FAILED, INPUT_PREPARE, INPUT_READY,
        TTS_LOAD, TTS_SYNTHESIS, TTS_CACHE, PLAYBACK_START, PLAYBACK_END, CANCEL, RESOURCE_WAIT }
    static final class Sample {
        final Stage stage;
        final long id, atNanos, durationNanos, amount;
        Sample(Stage stage, long id, long at, long duration, long amount) {
            this.stage=stage; this.id=id; atNanos=at; durationNanos=duration; this.amount=amount;
        }
    }
    private static final int LIMIT=512;
    private static final ArrayDeque<Sample> samples=new ArrayDeque<>();
    static synchronized void record(Stage stage,long id,long started,long amount) {
        long now=System.nanoTime();
        if(samples.size()==LIMIT)samples.removeFirst();
        samples.addLast(new Sample(stage,id,now,Math.max(0,now-started),amount));
    }
    static synchronized List<Sample> snapshot(){return new ArrayList<>(samples);}
    static synchronized void clear(){samples.clear();}
    private InteractionTrace() { }
}

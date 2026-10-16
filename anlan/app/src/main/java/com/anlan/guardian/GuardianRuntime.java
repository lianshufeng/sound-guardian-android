package com.anlan.guardian;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 只保存不可变数据，不持有 Activity。用于旋转、返回主页后的状态恢复。 */
final class GuardianRuntime {
    enum Status { STOPPED, STARTING, LISTENING, ERROR }
    static final class ModelResult {
        final String label, name; final float score;
        ModelResult(String label, String name, float score) { this.label = label; this.name = name; this.score = score; }
    }
    static final class Snapshot {
        final Status status;
        final List<SoundEvent> events;
        final List<ModelResult> modelResults;
        final String error, topLabel, topRawLabel;
        final float topScore, inferenceMs, dbfs;
        final boolean activity;
        Snapshot(Status status, List<SoundEvent> events, String error, String topLabel,
                 float inferenceMs, float dbfs, boolean activity) {
            this(status, events, error, topLabel, topLabel, 0, inferenceMs, dbfs, activity);
        }
        Snapshot(Status status, List<SoundEvent> events, String error, String topLabel,
                 float topScore, float inferenceMs, float dbfs, boolean activity) {
            this(status, events, error, topLabel, topLabel, topScore, inferenceMs, dbfs, activity);
        }
        Snapshot(Status status, List<SoundEvent> events, String error, String topLabel, String topRawLabel,
                 float topScore, float inferenceMs, float dbfs, boolean activity) {
            this(status, events, Collections.emptyList(), error, topLabel, topRawLabel, topScore, inferenceMs, dbfs, activity);
        }
        Snapshot(Status status, List<SoundEvent> events, List<ModelResult> modelResults, String error, String topLabel, String topRawLabel,
                 float topScore, float inferenceMs, float dbfs, boolean activity) {
            this.status = status;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
            this.modelResults = Collections.unmodifiableList(new ArrayList<>(modelResults));
            this.error = error; this.topLabel = topLabel; this.topRawLabel = topRawLabel; this.topScore = topScore;
            this.inferenceMs = inferenceMs; this.dbfs = dbfs; this.activity = activity;
        }
    }
    private static long generation;
    private static volatile Snapshot latest = new Snapshot(Status.STOPPED,
            Collections.emptyList(), "", "", 0, -96, false);
    static synchronized long begin() { generation++; return generation; }
    static synchronized boolean publish(long token, Snapshot snapshot) {
        if (token != generation) return false;
        latest = snapshot; return true;
    }
    static Snapshot snapshot() { return latest; }
}

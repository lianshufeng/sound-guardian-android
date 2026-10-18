package com.anlan.guardian;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 只保存不可变数据，不持有 Activity。用于旋转、返回主页后的状态恢复。 */
final class GuardianRuntime {
    enum Status { STOPPED, STARTING, LISTENING, ERROR }
    static final class WakeSnapshot {
        final String status;
        final long at;
        WakeSnapshot(String status, long at) { this.status = status; this.at = at; }
    }
    private static volatile WakeSnapshot wake = new WakeSnapshot("未启动", 0);
    static synchronized boolean publishWake(long token, String status, long at) {
        if (token != generation) return false;
        wake = new WakeSnapshot(status, at); return true;
    }
    static WakeSnapshot wakeSnapshot() { return wake; }
    private static volatile InteractionPorts.UnderstandingState understanding = new InteractionPorts.UnderstandingState("未启动", false, 0, null, 0);
    static InteractionPorts.UnderstandingState understandingSnapshot() { return understanding; }
    private static volatile String conversationId = "", conversationState = "IDLE";
    static synchronized void publishConversation(String id, String state) { conversationId = id == null ? "" : id; conversationState = state == null ? "IDLE" : state; }
    static String conversationState() { return conversationState; }
    static String conversationId() { return conversationId; }
    private static volatile String inputPhase = "IDLE";
    static void publishInput(String value) { inputPhase = value; }
    static String inputPhase() { return inputPhase; }
    static synchronized boolean publishUnderstanding(long token, InteractionPorts.UnderstandingState update) {
        if (token != generation) return false;
        understanding = update; return true;
    }
    enum SpeechPhase { IDLE, PREPARING, PLAYING, WAITING, COMPLETE, CANCELLED, ERROR }
    static final class SpeechSnapshot {
        final long id, at;
        final SpeechPhase phase;
        final InteractionPorts.SpeechRequest request;
        final String error;
        SpeechSnapshot(long id, SpeechPhase phase, InteractionPorts.SpeechRequest request, long at, String error) {
            this.id = id; this.phase = phase; this.request = request; this.at = at; this.error = error;
        }
    }
    private static volatile SpeechSnapshot speech = new SpeechSnapshot(0, SpeechPhase.IDLE, null, 0, "");
    private static volatile String speechStatus = "未启动";
    private static final List<java.lang.ref.WeakReference<Runnable>> speechObservers = new ArrayList<>();
    static synchronized void addSpeechObserver(Runnable observer) { speechObservers.add(new java.lang.ref.WeakReference<>(observer)); }
    static synchronized void removeSpeechObserver(Runnable observer) {
        speechObservers.removeIf(reference -> reference.get() == null || reference.get() == observer);
    }
    static SpeechSnapshot speechSnapshot() { return speech; }
    static String speechStatus() { return speechStatus; }
    static synchronized boolean publishSpeechStatus(long token, String status) {
        if (token != generation) return false;
        speechStatus = status; return true;
    }
    static boolean publishSpeech(long token, SpeechSnapshot value) {
        List<Runnable> observers = new ArrayList<>();
        synchronized (GuardianRuntime.class) {
            if (token != generation || value.id < speech.id) return false;
            speech = value;
            speechObservers.removeIf(reference -> reference.get() == null);
            for (java.lang.ref.WeakReference<Runnable> reference : speechObservers) {
                Runnable observer = reference.get(); if (observer != null) observers.add(observer);
            }
        }
        // 服务在主线程发布播放阶段；可见页面同步更新，不等待异步广播。
        for (Runnable observer : observers) observer.run();
        return true;
    }
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
    static synchronized long begin() {
        generation++; wake = new WakeSnapshot("未启动", 0);
        understanding = new InteractionPorts.UnderstandingState("未启动", false, 0, null, 0);
        conversationId = ""; conversationState = "IDLE";
        inputPhase = "IDLE";
        speech = new SpeechSnapshot(0, SpeechPhase.IDLE, null, 0, ""); speechStatus = "未启动";
        return generation;
    }
    static synchronized boolean publish(long token, Snapshot snapshot) {
        if (token != generation) return false;
        latest = snapshot; return true;
    }
    static Snapshot snapshot() { return latest; }
}

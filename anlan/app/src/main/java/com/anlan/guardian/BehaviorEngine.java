package com.anlan.guardian;

import java.util.List;
import java.util.Random;

/** 纯 Java、单线程状态机。所有计时均由调用方提供的单调时钟驱动，可确定性测试。 */
public final class BehaviorEngine {
    public static final class Timing {
        public final long drowsyMs, sleepMs, peekEveryMs, peekMs, eventHoldMs;
        public Timing(long drowsy, long sleep, long peekEvery, long peek, long hold) {
            if (drowsy <= 0 || sleep <= drowsy || peekEvery <= peek || peek <= 0 || hold < 2000)
                throw new IllegalArgumentException("行为时间配置不合法");
            drowsyMs = drowsy; sleepMs = sleep; peekEveryMs = peekEvery;
            peekMs = peek; eventHoldMs = hold;
        }
        public static Timing defaults() { return new Timing(90000, 180000, 45000, 2400, 5500); }
    }

    public static final class State {
        public final Emotion emotion;
        public final String message;
        public final String eventName;
        public final int variant;
        public final boolean guarding;
        State(Emotion emotion, String message, int variant, boolean guarding, String eventName) {
            this.emotion = emotion; this.message = message;
            this.variant = variant; this.guarding = guarding;
            this.eventName = eventName;
        }
    }

    private final Timing timing;
    private final Random random;
    private boolean guarding;
    private String error = "";
    private long lastActivity, eventAt, releaseAt, attentionUntil, nextAttention, previewUntil;
    private SoundEvent current;
    private boolean currentActive;
    private int variant;
    private Emotion preview;
    private String previewName = "";
    private Emotion modelEmotion;
    private String modelName = "";
    private long modelUntil;

    public BehaviorEngine(Timing timing, Random random, long now) {
        this.timing = timing; this.random = random; lastActivity = now;
    }

    public void setGuarding(boolean enabled, long now) {
        if (guarding == enabled && error.isEmpty()) return;
        guarding = enabled; error = ""; current = null; preview = null;
        modelEmotion = null; modelName = ""; modelUntil = 0; attentionUntil = 0; nextAttention = now; lastActivity = now;
    }

    public void fail(String message, long now) {
        setGuarding(false, now); error = message;
    }

    public void activity(long now) {
        if (!guarding) return;
        lastActivity = now;
        if (current == null && now >= nextAttention) {
            attentionUntil = now + 2200; nextAttention = now + 12000;
        }
    }

    /** 更新模型对环境声音的日常拟人反应，分数不足时不打扰当前状态。 */
    public void model(String rawLabel, String displayName, float score, long now) {
        if (!guarding) return;
        if (score < .25f || rawLabel == null || rawLabel.isEmpty()) {
            if (now >= modelUntil) modelEmotion = null;
            return;
        }
        Emotion mapped = ModelEmotionMapper.emotion(rawLabel);
        if (mapped == null) return;
        modelEmotion = mapped; modelName = displayName == null ? "" : displayName; modelUntil = now + 1400;
        lastActivity = now;
        if (mapped == Emotion.LISTENING) activity(now);
    }

    public void events(List<SoundEvent> events, long now) {
        if (!guarding) return;
        SoundEvent best = null;
        currentActive = false;
        for (SoundEvent event : events) {
            if (!event.active) continue;
            if (current != null && event.id.equals(current.id)) currentActive = true;
            if (best == null || event.priority() > best.priority()
                    || (event.priority() == best.priority() && event.start > best.start)) best = event;
        }
        if (best != null) {
            lastActivity = now;
            if (current == null || (!best.id.equals(current.id)
                    && (best.priority() > current.priority() || !currentActive && now >= releaseAt))) {
                current = best; eventAt = now; variant = (variant + 1 + random.nextInt(2)) % 3;
                preview = null;
                currentActive = true;
            }
        }
        if (currentActive) releaseAt = now + timing.eventHoldMs;
    }

    /** 仅供本地预览；真实高优先级事件仍然优先。 */
    public void preview(Emotion emotion, long now) {
        if (current != null && current.priority() >= 2) return;
        preview = emotion; previewName = emotion.title; previewUntil = now + 7000;
        variant = (variant + 1) % 3;
    }

    public State state(long now) {
        if (!error.isEmpty()) return state(Emotion.UNAVAILABLE, "监听异常 · 请检查麦克风后重新开始");
        if (current != null) {
            if (!currentActive && now >= releaseAt + 2400) {
                current = null; lastActivity = now;
            } else if (!currentActive && now >= releaseAt) {
                return state(Emotion.RELIEVED, "声音已结束，我继续留意");
            } else {
                long age = now - eventAt;
                if (current.priority() >= 3) {
                    Emotion face = age < 650 ? Emotion.STARTLED
                            : age < 2000 ? Emotion.SURPRISED
                            : current.priority() == 4 ? Emotion.ALERT : Emotion.TENSE;
                    return state(face, "留意到" + current.name + "，正在关注");
                }
                if (current.priority() == 2)
                    return state(age < 850 ? Emotion.ATTENTIVE
                            : variant == 0 ? Emotion.WORRIED : Emotion.CARING,
                            "听到了" + current.name + "，我在这里");
                return state(age < 600 ? Emotion.ATTENTIVE : Emotion.CURIOUS, "听到了" + current.name);
            }
        }
        if (preview != null && now < previewUntil) return state(preview, "表情预览 · " + previewName);
        preview = null;
        if (modelEmotion != null && now < modelUntil) return state(modelEmotion, "听到" + modelName + "，我在留意");
        modelEmotion = null;
        if (!guarding) return state(Emotion.CALM, "正在准备声音守护");
        if (now < attentionUntil) return state(Emotion.LISTENING, "听到了动静，我在留意");
        long quiet = now - lastActivity;
        if (quiet >= timing.sleepMs) {
            boolean peek = (quiet - timing.sleepMs) % timing.peekEveryMs < timing.peekMs;
            return state(peek ? Emotion.WAKING : Emotion.SLEEPING, "小憩一下，声音守护仍在继续");
        }
        if (quiet >= timing.drowsyMs) return state(Emotion.DROWSY, "安静陪伴，持续守护");
        return state(quiet % 35000 < 4500 ? Emotion.CONTENT : Emotion.CALM, "我在，安心就好");
    }

    public void touch(long now) {
        lastActivity = now;
        if (current == null) { attentionUntil = now + 1600; nextAttention = now + 12000; }
    }

    public void simulate(SoundEvent event, long now) {
        if (current != null && currentActive) return;
        current = event; eventAt = now; currentActive = false;
        releaseAt = now + timing.eventHoldMs; preview = null;
    }

    private State state(Emotion emotion, String text) { return new State(emotion, text, variant, guarding, current == null ? "" : current.name); }
}

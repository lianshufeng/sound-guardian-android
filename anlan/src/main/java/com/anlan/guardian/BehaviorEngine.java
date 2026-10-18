package com.anlan.guardian;

import java.util.List;
import java.util.Random;

/** 纯 Java、单线程状态机。所有计时均由调用方提供的单调时钟驱动，可确定性测试。 */
public final class BehaviorEngine {
    static final String SNAP_MESSAGE = "这个节奏让我也开心起来啦！";
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
        public final boolean touchGaze;
        public final float gazeX, gazeY;
        State(Emotion emotion, String message, int variant, boolean guarding, String eventName) {
            this(emotion, message, variant, guarding, eventName, false, 0, 0);
        }
        State(Emotion emotion, String message, int variant, boolean guarding, String eventName,
              boolean touchGaze, float gazeX, float gazeY) {
            this.emotion = emotion; this.message = message;
            this.variant = variant; this.guarding = guarding;
            this.eventName = eventName;
            this.touchGaze = touchGaze; this.gazeX = gazeX; this.gazeY = gazeY;
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
    private long wakeAt = -1, lastWakeAt = -1;
    private boolean wakeFromRest;
    private long restUntil;
    private long speechId = -1, speechListeningUntil;
    private boolean speaking;
    private String speechText = "";
    private final TouchInteraction touch = new TouchInteraction();
    private final EmpathyPolicy empathy = new EmpathyPolicy();
    private long voiceUntil;
    private String inputPhase = "IDLE";

    public void inputPhase(String phase) {
        if (!inputPhase.equals("IDLE") && phase.equals("IDLE")) { wakeAt = -1; speechListeningUntil = 0; }
        inputPhase = phase;
    }

    public void configureTouch(long holdMs, long lingerMs) { touch.configure(holdMs, lingerMs); }
    public void configureEmpathy(long holdMs, long lingerMs) {
        empathy.configure(holdMs, lingerMs);
    }
    public void gesture(int action, float x, float y, long now) {
        Emotion before = state(now).emotion;
        boolean waking = before == Emotion.SLEEPING || before == Emotion.DROWSY;
        touch.accept(action, x, y, now, waking);
        if (action != TouchInteraction.CANCEL) { lastActivity = now; preview = null; }
    }
    public void voiceActivity(boolean active, long at, long now) {
        if (!guarding || at > now || now - at > 1500) return;
        if (active) { voiceUntil = at + 1300; lastActivity = now; }
    }
    public void understanding(long id, String label, long at, long now) {
        if (guarding && empathy.accept(id, label, at, now)) lastActivity = now;
    }

    public void speechStarted(long id, String text, long now) {
        if (!guarding || id <= speechId) return;
        speechId = id; speechText = text; speaking = true; lastActivity = now;
        wakeAt = -1; preview = null; speechListeningUntil = 0;
        empathy.clear(); voiceUntil = 0;
    }
    public void speechFinished(long id, long now) {
        if (!guarding || id < speechId || id == speechId && !speaking) return;
        // 页面可能在后台错过新请求的开始阶段，完成快照仍须清除旧的播报状态。
        speechId = id; speaking = false; lastActivity = Math.max(lastActivity, now); speechListeningUntil = speechText.equals(SNAP_MESSAGE) ? 0 : now + 4000;
    }

    /** 唤醒事件按单调时间去重；后台过期事件不在返回主页时重新播放。 */
    public void wakeWord(long at, long now) {
        if (!guarding || at <= lastWakeAt || at > now) return;
        if (lastWakeAt >= 0 && at - lastWakeAt < InteractionPorts.WAKE_COOLDOWN_MS) return;
        lastWakeAt = at;
        if (now - at >= 5400) return;
        Emotion before = state(now).emotion;
        wakeFromRest = now < restUntil || before == Emotion.SLEEPING || before == Emotion.DROWSY || before == Emotion.WAKING;
        wakeAt = at; lastActivity = now; preview = null;
    }

    public BehaviorEngine(Timing timing, Random random, long now) {
        this.timing = timing; this.random = random; lastActivity = now;
    }

    public void setGuarding(boolean enabled, long now) {
        if (guarding == enabled && error.isEmpty()) return;
        guarding = enabled; error = ""; current = null; preview = null;
        wakeAt = -1; lastWakeAt = -1; restUntil = 0;
        speaking = false; speechId = -1; speechListeningUntil = 0;
        touch.clear(); empathy.reset(); voiceUntil = 0; inputPhase = "IDLE";
        modelEmotion = null; modelName = ""; modelUntil = 0; attentionUntil = 0; nextAttention = now; lastActivity = now;
    }

    public void fail(String message, long now) {
        setGuarding(false, now); error = message;
    }

    public void activity(long now) {
        if (!guarding) return;
        if (now - lastActivity >= timing.drowsyMs) restUntil = now + 2500;
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
        if (mapped == Emotion.LISTENING && now - lastActivity >= timing.drowsyMs) restUntil = now + 2500;
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
        Emotion empathetic = empathy.emotion(now);
        // 先清理过期事件，避免第一帧仍被旧警报挡住，导致实际播报与字幕不同步。
        if (current != null && !currentActive && now >= releaseAt + 2400) { current = null; lastActivity = now; }
        if (!error.isEmpty()) return state(Emotion.UNAVAILABLE, "监听异常 · 请检查麦克风后重新开始");
        if (guarding && (current == null || current.priority() < 3)) {
            if (speaking) return state(speechText.equals(SNAP_MESSAGE) ? Emotion.HAPPY : Emotion.ACKNOWLEDGE, speechText);
        }
        long wakeAge = wakeAt < 0 ? Long.MAX_VALUE : now - wakeAt;
        long wakingMs = wakeFromRest ? 600 : 0;
        boolean responding = guarding && wakeAge < wakingMs + 4800;
        if (responding && inputPhase.equals("IDLE") && (current == null || current.priority() < 3)) {
            if (wakeAge < wakingMs) return state(Emotion.WAKING, "我醒啦");
            if (wakeAge < wakingMs + 800) return state(Emotion.ACKNOWLEDGE, "我在");
            if (!touch.active(now) && empathetic == null) return state(Emotion.LISTENING, "我在");
        }
        if (touch.active(now) && (current == null || current.priority() < 3))
            return new State(touch.emotion(now), "我在陪你", variant, guarding, current == null ? "" : current.name,
                    true, touch.gazeX(now), touch.gazeY(now));
        if (guarding && !inputPhase.equals("IDLE") && (current == null || current.priority() < 3))
            return state(inputPhase.equals("LISTENING") ? Emotion.LISTENING : Emotion.ATTENTIVE,
                    inputPhase.equals("LISTENING") ? "我在聆听" : "正在准备倾听");
        if (current != null) {
            if (!currentActive && now >= releaseAt) {
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
                if (current.label.equals("Finger snapping"))
                    return state(Emotion.HAPPY, SNAP_MESSAGE);
                return state(age < 600 ? Emotion.ATTENTIVE : Emotion.CURIOUS, "听到了" + current.name);
            }
        }
        if (preview != null && now < previewUntil) return state(preview, "表情预览 · " + previewName);
        preview = null;
        if (guarding && empathetic != null) return state(empathetic, "我在认真听");
        if (guarding && (now < voiceUntil || now < speechListeningUntil)) return state(Emotion.LISTENING, "我在聆听");
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
        restUntil = 0;
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

package com.anlan.guardian;

/** 交互能力边界：关怀与语音交互共用本地播报和语音输入。 */
public final class InteractionPorts {
    private InteractionPorts() { }
    static final long WAKE_COOLDOWN_MS = 3000;

    public interface CarePolicy {
        /** 根据确认事件和冷却策略决定是否建议询问；不得直接启动外部动作。 */
        CareRequest evaluate(SoundEvent event);
    }
    public static final class CareRequest {
        public enum Mode { NOTICE, ASK }
        public final int schemaVersion = 1;
        public final String careId, scenarioId, eventId, question;
        public final Mode mode;
        public final long replyTimeoutMs, expiresAt;
        final boolean safety;
        public CareRequest(String eventId, String question) {
            this(java.util.UUID.randomUUID().toString(), "manual", eventId, question, Mode.ASK, 20000, System.currentTimeMillis() + 60000);
        }
        public CareRequest(String careId, String scenarioId, String eventId, String question,
                           Mode mode, long replyTimeoutMs, long expiresAt) {
            this(careId, scenarioId, eventId, question, mode, replyTimeoutMs, expiresAt, false);
        }
        CareRequest(String careId, String scenarioId, String eventId, String question,
                    Mode mode, long replyTimeoutMs, long expiresAt, boolean safety) {
            if (careId == null || careId.trim().isEmpty() || careId.length() > 128
                    || scenarioId == null || scenarioId.trim().isEmpty() || scenarioId.length() > 128 || eventId != null && eventId.length() > 256
                    || question == null || question.trim().isEmpty() || question.codePointCount(0, question.length()) > 300
                    || mode == null || expiresAt <= 0
                    || mode == Mode.NOTICE && replyTimeoutMs != 0
                    || mode == Mode.ASK && (replyTimeoutMs < 5000 || replyTimeoutMs > 60000))
                throw new IllegalArgumentException("关怀请求参数不合法");
            this.careId = careId.trim(); this.scenarioId = scenarioId; this.eventId = eventId == null ? "" : eventId;
            this.question = question.trim(); this.mode = mode; this.replyTimeoutMs = replyTimeoutMs; this.expiresAt = expiresAt;
            this.safety = safety;
        }
    }
    public enum ConversationState { IDLE, LISTENING, THINKING, SPEAKING, WAITING, UNAVAILABLE }
    public interface WakeWordDetector extends AutoCloseable {
        /** 与声音推理共享采集流，不开启第二个麦克风。 */
        void acceptPcm(short[] pcm, int count, int sampleRate);
        void setListener(Runnable onWake);
        @Override void close();
    }
    public interface SpeechInput {
        /** UnderstandingWorker 的分段中文输入；取消后旧会话不得继续回调。 */
        void start(Result result);
        default void start(Result result, long timeoutMs) { start(result); }
        void cancel();
        interface Result {
            default void onReady() { }
            void onText(String text);
            void onError(String message);
            default void onUnderstanding(com.soundguardian.core.VoiceUnderstandingResult result) { onText(result.text); }
            /** 仅在 VAD 当前话轮已结束且其片段全部完成识别后调用。 */
            default void onUtteranceEnd() { }
            default void onVoice(boolean speaking) { }
        }
    }
    /** 交互层状态契约；页面及运行时不依赖后台工作器的嵌套实现类型。 */
    public static final class UnderstandingState {
        public final String status;
        public final boolean voice;
        public final long at;
        public final com.soundguardian.core.VoiceUnderstandingResult result;
        public final int dropped;
        /** 当前输入窗口的短时归一化音量；仅用于界面波浪，不代表录音内容。 */
        public final float level;
        public final boolean window;
        public UnderstandingState(String status, boolean voice, long at,
                                  com.soundguardian.core.VoiceUnderstandingResult result, int dropped) {
            this(status, voice, at, result, dropped, 0f);
        }
        public UnderstandingState(String status, boolean voice, long at,
                                  com.soundguardian.core.VoiceUnderstandingResult result, int dropped, float level) {
            this(status, voice, at, result, dropped, level, false);
        }
        public UnderstandingState(String status, boolean voice, long at,
                                  com.soundguardian.core.VoiceUnderstandingResult result, int dropped, float level, boolean window) {
            this.status = status; this.voice = voice; this.at = at; this.result = result; this.dropped = dropped;
            this.level = Float.isFinite(level) ? Math.max(0f, Math.min(1f, level)) : 0f;
            this.window = window;
        }
    }
    public interface DialogueAgent {
        void stream(String sessionId, java.util.List<DialogueMessage> messages, int maxTokens, Reply result);
        void cancel(String sessionId);
        interface Reply {
            /** 在网络线程同步消费增量；可阻塞形成背压，但必须支持取消并有超时。 */
            void onDelta(String text) throws Exception;
            void onComplete(String reason);
            void onError(String reason);
        }
    }
    public static final class DialogueMessage {
        public final String role, text;
        public DialogueMessage(String role, String text) { this.role = role; this.text = text; }
    }
    public static final class SpeechRequest {
        public enum Source { WAKE, CARE, DIALOGUE, GENERAL, INTERACTION, CUE }
        public final Source source;
        public final String text, eventId, careId;
        final SpeechOutput.Progress observer;
        public SpeechRequest(Source source, String text, String eventId) {
            this(source, text, eventId, "", null);
        }
        SpeechRequest(Source source, String text, String eventId, String careId, SpeechOutput.Progress observer) {
            if (source == null || text == null || text.trim().isEmpty()) throw new IllegalArgumentException("播报请求不合法");
            this.source = source; this.text = text.trim(); this.eventId = eventId == null ? "" : eventId;
            this.careId = careId; this.observer = observer;
        }
        public static SpeechRequest wake() { return new SpeechRequest(Source.WAKE, "我在", ""); }
        static SpeechRequest fingerSnaps(String eventId) { return new SpeechRequest(Source.INTERACTION, BehaviorEngine.SNAP_MESSAGE, eventId); }
        public static SpeechRequest care(CareRequest request) { return care(request, null); }
        static SpeechRequest care(CareRequest request, SpeechOutput.Progress observer) {
            return new SpeechRequest(Source.CARE, request.question, request.eventId, request.careId, observer);
        }
    }
    /** 内部播音适配契约；业务统一使用 SpeechPlayback，不能直接实例化播放器。 */
    public interface SpeechStream {
        /** 原子追加；false 表示缓存满，未接收任何文字，上游须保留该片段重试。 */
        boolean append(String delta);
        void end();
        void cancel();
    }
    public interface SpeechStreamListener {
        default void onSegmentStarted(SpeechRequest segment) { }
        default void onComplete() { }
        default void onCancelled() { }
        default void onError(String message) { }
    }
    public interface SpeechOutput extends AutoCloseable {
        void speak(SpeechRequest request, Progress progress);
        default void speak(String text, Progress progress) { speak(new SpeechRequest(SpeechRequest.Source.GENERAL, text, ""), progress); }
        void stop();
        @Override void close();
        interface Progress {
            default void onPreparing() { }
            default void onStart() { }
            default void onLevel(float amplitude) { }
            default void onSegmentStart(SpeechRequest segment) { onStart(); }
            default void onSegmentComplete(SpeechRequest segment) { }
            void onComplete();
            void onError(String message);
            default void onCancelled() { }
        }
    }
}

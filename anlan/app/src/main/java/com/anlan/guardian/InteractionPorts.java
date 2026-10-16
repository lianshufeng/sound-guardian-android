package com.anlan.guardian;

/** 后续交互边界。首版不实例化、不连接网络、不播报或发起求助。 */
public final class InteractionPorts {
    private InteractionPorts() { }

    public interface CarePolicy {
        /** 根据确认事件和冷却策略决定是否建议询问；不得直接启动外部动作。 */
        CareRequest evaluate(SoundEvent event);
    }
    public static final class CareRequest {
        public final String eventId, question;
        public CareRequest(String eventId, String question) {
            this.eventId = eventId; this.question = question;
        }
    }
    public enum ConversationState { IDLE, LISTENING, THINKING, SPEAKING, WAITING, UNAVAILABLE }
    public interface WakeWordDetector extends AutoCloseable {
        /** 后续与推理共享采集流，不再竞争开启第二个麦克风。 */
        void acceptPcm(short[] pcm, int count, int sampleRate);
        void setListener(Runnable onWake);
        @Override void close();
    }
    public interface SpeechInput {
        void start(Result result);
        void cancel();
        interface Result { void onText(String text); void onError(String message); }
    }
    public interface DialogueAgent {
        void reply(String sessionId, String text, Reply result);
        void cancel(String sessionId);
        interface Reply { void onReply(String text, Emotion emotion); void onError(String message); }
    }
    public interface SpeechOutput {
        void speak(String text, Progress progress);
        void stop();
        interface Progress { void onLevel(float amplitude); void onComplete(); void onError(String message); }
    }
}

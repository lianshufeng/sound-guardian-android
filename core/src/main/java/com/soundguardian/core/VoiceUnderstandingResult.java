package com.soundguardian.core;

/** 与具体推理运行库无关的不可变语音结果；不携带 native 对象或界面引用。 */
public final class VoiceUnderstandingResult {
    public final long id, at;
    public final String text, emotion, language, event;
    public final float durationMs, inferenceMs;
    public VoiceUnderstandingResult(long id, long at, String text, String emotion, String language, String event,
                                    float durationMs, float inferenceMs) {
        this.id = id; this.at = at; this.text = text; this.emotion = emotion; this.language = language; this.event = event;
        this.durationMs = durationMs; this.inferenceMs = inferenceMs;
    }
}

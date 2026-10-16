package com.soundguardian.core;

/** 两款应用共用的音频输入和事件上下文契约。 */
public final class SoundContract {
    private SoundContract() { }
    public static final int SAMPLE_RATE = 16000;
    public static final int INPUT_SAMPLES = 15600;
    public static final int HOP_SAMPLES = 7680;
    public static final int OVERLAP_SAMPLES = INPUT_SAMPLES - HOP_SAMPLES;
    public static final int CLASSES = 521;
    public static final int WINDOW_MS = INPUT_SAMPLES * 1000 / SAMPLE_RATE;
    public static final int CONTEXT_MS = 3000;
    public static final int EVIDENCE_MS = 1200;
}

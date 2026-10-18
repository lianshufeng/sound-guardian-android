package com.soundguardian.core;

import android.content.Context;
import com.k2fsa.sherpa.onnx.*;
import java.io.IOException;
import org.json.JSONObject;

/** 共享文字及声音情绪识别；不采集音频，不创建播放器。所有 native 调用须在所属工作线程串行执行。 */
public final class SherpaVoiceUnderstanding implements AutoCloseable {
    private static final String BASE = "understanding/";
    private final OfflineRecognizer recognizer;

    public static final class Settings {
        public final int threads, sampleRate;
        public final String language;
        public final float threshold, minSpeech, minSilence, maxSegment;
        public final long sessionSilenceMs, idleReleaseMs;
        public Settings(Context context) throws Exception {
            JSONObject json = new JSONObject(AppConfig.read(context, BASE + "config.json"));
            sampleRate = json.getInt("sample_rate"); threads = json.getInt("num_threads"); language = json.getString("language");
            threshold = (float) json.getDouble("vad_threshold"); minSpeech = (float) json.getDouble("min_speech_s");
            minSilence = (float) json.getDouble("min_silence_s"); maxSegment = (float) json.getDouble("max_segment_s");
            sessionSilenceMs = json.getLong("session_silence_ms"); idleReleaseMs = json.getLong("idle_release_ms");
            if (sampleRate != SoundContract.SAMPLE_RATE || threads < 1 || threads > 2 || !language.equals("zh")
                    || !(threshold > 0 && threshold < 1 && minSpeech > 0 && minSilence > 0 && maxSegment >= 2 && maxSegment <= 8)
                    || sessionSilenceMs < 2000 || sessionSilenceMs > 30000 || idleReleaseMs < 10000 || idleReleaseMs > 300000)
                throw new IOException("语音理解配置不合法");
        }
    }
    public SherpaVoiceUnderstanding(Context context, Settings settings) throws IOException {
        check(context, "model.int8.onnx", "tokens.txt");
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.getFeatConfig().setSampleRate(settings.sampleRate);
        config.getModelConfig().setNumThreads(settings.threads);
        config.getModelConfig().setTokens(BASE + "tokens.txt");
        config.getModelConfig().getSenseVoice().setModel(BASE + "model.int8.onnx");
        config.getModelConfig().getSenseVoice().setLanguage(settings.language);
        config.getModelConfig().getSenseVoice().setUseInverseTextNormalization(true);
        recognizer = new OfflineRecognizer(context.getAssets(), config);
    }
    public VoiceUnderstandingResult recognize(float[] samples, long id) {
        if (samples.length == 0 || samples.length > SoundContract.SAMPLE_RATE * 9)
            throw new IllegalArgumentException("语音片段长度不合法");
        OfflineStream stream = recognizer.createStream();
        if (stream.getPtr() == 0) { stream.release(); throw new IllegalStateException("文字识别流创建失败"); }
        long start = android.os.SystemClock.elapsedRealtimeNanos();
        try {
            stream.acceptWaveform(samples, SoundContract.SAMPLE_RATE); recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);
            return new VoiceUnderstandingResult(id, android.os.SystemClock.elapsedRealtime(), result.getText(), result.getEmotion(),
                    result.getLang(), result.getEvent(), samples.length * 1000f / SoundContract.SAMPLE_RATE,
                    (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1000000f);
        } finally { stream.release(); }
    }
    @Override public void close() { recognizer.release(); }

    public static final class VoiceDetector implements AutoCloseable {
        private final Vad vad;
        public VoiceDetector(Context context, Settings settings) throws IOException {
            check(context, "silero_vad.onnx");
            VadModelConfig config = new VadModelConfig();
            config.setSampleRate(settings.sampleRate); config.setNumThreads(1);
            SileroVadModelConfig silero = config.getSileroVadModelConfig();
            silero.setModel(BASE + "silero_vad.onnx"); silero.setThreshold(settings.threshold);
            silero.setWindowSize(512); silero.setMinSpeechDuration(settings.minSpeech);
            silero.setMinSilenceDuration(settings.minSilence); silero.setMaxSpeechDuration(settings.maxSegment);
            vad = new Vad(context.getAssets(), config);
        }
        public void accept(float[] samples) { vad.acceptWaveform(samples); }
        public boolean speaking() { return vad.isSpeechDetected(); }
        public boolean hasSegment() { return !vad.empty(); }
        public float[] takeSegment() { float[] samples = vad.front().getSamples(); vad.pop(); return samples; }
        public void reset() { vad.reset(); vad.clear(); }
        @Override public void close() { vad.release(); }
    }
    private static void check(Context context, String... names) throws IOException {
        for (String name : names) try (java.io.InputStream ignored = context.getAssets().open(BASE + name)) { }
    }
}

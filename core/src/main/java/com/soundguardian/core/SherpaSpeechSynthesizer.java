package com.soundguardian.core;

import android.content.Context;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import java.io.IOException;
import org.json.JSONObject;

/** 共享中文合成适配器，只产生 PCM；业务必须通过应用的统一 SpeechOutput 播报。 */
public final class SherpaSpeechSynthesizer implements AutoCloseable {
    private final OfflineTts tts;
    private final int speaker;
    private final float speed;

    public SherpaSpeechSynthesizer(Context context) throws Exception {
        JSONObject settings = new JSONObject(AppConfig.read(context, "tts/config.json"));
        speaker = settings.getInt("speaker_id"); speed = (float) settings.getDouble("speed");
        if (speaker < 0 || speed <= 0 || !Float.isFinite(speed)) throw new IOException("TTS 音色或语速配置不合法");
        JSONObject manifest = new JSONObject(AppConfig.read(context, "tts/manifest.json"));
        if (!"sherpa-onnx-vits-zh-ll".equals(manifest.getString("model"))) throw new IOException("TTS 模型类型不合法");
        String base = "tts/model/";
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.getModel().setNumThreads(settings.getInt("num_threads"));
        com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig model = config.getModel().getVits();
        model.setModel(base + "model.onnx"); model.setLexicon(base + "lexicon.txt"); model.setTokens(base + "tokens.txt");
        config.setRuleFsts(base + "phone.fst," + base + "date.fst," + base + "number.fst");
        tts = new OfflineTts(context.getAssets(), config);
        if (speaker >= tts.numSpeakers()) { tts.release(); throw new IOException("TTS 音色编号超出模型范围"); }
    }

    public GeneratedAudio synthesize(String text) {
        return synthesize(text, () -> true);
    }

    public GeneratedAudio synthesize(String text, java.util.function.BooleanSupplier active) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("播报文字不能为空");
        // JNI 按 invoke(float[]): Integer 查找回调；Java lambda 的擦除签名不兼容。
        GeneratedAudio audio = tts.generateWithCallback(text, speaker, speed, new kotlin.jvm.functions.Function1<float[], Integer>() {
            @Override public Integer invoke(float[] samples) { return active.getAsBoolean() ? 1 : 0; }
        });
        if (!active.getAsBoolean()) throw new java.util.concurrent.CancellationException("播报合成已取消");
        if (audio.getSamples().length == 0) throw new IllegalStateException("TTS 未生成音频");
        float peak = 0;
        for (float sample : audio.getSamples()) {
            if (!Float.isFinite(sample)) throw new IllegalStateException("TTS 生成了无效音频（NaN/Infinity）");
            peak = Math.max(peak, Math.abs(sample));
        }
        if (peak < .001f) throw new IllegalStateException("TTS 生成了静音");
        return audio;
    }

    @Override public void close() { tts.release(); }
}

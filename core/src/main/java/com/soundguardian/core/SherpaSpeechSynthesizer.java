package com.soundguardian.core;

import android.content.Context;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
        String base = "tts/model/";
        String revision = new JSONObject(AppConfig.read(context, "tts/manifest.json")).getString("archive_sha256");
        if (!revision.matches("[a-f0-9]{64}")) throw new IOException("TTS 模型版本清单不合法");
        File data = new File(context.getFilesDir(), "tts-" + revision.substring(0, 16) + "/espeak-ng-data");
        copyData(context, base + "espeak-ng-data", data);
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.getModel().setNumThreads(settings.getInt("num_threads"));
        OfflineTtsKokoroModelConfig model = config.getModel().getKokoro();
        model.setModel(base + "model.onnx"); model.setVoices(base + "voices.bin");
        model.setTokens(base + "tokens.txt"); model.setDataDir(data.getAbsolutePath());
        model.setLexicon(base + "lexicon-us-en.txt," + base + "lexicon-zh.txt");
        config.setRuleFsts(base + "date-zh.fst," + base + "number-zh.fst," + base + "phone-zh.fst");
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

    private static synchronized void copyData(Context context, String asset, File target) throws IOException {
        String[] children = context.getAssets().list(asset);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("TTS 数据目录创建失败");
            for (String child : children) copyData(context, asset + "/" + child, new File(target, child));
        } else if (!target.isFile()) {
            File temporary = new File(target.getPath() + ".tmp");
            try (InputStream in = context.getAssets().open(asset); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            if (!temporary.renameTo(target)) throw new IOException("TTS 数据文件写入失败");
        }
    }
    @Override public void close() { tts.release(); }
}

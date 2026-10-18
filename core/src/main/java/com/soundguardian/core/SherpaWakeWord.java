package com.soundguardian.core;

import android.content.Context;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import java.io.IOException;

/** 单线程使用；由调用方提供共享 PCM，不拥有麦克风。运行库由启用此能力的应用提供。 */
public final class SherpaWakeWord implements AutoCloseable {
    private static final String DIRECTORY = "wakeword/";
    private final KeywordSpotter spotter;
    private OnlineStream stream;
    private boolean closed;

    public SherpaWakeWord(Context context) throws IOException {
        // 初始化 native 对象前检查文件，避免缺失资产传入底层。
        String[] models = {"encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                "joiner-epoch-12-avg-2-chunk-16-left-64.onnx"};
        for (String name : new String[]{models[0], models[1], models[2], "tokens.txt", "keywords.txt"}) {
            try (java.io.InputStream ignored = context.getAssets().open(DIRECTORY + name)) { }
        }
        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.getFeatConfig().setSampleRate(SoundContract.SAMPLE_RATE);
        OnlineModelConfig model = config.getModelConfig();
        model.getTransducer().setEncoder(DIRECTORY + models[0]);
        model.getTransducer().setDecoder(DIRECTORY + models[1]);
        model.getTransducer().setJoiner(DIRECTORY + models[2]);
        model.setTokens(DIRECTORY + "tokens.txt");
        model.setModelType("zipformer2"); model.setNumThreads(1);
        config.setKeywordsFile(DIRECTORY + "keywords.txt");
        spotter = new KeywordSpotter(context.getAssets(), config);
        OnlineStream created;
        try {
            created = spotter.createStream("");
            if (created.getPtr() == 0) throw new IOException("唤醒词流创建失败");
        } catch (RuntimeException | IOException error) { spotter.release(); throw error; }
        stream = created;
    }

    public boolean accept(float[] samples) {
        if (closed) throw new IllegalStateException("唤醒引擎已关闭");
        stream.acceptWaveform(samples, SoundContract.SAMPLE_RATE);
        boolean detected = false;
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
            if (!spotter.getResult(stream).getKeyword().isEmpty()) {
                detected = true; reset(); break;
            }
        }
        return detected;
    }

    public void reset() {
        if (closed) return;
        // 新会话同时清除声学缓存，避免前一段语音影响再次唤醒。
        stream.release(); stream = spotter.createStream("");
        if (stream.getPtr() == 0) throw new IllegalStateException("唤醒词流重建失败");
    }
    @Override public void close() {
        if (closed) return;
        closed = true; stream.release(); spotter.release();
    }
}

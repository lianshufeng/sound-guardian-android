package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.SherpaSpeechSynthesizer;
import com.soundguardian.core.SherpaVoiceUnderstanding;
import com.soundguardian.core.SherpaWakeWord;
import com.soundguardian.core.YamnetClassifier;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** 显式传入 profile_resources=true 才运行；每种模式单独启动测试进程，避免原生分配器互相污染。 */
@RunWith(AndroidJUnit4.class)
public final class ResourceProfileDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();
    private long wall, cpu;
    private volatile long peakRssKiB, peakNativeKiB;
    private volatile boolean sampling;

    private void begin() { wall = SystemClock.elapsedRealtime(); cpu = Process.getElapsedCpuTime(); }
    private void report(String phase, double audioMs) throws Exception {
        long elapsed = SystemClock.elapsedRealtime() - wall, used = Process.getElapsedCpuTime() - cpu;
        Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
        JSONObject result = new JSONObject().put("phase", phase).put("wall_ms", elapsed).put("cpu_ms", used)
                .put("cpu_one_core_percent", elapsed == 0 ? 0 : used * 100.0 / elapsed)
                .put("pss_kib", memory.getTotalPss()).put("native_alloc_kib", Debug.getNativeHeapAllocatedSize() / 1024)
                .put("sampled_peak_rss_kib", peakRssKiB).put("sampled_peak_native_kib", peakNativeKiB)
                .put("audio_ms", audioMs);
        Bundle status = new Bundle(); status.putString("resource_profile", result.toString());
        instrumentation.sendStatus(0, status);
    }

    @Test public void measureSelectedScenario() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("性能采样只在显式请求时执行", "true".equals(args.getString("profile_resources")));
        String mode = args.getString("profile_mode", "service");
        sampling = true;
        Thread sampler = new Thread(() -> {
            while (sampling) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/status"))) {
                    String line;
                    while ((line = reader.readLine()) != null)
                        if (line.startsWith("VmRSS:")) peakRssKiB = Math.max(peakRssKiB, Long.parseLong(line.split("\\s+")[1]));
                    peakNativeKiB = Math.max(peakNativeKiB, Debug.getNativeHeapAllocatedSize() / 1024);
                    Thread.sleep(200);
                } catch (Exception ignored) { break; }
            }
        }, "resource-sampler");
        sampler.start();
        try {
        app.stopService(new Intent(app, GuardianService.class)); SystemClock.sleep(1000);
        begin(); report("process_baseline", 0);
        if (mode.equals("service")) { service(); return; }
        float[] input = fixture();
        begin();
        switch (mode) {
            case "yamnet":
                try (YamnetClassifier model = new YamnetClassifier(app)) {
                    report("yamnet_load", 0); begin();
                    float[] window = java.util.Arrays.copyOf(input, 15600);
                    for (int i = 0; i < 40; i++) assertEquals(521, model.classify(window).length);
                    report("yamnet_40_windows", 40 * 480);
                }
                break;
            case "wake":
                try (SherpaWakeWord model = new SherpaWakeWord(app)) {
                    report("wake_load", 0); begin();
                    float[] frame = new float[1600];
                    for (int i = 0; i < 200; i++) model.accept(frame);
                    report("wake_20s_silence", 20000);
                }
                break;
            case "understanding":
                SherpaVoiceUnderstanding.Settings settings = new SherpaVoiceUnderstanding.Settings(app);
                try (SherpaVoiceUnderstanding model = new SherpaVoiceUnderstanding(app, settings)) {
                    report("understanding_load", 0); begin();
                    for (int i = 0; i < 6; i++) assertTrue(model.recognize(input, i + 1).text.contains("时间"));
                    report("understanding_6_utterances", input.length * 1000.0 / 16000 * 6);
                }
                break;
            case "tts":
                try (SherpaSpeechSynthesizer model = new SherpaSpeechSynthesizer(app)) {
                    report("tts_load", 0); begin();
                    com.k2fsa.sherpa.onnx.GeneratedAudio audio = model.synthesize("我在");
                    report("tts_wake_synthesize", audio.getSamples().length * 1000.0 / audio.getSampleRate()); begin();
                    audio = model.synthesize("你好，我是小糖。我会在这里，安静地陪伴你。");
                    report("tts_sentence_synthesize", audio.getSamples().length * 1000.0 / audio.getSampleRate());
                }
                break;
            case "companion":
                try (YamnetClassifier guard = new YamnetClassifier(app);
                     SherpaWakeWord wake = new SherpaWakeWord(app);
                     SherpaVoiceUnderstanding.VoiceDetector vad = new SherpaVoiceUnderstanding.VoiceDetector(app, new SherpaVoiceUnderstanding.Settings(app));
                     SherpaVoiceUnderstanding understanding = new SherpaVoiceUnderstanding(app, new SherpaVoiceUnderstanding.Settings(app))) {
                    report("companion_models_loaded", 0); begin();
                    for (int i = 0; i < 6; i++) assertTrue(understanding.recognize(input, i + 1).text.contains("时间"));
                    report("companion_6_utterances", input.length * 1000.0 / 16000 * 6);
                }
                break;
            default: throw new IllegalArgumentException("未知采样模式：" + mode);
        }
        begin(); SystemClock.sleep(3000); report(mode + "_released", 0);
        } finally { sampling = false; sampler.interrupt(); sampler.join(2000); }
    }

    private void service() throws Exception {
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            long deadline = SystemClock.elapsedRealtime() + 120000;
            while ((!GuardianRuntime.speechStatus().startsWith("已就绪")
                    || GuardianRuntime.snapshot().status != GuardianRuntime.Status.LISTENING)
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertEquals(GuardianRuntime.speechStatus(), GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
            assertTrue(GuardianRuntime.speechStatus(), GuardianRuntime.speechStatus().startsWith("已就绪"));
            begin(); report("service_ready", 0);
            begin(); SystemClock.sleep(20000); report("service_foreground_20s", 20000);
            SpeechPlayback.speak(InteractionPorts.SpeechRequest.wake());
            deadline = SystemClock.elapsedRealtime() + 30000;
            while (GuardianRuntime.speechSnapshot().phase != GuardianRuntime.SpeechPhase.COMPLETE
                    && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10);
            assertEquals(GuardianRuntime.speechSnapshot().error, GuardianRuntime.SpeechPhase.COMPLETE, GuardianRuntime.speechSnapshot().phase);
            begin(); report("service_after_wake", 0);
            instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
            begin(); SystemClock.sleep(20000); report("service_background_20s", 20000);
        } finally {
            app.stopService(new Intent(app, GuardianService.class));
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private float[] fixture() throws Exception {
        byte[] bytes;
        try (InputStream in = instrumentation.getContext().getAssets().open("understanding-test/zh.pcm")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            bytes = out.toByteArray();
        }
        ByteBuffer pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = pcm.getShort() / 32768f;
        return samples;
    }
}

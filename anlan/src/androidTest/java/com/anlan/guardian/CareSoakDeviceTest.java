package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** 显式 -e care_soak true 才执行一小时真实时间验收；每分钟输出可核对资源记录。 */
@RunWith(AndroidJUnit4.class)
public final class CareSoakDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();
    private void shell(String command) throws Exception {
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))) {
            byte[] bytes = new byte[1024]; while (in.read(bytes) != -1) { }
        }
    }
    private Care.Result care(boolean ask, int round) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Care.Result> outcome = new AtomicReference<>();
        InteractionPorts.CareRequest request = new InteractionPorts.CareRequest(java.util.UUID.randomUUID().toString(), "soak-test", "",
                ask ? "需要我继续陪着你吗？" : "我在这里，会继续安静地陪着你。",
                ask ? InteractionPorts.CareRequest.Mode.ASK : InteractionPorts.CareRequest.Mode.NOTICE, ask ? 10000 : 0, System.currentTimeMillis() + 60000);
        Care.submit(request, new Care.Listener() {
            @Override public void onState(String id, String state) { if (ask && state.equals("LISTENING")) Care.respond(id, "我没事"); }
            @Override public void onResult(Care.Result result) { outcome.set(result); done.countDown(); }
        });
        assertTrue("第 " + round + " 轮关怀未完成", done.await(60, TimeUnit.SECONDS));
        assertEquals(outcome.get().reason, ask ? Care.Status.REPLIED : Care.Status.COMPLETED, outcome.get().status);
        return outcome.get();
    }
    private void recognition() throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> failure = new AtomicReference<>();
        try (UnderstandingWorker worker = new UnderstandingWorker(app, false, state -> { })) {
            worker.start(new InteractionPorts.SpeechInput.Result() {
                @Override public void onText(String text) { }
                @Override public void onUnderstanding(VoiceUnderstandingResult result) {
                    if (!result.text.contains("时间")) failure.set("中文固定样本识别结果异常：" + result.text);
                    if (!result.emotion.contains("NEUTRAL")) failure.set("固定样本情绪标签异常：" + result.emotion);
                }
                @Override public void onUtteranceEnd() { done.countDown(); }
                @Override public void onError(String message) { failure.set(message); done.countDown(); }
            }, 30000);
            byte[] bytes;
            try (java.io.InputStream in = instrumentation.getContext().getAssets().open("understanding-test/zh.pcm")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] block = new byte[8192]; int read;
                while ((read = in.read(block)) != -1) out.write(block, 0, read); bytes = out.toByteArray();
            }
            java.nio.ByteBuffer input = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset < bytes.length / 2 + 16000; offset += 1600) {
                short[] block = new short[1600]; for (int i = 0; i < block.length && input.remaining() >= 2; i++) block[i] = input.getShort();
                worker.acceptPcm(block, block.length, 16000); SystemClock.sleep(100);
            }
            assertTrue("固定中文样本未返回完整话轮", done.await(20, TimeUnit.SECONDS)); assertNull(failure.get());
        }
    }
    @Test public void hourOfGuardCareReloadAndBackground() throws Exception {
        assumeTrue("一小时验收须显式启用", "true".equals(InstrumentationRegistry.getArguments().getString("care_soak")));
        android.content.SharedPreferences prefs = app.getSharedPreferences("care", 0);
        java.util.Map<String, ?> previous = new java.util.HashMap<>(prefs.getAll());
        android.content.SharedPreferences interaction = app.getSharedPreferences("interaction", 0);
        boolean companion = interaction.getBoolean("companion_listening", false);
        android.media.AudioManager audio = app.getSystemService(android.media.AudioManager.class); boolean muted = audio.isMicrophoneMute();
        shell("pm grant com.anlan.guardian android.permission.RECORD_AUDIO");
        instrumentation.getUiAutomation().adoptShellPermissionIdentity(android.Manifest.permission.MODIFY_AUDIO_SETTINGS);
        try { audio.setMicrophoneMute(true); } finally { instrumentation.getUiAutomation().dropShellPermissionIdentity(); }
        prefs.edit().putBoolean("enabled", true).putBoolean("quiet_enabled", false).putLong("snooze_until", 0).commit();
        interaction.edit().putBoolean("companion_listening", true).commit();
        MainActivity activity = (MainActivity)instrumentation.startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long started = SystemClock.elapsedRealtime(), cpu = android.os.Process.getElapsedCpuTime(); int cycles = 0;
        long maxPss = 0, maxNative = 0;
        try {
            long ready = SystemClock.elapsedRealtime() + 60000;
            while (GuardianRuntime.snapshot().status != GuardianRuntime.Status.LISTENING && SystemClock.elapsedRealtime() < ready) SystemClock.sleep(50);
            assertEquals(GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
            long end = started + 3600000, nextCycle = started, nextSample = started;
            while (SystemClock.elapsedRealtime() < end) {
                assertEquals("一小时运行中守护断开", GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
                long now = SystemClock.elapsedRealtime();
                if (now >= nextCycle) {
                    care(false, cycles); SystemClock.sleep(700); care(true, cycles); SystemClock.sleep(700); recognition();
                    shell("am send-trim-memory com.anlan.guardian RUNNING_LOW");
                    cycles++; nextCycle = started + cycles * 300000L;
                    if (cycles % 2 == 1) instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
                    else { shell("am start -n com.anlan.guardian/.MainActivity -f 0x10020000"); SystemClock.sleep(2000); }
                }
                if (now >= nextSample) {
                    Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
                    maxPss = Math.max(maxPss, memory.getTotalPss()); maxNative = Math.max(maxNative, Debug.getNativeHeapAllocatedSize() / 1024);
                    JSONObject sample = new JSONObject().put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("cycles", cycles)
                            .put("pss_kib", memory.getTotalPss()).put("native_kib", Debug.getNativeHeapAllocatedSize() / 1024)
                            .put("peak_sampled_pss_kib", maxPss).put("peak_sampled_native_kib", maxNative)
                            .put("cpu_ms", android.os.Process.getElapsedCpuTime() - cpu).put("guard", GuardianRuntime.snapshot().status.name());
                    Bundle status = new Bundle(); status.putString("care_soak_sample", sample.toString()); instrumentation.sendStatus(0, status);
                    nextSample = SystemClock.elapsedRealtime() + 60000;
                }
                SystemClock.sleep(1000);
            }
            assertTrue("完整一小时至少完成十二轮", cycles >= 12);
            care(false, cycles); SystemClock.sleep(700); recognition();
            Bundle result = new Bundle(); result.putString("care_soak_summary", "elapsed_ms=" + (SystemClock.elapsedRealtime() - started) + ", cycles=" + cycles + ", peak_pss_kib=" + maxPss); instrumentation.sendStatus(0, result);
        } finally {
            app.stopService(new Intent(app, GuardianService.class)); instrumentation.runOnMainSync(activity::finish);
            interaction.edit().putBoolean("companion_listening", companion).commit();
            android.content.SharedPreferences.Editor edit = prefs.edit().clear();
            for (java.util.Map.Entry<String, ?> entry : previous.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
                else if (value instanceof Integer) edit.putInt(key, (Integer)value);
                else if (value instanceof Long) edit.putLong(key, (Long)value);
                else if (value instanceof String) edit.putString(key, (String)value);
                else if (value instanceof Float) edit.putFloat(key, (Float)value);
            }
            edit.commit();
            instrumentation.getUiAutomation().adoptShellPermissionIdentity(android.Manifest.permission.MODIFY_AUDIO_SETTINGS);
            try { audio.setMicrophoneMute(muted); } finally { instrumentation.getUiAutomation().dropShellPermissionIdentity(); }
        }
    }
}

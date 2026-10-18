package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SpeechPlaybackDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();
    @Test public void streamedDeltasPlayBeforeEndAndKeepCaptionOrder() throws Exception {
        MainActivity activity = launch();
        InteractionPorts.SpeechStream stream = null;
        try {
            await(() -> GuardianRuntime.speechStatus().startsWith("已就绪"), 120000);
            java.util.List<String> spoken = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> error = new java.util.concurrent.atomic.AtomicReference<>("");
            long opened = SystemClock.elapsedRealtime();
            stream = SpeechPlayback.openStream(new InteractionPorts.SpeechStreamListener() {
                @Override public void onSegmentStarted(InteractionPorts.SpeechRequest segment) {
                    assertSame(android.os.Looper.getMainLooper(), android.os.Looper.myLooper());
                    assertEquals(segment.text, ((TextView) activity.findViewById(R.id.emotion_name)).getText().toString());
                    spoken.add(segment.text);
                }
                @Override public void onComplete() { completed.incrementAndGet(); }
                @Override public void onError(String message) { error.set(message); }
                @Override public void onCancelled() { error.set("流式测试不应被取消"); }
            });
            assertFalse("超载片段必须整体拒收", stream.append("超过缓冲上限".repeat(100)));
            String first = "你好，我是小安。", rest = "这是一段模拟大模型逐字输出的语音测试。我们保持同样的语速，按顺序说完。";
            for (int i = 0; i < first.length(); i++) { assertTrue(stream.append(first.substring(i, i + 1))); SystemClock.sleep(25); }
            await(() -> !spoken.isEmpty() || !error.get().isEmpty(), 60000);
            assertEquals("", error.get()); assertEquals("不能等待 end 才开始播报", first, spoken.get(0));
            assertEquals(0, completed.get()); reportSpeechMemory("stream_first_segment");
            android.os.Bundle metrics = new android.os.Bundle(); metrics.putLong("stream_first_audio_ms", SystemClock.elapsedRealtime() - opened);
            instrumentation.sendStatus(0, metrics);
            for (int i = 0; i < rest.length(); i++) { assertTrue(stream.append(rest.substring(i, i + 1))); SystemClock.sleep(25); }
            stream.end();
            await(() -> completed.get() != 0 || !error.get().isEmpty(), 120000);
            assertEquals("", error.get()); assertEquals(1, completed.get());
            assertEquals("不能遗漏、重复或重排文字", first + rest, String.join("", spoken));
            assertEquals(GuardianRuntime.SpeechPhase.COMPLETE, GuardianRuntime.speechSnapshot().phase);
            instrumentation.runOnMainSync(() -> assertFalse(spoken.get(spoken.size() - 1).contentEquals(((TextView) activity.findViewById(R.id.emotion_name)).getText())));
            reportSpeechMemory("stream_complete");
        } finally { if (stream != null) stream.cancel(); stop(activity); }
    }

    @Test public void replacedStreamCannotCancelNewStreamOrOrdinarySpeech() throws Exception {
        MainActivity activity = launch();
        try {
            await(() -> GuardianRuntime.speechStatus().startsWith("已就绪"), 120000);
            java.util.concurrent.atomic.AtomicInteger oldCancelled = new java.util.concurrent.atomic.AtomicInteger();
            InteractionPorts.SpeechStream old = SpeechPlayback.openStream(new InteractionPorts.SpeechStreamListener() {
                @Override public void onCancelled() { oldCancelled.incrementAndGet(); }
            });
            assertTrue(old.append("这段播报会被新的会话替换。"));
            await(() -> GuardianRuntime.speechSnapshot().phase == GuardianRuntime.SpeechPhase.PLAYING, 60000);
            java.util.concurrent.atomic.AtomicInteger newCancelled = new java.util.concurrent.atomic.AtomicInteger();
            InteractionPorts.SpeechStream next = SpeechPlayback.openStream(new InteractionPorts.SpeechStreamListener() {
                @Override public void onCancelled() { newCancelled.incrementAndGet(); }
            });
            old.cancel();
            try { old.append("旧内容不应被接收"); fail(); } catch (IllegalStateException expected) { }
            assertTrue(next.append("新会话能够继续播报。"));
            await(() -> oldCancelled.get() == 1 && GuardianRuntime.speechSnapshot().phase == GuardianRuntime.SpeechPhase.PLAYING, 60000);
            assertEquals("新会话能够继续播报。", GuardianRuntime.speechSnapshot().request.text);
            long previous = GuardianRuntime.speechSnapshot().id;
            SpeechPlayback.speak("我在"); next.cancel(); old.cancel();
            await(() -> GuardianRuntime.speechSnapshot().id > previous
                    && GuardianRuntime.speechSnapshot().phase == GuardianRuntime.SpeechPhase.COMPLETE, 15000);
            assertEquals("我在", GuardianRuntime.speechSnapshot().request.text);
            assertEquals(1, oldCancelled.get()); assertEquals(1, newCancelled.get());
        } finally { stop(activity); }
    }

    @Test public void serviceStopCancelsWaitingStreamWithoutStartingAudio() throws Exception {
        MainActivity activity = launch();
        try {
            await(() -> GuardianRuntime.speechStatus().startsWith("已就绪"), 120000);
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            InteractionPorts.SpeechStream stream = SpeechPlayback.openStream(new InteractionPorts.SpeechStreamListener() {
                @Override public void onCancelled() { cancelled.set(true); }
                @Override public void onSegmentStarted(InteractionPorts.SpeechRequest segment) { fail("空流不应播放"); }
            });
            app.stopService(new Intent(app, GuardianService.class));
            await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.STOPPED && cancelled.get(), 10000);
            try { stream.append("旧会话内容"); fail(); } catch (IllegalStateException expected) { }
            try { SpeechPlayback.openStream(new InteractionPorts.SpeechStreamListener() { }); fail(); }
            catch (IllegalStateException expected) { }
        } finally { stop(activity); }
    }

    @Test public void longerChineseSpeechCompletesThroughUnifiedEntry() throws Exception {
        MainActivity activity = launch();
        try {
            await(() -> GuardianRuntime.speechStatus().startsWith("已就绪"), 120000);
            String text = "我在。我是小安，会在这里陪着你。你可以轻轻摸摸我的脸，也可以叫我的名字。听到重要的声音时，我会及时提醒你。";
            long previous = GuardianRuntime.speechSnapshot().id;
            reportSpeechMemory("long_speech_before");
            instrumentation.runOnMainSync(() -> SpeechPlayback.speak(text));
            await(() -> GuardianRuntime.speechSnapshot().id > previous
                    && GuardianRuntime.speechSnapshot().phase != GuardianRuntime.SpeechPhase.PREPARING, 60000);
            assertEquals(GuardianRuntime.speechSnapshot().error, GuardianRuntime.SpeechPhase.PLAYING, GuardianRuntime.speechSnapshot().phase);
            long requestId = GuardianRuntime.speechSnapshot().id, started = GuardianRuntime.speechSnapshot().at;
            instrumentation.runOnMainSync(() -> assertEquals(text, ((TextView) activity.findViewById(R.id.emotion_name)).getText().toString()));
            reportSpeechMemory("long_speech_playing");
            await(() -> GuardianRuntime.speechSnapshot().id != requestId
                    || GuardianRuntime.speechSnapshot().phase != GuardianRuntime.SpeechPhase.PLAYING, 120000);
            assertEquals("长句不能被自身唤醒替换", requestId, GuardianRuntime.speechSnapshot().id);
            assertEquals(GuardianRuntime.speechSnapshot().error, GuardianRuntime.SpeechPhase.COMPLETE, GuardianRuntime.speechSnapshot().phase);
            instrumentation.runOnMainSync(() -> assertFalse(text.contentEquals(((TextView) activity.findViewById(R.id.emotion_name)).getText())));
            reportSpeechMemory("long_speech_complete");
            android.os.Bundle state = new android.os.Bundle();
            state.putLong("speech_playback_ms", GuardianRuntime.speechSnapshot().at - started); instrumentation.sendStatus(0, state);
            assertEquals(GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
        } finally { stop(activity); }
    }

    @Test(timeout = 45000) public void repeatedChineseSynthesisRemainsAudible() throws Exception {
        MainActivity activity = launch();
        try {
            await(() -> GuardianRuntime.speechStatus().startsWith("已就绪"), 120000);
            reportSpeechMemory("before_model");
            try (com.soundguardian.core.SherpaSpeechSynthesizer model = new com.soundguardian.core.SherpaSpeechSynthesizer(app)) {
                reportSpeechMemory("model_loaded");
                for (String text : new String[]{"我在", "你好，我是小安。我会在这里，安静地陪伴你。", "我在"}) {
                    android.os.Bundle state = new android.os.Bundle(); state.putString("synthesis_begin", text); instrumentation.sendStatus(0, state);
                    com.k2fsa.sherpa.onnx.GeneratedAudio audio = model.synthesize(text);
                    assertTrue("连续中文合成必须返回可用样本", audio.getSamples().length > 1000);
                    state.clear(); state.putString("synthesis_end", text); instrumentation.sendStatus(0, state);
                }
                reportSpeechMemory("after_three_syntheses");
            }
            SystemClock.sleep(1000); reportSpeechMemory("model_released");
        } finally { stop(activity); }
    }

    private void reportSpeechMemory(String phase) {
        android.os.Debug.MemoryInfo memory = new android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(memory);
        android.os.Bundle state = new android.os.Bundle();
        state.putString("speech_memory", phase + " pss_kib=" + memory.getTotalPss()
                + " native_alloc_kib=" + android.os.Debug.getNativeHeapAllocatedSize() / 1024);
        instrumentation.sendStatus(0, state);
    }
    @Test public void synthesizedWakeContainsAudiblePcmAfterModelRelease() throws Exception {
        app.stopService(new Intent(app, GuardianService.class)); SystemClock.sleep(1000);
        com.k2fsa.sherpa.onnx.GeneratedAudio audio;
        try (com.soundguardian.core.SherpaSpeechSynthesizer model = new com.soundguardian.core.SherpaSpeechSynthesizer(app)) {
            audio = model.synthesize("我在");
        }
        float peak = 0; double energy = 0; int nonFinite = 0;
        for (float value : audio.getSamples()) {
            if (!Float.isFinite(value)) { nonFinite++; continue; }
            peak = Math.max(peak, Math.abs(value)); energy += value * value;
        }
        java.lang.reflect.Method conversion = LocalSpeechOutput.class.getDeclaredMethod("pcm", float[].class, int.class);
        conversion.setAccessible(true);
        short[] pcm = (short[]) conversion.invoke(null, audio.getSamples(), audio.getSampleRate());
        int pcmPeak = 0, nonZero = 0;
        for (short value : pcm) { pcmPeak = Math.max(pcmPeak, Math.abs((int)value)); if (value != 0) nonZero++; }
        String metrics = "samples=" + audio.getSamples().length + " rate=" + audio.getSampleRate() + " peak=" + peak
                + " rms=" + Math.sqrt(energy / audio.getSamples().length) + " nonfinite=" + nonFinite
                + " pcm_peak=" + pcmPeak + " pcm_nonzero=" + nonZero;
        android.util.Log.i("AnlanSpeechTest", metrics);
        android.os.Bundle status = new android.os.Bundle(); status.putString("speech_signal", metrics);
        instrumentation.sendStatus(0, status);
        assertEquals("合成结果不能含 NaN/Infinity：" + metrics, 0, nonFinite);
        assertTrue("转换后的 PCM 必须具有语音幅度：" + metrics, pcmPeak > 300 && nonZero > 1600);
    }

    @Test public void rapidBackendCloseDoesNotFillLifecycleQueue() throws Exception {
        for (int i = 0; i < 24; i++) {
            LocalSpeechOutput abandoned = new LocalSpeechOutput(app, status -> { });
            abandoned.close();
        }
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        try (LocalSpeechOutput current = new LocalSpeechOutput(app, status -> { if (status.startsWith("已就绪")) ready.set(true); })) {
            await(ready::get, 120000);
        }
    }

    @Test public void cachedWakeSurvivesTrimAndDynamicSpeechReloads() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>("");
        InteractionPorts.SpeechOutput.Progress progress = new InteractionPorts.SpeechOutput.Progress() {
            @Override public void onStart() { }
            @Override public void onComplete() { completed.incrementAndGet(); }
            @Override public void onCancelled() { failure.set("不应取消"); }
            @Override public void onError(String message) { failure.set(message); }
        };
        try (LocalSpeechOutput output = new LocalSpeechOutput(app, status -> { if (status.startsWith("已就绪")) ready.set(true); })) {
            await(ready::get, 120000);
            output.trimMemory(); output.speak(InteractionPorts.SpeechRequest.wake(), progress);
            await(() -> completed.get() == 1 || !failure.get().isEmpty(), 15000);
            assertEquals("", failure.get());
            output.speak("你好，小安在这里。", progress);
            await(() -> completed.get() == 2 || !failure.get().isEmpty(), 60000);
            assertEquals("", failure.get());
            output.trimMemory();
            output.speak("你好，小安在这里。", progress);
            await(() -> completed.get() == 3 || !failure.get().isEmpty(), 60000);
            assertEquals("", failure.get());
        }
    }

    @Test public void chineseWakeSpeechSynchronizesCaptionAndReturnsToGuard() throws Exception {
        AiSettings settings=new AiSettings(app);AiSettings.Profile previous=settings.load();
        settings.save(new AiSettings.Profile(false,previous.baseUrl,previous.token,previous.model,previous.disableThinking,previous.cue));
        MainActivity activity = launch();
        try {
            await(() -> GuardianRuntime.speechStatus().startsWith("已就绪"), 120000);
            long expectedRequestId = GuardianRuntime.speechSnapshot().id + 1;
            instrumentation.runOnMainSync(() -> {
                SpeechPlayback.speak(InteractionPorts.SpeechRequest.wake());
                assertEquals(GuardianRuntime.SpeechPhase.PREPARING,GuardianRuntime.speechSnapshot().phase);
                assertFalse("我在".contentEquals(((TextView) activity.findViewById(R.id.emotion_name)).getText()));
            });
            await(() -> GuardianRuntime.speechSnapshot().phase == GuardianRuntime.SpeechPhase.PLAYING, 15000);
            long started = GuardianRuntime.speechSnapshot().at;
            instrumentation.runOnMainSync(() -> {
                TextView caption = activity.findViewById(R.id.emotion_name);
                assertEquals(View.VISIBLE, caption.getVisibility());
                assertEquals("我在", caption.getText().toString());
                assertEquals("小安 · 收到", activity.findViewById(R.id.face).getContentDescription());
            });
            await(() -> GuardianRuntime.speechSnapshot().phase == GuardianRuntime.SpeechPhase.COMPLETE, 15000);
            assertTrue("必须等待真实播放结束", GuardianRuntime.speechSnapshot().at - started >= 100);
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> assertFalse("我在".contentEquals(((TextView) activity.findViewById(R.id.emotion_name)).getText())));
            assertEquals(GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
            SystemClock.sleep(4500);
            assertEquals(GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
            assertEquals(expectedRequestId, GuardianRuntime.speechSnapshot().id);
        } finally { stop(activity);settings.save(previous); }
    }

    @Test public void arbitraryChineseUsesSameEntryAndServiceStopCancelsPlayback() throws Exception {
        MainActivity activity = launch();
        try {
            await(() -> GuardianRuntime.speechStatus().startsWith("已就绪"), 120000);
            assertEquals("主动关怀应保持未自动触发", GuardianRuntime.SpeechPhase.IDLE, GuardianRuntime.speechSnapshot().phase);
            SpeechPlayback.speak("你好，我是小安。我会在这里，安静地陪伴你。");
            await(() -> GuardianRuntime.speechSnapshot().phase == GuardianRuntime.SpeechPhase.PLAYING, 60000);
            long previous = GuardianRuntime.speechSnapshot().id;
            SpeechPlayback.speak("我在");
            await(() -> GuardianRuntime.speechSnapshot().id > previous && GuardianRuntime.speechSnapshot().phase == GuardianRuntime.SpeechPhase.PLAYING, 10000);
            assertEquals("我在", GuardianRuntime.speechSnapshot().request.text);
            app.stopService(new Intent(app, GuardianService.class));
            await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.STOPPED, 10000);
            assertEquals(GuardianRuntime.SpeechPhase.IDLE, GuardianRuntime.speechSnapshot().phase);
            try { SpeechPlayback.speak("不应播放"); fail("服务停止后不能接受播报"); }
            catch (IllegalStateException expected) { }
        } finally { stop(activity); }
    }

    private MainActivity launch() throws Exception {
        for (String permission : new String[]{"android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"}) {
            if (permission.endsWith("POST_NOTIFICATIONS") && android.os.Build.VERSION.SDK_INT < 33) continue;
            try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.getUiAutomation().executeShellCommand("pm grant com.anlan.guardian " + permission))) {
                while (in.read() != -1) { }
            }
        }
        // 部分真机限制 instrumentation 从后台启动页面；由 shell 启动并用生命周期监视器接收实例。
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        MainActivity activity;
        try {
            try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.getUiAutomation().executeShellCommand("am start -n com.anlan.guardian/.MainActivity"))) {
                while (in.read() != -1) { }
            }
            activity = (MainActivity) monitor.waitForActivityWithTimeout(30000);
        } finally { instrumentation.removeMonitor(monitor); }
        assertNotNull("真机测试页面未启动", activity);
        await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.LISTENING, 30000);
        return activity;
    }
    private void stop(MainActivity activity) {
        app.stopService(new Intent(app, GuardianService.class));
        instrumentation.runOnMainSync(activity::finish);
        await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.STOPPED, 10000);
    }
    private void await(BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10);
        assertTrue(GuardianRuntime.speechStatus() + " / " + GuardianRuntime.speechSnapshot().phase + " / " + GuardianRuntime.speechSnapshot().error, condition.getAsBoolean());
    }
}

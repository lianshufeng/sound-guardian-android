package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.SherpaVoiceUnderstanding;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class UnderstandingDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();
    private float[] fixture() throws Exception {
        byte[] bytes;
        try (InputStream in = instrumentation.getContext().getAssets().open("understanding-test/zh.pcm")) { bytes = readAll(in); }
        ByteBuffer pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) samples[i] = pcm.getShort() / 32768f;
        return samples;
    }
    @Test public void sameInferenceReturnsChineseTextEmotionAndSpeechEvent() throws Exception {
        app.stopService(new android.content.Intent(app, GuardianService.class));
        SystemClock.sleep(1500);
        SherpaVoiceUnderstanding.Settings settings = new SherpaVoiceUnderstanding.Settings(app);
        long before = SystemClock.elapsedRealtime();
        try (SherpaVoiceUnderstanding model = new SherpaVoiceUnderstanding(app, settings)) {
            long loadedMs = SystemClock.elapsedRealtime() - before;
            com.soundguardian.core.VoiceUnderstandingResult result = model.recognize(fixture(), 1);
            assertTrue(result.text, result.text.contains("时间"));
            assertFalse(result.emotion.isEmpty()); assertTrue(result.event, result.event.contains("Speech"));
            assertTrue(result.language, result.language.contains("zh"));
            android.util.Log.i("AnlanUnderstandingTest", "load_ms=" + loadedMs + " decode_ms=" + result.inferenceMs
                    + " audio_ms=" + result.durationMs + " text=" + result.text + " emotion=" + result.emotion);
            com.soundguardian.core.VoiceUnderstandingResult second = model.recognize(fixture(), 2);
            assertEquals(result.text, second.text);
            android.util.Log.i("AnlanUnderstandingTest", "warm_decode_ms=" + second.inferenceMs);
        }
    }
    @Test public void recognizerCoexistsWithGuardWakeAndTtsModels() throws Exception {
        app.stopService(new android.content.Intent(app, GuardianService.class)); SystemClock.sleep(1500);
        SherpaVoiceUnderstanding.Settings settings = new SherpaVoiceUnderstanding.Settings(app);
        try (com.soundguardian.core.YamnetClassifier guard = new com.soundguardian.core.YamnetClassifier(app);
             com.soundguardian.core.SherpaWakeWord wake = new com.soundguardian.core.SherpaWakeWord(app);
             com.soundguardian.core.SherpaSpeechSynthesizer tts = new com.soundguardian.core.SherpaSpeechSynthesizer(app);
             SherpaVoiceUnderstanding understanding = new SherpaVoiceUnderstanding(app, settings)) {
            assertTrue(tts.synthesize("我在").getSamples().length > 0);
            float[] input = fixture();
            java.util.concurrent.ExecutorService parallel = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<float[]> sound = parallel.submit(() -> {
                    wake.accept(java.util.Arrays.copyOf(input, 16000));
                    return guard.classify(java.util.Arrays.copyOf(input, 15600));
                });
                com.soundguardian.core.VoiceUnderstandingResult result = understanding.recognize(input, 1);
                assertEquals(521, sound.get(20, java.util.concurrent.TimeUnit.SECONDS).length);
                assertFalse(result.text.isEmpty());
                android.os.Debug.MemoryInfo memory = new android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(memory);
                android.util.Log.i("AnlanUnderstandingTest", "coexist_decode_ms=" + result.inferenceMs + " pss_kib=" + memory.getTotalPss());
            } finally { parallel.shutdownNow(); }
        }
    }
    @Test public void vadSegmentsSpeechAndDoesNotTurnSilenceIntoSpeech() throws Exception {
        SherpaVoiceUnderstanding.Settings settings = new SherpaVoiceUnderstanding.Settings(app);
        try (SherpaVoiceUnderstanding.VoiceDetector vad = new SherpaVoiceUnderstanding.VoiceDetector(app, settings)) {
            for (int i = 0; i < 80; i++) vad.accept(new float[512]);
            assertFalse(vad.speaking()); assertFalse(vad.hasSegment());
            float[] input = fixture(); int segments = 0, samples = 0;
            for (int offset = 0; offset < input.length + 32000; offset += 512) {
                float[] frame = new float[512];
                if (offset < input.length) System.arraycopy(input, offset, frame, 0, Math.min(512, input.length - offset));
                vad.accept(frame);
                while (vad.hasSegment()) { float[] segment = vad.takeSegment(); segments++; samples += segment.length; assertTrue(segment.length <= 16000 * 9); }
            }
            assertTrue(segments > 0); assertTrue(samples > 16000);
            vad.reset(); assertFalse(vad.hasSegment());
        }
    }
    @Test public void workerSuppressesSelfSpeechAndPublishesRealRecognition() throws Exception {
        CopyOnWriteArrayList<InteractionPorts.UnderstandingState> updates = new CopyOnWriteArrayList<>();
        try (UnderstandingWorker worker = new UnderstandingWorker(app, true, updates::add)) {
            float[] samples = fixture();
            worker.setSuppressed(true); feed(worker, samples);
            assertTrue(updates.stream().noneMatch(update -> update.result != null));
            worker.setSuppressed(false); worker.openWindow(); feed(worker, samples);
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (updates.stream().noneMatch(update -> update.result != null) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertTrue(updates.toString(), updates.stream().anyMatch(update -> update.result != null && !update.result.text.isEmpty()));
            assertTrue("实际PCM应发布非零音量", updates.stream().anyMatch(update -> update.level > .05f));
            long beforeTrim = updates.stream().filter(update -> update.result != null).mapToLong(update -> update.result.id).max().getAsLong();
            worker.trimMemory(); feed(worker, samples);
            assertTrue(updates.get(updates.size() - 1).status.contains("内存"));
            worker.openWindow(); feed(worker, samples);
            deadline = SystemClock.elapsedRealtime() + 30000;
            while (updates.stream().noneMatch(update -> update.result != null && update.result.id > beforeTrim) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertTrue("内存回收后应能开启新会话", updates.stream().anyMatch(update -> update.result != null && update.result.id > beforeTrim));
            worker.setSuppressed(true);
            await(() -> !updates.isEmpty() && updates.get(updates.size() - 1).level == 0f, 3000);
            int size = updates.size(); SystemClock.sleep(1500);
            assertTrue(updates.subList(size, updates.size()).stream().noneMatch(update -> update.result != null));
        }
    }
    @Test public void fastWakeInputSkipsCueAndActuallyAcceptsPcmAfterReady() throws Exception {
        MainActivity activity = (MainActivity)instrumentation.startActivitySync(new android.content.Intent(app, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        app.stopService(new android.content.Intent(app, GuardianService.class));
        await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.STOPPED, 15000);
        java.util.concurrent.CountDownLatch prepared = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger cues = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        CopyOnWriteArrayList<String> texts = new CopyOnWriteArrayList<>();
        InputSessionController[] controller = new InputSessionController[1];
        try (UnderstandingWorker worker = new UnderstandingWorker(app, false, update -> { })) {
            worker.setSuppressed(true);
            worker.prepare(prepared::countDown, reason -> { failure.set(reason); prepared.countDown(); });
            assertTrue(prepared.await(30, java.util.concurrent.TimeUnit.SECONDS)); assertNull(failure.get());
            DialogueConfig config = new DialogueConfig(app);
            long started = SystemClock.elapsedRealtime();
            instrumentation.runOnMainSync(() -> {
                controller[0] = new InputSessionController(new InputSessionController.Host() {
                    public UnderstandingWorker worker() { return worker; }
                    public boolean available() { return true; }
                    public boolean cueEnabled() { return true; }
                    public void readyForInput() { worker.setSuppressed(false); }
                    public void cue(Runnable done, java.util.function.Consumer<String> error) { cues.incrementAndGet(); done.run(); }
                }, config.tailMs, config.wakeTailMs);
                controller[0].start(new InteractionPorts.SpeechInput.Result() {
                    public void onReady() { ready.countDown(); }
                    public void onText(String text) { texts.add(text); }
                    public void onError(String reason) { failure.set(reason); ready.countDown(); }
                }, 20000, true);
            });
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); assertNull(failure.get());
            long readyMs = SystemClock.elapsedRealtime() - started;
            assertEquals("首轮不额外播放提示音", 0, cues.get());
            assertTrue("仍保留短尾音保护", readyMs >= config.wakeTailMs);
            android.util.Log.i("AnlanFastInputTest", "warm_ready_ms=" + readyMs + " tail_ms=" + config.wakeTailMs);
            assertEquals("LISTENING", GuardianRuntime.inputPhase());
            feed(worker, fixture());
            await(() -> !texts.isEmpty() || failure.get() != null, 30000);
            assertNull(failure.get()); assertFalse("输入就绪后真实PCM可识别", texts.isEmpty());
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (controller[0] != null) controller[0].close();
                activity.finish();
            });
        }
    }
    @Test public void boundedAudioQueueReportsOverloadInsteadOfGrowing() throws Exception {
        CopyOnWriteArrayList<InteractionPorts.UnderstandingState> updates = new CopyOnWriteArrayList<>();
        try (UnderstandingWorker worker = new UnderstandingWorker(app, true, updates::add)) {
            short[] silence = new short[1600];
            for (int i = 0; i < 1000; i++) worker.acceptPcm(silence, silence.length, 16000);
            await(() -> updates.stream().anyMatch(update -> update.dropped > 0), 3000);
            assertTrue(updates.stream().anyMatch(update -> update.dropped > 0));
            assertTrue(updates.stream().noneMatch(update -> update.result != null));
        }
    }
    @Test public void slowStateConsumerDoesNotBlockPcmDeliveryAndRunsOnMainThread() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean wrongThread = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ScheduledExecutorService escape = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try (UnderstandingWorker worker = new UnderstandingWorker(app, true, update -> {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) wrongThread.set(true);
            entered.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        })) {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            escape.schedule(release::countDown, 2, java.util.concurrent.TimeUnit.SECONDS);
            long before = SystemClock.elapsedRealtime(); short[] silence = new short[1600];
            for (int i = 0; i < 64; i++) worker.acceptPcm(silence, silence.length, 16000);
            assertTrue("状态回调不得阻塞音频投递", SystemClock.elapsedRealtime() - before < 500);
            assertFalse("状态通知必须在主线程", wrongThread.get());
        } finally { release.countDown(); escape.shutdownNow(); }
    }
    @Test public void replacingInputCallbackDoesNotReceivePreviousSegment() throws Exception {
        java.util.concurrent.atomic.AtomicReference<UnderstandingWorker> reference = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> previousText = new java.util.concurrent.atomic.AtomicReference<>();
        CopyOnWriteArrayList<String> newTexts = new CopyOnWriteArrayList<>();
        InteractionPorts.SpeechInput.Result replacement = new InteractionPorts.SpeechInput.Result() {
            public void onText(String text) { newTexts.add(text); }
            public void onError(String message) { }
        };
        try (UnderstandingWorker worker = new UnderstandingWorker(app, true, update -> {
            if (update.result != null && previousText.compareAndSet(null, update.result.text)) reference.get().start(replacement);
        })) {
            reference.set(worker);
            worker.start(new InteractionPorts.SpeechInput.Result() {
                public void onText(String text) { }
                public void onError(String message) { }
            });
            feed(worker, fixture());
            await(() -> previousText.get() != null, 30000);
            SystemClock.sleep(1500);
            assertFalse("旧片段不得进入新文字回调", newTexts.contains(previousText.get()));
        }
    }
    private void await(java.util.function.BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    @Test public void faceReceivesLongPressPettingAndCancelWhileGuardContinues() throws Exception {
        try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand("pm grant com.anlan.guardian android.permission.RECORD_AUDIO"))) { readAll(in); }
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new android.content.Intent(app, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            SystemClock.sleep(2500);
            android.view.View face = activity.findViewById(R.id.face);
            sendTouch(face, android.view.MotionEvent.ACTION_DOWN, .5f);
            SystemClock.sleep(1000);
            instrumentation.runOnMainSync(() -> assertEquals("小糖 · 害羞", face.getContentDescription()));
            sendTouch(face, android.view.MotionEvent.ACTION_CANCEL, .5f);
            sendTouch(face, android.view.MotionEvent.ACTION_DOWN, .5f);
            SystemClock.sleep(300);
            sendTouch(face, android.view.MotionEvent.ACTION_MOVE, .8f);
            sendTouch(face, android.view.MotionEvent.ACTION_MOVE, .2f);
            instrumentation.runOnMainSync(() -> assertEquals("小糖 · 蹭手", face.getContentDescription()));
            sendTouch(face, android.view.MotionEvent.ACTION_CANCEL, .2f);
            instrumentation.runOnMainSync(() -> assertNotEquals("小糖 · 蹭手", face.getContentDescription()));
            assertEquals(GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
        } finally {
            app.stopService(new android.content.Intent(app, GuardianService.class));
            instrumentation.runOnMainSync(activity::finish);
        }
    }
    private void sendTouch(android.view.View face, int action, float x) {
        instrumentation.runOnMainSync(() -> {
            long time = SystemClock.uptimeMillis();
            android.view.MotionEvent event = android.view.MotionEvent.obtain(time, time, action, face.getWidth() * x, face.getHeight() * .5f, 0);
            face.dispatchTouchEvent(event); event.recycle();
        });
    }
    private void feed(UnderstandingWorker worker, float[] samples) {
        for (int offset = 0; offset < samples.length + 16000; offset += 1600) {
            short[] pcm = new short[1600];
            for (int i = 0; i < pcm.length && offset + i < samples.length; i++) pcm[i] = (short) (samples[offset + i] * 32767);
            worker.acceptPcm(pcm, pcm.length, 16000); SystemClock.sleep(100);
        }
    }
    private byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] bytes = new byte[8192]; int count;
        while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count); return out.toByteArray();
    }
}

package com.anlan.guardian;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;

/** 真实协调器及主线程状态机；输入、网络和播报边界可控，不使用用户凭据。 */
public final class ConversationContinuationDeviceTest {
    private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void await(BooleanSupplier condition, long ms) {
        long until = SystemClock.elapsedRealtime() + ms;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
        assertTrue("等待续说验收条件超时", condition.getAsBoolean());
    }
    private VoiceUnderstandingResult result(String text, String event) {
        return new VoiceUnderstandingResult(System.nanoTime(), SystemClock.elapsedRealtime(), text, "NEUTRAL", "zh", event, 1000, 10);
    }
    private final class Agent implements InteractionPorts.DialogueAgent {
        final List<List<InteractionPorts.DialogueMessage>> requests = new CopyOnWriteArrayList<>();
        final List<Reply> replies = new CopyOnWriteArrayList<>();
        int cancelled;
        public void stream(String id, List<InteractionPorts.DialogueMessage> messages, int max, Reply reply) { requests.add(messages); replies.add(reply); }
        public void cancel(String id) { cancelled++; }
    }
    private final class PendingStream implements InteractionPorts.SpeechStream {
        final InteractionPorts.SpeechStreamListener listener;
        final StringBuilder text = new StringBuilder();
        boolean cancelled, began;
        PendingStream(InteractionPorts.SpeechStreamListener listener) { this.listener = listener; }
        public boolean append(String delta) { if (cancelled) throw new IllegalStateException("CANCELLED"); text.append(delta); return true; }
        public void end() { }
        public void cancel() { cancelled = true; listener.onCancelled(); }
        boolean play() {
            if (cancelled || !listener.beforePlayback()) return false;
            began = true; listener.onSegmentStarted(new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.DIALOGUE, text.toString(), "")); return true;
        }
        void complete() { assertTrue(began); listener.onComplete(); }
    }
    private final class Harness implements AutoCloseable {
        final CareAcceptanceContext isolated = new CareAcceptanceContext(app);
        final Agent agent = new Agent();
        final List<PendingStream> outputs = new CopyOnWriteArrayList<>();
        final List<String> local = new ArrayList<>();
        volatile InteractionPorts.SpeechInput.Result input;
        ConversationCoordinator coordinator;
        long lease;
        boolean available = true, busy;
        Harness(boolean ready) throws Exception { this(ready, null); }
        Harness(boolean ready, UnderstandingWorker worker) throws Exception {
            new AiSettings(isolated).save(new AiSettings.Profile(true, "https://example.com/v1", "", "test", true, false));
            main(() -> {
                GuardianRuntime.begin();
                lease = SpeechPlayback.attach(request -> { }, (source, event, listener) -> {
                    PendingStream output = new PendingStream(listener); outputs.add(output); return output;
                });
                coordinator = new ConversationCoordinator(isolated, new ConversationCoordinator.Host() {
                    public UnderstandingWorker input() { return worker; }
                    public boolean available() { return available; }
                    public boolean claimInputForPlayback() { return !busy && (worker == null || worker.suppressIfIdle()); }
                    public void startInput(InteractionPorts.SpeechInput.Result callback, long timeout) {
                        input = callback;
                        if (worker != null) { worker.setSuppressed(false); worker.start(callback, timeout); }
                        if (ready) callback.onReady();
                    }
                    public void stopInput() { if (worker != null) worker.cancel(input); input = null; }
                    public void speak(String text, Runnable complete, java.util.function.Consumer<String> failed) { local.add(text); complete.run(); }
                    public void stopSpeech() { }
                }, profile -> agent);
                coordinator.startAfterWake();
            });
        }
        void submit(String text) { main(() -> { InteractionPorts.SpeechInput.Result owner = input; assertNotNull(owner); owner.onUnderstanding(result(text, "Speech")); owner.onUtteranceEnd(); }); }
        PendingStream generate(int request, String text) throws Exception {
            agent.replies.get(request).onDelta(text);
            await(() -> outputs.size() > request, 3000); return outputs.get(request);
        }
        public void close() throws Exception {
            main(() -> { coordinator.close(); SpeechPlayback.detach(lease); });
            java.lang.reflect.Field storage = ConversationCoordinator.class.getDeclaredField("storage"); storage.setAccessible(true);
            assertTrue(((java.util.concurrent.ExecutorService)storage.get(coordinator)).awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            isolated.close();
        }
    }

    @Test public void firstWaitStartsAtReadyAndLaterTurnsStillUseThreeSeconds() throws Exception {
        try (Harness h = new Harness(false)) {
            SystemClock.sleep(3300); assertTrue(Conversation.active());
            main(() -> h.input.onReady());
            SystemClock.sleep(3300); assertTrue(Conversation.active());
            h.submit("你是谁"); await(() -> h.input != null, 1000);
            main(() -> h.input.onReady());
            await(() -> !Conversation.active(), 4000);
            main(() -> assertEquals("TIMEOUT", GuardianRuntime.conversationState()));
            assertEquals(6000, new DialogueConfig(app).wakeListenMs);
        }
    }
    @Test public void silentFirstWindowExpiresAfterSixSeconds() throws Exception {
        try (Harness h = new Harness(true)) {
            long start = SystemClock.elapsedRealtime();
            SystemClock.sleep(3300); assertTrue(Conversation.active());
            await(() -> !Conversation.active(), 4000);
            assertTrue(SystemClock.elapsedRealtime() - start >= 5800);
            main(() -> assertEquals("TIMEOUT", GuardianRuntime.conversationState()));
        }
    }
    @Test public void supplementBeforeFirstDeltaInvalidatesOldNetworkAndExitRemainsLocal() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("我想做晚饭。");
            h.submit("给我一点建议。");
            try { h.agent.replies.get(0).onDelta("旧回答"); fail("旧请求不应继续消费正文"); }
            catch (IllegalStateException expected) { assertEquals("CANCELLED", expected.getMessage()); }
            h.agent.replies.get(0).onError("OLD_NETWORK_ERROR");
            await(() -> h.agent.requests.size() == 2, 3000);
            assertEquals("我想做晚饭。给我一点建议。", h.agent.requests.get(1).get(h.agent.requests.get(1).size() - 1).text);
            h.submit("停止");
            assertFalse(Conversation.active()); assertEquals(2, h.agent.requests.size());
        }
    }
    @Test public void validSupplementsCancelOldReplyMergeRepeatedlyAndDoNotPolluteHistory() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("帮我想想晚饭吃什么。");
            PendingStream old = h.generate(0, "旧回答不能播放。");
            InteractionPorts.SpeechInput.Result owner = h.input;
            main(() -> { owner.onVoice(true); assertEquals("CONTINUING_VOICE", GuardianRuntime.conversationState()); assertFalse(old.play()); });
            h.submit("家里只有鸡蛋和番茄。");
            assertTrue(old.cancelled); assertTrue(h.agent.cancelled >= 1);
            assertEquals("CONTINUING", GuardianRuntime.conversationState());
            await(() -> h.agent.requests.size() == 2, 3000);
            PendingStream second = h.generate(1, "第二次旧回答。");
            h.submit("还要做得清淡一点。");
            await(() -> h.agent.requests.size() == 3, 3000); assertTrue(second.cancelled);
            String combined = h.agent.requests.get(2).get(h.agent.requests.get(2).size() - 1).text;
            assertEquals("帮我想想晚饭吃什么。家里只有鸡蛋和番茄。还要做得清淡一点。", combined);
            main(() -> { old.listener.onError("OLD_ERROR"); old.listener.onComplete(); });
            assertEquals("THINKING", GuardianRuntime.conversationState());
            PendingStream latest = h.generate(2, "可以做清淡的番茄炒蛋。");
            main(() -> { assertTrue(latest.play()); latest.complete(); });
            await(() -> h.input != null, 1000);
            h.submit("换一个做法呢？");
            assertEquals(4, h.agent.requests.size());
            List<InteractionPorts.DialogueMessage> messages = h.agent.requests.get(3);
            assertEquals(4, messages.size()); assertEquals(combined, messages.get(1).text);
            assertEquals("可以做清淡的番茄炒蛋。", messages.get(2).text);
            main(() -> { owner.onError("OLD_INPUT"); h.coordinator.stop("TEST_END"); });
        }
    }
    @Test public void noiseOnlyTemporarilyHoldsPlaybackWithoutCancellingRequest() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("给我一点晚饭建议。");
            main(() -> h.input.onVoice(true));
            PendingStream output = h.generate(0, "可以吃些热乎的饭菜。");
            main(() -> {
                assertFalse(output.play());
                h.input.onUnderstanding(result("砰", "Noise"));
                h.input.onUnderstanding(result("……", "Speech"));
                h.input.onVoice(false); h.input.onAnalysisIdle();
                assertEquals("THINKING", GuardianRuntime.conversationState());
                assertEquals(0, h.agent.cancelled); assertEquals(1, h.agent.requests.size());
                h.busy = true; assertFalse(output.play()); h.busy = false;
                assertTrue(output.play()); assertNull(h.input); output.complete();
            });
        }
    }
    @Test public void cancelAndHighPriorityEventInvalidatePendingPlaybackAndInput() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("给我一点鼓励。"); PendingStream output = h.generate(0, "你已经做得很好。");
            InteractionPorts.SpeechInput.Result old = h.input;
            main(() -> { h.available = false; assertFalse(output.play()); old.onUnderstanding(result("再补充一点", "Speech")); output.listener.onComplete(); });
            assertFalse(Conversation.active()); assertEquals(1, h.agent.requests.size());
        }
    }
    @Test public void realPcmSpeechAfterSubmissionIsMergedThroughTheSameInputWorker() throws Exception {
        app.stopService(new android.content.Intent(app, GuardianService.class)); SystemClock.sleep(1500);
        byte[] bytes;
        try (java.io.InputStream fixture = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("understanding-test/zh.pcm")) { bytes = fixture.readAllBytes(); }
        short[] pcm = new short[bytes.length / 2]; java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>("");
        try (UnderstandingWorker worker = new UnderstandingWorker(app, false, update -> { })) {
            worker.prepare(ready::countDown, reason -> { error.set(reason); ready.countDown(); });
            assertTrue(ready.await(60000, java.util.concurrent.TimeUnit.MILLISECONDS)); assertEquals("", error.get());
            try (Harness h = new Harness(true, worker)) {
                h.submit("刚刚想到一个问题。"); assertEquals(1, h.agent.requests.size());
                for (int offset = 0; offset < pcm.length + 24000; offset += 1600) {
                    short[] chunk = new short[1600]; if (offset < pcm.length) System.arraycopy(pcm, offset, chunk, 0, Math.min(1600, pcm.length - offset));
                    worker.acceptPcm(chunk, chunk.length, 16000); SystemClock.sleep(100);
                }
                await(() -> h.agent.requests.size() >= 2, 15000);
                String input = h.agent.requests.get(h.agent.requests.size() - 1).get(h.agent.requests.get(h.agent.requests.size() - 1).size() - 1).text;
                assertTrue(input.startsWith("刚刚想到一个问题。")); assertTrue(input, input.contains("时间"));
                assertTrue(h.agent.cancelled > 0);
            }
        }
    }
}

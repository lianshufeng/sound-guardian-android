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
import org.junit.Ignore;
import org.junit.Test;
import static org.junit.Assert.*;

/** 真实协调器及主线程状态机；输入、网络和播报边界可控，不使用用户凭据。旧续说用例保留为忽略项。 */
public final class ConversationContinuationDeviceTest {
    private final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable action) { InstrumentationRegistry.getInstrumentation().runOnMainSync(action); }
    private void await(BooleanSupplier condition, long ms) {
        long until = SystemClock.elapsedRealtime() + ms;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20);
        assertTrue("等待对话验收条件超时", condition.getAsBoolean());
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
        final StringBuffer text = new StringBuffer();
        volatile boolean cancelled, began;
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
        final List<String> actualPlayed = new CopyOnWriteArrayList<>();
        volatile InteractionPorts.SpeechInput.Result input;
        ConversationCoordinator coordinator;
        long lease;
        volatile boolean available = true, busy;
        Harness(boolean ready) throws Exception { this(ready, null); }
        Harness(boolean ready, UnderstandingWorker worker) throws Exception { this(ready, worker, null, null); }
        Harness(boolean ready, UnderstandingWorker worker,
                java.util.function.Function<AiSettings.Profile, InteractionPorts.DialogueAgent> factory,
                LocalSpeechOutput actualOutput) throws Exception {
            new AiSettings(isolated).save(new AiSettings.Profile(true, "https://example.com/v1", "", "test", true, false));
            main(() -> {
                GuardianRuntime.begin();
                lease = SpeechPlayback.attach(request -> { }, (source, event, listener) -> {
                    if (actualOutput != null) try {
                        return actualOutput.openStream(source, event, new InteractionPorts.SpeechOutput.Progress() {
                            public boolean beforePlayback() { return listener.beforePlayback(); }
                            public void onSegmentStart(InteractionPorts.SpeechRequest segment) { actualPlayed.add(segment.text); listener.onSegmentStarted(segment); }
                            public void onComplete() { listener.onComplete(); }
                            public void onCancelled() { listener.onCancelled(); }
                            public void onError(String message) { listener.onError(message); }
                        });
                    } catch (Exception error) { throw new IllegalStateException(error); }
                    PendingStream output = new PendingStream(listener); outputs.add(output); return output;
                });
                coordinator = new ConversationCoordinator(isolated, new ConversationCoordinator.Host() {
                    public UnderstandingWorker input() { return worker; }
                    public boolean available() { return available; }
                    public boolean claimInputForPlayback() { return !busy && (worker == null || worker.suppressIfIdle()); }
                    public boolean inputIdle() { return !busy && (worker == null || worker.isInputIdle()); }
                    public void startInput(InteractionPorts.SpeechInput.Result callback, long timeout) {
                        input = callback;
                        if (worker != null) { worker.setSuppressed(false); worker.start(callback, timeout); }
                        if (ready) callback.onReady();
                    }
                    public void stopInput() { if (worker != null) worker.cancel(input); input = null; }
                    public void speak(String text, Runnable complete, java.util.function.Consumer<String> failed) { local.add(text); complete.run(); }
                    public void stopSpeech() { }
                }, factory == null ? profile -> agent : factory);
                coordinator.startAfterWake();
            });
        }
        void submit(String text) { main(() -> { InteractionPorts.SpeechInput.Result owner = input; assertNotNull(owner); owner.onUnderstanding(result(text, "Speech")); owner.onUtteranceEnd(); }); }
        PendingStream generate(int request, String text) throws Exception {
            int previous = outputs.size();
            agent.replies.get(request).onDelta(text);
            await(() -> outputs.size() > previous, 3000); return outputs.get(previous);
        }
        public void close() throws Exception {
            main(() -> { coordinator.close(); SpeechPlayback.detach(lease); });
            java.lang.reflect.Field storage = ConversationCoordinator.class.getDeclaredField("storage"); storage.setAccessible(true);
            assertTrue(((java.util.concurrent.ExecutorService)storage.get(coordinator)).awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            isolated.close();
        }
    }

    @Ignore("旧续说行为已移除") @Test public void firstWaitStartsAtReadyAndLaterTurnsStillUseThreeSeconds() throws Exception {
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
    @Test public void wakeInterruptsModelAndSpeechButNeverTheNextInputTurn() throws Exception {
        try (Harness h = new Harness(true)) {
            InteractionPorts.SpeechInput.Result firstInput = h.input;
            main(() -> {
                assertFalse(h.coordinator.canInterruptWithWake());
                assertFalse(h.coordinator.interruptForWake());
                assertSame(firstInput, h.input);
            });

            h.submit("第一轮问题");
            PendingStream firstReply = h.generate(0, "第一轮回答。");
            main(() -> { assertTrue(firstReply.play()); firstReply.complete(); });
            await(() -> h.input != null && h.input != firstInput, 2000);
            main(() -> assertFalse(h.coordinator.canInterruptWithWake()));

            InteractionPorts.SpeechInput.Result secondInput = h.input;
            h.submit("被打断的问题");
            InteractionPorts.DialogueAgent.Reply interruptedReply = h.agent.replies.get(1);
            main(() -> {
                assertTrue(h.coordinator.canInterruptWithWake());
                assertTrue(h.coordinator.interruptForWake());
                assertTrue(Conversation.active());
                assertFalse(h.coordinator.canInterruptWithWake());
                assertNull(h.input);
            });
            assertNotSame(secondInput, h.input);
            try { interruptedReply.onDelta("这段回答必须作废"); fail("被唤醒打断的模型响应不应继续"); }
            catch (IllegalStateException expected) { assertEquals("CANCELLED", expected.getMessage()); }

            main(h.coordinator::startAfterWake);
            await(() -> h.input != null && h.input != secondInput, 2000);
            InteractionPorts.SpeechInput.Result resumedInput = h.input;
            main(() -> {
                assertFalse(h.coordinator.canInterruptWithWake());
                assertFalse(h.coordinator.interruptForWake());
                assertSame(resumedInput, h.input);
            });

            h.submit("继续问第三轮");
            List<InteractionPorts.DialogueMessage> messages = h.agent.requests.get(2);
            assertEquals("第一轮问题", messages.get(1).text);
            assertEquals("第一轮回答。", messages.get(2).text);
            assertEquals("继续问第三轮", messages.get(3).text);
            assertFalse(messages.stream().anyMatch(message -> message.text.contains("被打断的问题")));

            PendingStream thirdReply = h.generate(2, "第三轮回答。");
            main(() -> {
                assertTrue(thirdReply.play());
                assertTrue(h.coordinator.canInterruptWithWake());
                assertTrue(h.coordinator.interruptForWake());
                assertTrue(thirdReply.cancelled);
                assertTrue(Conversation.active());
            });
            main(h.coordinator::startAfterWake);
            await(() -> h.input != null, 2000);
            main(() -> {
                assertFalse(h.coordinator.canInterruptWithWake());
                assertFalse(h.coordinator.interruptForWake());
            });
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
    @Ignore("旧续说行为已移除") @Test public void supplementBeforeFirstDeltaIsBatchedAndExitRemainsLocal() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("我想做晚饭。");
            h.submit("给我一点建议。");
            PendingStream draft = h.generate(0, "提前准备的旧草稿。");
            main(() -> assertFalse(draft.play()));
            assertEquals(0, h.agent.cancelled); assertEquals(1, h.agent.requests.size());
            await(() -> h.agent.requests.size() == 2, 3000);
            try { h.agent.replies.get(0).onDelta("旧回答"); fail("旧请求不应继续消费正文"); }
            catch (IllegalStateException expected) { assertEquals("CANCELLED", expected.getMessage()); }
            h.agent.replies.get(0).onError("OLD_NETWORK_ERROR");
            assertTrue(draft.cancelled);
            assertEquals("我想做晚饭。给我一点建议。", h.agent.requests.get(1).get(h.agent.requests.get(1).size() - 1).text);
            h.submit("停止");
            assertFalse(Conversation.active()); assertEquals(2, h.agent.requests.size());
        }
    }
    @Ignore("旧续说行为已移除") @Test public void validSupplementsCancelOldReplyMergeRepeatedlyAndDoNotPolluteHistory() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("帮我想想晚饭吃什么。");
            PendingStream old = h.generate(0, "旧回答不能播放。");
            InteractionPorts.SpeechInput.Result owner = h.input;
            main(() -> { owner.onVoice(true); assertEquals("CONTINUING_VOICE", GuardianRuntime.conversationState()); assertFalse(old.play()); });
            h.submit("家里只有鸡蛋和番茄。");
            assertFalse(old.cancelled); assertEquals(0, h.agent.cancelled);
            assertEquals("CONTINUING", GuardianRuntime.conversationState());
            await(() -> h.agent.requests.size() == 2, 3000);
            assertTrue(old.cancelled); assertEquals(1, h.agent.cancelled);
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
    @Ignore("旧续说行为已移除") @Test public void noiseOnlyTemporarilyHoldsPlaybackWithoutCancellingRequest() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("给我一点晚饭建议。");
            main(() -> h.input.onVoice(true));
            PendingStream output = h.generate(0, "可以吃些热乎的饭菜。");
            main(() -> {
                assertFalse(output.play());
                h.input.onUnderstanding(result("砰", "Noise"));
                h.input.onUnderstanding(result("……", "Speech"));
                h.input.onVoice(false); h.input.onAnalysisIdle();
                assertEquals("PREPARING_SPEECH", GuardianRuntime.conversationState());
                assertEquals(0, h.agent.cancelled); assertEquals(1, h.agent.requests.size());
                h.busy = true; assertFalse(output.play()); h.busy = false;
                assertTrue(output.play()); assertNull(h.input); output.complete();
            });
        }
    }
    @Ignore("旧续说行为已移除") @Test public void rapidCorrectionFragmentsReplaceOnlyOnceAfterStableEnd() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("请介绍北京的景点。"); PendingStream draft = h.generate(0, "北京可以去故宫。");
            h.submit("不是北京，"); SystemClock.sleep(400);
            main(() -> h.input.onVoice(true));
            SystemClock.sleep(800);
            assertEquals(1, h.agent.requests.size()); assertEquals(0, h.agent.cancelled);
            main(() -> { assertFalse(draft.play()); h.input.onUnderstanding(result("是南京。", "Speech")); h.input.onVoice(false); h.input.onUtteranceEnd(); });
            await(() -> h.agent.requests.size() == 2, 3000);
            assertEquals(1, h.agent.cancelled); assertTrue(draft.cancelled);
            assertEquals("请介绍北京的景点。不是北京，是南京。", h.agent.requests.get(1).get(1).text);
            PendingStream updated = h.generate(1, "南京可以考虑玄武湖。");
            h.agent.replies.get(1).onComplete("stop");
            main(() -> { assertTrue(updated.play()); updated.complete(); });
            await(() -> h.input != null, 1000); h.submit("继续介绍。");
            assertFalse(h.agent.requests.get(2).stream().anyMatch(message -> message.text.contains("北京可以去故宫")));
        }
    }
    @Test public void slowNetworkHasFeedbackAndDoesNotUseInputSilenceTimeout() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("请推荐一个旅游城市。");
            SystemClock.sleep(3300);
            assertTrue(Conversation.active()); assertEquals("WAITING_REPLY", GuardianRuntime.conversationState());
            PendingStream draft = h.generate(0, "可以考虑南京。");
            assertEquals("PREPARING_SPEECH", GuardianRuntime.conversationState());
            assertEquals(0, h.agent.cancelled);
            main(() -> { assertTrue(draft.play()); draft.complete(); });
        }
    }
    @Ignore("旧续说行为已移除") @Test public void completedDraftAndOldNetworkFailureCannotOverrideBufferedSupplement() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("请介绍北京的景点。"); PendingStream draft = h.generate(0, "北京可以去故宫。");
            main(() -> h.input.onUnderstanding(result("改成南京。", "Speech")));
            h.agent.replies.get(0).onComplete("stop"); h.agent.replies.get(0).onError("OLD_NETWORK_ERROR");
            SystemClock.sleep(200);
            assertTrue(Conversation.active()); assertEquals(1, h.agent.requests.size());
            main(() -> { assertFalse(draft.play()); h.input.onUtteranceEnd(); });
            await(() -> h.agent.requests.size() == 2, 3000);
            assertTrue(draft.cancelled); assertEquals("THINKING", GuardianRuntime.conversationState());
        }
    }
    @Ignore("旧续说行为已移除") @Test public void pendingDecodeAndLateVoiceBlockEvenPreparedDraft() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("请给我一点建议。"); PendingStream draft = h.generate(0, "先休息一下。");
            main(() -> { h.busy = true; assertFalse(draft.play()); h.input.onVoice(true); });
            SystemClock.sleep(1200);
            assertEquals(1, h.agent.requests.size()); assertEquals(0, h.agent.cancelled);
            main(() -> { h.input.onVoice(false); h.input.onAnalysisIdle(); h.busy = false; assertTrue(draft.play()); draft.complete(); });
        }
    }
    @Ignore("旧续说行为已移除") @Test public void localExitCancelsDraftImmediatelyWithoutWaitingForStableEnd() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("请介绍北京的景点。"); PendingStream draft = h.generate(0, "北京可以去故宫。");
            main(() -> h.input.onUnderstanding(result("再说一下南京。", "Speech")));
            h.submit("停止");
            assertTrue(draft.cancelled); assertFalse(Conversation.active()); assertEquals(1, h.agent.requests.size());
        }
    }
    @Ignore("旧续说行为已移除") @Test public void slowSseAdapterCoalescesBufferedTextWithoutSendingEveryFragment() throws Exception {
        List<String> payloads = new CopyOnWriteArrayList<>();
        try (Harness h = new Harness(true, null, profile -> new OpenAiCompatibleAgent(profile, new DialogueConfig(app), url ->
                new java.net.HttpURLConnection(new java.net.URL(url)) {
                    final java.io.ByteArrayOutputStream request = new java.io.ByteArrayOutputStream();
                    public void connect() { } public void disconnect() { } public boolean usingProxy() { return false; }
                    public java.io.OutputStream getOutputStream() { return request; }
                    public int getResponseCode() { payloads.add(new String(request.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)); SystemClock.sleep(1800); return 200; }
                    public String getContentType() { return "text/event-stream"; }
                    public java.io.InputStream getInputStream() {
                        String answer = payloads.size() > 1 ? "南京可以考虑玄武湖。" : "北京可以去故宫。";
                        String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + answer + "\"}}]}\n\n"
                                + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                                + "data: [DONE]\n\n";
                        return new java.io.ByteArrayInputStream(sse.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }), null)) {
            h.submit("请介绍北京的景点。"); await(() -> payloads.size() == 1, 2000);
            h.submit("不是北京，"); SystemClock.sleep(300); h.submit("是南京。");
            assertEquals(1, payloads.size());
            await(() -> payloads.size() == 2 && h.outputs.stream().anyMatch(output -> !output.cancelled && output.text.toString().contains("南京")), 8000);
            org.json.JSONArray messages = new org.json.JSONObject(payloads.get(1)).getJSONArray("messages");
            assertEquals("请介绍北京的景点。不是北京，是南京。", messages.getJSONObject(messages.length()-1).getString("content"));
            main(() -> {
                PendingStream updated = h.outputs.get(h.outputs.size()-1);
                assertTrue(updated.play()); updated.complete();
                assertTrue(h.outputs.stream().filter(output -> output != updated).allMatch(output -> output.cancelled && !output.began));
            });
            assertEquals(2, payloads.size());
        }
    }
    @Ignore("旧续说行为已移除") @Test public void generatedPcmSamplesRetainIncrementalInputAndDiscardUnplayedDraft() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("draft_pcm", "false")));
        app.stopService(new android.content.Intent(app, GuardianService.class)); SystemClock.sleep(1000);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>("");
        try (UnderstandingWorker worker = new UnderstandingWorker(app, false, update -> { })) {
            worker.prepare(ready::countDown, reason -> { error.set(reason); ready.countDown(); });
            assertTrue(ready.await(60000, java.util.concurrent.TimeUnit.MILLISECONDS)); assertEquals("", error.get());
            for (String name : new String[]{"correction", "normal", "slow", "long-pause", "stop"}) {
                byte[] bytes = java.nio.file.Files.readAllBytes(new java.io.File("/data/local/tmp/anlan-drafts/" + name + ".pcm").toPath());
                short[] pcm = new short[bytes.length / 2]; java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                try (Harness h = new Harness(true, worker)) {
                    h.submit("请介绍北京的景点。"); PendingStream draft = h.generate(0, "北京可以去故宫。");
                    for (int offset = 0; offset < pcm.length; offset += 1600) {
                        if (h.input == null) break;
                        short[] chunk = new short[1600]; System.arraycopy(pcm, offset, chunk, 0, Math.min(1600, pcm.length-offset));
                        worker.acceptPcm(chunk, chunk.length, 16000); SystemClock.sleep(100);
                    }
                    if (name.equals("stop")) await(() -> !Conversation.active(), 10000);
                    else {
                        await(() -> h.agent.requests.size() >= 2 && (GuardianRuntime.conversationState().equals("THINKING")
                                || GuardianRuntime.conversationState().equals("WAITING_REPLY")), 15000);
                        List<InteractionPorts.DialogueMessage> latest = h.agent.requests.get(h.agent.requests.size()-1);
                        String text = latest.get(latest.size()-1).text;
                        assertTrue(text, text.startsWith("请介绍北京的景点。")); assertTrue(text, text.contains("南京"));
                        assertEquals("历史中不能混入未播北京草稿", 2, latest.size());
                    }
                    assertTrue("旧草稿必须作废：" + name, draft.cancelled); assertFalse(draft.began);
                    android.os.Bundle report = new android.os.Bundle(); report.putString("draft_pcm_case", name + " requests=" + h.agent.requests.size()
                            + " cancellations=" + h.agent.cancelled + " old_played=" + draft.began); InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
                }
            }
        }
    }
    @Ignore("旧续说行为已移除") @Test public void nativeTtsOnlyPlaysLatestConfirmedDraft() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("draft_tts", "false")));
        app.stopService(new android.content.Intent(app, GuardianService.class)); SystemClock.sleep(1000);
        AtomicReference<String> status = new AtomicReference<>("");
        try (LocalSpeechOutput output = new LocalSpeechOutput(app, status::set); Harness h = new Harness(true, null, null, output)) {
            h.busy = true; h.submit("请介绍北京的景点。");
            h.agent.replies.get(0).onDelta("北京可以去故宫。"); h.agent.replies.get(0).onComplete("stop");
            h.submit("不是北京，是南京。");
            main(() -> h.busy = false);
            await(() -> h.agent.requests.size() == 2, 4000);
            h.agent.replies.get(1).onDelta("南京可以考虑玄武湖。"); h.agent.replies.get(1).onComplete("stop");
            main(() -> h.busy = false);
            await(() -> !h.actualPlayed.isEmpty() || !Conversation.active(), 120000);
            assertFalse(status.get(), h.actualPlayed.isEmpty());
            assertTrue(String.join("", h.actualPlayed), String.join("", h.actualPlayed).contains("南京"));
            assertFalse(String.join("", h.actualPlayed).contains("故宫"));
            await(() -> h.input != null || !Conversation.active(), 60000);
            assertTrue("实际TTS完成后应进入下一轮倾听：" + status.get(), Conversation.active());
        }
    }
    @Ignore("旧续说行为已移除") @Test public void nativePcmAndTtsCoexistWithoutPlayingSupersededDraft() throws Exception {
        org.junit.Assume.assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("draft_pcm", "false"))
                && Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("draft_tts", "false")));
        app.stopService(new android.content.Intent(app, GuardianService.class)); SystemClock.sleep(1000);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>("");
        try (UnderstandingWorker worker = new UnderstandingWorker(app, false, update -> { });
                LocalSpeechOutput output = new LocalSpeechOutput(app, error::set)) {
            worker.prepare(ready::countDown, reason -> { error.set(reason); ready.countDown(); });
            assertTrue(ready.await(60000, java.util.concurrent.TimeUnit.MILLISECONDS));
            try (Harness h = new Harness(true, worker, null, output)) {
                h.busy = true; h.submit("请介绍北京的景点。");
                h.agent.replies.get(0).onDelta("北京可以去故宫。"); h.agent.replies.get(0).onComplete("stop");
                byte[] bytes = java.nio.file.Files.readAllBytes(new java.io.File("/data/local/tmp/anlan-drafts/normal.pcm").toPath());
                short[] pcm = new short[bytes.length/2]; java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
                for (int offset = 0; offset < pcm.length && h.input != null; offset += 1600) {
                    short[] chunk = new short[1600]; System.arraycopy(pcm, offset, chunk, 0, Math.min(1600, pcm.length-offset));
                    worker.acceptPcm(chunk, chunk.length, 16000);
                    if (!worker.isInputIdle()) h.busy = false;
                    SystemClock.sleep(100);
                }
                await(() -> h.agent.requests.size() >= 2 || !Conversation.active(), 15000);
                assertTrue("识别和TTS共存时输入应保持有效：" + error.get(), Conversation.active());
                List<InteractionPorts.DialogueMessage> latest = h.agent.requests.get(h.agent.requests.size()-1);
                assertTrue(latest.get(latest.size()-1).text.contains("南京")); assertTrue(h.actualPlayed.isEmpty());
                int last = h.agent.requests.size()-1;
                h.agent.replies.get(last).onDelta("南京可以考虑玄武湖。"); h.agent.replies.get(last).onComplete("stop");
                await(() -> !h.actualPlayed.isEmpty() || !Conversation.active(), 120000);
                assertTrue(error.get(), String.join("", h.actualPlayed).contains("南京"));
                assertFalse(String.join("", h.actualPlayed).contains("故宫"));
                await(() -> h.input != null || !Conversation.active(), 60000); assertTrue(Conversation.active());
            }
        }
    }
    @Ignore("旧续说行为已移除") @Test public void cancelAndHighPriorityEventInvalidatePendingPlaybackAndInput() throws Exception {
        try (Harness h = new Harness(true)) {
            h.submit("给我一点鼓励。"); PendingStream output = h.generate(0, "你已经做得很好。");
            InteractionPorts.SpeechInput.Result old = h.input;
            main(() -> { h.available = false; assertFalse(output.play()); old.onUnderstanding(result("再补充一点", "Speech")); output.listener.onComplete(); });
            assertFalse(Conversation.active()); assertEquals(1, h.agent.requests.size());
        }
    }
    @Ignore("旧续说行为已移除") @Test public void realPcmSpeechAfterSubmissionIsMergedThroughTheSameInputWorker() throws Exception {
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

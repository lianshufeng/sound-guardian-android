package com.anlan.guardian;

import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SpeechPlaybackTest {
    @Test public void streamFactoryRunsOutsideGlobalBindingLockAndOldDetachIsSafe() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicLong latest = new java.util.concurrent.atomic.AtomicLong();
        InteractionPorts.SpeechStream expected = new InteractionPorts.SpeechStream() {
            @Override public boolean append(String delta) { return true; }
            @Override public void end() { }
            @Override public void cancel() { }
        };
        long lease = SpeechPlayback.attach(request -> { }, (source, eventId, listener) -> {
            try { latest.set(executor.submit(() -> SpeechPlayback.attach(request -> { }, (s, e, l) -> expected)).get(2, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (Exception error) { throw new AssertionError(error); }
            return expected;
        });
        try {
            assertSame(expected, SpeechPlayback.openStream(new InteractionPorts.SpeechStreamListener() { }));
            SpeechPlayback.detach(lease);
            assertSame(expected, SpeechPlayback.openStream(new InteractionPorts.SpeechStreamListener() { }));
        } finally { SpeechPlayback.detach(lease); SpeechPlayback.detach(latest.get()); executor.shutdownNow(); }
    }
    @Test public void callbackMayCoordinateSessionBindingOnAnotherThread() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicLong replacement = new java.util.concurrent.atomic.AtomicLong();
        long lease = SpeechPlayback.attach(request -> {
            try {
                executor.submit(() -> replacement.set(SpeechPlayback.attach(ignored -> { })))
                        .get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception error) { throw new AssertionError("播报回调不能在全局会话锁内执行", error); }
        });
        try { SpeechPlayback.speak("我在"); assertTrue(replacement.get() > lease); }
        finally {
            executor.shutdownNow(); executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            SpeechPlayback.detach(lease); SpeechPlayback.detach(replacement.get());
        }
    }
    @Test public void careRequestIsOnlyDataAndDoesNotTriggerSpeech() {
        AtomicReference<InteractionPorts.SpeechRequest> received = new AtomicReference<>();
        long lease = SpeechPlayback.attach(received::set);
        try {
            InteractionPorts.SpeechRequest care = InteractionPorts.SpeechRequest.care(new InteractionPorts.CareRequest("event-1", "你还好吗？"));
            assertNull(received.get());
            SpeechPlayback.speak(care);
            assertSame(care, received.get());
            assertEquals(InteractionPorts.SpeechRequest.Source.CARE, care.source);
            assertEquals("event-1", care.eventId);
        } finally { SpeechPlayback.detach(lease); }
    }

    @Test public void oldServiceCannotDetachNewSpeechSession() {
        long old = SpeechPlayback.attach(request -> fail("旧会话不应收到请求"));
        AtomicReference<InteractionPorts.SpeechRequest> received = new AtomicReference<>();
        long current = SpeechPlayback.attach(received::set);
        SpeechPlayback.detach(old);
        SpeechPlayback.speak("我在");
        assertEquals("我在", received.get().text);
        SpeechPlayback.detach(current);
        try { SpeechPlayback.speak("你好"); fail("停止后不应接受请求"); }
        catch (IllegalStateException expected) { }
    }

    @Test public void speechHoldsAcknowledgementAndListensAfterActualEnd() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(), new Random(1), 0);
        engine.setGuarding(true, 0);
        engine.speechStarted(1, "我在", 1000);
        engine.model("Speech", "说话", .9f, 1300);
        assertEquals(Emotion.ACKNOWLEDGE, engine.state(4000).emotion);
        engine.speechStarted(1, "我在", 4000);
        engine.speechFinished(1, 4500);
        assertEquals(Emotion.LISTENING, engine.state(8499).emotion);
        assertNotEquals(Emotion.LISTENING, engine.state(8500).emotion);
        engine.speechStarted(2, "你好", 9000);
        engine.events(Collections.singletonList(new SoundEvent("alarm", "Alarm", "警报", 9100, 9100, true)), 9100);
        assertEquals(Emotion.STARTLED, engine.state(9100).emotion);
        engine.setGuarding(false, 9200);
        assertEquals(Emotion.CALM, engine.state(9200).emotion);
    }

    @Test public void staleSpeechCallbacksCannotOverwriteNewSessionOrRequest() {
        long old = GuardianRuntime.begin(), current = GuardianRuntime.begin();
        InteractionPorts.SpeechRequest request = InteractionPorts.SpeechRequest.wake();
        assertFalse(GuardianRuntime.publishSpeech(old, new GuardianRuntime.SpeechSnapshot(1, GuardianRuntime.SpeechPhase.PLAYING, request, 100, "")));
        assertTrue(GuardianRuntime.publishSpeech(current, new GuardianRuntime.SpeechSnapshot(2, GuardianRuntime.SpeechPhase.PREPARING, request, 200, "")));
        assertFalse(GuardianRuntime.publishSpeech(current, new GuardianRuntime.SpeechSnapshot(1, GuardianRuntime.SpeechPhase.COMPLETE, request, 300, "")));
        assertFalse(GuardianRuntime.publishUnderstanding(old, new InteractionPorts.UnderstandingState("旧会话", true, 100, null, 0)));
        assertTrue(GuardianRuntime.publishUnderstanding(current, new InteractionPorts.UnderstandingState("新会话", false, 200, null, 0)));
        assertEquals(0f, GuardianRuntime.understandingSnapshot().level, 0f);
        assertTrue(GuardianRuntime.publishUnderstanding(current, new InteractionPorts.UnderstandingState("音量", true, 210, null, 0, .8f)));
        assertFalse(GuardianRuntime.publishUnderstanding(old, new InteractionPorts.UnderstandingState("旧音量", true, 220, null, 0, 1f)));
        assertEquals(.8f, GuardianRuntime.understandingSnapshot().level, 0f);
        assertEquals(0f, new InteractionPorts.UnderstandingState("无效音量", true, 230, null, 0, Float.NaN).level, 0f);
        GuardianRuntime.begin();
        assertEquals(0f, GuardianRuntime.understandingSnapshot().level, 0f);
    }
    @Test public void expiredAlarmDoesNotHideFirstFrameOfNewSpeech() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(), new Random(1), 0);
        engine.setGuarding(true, 0);
        engine.simulate(new SoundEvent("alarm", "Alarm", "警报", 100, 100, false), 100);
        engine.speechStarted(1, "我在", 8100);
        assertEquals(Emotion.ACKNOWLEDGE, engine.state(8100).emotion);
        assertEquals("我在", engine.state(8100).message);
    }
    @Test public void missedNewSpeechStartDoesNotLeaveOldSpeechActive() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(), new Random(1), 0);
        engine.setGuarding(true, 0);
        engine.speechStarted(1, "我在", 100);
        engine.speechFinished(2, 1000);
        assertEquals(Emotion.LISTENING, engine.state(1000).emotion);
        assertNotEquals(Emotion.ACKNOWLEDGE, engine.state(6000).emotion);
        engine.speechStarted(3, "你好", 7000);
        engine.speechFinished(2, 7100);
        assertEquals(Emotion.ACKNOWLEDGE, engine.state(7100).emotion);
    }
}

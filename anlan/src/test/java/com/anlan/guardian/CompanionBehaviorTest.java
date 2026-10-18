package com.anlan.guardian;

import java.util.Collections;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CompanionBehaviorTest {
    private BehaviorEngine engine() {
        BehaviorEngine value = new BehaviorEngine(BehaviorEngine.Timing.defaults(), new Random(1), 0);
        value.setGuarding(true, 0); return value;
    }
    @Test public void holdTracksFingerAndTurnsShyThenReleases() {
        BehaviorEngine value = engine();
        value.gesture(TouchInteraction.DOWN, .8f, -.4f, 100);
        assertTrue(value.state(110).touchGaze);
        assertEquals(.8f, value.state(110).gazeX, .001f);
        assertEquals(Emotion.SHY, value.state(950).emotion);
        value.gesture(TouchInteraction.UP, .8f, -.4f, 1000);
        assertEquals(0, value.state(1700).gazeX, .001f);
        assertFalse(value.state(2500).touchGaze);
    }
    @Test public void jitterDoesNotPetButSmoothReturnStrokeDoes() {
        BehaviorEngine value = engine();
        value.gesture(TouchInteraction.DOWN, 0, 0, 100);
        value.gesture(TouchInteraction.MOVE, .01f, 0, 200);
        value.gesture(TouchInteraction.MOVE, 0, 0, 300);
        assertNotEquals(Emotion.NUZZLE, value.state(400).emotion);
        value.gesture(TouchInteraction.MOVE, .3f, 0, 450);
        value.gesture(TouchInteraction.MOVE, -.2f, 0, 700);
        assertEquals(Emotion.NUZZLE, value.state(700).emotion);
        value.gesture(TouchInteraction.CANCEL, 0, 0, 800);
        assertFalse(value.state(800).touchGaze);
    }
    @Test public void alarmOverridesTouchAndEmotion() {
        BehaviorEngine value = engine();
        value.gesture(TouchInteraction.DOWN, 1, 0, 100);
        value.understanding(1, "HAPPY", 200, 200);
        value.events(Collections.singletonList(new SoundEvent("alarm", "Alarm", "警报", 200, 200, true)), 200);
        assertEquals(Emotion.ALERT, value.state(3000).emotion);
        assertFalse(value.state(3000).touchGaze);
    }
    @Test public void smallContinuousMotionEventsStillRecognizeGentlePetting() {
        BehaviorEngine value = engine();
        value.gesture(TouchInteraction.DOWN, 0, 0, 100);
        for (int i = 1; i <= 30; i++) value.gesture(TouchInteraction.MOVE, i * .01f, 0, 100 + i * 20);
        for (int i = 1; i <= 30; i++) value.gesture(TouchInteraction.MOVE, .3f - i * .01f, 0, 700 + i * 20);
        assertEquals(Emotion.NUZZLE, value.state(1300).emotion);
    }
    @Test public void sadnessAndAngerReceiveCareRatherThanImitation() {
        BehaviorEngine value = engine();
        value.understanding(1, "<|SAD|>", 100, 100);
        assertEquals(Emotion.CARING, value.state(100).emotion);
        value.understanding(2, "<|ANGRY|>", 300, 300);
        assertEquals(Emotion.CARING, value.state(300).emotion);
    }
    @Test public void repeatedUpdatesCannotRecountHappySegmentOrReviveOldResult() {
        BehaviorEngine value = engine();
        value.understanding(1, "HAPPY", 100, 100);
        value.understanding(1, "HAPPY", 100, 200);
        assertEquals(Emotion.SMILE, value.state(200).emotion);
        value.understanding(2, "HAPPY", 500, 500);
        assertEquals(Emotion.SMILE, value.state(500).emotion);
        assertEquals(Emotion.HAPPY, value.state(2200).emotion);
        value.understanding(3, "SAD", 1000, 9000);
        assertNotEquals(Emotion.CARING, value.state(9000).emotion);
    }
    @Test public void rapidlyChangingEmotionWaitsForMinimumHoldAndThenExpires() {
        BehaviorEngine value = engine();
        value.understanding(1, "HAPPY", 100, 100);
        value.understanding(2, "SAD", 500, 500);
        assertEquals(Emotion.SMILE, value.state(1000).emotion);
        assertEquals(Emotion.CARING, value.state(2200).emotion);
        assertNotEquals(Emotion.CARING, value.state(5000).emotion);
    }
    @Test public void touchAndEmotionCanRespondDuringPostWakeListeningButNotSpeaking() {
        BehaviorEngine value = engine();
        value.speechStarted(1, "我在", 100);
        value.gesture(TouchInteraction.DOWN, 0, 0, 200);
        assertEquals(Emotion.ACKNOWLEDGE, value.state(1000).emotion);
        value.speechFinished(1, 1100);
        assertEquals(Emotion.SHY, value.state(1200).emotion);
        value.gesture(TouchInteraction.CANCEL, 0, 0, 1300);
        value.understanding(1, "SAD", 1400, 1400);
        assertEquals(Emotion.CARING, value.state(1400).emotion);
    }

    @Test public void inputPreparationAndListeningStayVisibleUntilSessionEndsAndYieldToAlarm() {
        BehaviorEngine value = engine();
        value.wakeWord(100, 100);
        value.inputPhase("PREPARING");
        assertEquals(Emotion.ATTENTIVE, value.state(1000).emotion);
        assertEquals(Emotion.ATTENTIVE, value.state(8000).emotion);
        value.inputPhase("CUE");
        assertEquals(Emotion.ATTENTIVE, value.state(8100).emotion);
        value.inputPhase("LISTENING");
        assertEquals(Emotion.LISTENING, value.state(8200).emotion);
        value.inputPhase("IDLE");
        assertEquals(Emotion.CALM, value.state(8300).emotion);
        value.inputPhase("LISTENING");
        value.events(Collections.singletonList(new SoundEvent("alarm", "Alarm", "警报", 8400, 8400, true)), 8400);
        assertEquals(Emotion.ALERT, value.state(12000).emotion);
        value.setGuarding(false, 12100);
        assertEquals(Emotion.CALM, value.state(12200).emotion);
    }
}

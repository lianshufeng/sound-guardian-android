package com.anlan.guardian;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EmpathyPolicyTest {
    @Test public void newResponseAfterSilenceStillGetsMinimumHold() {
        EmpathyPolicy policy = new EmpathyPolicy();
        policy.accept(1, "HAPPY", 100, 100);
        assertNull(policy.emotion(8000));
        policy.accept(2, "HAPPY", 10000, 10000);
        policy.accept(3, "SAD", 10100, 10100);
        assertEquals(Emotion.SMILE, policy.emotion(11000));
        assertEquals(Emotion.CARING, policy.emotion(12000));
    }
    @Test public void clearingForSpeechDoesNotReplayOldRecognition() {
        EmpathyPolicy policy = new EmpathyPolicy();
        assertTrue(policy.accept(1, "SAD", 100, 100));
        policy.clear();
        assertFalse(policy.accept(1, "SAD", 100, 200));
        assertNull(policy.emotion(200));
        assertTrue(policy.accept(2, "NEUTRAL", 300, 300));
        assertEquals(Emotion.LISTENING, policy.emotion(300));
    }
}

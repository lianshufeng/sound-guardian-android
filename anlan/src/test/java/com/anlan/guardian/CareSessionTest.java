package com.anlan.guardian;

import com.soundguardian.core.VoiceUnderstandingResult;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CareSessionTest {
    private InteractionPorts.CareRequest request() {
        return new InteractionPorts.CareRequest("care-1", "S02", "event-1", "你还好吗？", InteractionPorts.CareRequest.Mode.ASK, 20000, 100000);
    }
    @Test public void listeningBudgetStartsWhenInputActuallyOpens() {
        CareSession session = new CareSession(request());
        assertEquals(0, session.deadline);
        session.listening(50000);
        assertEquals(70000, session.deadline);
    }
    @Test public void orderedSegmentsRetainOriginalEmotionAndTerminalIsUnique() {
        CareSession session = new CareSession(request()); session.listening(100);
        VoiceUnderstandingResult first = new VoiceUnderstandingResult(1, 100, "我没事，", "NEUTRAL", "zh", "Speech", 1000, 20);
        VoiceUnderstandingResult second = new VoiceUnderstandingResult(2, 200, "只是呛了一下。", "HAPPY", "zh", "Speech", 1500, 30);
        assertTrue(session.append(first, 1000)); assertTrue(session.append(second, 1000));
        Care.Result result = session.finish(Care.Status.REPLIED, "VOICE", 300);
        assertEquals("我没事，只是呛了一下。", result.replyText);
        assertEquals("NEUTRAL", result.segments.get(0).emotion);
        assertEquals("HAPPY", result.segments.get(1).emotion);
        assertNull(session.finish(Care.Status.TIMEOUT, "NO_REPLY", 400));
        try { result.segments.clear(); fail(); } catch (UnsupportedOperationException expected) { }
    }
    @Test public void overlongSegmentIsRejectedWholeAndNoTextLeaksBeforeListening() {
        CareSession session = new CareSession(request());
        VoiceUnderstandingResult value = new VoiceUnderstandingResult(1, 100, "😀你好", "NEUTRAL", "zh", "Speech", 1000, 20);
        assertFalse(session.append(value, 3)); session.listening(100);
        assertFalse(session.append(value, 2)); assertEquals(0, session.reply.length());
        assertTrue(session.append(value, 3));
    }
    @Test public void explicitDeclineIsNotInterpretedAsHelpOrSystemCancellation() {
        assertEquals("DECLINE", CareSession.intent("不需要。"));
        assertEquals("OK", CareSession.intent("我没事！"));
        assertEquals("OTHER", CareSession.intent("我不需要帮助"));
        assertEquals("UNKNOWN", CareSession.intent(""));
    }
    @Test public void invalidTimeoutAndNoticeWaitingAreRejected() {
        try { new InteractionPorts.CareRequest("id", "manual", "", "你好", InteractionPorts.CareRequest.Mode.NOTICE, 10, 1000); fail(); }
        catch (IllegalArgumentException expected) { }
        try { new InteractionPorts.CareRequest("id", "manual", "", "你好", InteractionPorts.CareRequest.Mode.ASK, 100, 1000); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void quietPeriodCanCrossMidnight() {
        assertTrue(LocalCarePolicy.inPeriod(23 * 60, 22 * 60, 7 * 60));
        assertTrue(LocalCarePolicy.inPeriod(6 * 60, 22 * 60, 7 * 60));
        assertFalse(LocalCarePolicy.inPeriod(12 * 60, 22 * 60, 7 * 60));
    }
    @Test public void negationAndQuotedIntentDoNotTriggerWeatherAdvice() {
        assertTrue(LocalCarePolicy.goingOut("我要出门了。"));
        assertFalse(LocalCarePolicy.goingOut("我不是准备出门"));
        assertFalse(LocalCarePolicy.goingOut("电视里说我要出门了"));
    }
}

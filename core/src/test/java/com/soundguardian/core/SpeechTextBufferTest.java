package com.soundguardian.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SpeechTextBufferTest {
    private SpeechTextBuffer buffer() { return new SpeechTextBuffer(4, 12, 24, 700, 30000, 0); }

    @Test public void deltaBoundariesDoNotBecomeSentenceBoundaries() {
        SpeechTextBuffer input = buffer();
        input.append("你", 0); input.append("好，小", 10); input.append("安。再见！", 20);
        assertEquals("你好，小安。", input.poll(20));
        assertEquals("再见！", input.poll(20));
        assertNull(input.poll(20)); input.end(); assertTrue(input.exhausted());
    }
    @Test public void slowInputFlushesAndEndDrainsFinalUnpunctuatedText() {
        SpeechTextBuffer input = buffer(); input.append("我在", 100);
        assertNull(input.poll(799)); assertEquals("我在", input.poll(800));
        input.append("还有话", 1000); input.end(); assertEquals("还有话", input.poll(1000));
        assertTrue(input.exhausted());
    }
    @Test public void backpressureRejectsWholeDeltaAndRetryDoesNotLoseText() {
        SpeechTextBuffer input = buffer(); String first = "一二三四五六七八九十一二三四五六七八九十一二三四";
        assertTrue(input.append(first, 0)); assertFalse(input.append("五六", 0));
        assertEquals(24, input.pendingChars()); String consumed = input.poll(0);
        assertTrue(input.append("五六", 1)); input.end();
        StringBuilder all = new StringBuilder(consumed);
        while (!input.exhausted()) all.append(input.poll(1));
        assertEquals(first + "五六", all.toString());
    }
    @Test public void punctuationFreeLongStreamHasBoundedSegmentsWithoutGlobalLengthLimit() {
        SpeechTextBuffer input = buffer(); String text = "无标点长内容测试".repeat(600);
        StringBuilder actual = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            assertTrue(input.append(text.substring(i, i + 1), i));
            String segment = input.poll(i);
            if (segment != null) { assertTrue(segment.length() <= 12); actual.append(segment); }
            assertTrue(input.pendingChars() <= 24);
        }
        input.end(); while (!input.exhausted()) actual.append(input.poll(text.length()));
        assertEquals(text, actual.toString());
    }
    @Test public void unicodeSurrogatesAreNotSplitAtMaximumOrTimedFlush() {
        SpeechTextBuffer input = new SpeechTextBuffer(1, 2, 8, 700, 30000, 0);
        input.append("安\uD83D", 0); assertEquals("安", input.poll(700)); assertNull(input.poll(1400));
        input.append("\uDE00好", 1400); input.end();
        assertEquals("😀", input.poll(1400)); assertEquals("好", input.poll(1400));
    }
    @Test public void decimalEnglishAndWhitespaceArePreserved() {
        SpeechTextBuffer input = new SpeechTextBuffer(4, 40, 100, 700, 30000, 0);
        input.append("温度 3.", 0); assertNull(input.poll(100));
        input.append("14。Hello. World!\n", 100); input.end();
        StringBuilder output = new StringBuilder(); while (!input.exhausted()) output.append(input.poll(100));
        assertEquals("温度 3.14。Hello. World!\n", output.toString());
    }
    @Test public void missingEndTimesOutButAnEmptyDeltaDoesNotKeepSessionAlive() {
        SpeechTextBuffer input = buffer(); assertFalse(input.inputTimedOut(29999));
        input.append("", 29999); assertTrue(input.inputTimedOut(30000));
        input.end(); assertFalse(input.inputTimedOut(30000));
    }
    @Test public void cancelClearsInputAndRejectsStaleAppend() {
        SpeechTextBuffer input = buffer(); input.append("不应播出", 0); input.cancel();
        assertTrue(input.exhausted()); assertEquals(0, input.pendingChars()); assertNull(input.poll(1000));
        try { input.append("旧内容", 1); fail(); } catch (IllegalStateException expected) { }
    }
    @Test public void localSynthesisDelayDoesNotConsumeUpstreamIdleTimeout() {
        SpeechTextBuffer input = buffer();input.append("第一句。", 0);
        assertEquals("第一句。", input.poll(0));
        input.consumerReady(35000);
        assertFalse(input.inputTimedOut(64999));
        input.append("", 64999);assertTrue(input.inputTimedOut(65000));
        input.end();assertFalse(input.inputTimedOut(65000));
    }
    @Test public void invalidCapacityIsRejected() {
        try { new SpeechTextBuffer(12, 40, 8, 700, 30000, 0); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}

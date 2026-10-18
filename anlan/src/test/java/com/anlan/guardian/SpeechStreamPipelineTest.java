package com.anlan.guardian;

import com.soundguardian.core.SpeechTextBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SpeechStreamPipelineTest {
    @Test public void slowLocalSynthesisStillPlaysBeforeWaitingForMoreInput() throws Exception {
        SpeechTextBuffer input=new SpeechTextBuffer(1,40,512,700,30000,0);input.append("第一句。",0);
        java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong();
        CountDownLatch waitingForInput=new CountDownLatch(1);AtomicInteger played=new AtomicInteger();
        ExecutorService playback=Executors.newSingleThreadExecutor();
        try{
            new SpeechStreamPipeline<String>(input,()->true,InteractionPorts.SpeechRequest.Source.DIALOGUE,"").run(
                    new SpeechStreamPipeline.Engine<String>(){
                        public String synthesize(InteractionPorts.SpeechRequest segment){clock.addAndGet(35000);return segment.text;}
                        public void play(String audio,InteractionPorts.SpeechRequest segment)throws Exception{
                            if(!waitingForInput.await(2,TimeUnit.SECONDS))throw new IllegalStateException("未进入上游等待");
                            played.incrementAndGet();input.end();
                        }
                        public void idle(){waitingForInput.countDown();}
                    },playback,clock::get);
            assertEquals(1,played.get());assertTrue(input.exhausted());
        }finally{playback.shutdownNow();}
    }
    @Test public void playbackFailureIsReportedOnceAndProducerDoesNotHang() throws Exception {
        SpeechTextBuffer input = new SpeechTextBuffer(1, 40, 512, 700, 30000, 0);
        input.append("第一句。第二句。第三句。", 0); input.end();
        ExecutorService playback = Executors.newSingleThreadExecutor(); AtomicInteger failed = new AtomicInteger();
        try {
            try {
                new SpeechStreamPipeline<String>(input, () -> true, InteractionPorts.SpeechRequest.Source.DIALOGUE, "").run(
                        new SpeechStreamPipeline.Engine<String>() {
                            @Override public String synthesize(InteractionPorts.SpeechRequest segment) { return segment.text; }
                            @Override public void play(String audio, InteractionPorts.SpeechRequest segment) { throw new IllegalStateException("输出失败"); }
                            @Override public void idle() { }
                            @Override public void failed(Exception error) { failed.incrementAndGet(); }
                        }, playback, () -> 0); fail();
            } catch (IllegalStateException expected) { assertEquals("输出失败", expected.getMessage()); }
            assertEquals(1, failed.get()); assertTrue(input.exhausted());
        } finally { playback.shutdownNow(); }
    }

    @Test public void cancellationDropsPrefetchedAudioAndDoesNotReportFailure() throws Exception {
        SpeechTextBuffer input = new SpeechTextBuffer(1, 40, 512, 700, 30000, 0);
        input.append("第一句。第二句。第三句。", 0); input.end();
        AtomicBoolean active = new AtomicBoolean(true); AtomicInteger played = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), secondReady = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger generated = new AtomicInteger();
        ExecutorService producer = Executors.newSingleThreadExecutor(), playback = Executors.newSingleThreadExecutor();
        try {
            Future<?> job = producer.submit(() -> {
                try {
                    new SpeechStreamPipeline<String>(input, active::get, InteractionPorts.SpeechRequest.Source.DIALOGUE, "").run(
                            new SpeechStreamPipeline.Engine<String>() {
                                @Override public String synthesize(InteractionPorts.SpeechRequest segment) {
                                    if (generated.incrementAndGet() == 2) secondReady.countDown(); return segment.text;
                                }
                                @Override public void play(String audio, InteractionPorts.SpeechRequest segment) throws Exception {
                                    played.incrementAndGet(); started.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS));
                                }
                                @Override public void idle() { }
                                @Override public void failed(Exception error) { throw new AssertionError(error); }
                            }, playback, () -> 0);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS)); assertTrue(secondReady.await(2, TimeUnit.SECONDS));
            active.set(false); input.cancel(); release.countDown(); job.get(3, TimeUnit.SECONDS);
            assertEquals(1, played.get()); assertTrue(input.exhausted());
        } finally { active.set(false); release.countDown(); producer.shutdownNow(); playback.shutdownNow(); }
    }

    @Test public void emptyOrPunctuationOnlyStreamDoesNotStartSynthesis() throws Exception {
        SpeechTextBuffer input = new SpeechTextBuffer(1, 40, 512, 700, 30000, 0);
        input.append(" ”。\n", 0); input.end();
        ExecutorService playback = Executors.newSingleThreadExecutor();
        try {
            new SpeechStreamPipeline<String>(input, () -> true, InteractionPorts.SpeechRequest.Source.DIALOGUE, "").run(
                    new SpeechStreamPipeline.Engine<String>() {
                        @Override public String synthesize(InteractionPorts.SpeechRequest segment) { throw new AssertionError("标点不应触发合成"); }
                        @Override public void play(String audio, InteractionPorts.SpeechRequest segment) { fail(); }
                        @Override public void idle() { }
                    }, playback, () -> 0);
            assertTrue(input.exhausted());
        } finally { playback.shutdownNow(); }
    }

    @Test public void inputTimeoutIsExplicitAndTerminatesWaitingConsumer() throws Exception {
        SpeechTextBuffer input = new SpeechTextBuffer(1, 40, 512, 700, 30000, 0);
        ExecutorService playback = Executors.newSingleThreadExecutor();
        try {
            try {
                new SpeechStreamPipeline<String>(input, () -> true, InteractionPorts.SpeechRequest.Source.DIALOGUE, "").run(
                        new SpeechStreamPipeline.Engine<String>() {
                            @Override public String synthesize(InteractionPorts.SpeechRequest segment) { throw new AssertionError(); }
                            @Override public void play(String audio, InteractionPorts.SpeechRequest segment) { fail(); }
                            @Override public void idle() { }
                        }, playback, () -> 30000);
                fail();
            } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("输入超时")); }
            assertTrue(input.exhausted());
        } finally { playback.shutdownNow(); }
    }

    @Test public void prefetchesOnlyNextSegmentAndPlaysAllInOrder() throws Exception {
        SpeechTextBuffer input = new SpeechTextBuffer(4, 40, 512, 700, 30000, 0);
        input.append("第一句。第二句。第三句。", 0); input.end();
        AtomicBoolean active = new AtomicBoolean(true);
        CountDownLatch playing = new CountDownLatch(1), secondReady = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger synthesized = new AtomicInteger(); List<String> spoken = Collections.synchronizedList(new ArrayList<>());
        ExecutorService producer = Executors.newSingleThreadExecutor(), playback = Executors.newSingleThreadExecutor();
        try {
            SpeechStreamPipeline<String> pipeline = new SpeechStreamPipeline<>(input, active::get, InteractionPorts.SpeechRequest.Source.DIALOGUE, "");
            Future<?> job = producer.submit(() -> {
                try { pipeline.run(new SpeechStreamPipeline.Engine<String>() {
                    @Override public String synthesize(InteractionPorts.SpeechRequest segment) {
                        if (synthesized.incrementAndGet() == 2) secondReady.countDown(); return segment.text;
                    }
                    @Override public void play(String audio, InteractionPorts.SpeechRequest segment) throws Exception {
                        if (spoken.isEmpty()) { playing.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); }
                        spoken.add(audio);
                    }
                    @Override public void idle() { }
                }, playback, () -> 0); } catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(playing.await(2, TimeUnit.SECONDS)); assertTrue(secondReady.await(2, TimeUnit.SECONDS));
            assertEquals("当前段未结束时只能预取下一段", 2, synthesized.get());
            release.countDown(); job.get(3, TimeUnit.SECONDS);
            assertEquals(java.util.Arrays.asList("第一句。", "第二句。", "第三句。"), spoken);
        } finally { active.set(false); release.countDown(); producer.shutdownNow(); playback.shutdownNow(); }
    }

    @Test public void synthesisFailureStopsQueuedOutputAndReportsFailure() throws Exception {
        SpeechTextBuffer input = new SpeechTextBuffer(1, 40, 512, 700, 30000, 0);
        input.append("失败。后续。", 0); input.end();
        AtomicBoolean active = new AtomicBoolean(true); AtomicInteger played = new AtomicInteger();
        ExecutorService playback = Executors.newSingleThreadExecutor();
        try {
            SpeechStreamPipeline<String> pipeline = new SpeechStreamPipeline<>(input, active::get, InteractionPorts.SpeechRequest.Source.DIALOGUE, "");
            try {
                pipeline.run(new SpeechStreamPipeline.Engine<String>() {
                    @Override public String synthesize(InteractionPorts.SpeechRequest segment) { throw new IllegalStateException("合成失败"); }
                    @Override public void play(String audio, InteractionPorts.SpeechRequest segment) { played.incrementAndGet(); }
                    @Override public void idle() { }
                    @Override public void failed(Exception error) { active.set(false); }
                }, playback, () -> 0); fail();
            } catch (IllegalStateException expected) { assertEquals("合成失败", expected.getMessage()); }
            assertEquals(0, played.get()); assertTrue(input.exhausted());
        } finally { playback.shutdownNow(); }
    }
}

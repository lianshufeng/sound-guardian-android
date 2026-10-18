package com.anlan.guardian;

import com.soundguardian.core.SpeechTextBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 合成由调用线程串行执行；播放只消费当前段和一个预取段，不创建模型或播放器。 */
final class SpeechStreamPipeline<T> {
    interface Engine<T> {
        T synthesize(InteractionPorts.SpeechRequest segment) throws Exception;
        void play(T audio, InteractionPorts.SpeechRequest segment) throws Exception;
        void idle();
        default void failed(Exception error) { }
    }
    private static final class Segment<T> {
        final T audio;
        final InteractionPorts.SpeechRequest request;
        Segment(T audio, InteractionPorts.SpeechRequest request) { this.audio = audio; this.request = request; }
    }
    final SpeechTextBuffer input;
    private final BooleanSupplier active;
    private final InteractionPorts.SpeechRequest.Source source;
    private final String eventId;
    private volatile boolean producerEnded;
    private volatile boolean aborted;
    private boolean running() { return !aborted && active.getAsBoolean(); }

    SpeechStreamPipeline(SpeechTextBuffer input, BooleanSupplier active, InteractionPorts.SpeechRequest.Source source, String eventId) {
        this.input = input; this.active = active; this.source = source; this.eventId = eventId;
    }

    void run(Engine<T> engine, Executor playback, java.util.function.LongSupplier clock) throws Exception {
        ArrayBlockingQueue<Segment<T>> ready = new ArrayBlockingQueue<>(1);
        Semaphore slots = new Semaphore(2);
        CountDownLatch drained = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        playback.execute(() -> {
            try {
                while (running() && !(producerEnded && ready.isEmpty())) {
                    Segment<T> segment = ready.poll(100, TimeUnit.MILLISECONDS);
                    if (segment == null) continue;
                    try { if (running()) engine.play(segment.audio, segment.request); }
                    finally { slots.release(); }
                }
            } catch (Exception | LinkageError error) {
                Exception wrapped = error instanceof Exception ? (Exception) error : new IllegalStateException("本地语音运行库异常", error);
                aborted = true;
                if (failure.compareAndSet(null, wrapped)) engine.failed(wrapped);
            }
            finally { drained.countDown(); }
        });
        try {
            while (running()) {
                if (failure.get() != null) throw failure.get();
                if (input.exhausted()) break;
                String text = input.poll(clock.getAsLong());
                if (text == null) {
                    if (input.inputTimedOut(clock.getAsLong())) throw new IllegalStateException("流式文字输入超时，播报已中断");
                    engine.idle(); Thread.sleep(20); continue;
                }
                // 独立的空白、标点不送模型；正文及其标点仍完整保留，避免尾部引号触发静音错误。
                if (!text.codePoints().anyMatch(Character::isLetterOrDigit)) continue;
                while (running() && !slots.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                    if (failure.get() != null) throw failure.get();
                    engine.idle();
                }
                if (!running()) break;
                InteractionPorts.SpeechRequest segment = new InteractionPorts.SpeechRequest(source, text, eventId);
                T audio = engine.synthesize(segment);
                while (running() && !ready.offer(new Segment<>(audio, segment), 100, TimeUnit.MILLISECONDS)) {
                    if (failure.get() != null) throw failure.get();
                }
            }
        } catch (Exception error) {
            aborted = true;
            if (failure.compareAndSet(null, error)) engine.failed(error);
            throw error;
        } catch (LinkageError error) {
            Exception wrapped = new IllegalStateException("本地语音运行库异常", error);
            aborted = true;
            if (failure.compareAndSet(null, wrapped)) engine.failed(wrapped);
            throw wrapped;
        } finally {
            producerEnded = true;
            if (!drained.await(5, TimeUnit.SECONDS)) {
                // 正常排空可能超过 5 秒；仅取消后的排空限制为 5 秒。
                while (running() && !drained.await(100, TimeUnit.MILLISECONDS)) { }
                if (!running() && !drained.await(5, TimeUnit.SECONDS))
                    throw new IllegalStateException("流式播报取消后未及时释放输出");
            }
            input.cancel();
        }
        if (failure.get() != null) throw failure.get();
    }
}

package com.anlan.guardian;

import android.content.Context;
import android.os.SystemClock;
import com.soundguardian.core.SherpaWakeWord;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** 有界队列隔离唤醒推理，不阻塞 YAMNet；中断音频连续性时重置解码器。 */
final class WakeWordWorker implements InteractionPorts.WakeWordDetector {
    private final ArrayBlockingQueue<float[]> queue = new ArrayBlockingQueue<>(16);
    private final Thread worker;
    private volatile boolean closed;
    private volatile boolean suppressed;
    private final java.util.concurrent.atomic.AtomicBoolean reset = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong inputSamples=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong suppressedSamples=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong overflows=new java.util.concurrent.atomic.AtomicLong();
    private volatile Runnable listener = () -> { };

    WakeWordWorker(Context context, Consumer<String> status, LongConsumer onWake) {
        Context app = context.getApplicationContext();
        worker = new Thread(() -> {
            try (SherpaWakeWord detector = new SherpaWakeWord(app)) {
                if (closed) return;
                status.accept("已就绪 · 小安");
                if (BuildConfig.DEBUG) android.util.Log.i("AnlanWake", "ready");
                long nextWake = 0, lastReport=SystemClock.elapsedRealtime(),decodedSamples=0,maxDecodeMs=0;
                while (!closed) {
                    float[] samples = queue.poll(500,java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (closed) break;
                    long reportAt=SystemClock.elapsedRealtime();
                    if(BuildConfig.DEBUG&&reportAt-lastReport>=5000){
                        android.util.Log.i("AnlanWake","health input_samples="+inputSamples.get()+" decoded_samples="+decodedSamples
                                +" suppressed_samples="+suppressedSamples.get()+" overflows="+overflows.get()
                                +" queued="+queue.size()+" max_decode_ms="+maxDecodeMs+" suppressed="+suppressed);
                        lastReport=reportAt;
                    }
                    if(samples==null)continue;
                    if (suppressed) continue;
                    if (reset.getAndSet(false)) detector.reset();
                    long decodeAt=SystemClock.elapsedRealtime();boolean matched=detector.accept(samples);
                    decodedSamples+=samples.length;maxDecodeMs=Math.max(maxDecodeMs,SystemClock.elapsedRealtime()-decodeAt);
                    if (matched) {
                        long now = SystemClock.elapsedRealtime();
                        if (BuildConfig.DEBUG) android.util.Log.i("AnlanWake", "match suppressed=" + suppressed
                                + " cooldown_ms=" + Math.max(0, nextWake - now));
                        if (!suppressed && now >= nextWake) { nextWake = now + InteractionPorts.WAKE_COOLDOWN_MS; onWake.accept(now); listener.run(); }
                    }
                }
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } catch (Exception | LinkageError error) {
                if (!closed) status.accept("不可用：" + error.getMessage());
            } finally { closed = true; queue.clear(); }
        }, "anlan-wakeword");
        worker.start();
    }

    @Override public void acceptPcm(short[] pcm, int count, int sampleRate) {
        if (closed) return;
        if(BuildConfig.DEBUG)inputSamples.addAndGet(count);
        if(suppressed){if(BuildConfig.DEBUG)suppressedSamples.addAndGet(count);return;}
        if (sampleRate != 16000) throw new IllegalArgumentException("唤醒输入必须为 16 kHz");
        float[] samples = new float[count];
        for (int i = 0; i < count; i++) samples[i] = pcm[i] / 32768f;
        if (!queue.offer(samples)) {
            if(BuildConfig.DEBUG)overflows.incrementAndGet();
            queue.clear(); reset.set(true); queue.offer(samples);
        }
    }
    @Override public void setListener(Runnable onWake) { listener = onWake; }
    void setSuppressed(boolean value) {
        if (BuildConfig.DEBUG && suppressed != value) android.util.Log.i("AnlanWake", "suppressed=" + value);
        suppressed = value; queue.clear(); reset.set(true);
    }
    @Override public void close() { closed = true; queue.clear(); worker.interrupt(); }
}

package com.anlan.guardian;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.soundguardian.core.SherpaVoiceUnderstanding;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** VAD 与重型识别分线程；队列有界，快速重启时所有 SenseVoice native 生命周期仍串行。 */
final class UnderstandingWorker implements InteractionPorts.SpeechInput, AutoCloseable {
    private static final ExecutorService MODEL = Executors.newSingleThreadExecutor(r -> new Thread(r, "anlan-sensevoice"));
    private static final class Chunk {
        final float[] samples; final long epoch, at;
        Chunk(float[] samples, long epoch) { this.samples = samples; this.epoch = epoch; at = SystemClock.elapsedRealtime(); }
    }
    private final Context app;
    private final long modelIdleMs;
    private final Consumer<InteractionPorts.UnderstandingState> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object control = new Object();
    private final ArrayBlockingQueue<Chunk> audio = new ArrayBlockingQueue<>(16);
    private final ArrayBlockingQueue<Chunk> segments = new ArrayBlockingQueue<>(2);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean notifying = new AtomicBoolean();
    private final AtomicBoolean warming = new AtomicBoolean();
    private final AtomicBoolean reclaiming = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<Preparation> preparation=new java.util.concurrent.atomic.AtomicReference<>();
    private static final class Preparation {
        final Runnable ready; final Consumer<String> failed; final long resourceEpoch;
        Preparation(Runnable ready,Consumer<String> failed,long resourceEpoch){this.ready=ready;this.failed=failed;this.resourceEpoch=resourceEpoch;}
    }
    private volatile InteractionPorts.UnderstandingState pendingState;
    private long pendingEpoch;
    private final Runnable dispatchState = this::dispatchState;
    private final Thread vadThread;
    private volatile boolean closed, continuous, suppressed, memoryPaused, voice, loaded;
    private volatile long until, epoch, lastNativeUse;
    private volatile long resourceEpoch;
    private volatile int dropped;
    private volatile float inputLevel;
    private long levelNotifiedAt;
    private volatile String pausedMessage = "内存紧张，倾听已暂停；唤醒可重试";
    private volatile com.soundguardian.core.VoiceUnderstandingResult latest;
    private volatile InteractionPorts.SpeechInput.Result textListener;
    private volatile boolean explicitWindow;
    private int pendingSegments;
    private boolean delivered, endpointReady;
    private volatile SherpaVoiceUnderstanding.Settings settings;
    // 只在 MODEL 队列访问。
    private SherpaVoiceUnderstanding recognizer;

    UnderstandingWorker(Context context, boolean companion, Consumer<InteractionPorts.UnderstandingState> listener) {
        app = context.getApplicationContext(); this.listener = listener; continuous = companion;
        modelIdleMs = ResourceBudget.read(app).modelIdleMs;
        vadThread = new Thread(this::vadLoop, "anlan-voice-vad"); vadThread.start();
    }
    void setContinuous(boolean enabled) {
        InteractionPorts.SpeechInput.Result previous;
        synchronized (control) {
            if (continuous == enabled && !memoryPaused) return;
            previous = textListener; textListener = null; explicitWindow = false;
            continuous = enabled; memoryPaused = false; until = 0; invalidate();
        }
        if (previous != null) main.post(() -> previous.onError("倾听模式已切换"));
        emit(enabled ? "陪伴倾听已开启" : "等待唤醒");
    }
    void openWindow() {
        synchronized (control) { memoryPaused = false; until = SystemClock.elapsedRealtime() + silenceMs(); }
        emit("正在倾听");
    }
    void prepare(Runnable ready, Consumer<String> failed) {
        preparation.set(new Preparation(ready,failed,resourceEpoch));
        prewarm();
    }
    void prewarm(){
        if(closed||!warming.compareAndSet(false,true))return;
        long lease=resourceEpoch;
        MODEL.execute(() -> {
            String failure="";
            try {
                if (closed) return;
                if (settings == null) settings = new SherpaVoiceUnderstanding.Settings(app);
                ensureRecognizer(()->!closed&&resourceEpoch==lease);
                if(lease!=resourceEpoch)throw new java.util.concurrent.CancellationException();
                lastNativeUse = SystemClock.elapsedRealtime();
            } catch (Exception | LinkageError error) { failure="INPUT_MODEL_FAILED"; }
            finally {
                Preparation pending=preparation.getAndSet(null);
                if(pending!=null&&pending.resourceEpoch!=lease&&pending.resourceEpoch==resourceEpoch){preparation.compareAndSet(null,pending);pending=null;}
                Preparation callback=pending;String reason=failure;
                // 只保留最新输入所有者；准备与PCM epoch分离，控制器租约拒绝旧回调。
                if(callback!=null)main.post(()->{if(!closed){if(reason.isEmpty())callback.ready.run();else callback.failed.accept(reason);}});
                warming.set(false);
                if(!closed&&preparation.get()!=null)prewarm();
            }
        });
    }
    private void ensureRecognizer() throws Exception {
        ensureRecognizer(()->!closed);
    }
    private void ensureRecognizer(java.util.function.BooleanSupplier active) throws Exception {
        if(!active.getAsBoolean())throw new java.util.concurrent.CancellationException();
        if(recognizer!=null)return;
        long started=System.nanoTime();
        recognizer=ResourceBudget.heavy(app,320L*1024*1024,active,()->new SherpaVoiceUnderstanding(app,settings));loaded=true;
        InteractionTrace.record(InteractionTrace.Stage.INPUT_PREPARE,0,started,1);
    }
    void setSuppressed(boolean value) {
        InteractionPorts.SpeechInput.Result previous;
        synchronized (control) {
            previous = textListener; textListener = null; explicitWindow = false;
            suppressed = value; until = 0; invalidate();
        }
        if (previous != null) main.post(() -> previous.onError("倾听被播报中断"));
        emit(value ? "播报期间暂停倾听" : continuous ? "陪伴倾听已开启" : "等待唤醒");
    }
    private long silenceMs() { return settings == null ? 4000 : settings.sessionSilenceMs; }
    private boolean enabled(long now) { return !closed && !suppressed && !memoryPaused && (continuous || now < until); }
    private void invalidate() { epoch++; audio.clear(); segments.clear(); voice = false; inputLevel = 0; latest = null; pendingSegments = 0; delivered = false; endpointReady = false; }

    void acceptPcm(short[] pcm, int count, int sampleRate) {
        if (!enabled(SystemClock.elapsedRealtime())) return;
        if (sampleRate != 16000) throw new IllegalArgumentException("语音理解输入必须为 16 kHz");
        synchronized (control) {
            if (!enabled(SystemClock.elapsedRealtime())) return;
            float[] samples = new float[count];
            for (int i = 0; i < count; i++) samples[i] = pcm[i] / 32768f;
            if (!audio.offer(new Chunk(samples, epoch))) {
                invalidate(); dropped++;
                emit("音频处理过载，本段已中断，请重说");
                reportError("音频处理过载，请重说", epoch);
            }
        }
    }
    private void vadLoop() {
        try {
            settings = new SherpaVoiceUnderstanding.Settings(app);
            try (SherpaVoiceUnderstanding.VoiceDetector detector = new SherpaVoiceUnderstanding.VoiceDetector(app, settings)) {
                float[] frame = new float[512]; int filled = 0; long seenEpoch = -1, heartbeat = 0;
                boolean wasEnabled = false;
                emit(continuous ? "陪伴倾听已开启" : "等待唤醒");
                while (!closed) {
                    long now = SystemClock.elapsedRealtime();
                    boolean active = enabled(now);
                    if (wasEnabled && !active) {
                        synchronized (control) {
                            // 开启新窗口可能与此检查并发，必须重新确认，不能清掉新会话。
                            active = enabled(SystemClock.elapsedRealtime());
                            if (!active) {
                                com.soundguardian.core.VoiceUnderstandingResult remembered = latest;
                                invalidate();
                                if (!suppressed && !memoryPaused) latest = remembered;
                            }
                        }
                        if (!active) {
                            detector.reset(); filled = 0;
                            emit(memoryPaused ? pausedMessage : suppressed ? "播报期间暂停倾听" : "等待唤醒");
                        }
                    }
                    wasEnabled = active;
                    if (loaded && now - lastNativeUse >= Math.min(settings.idleReleaseMs, modelIdleMs) && !draining.get() && reclaiming.compareAndSet(false,true)) {
                        MODEL.execute(()->{
                            try{if(!draining.get()&&!warming.get()&&SystemClock.elapsedRealtime()-lastNativeUse>=Math.min(settings.idleReleaseMs,modelIdleMs))releaseModel();}
                            finally{reclaiming.set(false);}
                        });
                    }
                    Chunk chunk = audio.poll(100, TimeUnit.MILLISECONDS);
                    if (chunk == null || !active || chunk.epoch != epoch) continue;
                    if (seenEpoch != chunk.epoch) { detector.reset(); filled = 0; seenEpoch = chunk.epoch; }
                    double energy = 0;
                    for (float sample : chunk.samples) energy += sample * sample;
                    synchronized (control) {
                        if (chunk.epoch != epoch || !enabled(SystemClock.elapsedRealtime())) continue;
                        inputLevel = now - chunk.at > 300 ? 0 : (float)Math.max(0, Math.min(1, (20 * Math.log10(Math.max(1e-6,
                                Math.sqrt(energy / chunk.samples.length))) + 55) / 40));
                        if (now - levelNotifiedAt >= 80) {
                            levelNotifiedAt = now;
                            emit(voice ? "正在听你说话" : "正在倾听");
                        }
                    }
                    for (float sample : chunk.samples) {
                        frame[filled++] = sample;
                        if (filled < frame.length) continue;
                        filled = 0; detector.accept(frame);
                        now = SystemClock.elapsedRealtime();
                        synchronized (control) {
                            if (chunk.epoch != epoch || !enabled(now)) break;
                            boolean speaking = detector.speaking();
                            endpointReady = false;
                            if (speaking && !continuous && !explicitWindow) until = now + silenceMs();
                            if (speaking != voice || speaking && now - heartbeat >= 400) {
                                voice = speaking; heartbeat = now; emit(speaking ? "正在听你说话" : "正在倾听");
                                InteractionPorts.SpeechInput.Result callback = textListener;
                                long callbackEpoch = epoch;
                                main.post(() -> {
                                    if (!closed && callbackEpoch == epoch && callback != null && callback == textListener) callback.onVoice(speaking);
                                });
                            }
                        }
                        while (detector.hasSegment()) {
                            float[] segment = detector.takeSegment();
                            if (segment.length >= 16000 * settings.minSpeech) enqueue(segment, chunk.epoch);
                        }
                        synchronized (control) { if (chunk.epoch == epoch) endpointReady = !voice; }
                        if (!voice) utteranceEnd(chunk.epoch);
                        if (chunk.epoch != epoch) break;
                    }
                }
            }
        } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
        catch (Exception | LinkageError error) {
            if (!closed) {
                emit("倾听不可用：" + error.getMessage());
                InteractionPorts.SpeechInput.Result callback = textListener;
                if (callback != null) main.post(() -> callback.onError("倾听不可用：" + error.getMessage()));
            }
        }
        finally { closed = true; audio.clear(); segments.clear(); MODEL.execute(this::releaseModel); }
    }
    private void enqueue(float[] samples, long session) {
        synchronized (control) {
            if (closed || suppressed || session != epoch) return;
            if (!segments.offer(new Chunk(samples, session))) {
                dropped++;
                if (continuous && textListener == null) {
                    segments.poll(); if (pendingSegments > 0) pendingSegments--;
                    segments.offer(new Chunk(samples, session));
                    emit("已跳过过期陪伴片段");
                } else {
                    invalidate(); emit("文字识别过载，本段已中断，请重说");
                    reportError("文字识别过载，请重说", epoch); return;
                }
            }
            pendingSegments++;
        }
        scheduleDecode();
    }
    private void scheduleDecode() {
        if (!draining.compareAndSet(false, true)) return;
        MODEL.execute(() -> {
            long decodingEpoch = -1;
            try {
                Chunk segment;
                while (!closed && (segment = segments.poll()) != null) {
                    long session = segment.epoch;
                    decodingEpoch = session;
                    if (session != epoch || suppressed || memoryPaused) continue;
                    if (SystemClock.elapsedRealtime() - segment.at > 6000) {
                        synchronized (control) {
                            dropped++;
                            if (continuous && textListener == null) { emit("已跳过过期陪伴片段"); main.post(() -> segmentDelivered(session)); continue; }
                            invalidate(); emit("文字识别延迟过长，本段已中断，请重说");
                            reportError("文字识别延迟过长，请重说", epoch); continue;
                        }
                    }
                    if (recognizer == null) {
                        emit("正在加载文字与情绪模型");
                        ensureRecognizer();
                    }
                    if (closed || session != epoch || suppressed || memoryPaused) continue;
                    lastNativeUse = SystemClock.elapsedRealtime();
                    final Chunk decodingSegment=segment;
                    com.soundguardian.core.VoiceUnderstandingResult result = ResourceBudget.heavy(app,0,
                            ()->!closed&&!suppressed&&!memoryPaused&&session==epoch,
                            ()->recognizer.recognize(decodingSegment.samples,SystemClock.elapsedRealtimeNanos()));
                    lastNativeUse = SystemClock.elapsedRealtime();
                    synchronized (control) {
                        if (closed || suppressed || session != epoch || memoryPaused) continue;
                        if (SystemClock.elapsedRealtime() - segment.at > 8000) {
                            dropped++;
                            if (continuous && textListener == null) { emit("已跳过过期陪伴结果"); main.post(() -> segmentDelivered(session)); continue; }
                            invalidate(); emit("文字识别超时，本段已中断，请重说");
                            reportError("文字识别超时，请重说", epoch); continue;
                        }
                        InteractionPorts.SpeechInput.Result callback = textListener;
                        if (!result.text.trim().isEmpty() && result.event.contains("Speech")) {
                            latest = result; emit("已识别 · 本地中文文字与情绪");
                            main.post(() -> {
                                if (!closed && session == epoch) {
                                    try {
                                        if (callback != null && callback == textListener) {
                                            synchronized (control) { delivered = true; }
                                            callback.onUnderstanding(result);
                                        }
                                    } finally { segmentDelivered(session); }
                                }
                            });
                        } else { emit("正在倾听"); main.post(() -> segmentDelivered(session)); }
                    }
                }
            } catch (Exception | LinkageError error) {
                releaseModel();
                synchronized (control) {
                    // 旧推理失败也不能暂停刚开启的新会话。
                    if (!closed && decodingEpoch == epoch) {
                        memoryPaused = true; invalidate();
                        pausedMessage = "文字与情绪识别不可用：" + error.getMessage();
                        emit(pausedMessage); reportError(error.getMessage(), epoch);
                    }
                }
            } finally {
                draining.set(false);
                if (!closed && !segments.isEmpty()) scheduleDecode();
            }
        });
    }
    private void reportError(String message, long session) {
        InteractionPorts.SpeechInput.Result callback = textListener;
        main.post(() -> {
            if (!closed && session == epoch && callback != null && callback == textListener) callback.onError(message); });
    }
    private void segmentDelivered(long session) {
        synchronized (control) {
            if (session != epoch) return;
            if (pendingSegments > 0) pendingSegments--;
        }
        utteranceEnd(session);
    }
    private void utteranceEnd(long session) {
        synchronized (control) {
            if (closed || session != epoch || voice || pendingSegments != 0 || !delivered) return;
        }
        main.post(() -> {
            InteractionPorts.SpeechInput.Result callback;
            synchronized (control) {
                if (closed || session != epoch || voice || !endpointReady || pendingSegments != 0 || !delivered) return;
                delivered = false; callback = textListener;
            }
            if (callback != null) callback.onUtteranceEnd();
        });
    }
    private void releaseModel() {
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        loaded = false;
    }
    void trimMemory() {
        resourceEpoch++;
        synchronized (control) { memoryPaused = true; invalidate(); }
        reportError("内存紧张，倾听已暂停", epoch);
        pausedMessage = "内存紧张，倾听已暂停；唤醒可重试";
        MODEL.execute(this::releaseModel); emit(pausedMessage);
    }
    private void emit(String message) {
        synchronized (control) {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            pendingState = new InteractionPorts.UnderstandingState(message, voice, now, latest, dropped, inputLevel,
                    enabled(now) && now < until && textListener == null);
            pendingEpoch = epoch;
        }
        // 合并状态通知；不在采集线程或 control 锁内调用外部代码、发送广播。
        if (notifying.compareAndSet(false, true)) main.post(dispatchState);
    }
    private void dispatchState() {
        InteractionPorts.UnderstandingState state; long session;
        synchronized (control) { state = pendingState; session = pendingEpoch; pendingState = null; }
        try { if (!closed && session == epoch && state != null) listener.accept(state); }
        finally {
            notifying.set(false);
            if (!closed && pendingState != null && notifying.compareAndSet(false, true)) main.post(dispatchState);
        }
    }
    @Override public void start(InteractionPorts.SpeechInput.Result callback) {
        startWindow(callback, silenceMs(), false);
    }
    @Override public void start(InteractionPorts.SpeechInput.Result callback, long timeoutMs) {
        startWindow(callback, timeoutMs, true);
    }
    private void startWindow(InteractionPorts.SpeechInput.Result callback, long timeoutMs, boolean explicit) {
        if (callback == null) throw new IllegalArgumentException("文字回调不能为空");
        if (timeoutMs < 1 || timeoutMs > 120000) throw new IllegalArgumentException("倾听时长不合法");
        InteractionPorts.SpeechInput.Result previous;
        synchronized (control) {
            if (closed) throw new IllegalStateException("倾听接口已关闭");
            previous = textListener;
            invalidate(); textListener = callback; memoryPaused = false;
            explicitWindow = explicit; until = SystemClock.elapsedRealtime() + timeoutMs;
        }
        if (previous != null && previous != callback) main.post(() -> previous.onError("倾听会话已替换"));
        emit("正在倾听");
    }
    @Override public void cancel() {
        InteractionPorts.SpeechInput.Result previous;
        synchronized (control) { previous = textListener; textListener = null; explicitWindow = false; until = 0; invalidate(); }
        if (previous != null) main.post(() -> previous.onError("倾听已取消"));
    }
    void cancel(InteractionPorts.SpeechInput.Result owner) {
        synchronized (control) { if (owner != null && textListener == owner) { textListener = null; explicitWindow = false; until = 0; invalidate(); } }
    }
    @Override public void close() {
        synchronized (control) { closed = true; textListener = null; invalidate(); }
        preparation.set(null);
        pendingState = null; main.removeCallbacks(dispatchState);
        vadThread.interrupt(); MODEL.execute(this::releaseModel);
    }
}

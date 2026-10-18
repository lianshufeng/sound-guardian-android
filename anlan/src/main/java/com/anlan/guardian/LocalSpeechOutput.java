package com.anlan.guardian;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.soundguardian.core.SherpaSpeechSynthesizer;
import com.soundguardian.core.SpeechTextBuffer;
import com.soundguardian.core.AppConfig;
import org.json.JSONObject;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 唯一实际播音实现。串行合成、预热唤醒短句，新请求替换旧请求，不持有 Activity。 */
final class LocalSpeechOutput implements InteractionPorts.SpeechOutput {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final AudioManager audioManager;
    private final Context app;
    private final long idleReleaseMs;
    // 服务快速重启时，先释放旧 native 引擎再加载新引擎，避免大模型同时驻留。
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), runnable -> new Thread(runnable, "anlan-speech"));
    private static final ThreadPoolExecutor PLAYBACK = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),runnable -> new Thread(runnable,"anlan-stream-playback"));
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private SherpaSpeechSynthesizer synthesizer;
    private final SpeechAudioCache<PreparedAudio> cache;
    private final String cacheRevision;
    private final java.util.concurrent.atomic.AtomicLong requests=new java.util.concurrent.atomic.AtomicLong();
    private final AtomicBoolean warming=new AtomicBoolean();
    private volatile long resourceEpoch;
    private String initializationError;
    private volatile boolean closed, memoryTrimmed;
    private Job current;
    private AudioTrack playing;
    private final Runnable initialize;
    private long lastSynthesisAt;
    private final Runnable idleRelease = this::releaseIfIdle;
    private void releaseIfIdle() { WORKER.execute(() -> {
        if (SystemClock.elapsedRealtime() - lastSynthesisAt >= idleReleaseMs) releaseSynthesizer();
    }); }

    LocalSpeechOutput(Context context, Consumer<String> status) {
        app = context.getApplicationContext(); audioManager = app.getSystemService(AudioManager.class);
        ResourceBudget budget=ResourceBudget.read(app);idleReleaseMs=budget.modelIdleMs;
        cache=new SpeechAudioCache<>(budget.speechCacheEntries,budget.speechCacheBytes);
        try { cacheRevision=AppConfig.read(app,"tts/config.json")+new JSONObject(AppConfig.read(app,"tts/manifest.json")).getString("archive_sha256"); }
        catch(Exception error){throw new IllegalStateException("语音配置读取失败",error);}
        initialize = () -> {
            try {
                if (closed) return;
                ensureSynthesizer(()->!closed);
                GeneratedAudio wake=synthesize("我在",()->!closed);
                remember("我在",new PreparedAudio(pcm(wake.getSamples(),wake.getSampleRate()),wake.getSampleRate()),resourceEpoch);
                main.post(() -> { if (!closed) status.accept("已就绪 · 中文女声 zf_001"); });
                long warmLease=requests.get();
                long warmEpoch=resourceEpoch;
                if(!closed&&current==null&&!memoryTrimmed){
                    GeneratedAudio snap=synthesize(BehaviorEngine.SNAP_MESSAGE,()->!closed&&requests.get()==warmLease&&resourceEpoch==warmEpoch);
                    remember(BehaviorEngine.SNAP_MESSAGE,new PreparedAudio(pcm(snap.getSamples(),snap.getSampleRate()),snap.getSampleRate()),warmEpoch);
                }
            } catch (java.util.concurrent.CancellationException cancelled) {
                // 用户请求优先于空闲补齐缓存，不把取消当作初始化故障。
            } catch (Exception | LinkageError error) {
                initializationError = error.toString();
                main.post(() -> { if (!closed) status.accept("不可用：" + initializationError); });
            } finally { releaseSynthesizer(); }
        };
        WORKER.execute(initialize);
    }

    @Override public void speak(InteractionPorts.SpeechRequest request, Progress progress) {
        if (request == null || progress == null) throw new IllegalArgumentException("播报请求和回调不能为空");
        if (request.text.length() > 2000) { main.post(() -> progress.onError("单次播报文字不能超过 2000 字")); return; }
        synchronized (lock) {
            if (closed) { main.post(() -> progress.onError("播报接口已关闭")); return; }
            cancelLocked();
            Job job = new Job(request, progress); current = job;
            main.post(() -> { if (job.active()) progress.onPreparing(); });
            PreparedAudio cached=request.source==InteractionPorts.SpeechRequest.Source.CUE?cue():cache.get(key(request.text));
            if(cached!=null){InteractionTrace.record(InteractionTrace.Stage.TTS_CACHE,0,System.nanoTime(),cached.samples.length);job.enqueuePlayback(cached);}
            else WORKER.execute(job);
        }
    }

    InteractionPorts.SpeechStream openStream(InteractionPorts.SpeechRequest.Source source, String eventId, Progress progress) throws Exception {
        JSONObject settings = new JSONObject(AppConfig.read(app, "tts/config.json")).getJSONObject("stream");
        SpeechTextBuffer input = new SpeechTextBuffer(settings.getInt("min_chars"), settings.getInt("max_chars"),
                settings.getInt("max_pending_chars"), settings.getLong("flush_ms"), settings.getLong("input_timeout_ms"), SystemClock.elapsedRealtime());
        Job job = new Job(null, progress);
        job.stream = new SpeechStreamPipeline<>(input, job::active, source, eventId);
        synchronized (lock) {
            if (closed) throw new IllegalStateException("播报接口已关闭");
            cancelLocked(); current = job; WORKER.execute(job);
        }
        return new InteractionPorts.SpeechStream() {
            @Override public boolean append(String delta) {
                if (!job.active()) throw new IllegalStateException("播报会话已失效");
                return input.append(delta, SystemClock.elapsedRealtime());
            }
            @Override public void end() { input.end(); }
            @Override public void cancel() { synchronized (lock) { if (current == job) cancelLocked(); } }
        };
    }

    private void cancelLocked() {
        if (current != null) {
            Job cancelled = current; current = null; WORKER.remove(cancelled);
            if(cancelled.playbackTask!=null)PLAYBACK.remove(cancelled.playbackTask);
            InteractionTrace.record(InteractionTrace.Stage.CANCEL,cancelled.traceId,System.nanoTime(),0);
            if (cancelled.stream != null) cancelled.stream.input.cancel();
            cancelled.finish(2, "");
        }
        if (playing != null) { try { playing.pause(); playing.flush(); } catch (IllegalStateException ignored) { } }
    }

    @Override public void stop() { synchronized (lock) { cancelLocked(); } }
    void prewarm(){
        if(closed||ResourceBudget.read(app).constrained||!warming.compareAndSet(false,true))return;
        long lease=requests.get();
        long epoch=resourceEpoch;
        WORKER.execute(()->{
            try{ensureSynthesizer(()->!closed&&requests.get()==lease&&resourceEpoch==epoch);lastSynthesisAt=SystemClock.elapsedRealtime();}
            catch(Exception|LinkageError ignored){ /* 提前准备失败由实际播报重试，不提前报告播放失败。 */ }
            finally{warming.set(false);if(!closed){main.removeCallbacks(idleRelease);main.postDelayed(idleRelease,idleReleaseMs);}}
        });
    }
    void trimMemory() {
        main.removeCallbacks(idleRelease);
        synchronized (lock) {
            memoryTrimmed=true;resourceEpoch++;
            if (current != null) cancelLocked();
            cache.retainOnly(key("我在"));
        }
        WORKER.execute(this::releaseSynthesizer);
    }
    // 只在 WORKER 访问 native 引擎；已合成的短句缓存和播放器不需要模型继续驻留。
    private void releaseSynthesizer() {
        if (synthesizer != null) { synthesizer.close(); synthesizer = null; }
    }
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true; cancelLocked();
            main.removeCallbacks(idleRelease);
            // 尚未开始的初始化直接撤销；已开始的 native 初始化/释放必须按序完成，不能因队列满而丢弃。
            if (!WORKER.remove(initialize)) WORKER.execute(() -> {
                releaseSynthesizer();
                cache.clear();
            });
        }
    }

    private String key(String text){return cacheRevision+'\n'+text;}
    private void remember(String text,PreparedAudio audio,long epoch){
        synchronized(lock){if(!closed&&resourceEpoch==epoch)cache.put(key(text),audio,audio.samples.length*2);}
    }
    private void ensureSynthesizer(java.util.function.BooleanSupplier active) throws Exception {
        if(synthesizer!=null)return;
        long start=System.nanoTime();
        synthesizer=ResourceBudget.heavy(app,512L*1024*1024,active,()->new SherpaSpeechSynthesizer(app));
        InteractionTrace.record(InteractionTrace.Stage.TTS_LOAD,0,start,0);
    }
    private GeneratedAudio synthesize(String text,java.util.function.BooleanSupplier active) throws Exception {
        GeneratedAudio audio=ResourceBudget.heavy(app,0,active,()->{
            long start=System.nanoTime();GeneratedAudio generated=synthesizer.synthesize(text,active);
            InteractionTrace.record(InteractionTrace.Stage.TTS_SYNTHESIS,0,start,generated.getSamples().length*1000000000L/generated.getSampleRate());
            return generated;
        });
        lastSynthesisAt=SystemClock.elapsedRealtime();
        return audio;
    }
    private static PreparedAudio cue(){
        int rate=16000;short[] samples=new short[2400];
        for(int i=0;i<samples.length;i++){
            double envelope=Math.sin(Math.PI*i/samples.length)*Math.exp(-3.0*i/samples.length);
            samples[i]=(short)(Math.sin(2*Math.PI*880*i/rate)*envelope*5500);
        }
        return new PreparedAudio(samples,rate);
    }

    private final class Job implements Runnable {
        final long traceId=requests.incrementAndGet(),submittedAt=System.nanoTime();
        final long cacheEpoch=resourceEpoch;
        final InteractionPorts.SpeechRequest request;
        final Progress progress;
        final AtomicBoolean terminal = new AtomicBoolean();
        SpeechStreamPipeline<PreparedAudio> stream;
        Runnable playbackTask;
        Job(InteractionPorts.SpeechRequest request, Progress progress) { this.request = request; this.progress = progress; }
        boolean active() { synchronized (lock) { return !closed && current == this && !terminal.get(); } }
        void finish(int result, String error) {
            if (!terminal.compareAndSet(false, true)) return;
            synchronized(lock){if(current==this)current=null;}
            main.post(() -> {
                if (result == 0) progress.onComplete();
                else if (result == 1) progress.onError(error);
                else progress.onCancelled();
            });
        }

        @Override public void run() {
            try {
                if (!active()) return;
                if (stream == null) enqueuePlayback(prepare(request));
                else stream.run(new SpeechStreamPipeline.Engine<PreparedAudio>() {
                    @Override public PreparedAudio synthesize(InteractionPorts.SpeechRequest segment) { return prepare(segment); }
                    @Override public void play(PreparedAudio audio, InteractionPorts.SpeechRequest segment) throws Exception { Job.this.play(audio, segment); }
                    @Override public void idle() { if (SystemClock.elapsedRealtime() - lastSynthesisAt >= idleReleaseMs) releaseSynthesizer(); }
                    @Override public void failed(Exception error) { finish(1, error.toString()); }
                }, PLAYBACK, SystemClock::elapsedRealtime);
                if (stream != null && active()) finish(0, "");
            } catch (Exception | LinkageError error) {
                if (active()) finish(1, error.toString());
            } finally {
                synchronized (lock) {
                    main.removeCallbacks(idleRelease);
                    if (!closed && synthesizer != null) main.postDelayed(idleRelease, idleReleaseMs);
                }
            }
        }

        void enqueuePlayback(PreparedAudio audio){
            synchronized(lock){
                if(!active())return;
                playbackTask=()->{
                    try{play(audio,request);if(active())finish(0,"");}
                    catch(Exception|LinkageError error){if(active())finish(1,error.toString());}
                };
                PLAYBACK.execute(playbackTask);
            }
        }

        private PreparedAudio prepare(InteractionPorts.SpeechRequest segment) {
            if(segment.source==InteractionPorts.SpeechRequest.Source.CUE)return cue();
            PreparedAudio cached=cache.get(key(segment.text));if(cached!=null)return cached;
            try{
                ensureSynthesizer(this::active);
                GeneratedAudio audio=synthesize(segment.text,this::active);
                PreparedAudio prepared=new PreparedAudio(pcm(audio.getSamples(),audio.getSampleRate()),audio.getSampleRate());
                if(segment.text.equals("我在")||segment.text.equals(BehaviorEngine.SNAP_MESSAGE))remember(segment.text,prepared,cacheEpoch);
                return prepared;
            }catch(RuntimeException error){throw error;}
            catch(Exception error){throw new IllegalStateException("中文合成暂不可用",error);}
        }

        private void play(PreparedAudio audio, InteractionPorts.SpeechRequest segment) throws Exception {
            AudioTrack track = null;
            AudioFocusRequest focus = null;
            try {
                if (!active()) return;
                short[] samples = audio.samples;
                track = new AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.length * 2).build();
                if (track.write(samples, 0, samples.length) != samples.length || track.getState() != AudioTrack.STATE_INITIALIZED)
                    throw new IllegalStateException("播报音频缓冲失败");
                synchronized (lock) { if (!active()) return; playing = track; }
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                                synchronized (lock) { if (current == this) cancelLocked(); }
                        }, main).build();
                AudioTrack output = track;
                AudioFocusRequest focusRequest = focus;
                FutureTask<Boolean> start = new FutureTask<>(() -> {
                    synchronized (lock) {
                        if (!active()) return false;
                        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                            throw new IllegalStateException("未获得播报音频焦点");
                        output.play();
                    }
                    if (stream == null) progress.onStart(); else progress.onSegmentStart(segment);
                    InteractionTrace.record(InteractionTrace.Stage.PLAYBACK_START,traceId,submittedAt,samples.length);
                    return true;
                });
                main.post(start);
                if (!start.get(5, TimeUnit.SECONDS)) return;
                long deadline = SystemClock.elapsedRealtime() + samples.length * 1000L / audio.sampleRate + 5000;
                while (active() && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < samples.length) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("播报播放超时");
                    SystemClock.sleep(10);
                }
                if (active() && stream != null) {
                    FutureTask<Void> ended = new FutureTask<>(() -> { if (active()) progress.onSegmentComplete(segment); return null; });
                    main.post(ended); ended.get(5, TimeUnit.SECONDS);
                }
                if(active())InteractionTrace.record(InteractionTrace.Stage.PLAYBACK_END,traceId,System.nanoTime(),samples.length);
            } finally {
                synchronized (lock) {
                    if (playing == track) playing = null;
                    if (track != null) track.release();
                }
                if (focus != null) audioManager.abandonAudioFocusRequest(focus);
            }
        }
    }

    private static final class PreparedAudio {
        final short[] samples;
        final int sampleRate;
        PreparedAudio(short[] samples, int sampleRate) { this.samples = samples; this.sampleRate = sampleRate; }
    }

    private static short[] pcm(float[] input, int sampleRate) {
        // 删除模型首尾静音，保留 20 ms 边缘，让字幕开始尽量贴近可听声音。
        int first = 0, last = input.length;
        while (first < last && Math.abs(input[first]) < .003f) first++;
        while (last > first && Math.abs(input[last - 1]) < .003f) last--;
        if (first == last) throw new IllegalStateException("TTS 生成了静音");
        first = Math.max(0, first - sampleRate / 50); last = Math.min(input.length, last + sampleRate / 50);
        short[] output = new short[last - first];
        for (int i = 0; i < output.length; i++) output[i] = (short) Math.round(Math.max(-1, Math.min(1, input[first + i])) * 32767);
        return output;
    }

}

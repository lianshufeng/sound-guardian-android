package com.anlan.guardian;
import com.soundguardian.core.SoundContract; import com.soundguardian.core.AppConfig; import com.soundguardian.core.EventEngine; import com.soundguardian.core.YamnetClassifier;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Collections;
import java.util.List;

/** 单一采集线程：声音模型、事件规则和唤醒词共享同一个 PCM 流。 */
public final class GuardianService extends Service {
    public static final String ACTION = "anlan.guardian.update";
    // 新旧 Service 实例共用串行队列，确保快速重启时旧录音完全释放后才创建新录音。
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor();
    private final Object audioLock = new Object();
    private AudioRecord record;
    private volatile boolean running;
    private volatile long token;
    private boolean audioLoopPending, destroyed, restartRequested;
    private int latestStartId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LocalSpeechOutput speechOutput;
    private volatile WakeWordWorker wakeWorker;
    private volatile UnderstandingWorker understandingWorker;
    private volatile boolean speechBusy;
    private long speechRequestId;
    private long speechDisplayId;
    private long speechLease;
    private CareCoordinator care;
    private ConversationCoordinator conversation;
    private InputSessionController inputSessions;
    private SpeechSession currentSpeech;
    private LocalCarePolicy localCare;
    private CareSchedules careSchedules;
    private InteractionPorts.SpeechInput.Result careInput;
    private volatile java.util.Set<String> safetyEventIds = Collections.emptySet();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (speechOutput != null) speechOutput.performanceModeChanged();
        if (understandingWorker != null) understandingWorker.performanceModeChanged();
        if (care != null && !getSharedPreferences("care", MODE_PRIVATE).getBoolean("enabled", false)) care.interrupt("USER_DISABLED");
        if (understandingWorker != null) understandingWorker.setContinuous(getSharedPreferences("interaction", MODE_PRIVATE).getBoolean("companion_listening", false));
        if (!running && audioLoopPending) { restartRequested = true; return START_NOT_STICKY; }
        startSession();
        return START_NOT_STICKY;
    }

    private void startSession() {
        if (!running) {
            token = GuardianRuntime.begin(); running = true; speechDisplayId = 0;
            final long sessionToken = token;
            try { startForeground(17, notification()); }
            catch (RuntimeException error) { running = false; publish(GuardianRuntime.Status.ERROR, Collections.emptyList(), error.toString(), "", 0, -96, false); stopSelf(); return; }
            publish(GuardianRuntime.Status.STARTING, Collections.emptyList(), "", "", 0, -96, false);
            GuardianRuntime.publishSpeechStatus(token, "正在加载中文语音");
            speechOutput = new LocalSpeechOutput(this, status -> {
                if (isCurrent(sessionToken) && GuardianRuntime.publishSpeechStatus(sessionToken, status))
                    sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
            });
            speechLease = SpeechPlayback.attach(request -> {
                Runnable speak = () -> { if (isCurrent(sessionToken)) speakRequest(request); };
                if (Looper.myLooper() == Looper.getMainLooper()) speak.run();
                else main.post(speak);
            }, (source, eventId, listener) -> openSpeechStream(sessionToken, source, eventId, listener));
            inputSessions = new InputSessionController(new InputSessionController.Host() {
                public UnderstandingWorker worker() { return understandingWorker; }
                public boolean available() { return isCurrent(sessionToken) && !speechBusy; }
                public void readyForInput() { if (understandingWorker != null) understandingWorker.setSuppressed(false); }
                public boolean cueEnabled() { return getSharedPreferences("ai_model",MODE_PRIVATE).getBoolean("cue",true); }
                public void cue(Runnable complete, java.util.function.Consumer<String> failed) {
                    SpeechPlayback.speak(new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.CUE,"提示音","","",new InteractionPorts.SpeechOutput.Progress() {
                        public void onComplete() { complete.run(); }
                        public void onError(String message) { failed.accept("CUE_FAILED"); }
                        public void onCancelled() { failed.accept("CUE_CANCELLED"); }
                    }));
                }
            },new DialogueConfig(this).tailMs,new DialogueConfig(this).wakeTailMs);
            conversation = new ConversationCoordinator(this,new ConversationCoordinator.Host() {
                public UnderstandingWorker input() { return understandingWorker; }
                public boolean available() { return isCurrent(sessionToken) && understandingWorker!=null && GuardianRuntime.snapshot().events.stream().noneMatch(event -> event.active&&event.priority()>=3); }
                public void startInput(InteractionPorts.SpeechInput.Result result,long timeout) { inputSessions.start(result,timeout); }
                public void startInput(InteractionPorts.SpeechInput.Result result,long timeout,boolean immediate) { inputSessions.start(result,timeout,immediate); }
                public void stopInput() { if (inputSessions!=null) inputSessions.cancel(); }
                public void prepareSpeech() { if(speechOutput!=null)speechOutput.prewarm(); }
                public void speak(String text,Runnable complete,java.util.function.Consumer<String> failed) {
                    SpeechPlayback.speak(new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.DIALOGUE,text,"","",new InteractionPorts.SpeechOutput.Progress() {
                        public void onComplete() { complete.run(); }
                        public void onError(String error) { failed.accept(error); }
                        public void onCancelled() { failed.accept("CANCELLED"); }
                    }));
                }
                public void stopSpeech() { if (currentSpeech!=null&&currentSpeech.request!=null&&(currentSpeech.request.source==InteractionPorts.SpeechRequest.Source.DIALOGUE||currentSpeech.request.source==InteractionPorts.SpeechRequest.Source.CUE)) { currentSpeech.onCancelled(); if (speechOutput!=null) speechOutput.stop(); } }
            });
            care = new CareCoordinator(this, new CareCoordinator.Host() {
                @Override public boolean available() { return isCurrent(sessionToken) && understandingWorker != null; }
                @Override public boolean blocked() {
                    return speechBusy || Conversation.active() || GuardianRuntime.snapshot().events.stream().anyMatch(event -> event.active && event.priority() >= 3);
                }
                @Override public boolean blocked(InteractionPorts.CareRequest request) {
                    if (!request.safety) return blocked();
                    List<SoundEvent> events = GuardianRuntime.snapshot().events;
                    return speechBusy || events.stream().noneMatch(event -> event.active && event.priority() >= 3 && event.id.equals(request.eventId));
                }
                @Override public String admission(InteractionPorts.CareRequest request) {
                    if (request.safety) return "";
                    android.content.SharedPreferences preferences = getSharedPreferences("care", MODE_PRIVATE);
                    java.util.Calendar now = java.util.Calendar.getInstance();
                    int minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
                    com.soundguardian.core.VoiceUnderstandingResult last = GuardianRuntime.understandingSnapshot().result;
                    boolean goodnight = request.scenarioId.equals("S64") && preferences.getBoolean("group_companion", false)
                            && last != null && SystemClock.elapsedRealtime() >= last.at && SystemClock.elapsedRealtime() - last.at < 6000
                            && (CareSession.normalize(last.text).equals("晚安") || CareSession.normalize(last.text).equals("我要睡了"));
                    if (preferences.getBoolean("quiet_enabled", true) && LocalCarePolicy.inPeriod(minute,
                            preferences.getInt("quiet_start", 1320), preferences.getInt("quiet_end", 420))
                            && !goodnight && !(request.scenarioId.equals("S18") && preferences.getBoolean("bedtime_enabled", false))) return "QUIET_HOURS";
                    if (!goodnight && System.currentTimeMillis() < preferences.getLong("snooze_until", 0)) return "SNOOZED";
                    InteractionPorts.UnderstandingState input = GuardianRuntime.understandingSnapshot();
                    if (input.voice && SystemClock.elapsedRealtime() - input.at < 2000) return "USER_SPEAKING";
                    return "";
                }
                @Override public void startInput(InteractionPorts.SpeechInput.Result callback, long timeoutMs) {
                    UnderstandingWorker worker = understandingWorker;
                    if (!isCurrent(sessionToken) || worker == null) throw new IllegalStateException("倾听不可用");
                    careInput = callback; inputSessions.start(callback, timeoutMs);
                }
                @Override public boolean asynchronousInput() { return true; }
                @Override public void replied(InteractionPorts.CareRequest request,Care.Result result) { if (conversation!=null) conversation.careReply(request,result); }
                @Override public void stopInput() {
                    if (careInput!=null && inputSessions!=null) inputSessions.cancel();
                    careInput = null;
                }
                @Override public void stopSpeech(String careId) {
                    if (currentSpeech != null && currentSpeech.request != null && careId.equals(currentSpeech.request.careId)) {
                        currentSpeech.onCancelled();
                        if (speechOutput != null) speechOutput.stop();
                    }
                }
            });
            localCare = new LocalCarePolicy(this);
            careSchedules = new CareSchedules(this);
            audioLoopPending = true;
            AUDIO.execute(() -> loop(sessionToken));
        }
    }
    private boolean isCurrent(long sessionToken) { return running && token == sessionToken; }

    private Notification notification() {
        NotificationChannel channel = new NotificationChannel("anlan_listen", getString(com.anlan.guardian.R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, "anlan_listen").setContentTitle("安澜正在守护")
                .setContentText("本地声音识别 · 不保存录音").setSmallIcon(R.drawable.ic_guardian)
                .setContentIntent(open).setOngoing(true).build();
    }

    private void loop(long sessionToken) {
        GuardAudioPipeline guard = null;
        java.util.concurrent.atomic.AtomicReference<Throwable> guardFailure = new java.util.concurrent.atomic.AtomicReference<>();
        AudioRecord audio = null;
        PowerManager.WakeLock wake = null;
        WakeWordWorker wakeWord = null;
        boolean failed = false;
        try {
            if (!running) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("未授予麦克风权限");
            guard = createGuardPipeline(sessionToken, guardFailure::set);
            publishWake("正在加载", 0);
            wakeWord = new WakeWordWorker(this, status -> { if (isCurrent(sessionToken)) publishWake(status, 0); },
                    at -> onWakeDetected(sessionToken, at));
            wakeWorker = wakeWord;
            understandingWorker = new UnderstandingWorker(this,
                    getSharedPreferences("interaction", MODE_PRIVATE).getBoolean("companion_listening", false), update -> {
                if (isCurrent(sessionToken) && GuardianRuntime.publishUnderstanding(sessionToken, update))
                    sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
            });
            int min = AudioRecord.getMinBufferSize(SoundContract.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("设备不支持 16 kHz 麦克风输入");
            synchronized (audioLock) {
                if (!running) return;
                audio = new AudioRecord(MediaRecorder.AudioSource.MIC, SoundContract.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 32000));
                if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("麦克风初始化失败");
                record = audio; audio.startRecording();
            }
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anlan:audio");
            wake.acquire(10 * 60 * 1000L);
            short[] readBuffer = new short[1600]; long sequence = 0;
            long renewAt = SystemClock.elapsedRealtime() + 8 * 60 * 1000L;
            boolean captureReported=false;
            while (running) {
                int count = audio.read(readBuffer, 0, readBuffer.length);
                if (!running) break;
                if (guardFailure.get() != null) throw new IllegalStateException("声音分析失败", guardFailure.get());
                if (count <= 0) throw new IllegalStateException("麦克风读取失败：" + count);
                if(BuildConfig.DEBUG&&!captureReported&&android.os.Build.VERSION.SDK_INT>=29){
                    android.media.AudioRecordingConfiguration capture=audio.getActiveRecordingConfiguration();
                    if(capture!=null){
                        android.media.AudioFormat client=capture.getClientFormat(),device=capture.getFormat();
                        android.util.Log.i("AnlanCapture","source="+capture.getClientAudioSource()+" client_rate="+client.getSampleRate()
                                +" client_channels="+client.getChannelCount()+" client_encoding="+client.getEncoding()
                                +" device_rate="+device.getSampleRate()+" device_channels="+device.getChannelCount()
                                +" silenced="+(android.os.Build.VERSION.SDK_INT>=29&&capture.isClientSilenced()));
                        captureReported=true;
                    }
                }
                long dispatchAt = System.nanoTime();
                GuardianRuntime.publishSoundLevel(sessionToken, readBuffer, count, SystemClock.elapsedRealtime());
                wakeWord.acceptPcm(readBuffer, count, SoundContract.SAMPLE_RATE);
                UnderstandingWorker understanding = understandingWorker;
                if (understanding != null) understanding.acceptPcm(readBuffer, count, SoundContract.SAMPLE_RATE);
                if (SystemClock.elapsedRealtime() >= renewAt) { wake.release(); wake.acquire(10 * 60 * 1000L); renewAt = SystemClock.elapsedRealtime() + 8 * 60 * 1000L; }
                guard.accept(readBuffer, count, sequence, System.currentTimeMillis()); sequence += count;
                InteractionTrace.record(InteractionTrace.Stage.CAPTURE, sessionToken, dispatchAt, count);
            }
        } catch (Exception error) {
            failed = running;
            if (failed) { android.util.Log.e("Anlan", "声音守护失败", error); publish(GuardianRuntime.Status.ERROR, Collections.emptyList(), error.toString(), "", 0, -96, false); }
        } finally {
            running = false;
            if (guard != null) {
                guard.close();
                boolean interrupted = false;
                while (true) try { guard.awaitStopped(); break; } catch (InterruptedException error) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
            ConversationCoordinator endingConversation = conversation;
            if (endingConversation != null) main.post(endingConversation::close);
            if (wakeWord != null) wakeWord.close();
            wakeWorker = null;
            if (understandingWorker != null) understandingWorker.close();
            understandingWorker = null;
            if (speechOutput != null) speechOutput.close();
            CareCoordinator endingCare = care;
            if (endingCare != null) endingCare.close();
            LocalCarePolicy endingPolicy = localCare;
            if (endingPolicy != null) main.post(endingPolicy::close);
            CareSchedules endingSchedules = careSchedules;
            if (endingSchedules != null) main.post(endingSchedules::close);
            SpeechPlayback.detach(speechLease);
            GuardianRuntime.publishSpeechStatus(token, "已停止");
            GuardianRuntime.publishWake(token, "已停止", 0);
            // 只有工作线程释放资源，主线程只请求停止，不会与推理 close 竞争。
            synchronized (audioLock) {
                if (audio != null) { try { audio.stop(); } catch (IllegalStateException ignored) { } audio.release(); }
                record = null;
            }
            if (wake != null && wake.isHeld()) wake.release();
            if (!failed) publish(GuardianRuntime.Status.STOPPED, Collections.emptyList(), "", "", 0, -96, false);
            main.post(() -> {
                audioLoopPending = false;
                if (destroyed) return;
                if (restartRequested) {
                    restartRequested = false;
                    startSession();
                } else stopSelf(latestStartId);
            });
        }
    }

    private GuardAudioPipeline createGuardPipeline(long sessionToken, java.util.function.Consumer<Throwable> failure) {
        return new GuardAudioPipeline(new GuardAudioPipeline.Processor() {
            YamnetClassifier model; AppConfig config; EventEngine events; EventHistoryWriter history;
            long lastSave;
            final java.util.Set<String> reactedSnaps = new java.util.LinkedHashSet<>();
            public void start() throws Exception {
                config = new AppConfig(GuardianService.this); model = new YamnetClassifier(GuardianService.this);
                events = new EventEngine(config.rules); history = new EventHistoryWriter(GuardianService.this);
            }
            public void window(float[] input, long wallTime) {
                if (!isCurrent(sessionToken)) return;
                long start = SystemClock.elapsedRealtimeNanos(); float[] scores = model.classify(input);
                float elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1000000f;
                if (!isCurrent(sessionToken)) return;
                events.update(scores, wallTime); List<SoundEvent> rows = records(events);
                if (speechBusy && rows.stream().anyMatch(event -> event.active && event.priority() >= 3 && !safetyEventIds.contains(event.id))) {
                    LocalSpeechOutput output = speechOutput; if (output != null) output.stop();
                }
                int top = 0; boolean activity = false;
                for (int j = 0; j < scores.length; j++) {
                    if (scores[j] > scores[top]) top = j;
                    if ((config.labels[j].equals("Speech") || config.labels[j].equals("Music")) && scores[j] >= .35f) activity = true;
                }
                double energy = 0; for (float sample : input) energy += sample * sample;
                float db = (float)Math.max(-96, 20 * Math.log10(Math.max(1e-9, Math.sqrt(energy / input.length))));
                publish(GuardianRuntime.Status.LISTENING, rows, modelResults(scores, config), "", config.name(config.labels[top]), config.labels[top], scores[top], elapsed, db, activity);
                for (SoundEvent event : rows) {
                    if (!event.active || !event.label.equals("Finger snapping") || !reactedSnaps.add(event.id)) continue;
                    if (reactedSnaps.size() > 100) reactedSnaps.remove(reactedSnaps.iterator().next());
                    if (!speechBusy) main.post(() -> {
                        if (isCurrent(sessionToken) && System.currentTimeMillis() - event.end < 2000)
                            SpeechPlayback.speak(InteractionPorts.SpeechRequest.fingerSnaps(event.id));
                    });
                }
                if (SystemClock.elapsedRealtime() - lastSave > 2000) { history.submit(rows); lastSave = SystemClock.elapsedRealtime(); }
            }
            public void gap(long wallTime) {
                if (events != null) {
                    events.stop(wallTime); List<SoundEvent> rows=records(events);
                    if (history != null) history.submit(rows);
                    if (isCurrent(sessionToken)) publish(GuardianRuntime.Status.LISTENING,rows,"","",0,-96,false);
                }
            }
            public void stop() {
                gap(System.currentTimeMillis());
                if (model != null) model.close();
                if (history != null) history.close();
            }
        }, failure);
    }

    private void onWakeDetected(long sessionToken, long at) {
        main.post(() -> {
            if (!isCurrent(sessionToken)) return;
            Care.userInteraction();
            if (GuardianRuntime.snapshot().events.stream().anyMatch(event -> event.active && event.priority() >= 3)) {
                if (BuildConfig.DEBUG) android.util.Log.i("AnlanWake", "response_blocked=high_priority_event at=" + at);
                return;
            }
            if (care != null) care.interrupt("USER_WAKE");
            if (conversation != null) conversation.stop("NEW_WAKE");
            publishWake("已就绪 · 小安", at);
            if (BuildConfig.DEBUG) android.util.Log.i("AnlanWake", "response_accepted at=" + at);
            SpeechPlayback.speak(InteractionPorts.SpeechRequest.wake());
            if (understandingWorker != null) understandingWorker.prewarm();
        });
    }
    private void speakRequest(InteractionPorts.SpeechRequest request) {
            if (request.source == InteractionPorts.SpeechRequest.Source.INTERACTION) {
                InteractionPorts.UnderstandingState input = GuardianRuntime.understandingSnapshot();
                GuardianRuntime.SpeechSnapshot previous = GuardianRuntime.speechSnapshot();
                long now = SystemClock.elapsedRealtime();
                if (GuardianRuntime.snapshot().status != GuardianRuntime.Status.LISTENING || speechBusy || !Care.activeId().isEmpty() || Conversation.active()
                        || input.voice && now >= input.at && now - input.at < 2000
                        || previous.phase != GuardianRuntime.SpeechPhase.IDLE && now - previous.at < 500
                        || GuardianRuntime.wakeSnapshot().at > 0 && now - GuardianRuntime.wakeSnapshot().at < 5400) {
                    if (request.observer != null) request.observer.onCancelled();
                    return;
                }
            }
            boolean safety = care != null && care.permitsSafety(request.careId, request.eventId);
            if (!running || !safety && GuardianRuntime.snapshot().events.stream().anyMatch(event -> event.active && event.priority() >= 3)) {
                if (request.observer != null) request.observer.onCancelled();
                return;
            }
            if (request.source != InteractionPorts.SpeechRequest.Source.CARE && request.source != InteractionPorts.SpeechRequest.Source.CUE && care != null) care.interrupt("OTHER_SPEECH");
            SpeechSession progress = beginSpeech(request, null);
            java.util.Set<String> protectedEvents = new java.util.HashSet<>();
            if (safety) for (SoundEvent event : GuardianRuntime.snapshot().events)
                if (event.active && event.priority() >= 3) protectedEvents.add(event.id);
            safetyEventIds = Collections.unmodifiableSet(protectedEvents);
            // 动画与缓存播报并行，不再额外等待600毫秒。
            main.postDelayed(() -> {
                if (!progress.current()) return;
                if (GuardianRuntime.snapshot().events.stream().anyMatch(event -> event.active && event.priority() >= 3 && !safetyEventIds.contains(event.id))) progress.onCancelled();
                else speechOutput.speak(request, progress);
            }, 0);
    }

    private SpeechSession beginSpeech(InteractionPorts.SpeechRequest request, InteractionPorts.SpeechStreamListener listener) {
        return beginSpeech(request, listener, false);
    }
    private SpeechSession beginSpeech(InteractionPorts.SpeechRequest request, InteractionPorts.SpeechStreamListener listener, boolean deferInput) {
        if (currentSpeech != null) currentSpeech.onCancelled();
        SpeechSession progress = new SpeechSession(request, listener);
        currentSpeech = progress;
        speechOutput.stop(); speechBusy = true;
        if (wakeWorker != null) wakeWorker.setSuppressed(true);
        if (!deferInput && understandingWorker != null) understandingWorker.setSuppressed(true);
        publishSpeech(progress.displayId, GuardianRuntime.SpeechPhase.PREPARING, request, "");
        return progress;
    }

    private InteractionPorts.SpeechStream openSpeechStream(long sessionToken, InteractionPorts.SpeechRequest.Source source,
                                                           String eventId, InteractionPorts.SpeechStreamListener listener) {
        java.util.concurrent.FutureTask<InteractionPorts.SpeechStream> open = new java.util.concurrent.FutureTask<>(() -> {
            if (!isCurrent(sessionToken)) throw new IllegalStateException("守护会话已结束");
            if (GuardianRuntime.snapshot().events.stream().anyMatch(event -> event.active && event.priority() >= 3))
                throw new IllegalStateException("高优先级守护事件正在处理，暂不接受播报");
            if (care != null) care.interrupt("OTHER_SPEECH");
            boolean deferInput = source == InteractionPorts.SpeechRequest.Source.DIALOGUE && conversation != null && conversation.acceptingSupplement();
            SpeechSession progress = beginSpeech(null, listener, deferInput);
            try { return speechOutput.openStream(source, eventId, progress); }
            catch (Exception error) { progress.onError(error.toString()); throw error; }
        });
        if (Looper.myLooper() == Looper.getMainLooper()) open.run(); else main.post(open);
        try { return open.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception error) { open.cancel(false); throw new IllegalStateException("无法创建流式播报会话", error); }
    }

    private final class SpeechSession implements InteractionPorts.SpeechOutput.Progress {
        final long id = ++speechRequestId, sessionToken = token;
        long displayId = ++speechDisplayId;
        InteractionPorts.SpeechRequest request;
        final InteractionPorts.SpeechStreamListener listener;
        boolean terminal;
        SpeechSession(InteractionPorts.SpeechRequest request, InteractionPorts.SpeechStreamListener listener) {
            this.request = request; this.listener = listener;
        }
        boolean current() { return isCurrent(sessionToken) && id == speechRequestId && !terminal; }
        @Override public boolean beforePlayback() {
            if (!current() || listener != null && !listener.beforePlayback()) return false;
            if (understandingWorker != null) understandingWorker.setSuppressed(true);
            return true;
        }
        @Override public void onStart() {
            if (current()) {
                publishSpeech(displayId, GuardianRuntime.SpeechPhase.PLAYING, request, "");
                if (BuildConfig.DEBUG && request != null && request.source == InteractionPorts.SpeechRequest.Source.WAKE)
                    android.util.Log.i("AnlanWake", "speech_start id=" + displayId);
                if(listener==null&&request!=null&&request.source==InteractionPorts.SpeechRequest.Source.DIALOGUE&&understandingWorker!=null)
                    understandingWorker.prewarm();
                if (request != null && request.observer != null) request.observer.onStart();
            }
        }
        @Override public void onSegmentStart(InteractionPorts.SpeechRequest segment) {
            if (!current()) return;
            request = segment; displayId = ++speechDisplayId;
            onStart();
            if (listener != null) listener.onSegmentStarted(segment);
        }
        @Override public void onSegmentComplete(InteractionPorts.SpeechRequest segment) {
            if (current()) publishSpeech(displayId, GuardianRuntime.SpeechPhase.WAITING, segment, "");
        }
        @Override public void onComplete() { finished(GuardianRuntime.SpeechPhase.COMPLETE, ""); }
        @Override public void onError(String message) { finished(GuardianRuntime.SpeechPhase.ERROR, message); }
        @Override public void onCancelled() { finished(GuardianRuntime.SpeechPhase.CANCELLED, ""); }
        private void finished(GuardianRuntime.SpeechPhase phase, String error) {
            if (terminal) return;
            if (current()) {
                speechBusy = false;
                safetyEventIds = Collections.emptySet();
                publishSpeech(displayId, phase, request, error);
                if (BuildConfig.DEBUG && request != null && request.source == InteractionPorts.SpeechRequest.Source.WAKE)
                    android.util.Log.i("AnlanWake", "speech_finish id=" + displayId + " phase=" + phase);
                if (request != null && request.source == InteractionPorts.SpeechRequest.Source.WAKE && phase == GuardianRuntime.SpeechPhase.COMPLETE) {
                    main.post(() -> {
                        if (isCurrent(sessionToken) && id == speechRequestId && !speechBusy && conversation != null)
                            conversation.startAfterWake();
                    });
                }
                main.postDelayed(() -> {
                    if (!isCurrent(sessionToken) || id != speechRequestId || speechBusy) return;
                    if (wakeWorker != null) wakeWorker.setSuppressed(false);
                    if (understandingWorker != null && GuardianRuntime.inputPhase().equals("IDLE")) {
                        understandingWorker.setSuppressed(false);
                        if (request != null && request.source == InteractionPorts.SpeechRequest.Source.WAKE && phase == GuardianRuntime.SpeechPhase.COMPLETE) {
                            if (!Conversation.active()) understandingWorker.openWindow();
                        }
                    }
                }, 500);
            }
            terminal = true;
            if (request != null && request.observer != null) {
                if (phase == GuardianRuntime.SpeechPhase.COMPLETE) request.observer.onComplete();
                else if (phase == GuardianRuntime.SpeechPhase.CANCELLED) request.observer.onCancelled();
                else request.observer.onError(error);
            }
            // 旧会话只通知自己的调用方，不改变新会话的字幕或唤醒抑制。
            if (listener != null) {
                if (phase == GuardianRuntime.SpeechPhase.COMPLETE) listener.onComplete();
                else if (phase == GuardianRuntime.SpeechPhase.CANCELLED) listener.onCancelled();
                else listener.onError(error);
            }
        }
    }
    private void publishSpeech(long id, GuardianRuntime.SpeechPhase phase, InteractionPorts.SpeechRequest request, String error) {
        if (running && GuardianRuntime.publishSpeech(token, new GuardianRuntime.SpeechSnapshot(id, phase, request, SystemClock.elapsedRealtime(), error)))
            sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
    }
    private void publishWake(String status, long at) {
        if (running && GuardianRuntime.publishWake(token, status, at))
            sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
    }
    private void publish(GuardianRuntime.Status status, List<SoundEvent> rows, String error, String label, float ms, float db, boolean activity) {
        publish(status, rows, error, label, 0, ms, db, activity);
    }
    private void publish(GuardianRuntime.Status status, List<SoundEvent> rows, String error, String label, float topScore, float ms, float db, boolean activity) {
        publish(status, rows, error, label, label, topScore, ms, db, activity);
    }
    private void publish(GuardianRuntime.Status status, List<SoundEvent> rows, String error, String label, String rawLabel, float topScore, float ms, float db, boolean activity) {
        publish(status, rows, Collections.emptyList(), error, label, rawLabel, topScore, ms, db, activity);
    }
    private void publish(GuardianRuntime.Status status, List<SoundEvent> rows, List<GuardianRuntime.ModelResult> modelResults, String error, String label, String rawLabel, float topScore, float ms, float db, boolean activity) {
        if (rows.stream().anyMatch(event -> event.active && event.priority() >= 3)) {
            if (conversation!=null) conversation.stop("PRIORITY_EVENT");
            CareCoordinator current = care;
            if (current != null) current.priorityEvents(rows);
        }
        if (GuardianRuntime.publish(token, new GuardianRuntime.Snapshot(status, rows, modelResults, error, label, rawLabel, topScore, ms, db, activity)))
            sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
    }
    private List<SoundEvent> records(EventEngine events) {
        List<SoundEvent> list = new ArrayList<>();
        for (EventEngine.Event event : events.history) list.add(new SoundEvent(event.rule.index + ":" + event.start, event.rule.label, event.rule.zh, event.start, event.end, event.active));
        return list;
    }
    private List<GuardianRuntime.ModelResult> modelResults(float[] scores, AppConfig config) {
        List<GuardianRuntime.ModelResult> result = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            if (!Float.isFinite(scores[i]) || scores[i] < .25f) continue;
            result.add(new GuardianRuntime.ModelResult(config.labels[i], config.name(config.labels[i]), scores[i]));
        }
        // 类别总数由共享模型契约限定；不再因名次截断已达到展示门槛的声音。
        result.sort((left, right) -> Float.compare(right.score, left.score));
        return result;
    }
    @Override public void onDestroy() {
        destroyed = true; restartRequested = false;
        if (conversation!=null) conversation.close();
        if (inputSessions!=null) inputSessions.close();
        if (care != null) care.close();
        if (localCare != null) localCare.close();
        if (careSchedules != null) careSchedules.close();
        if (currentSpeech != null) currentSpeech.onCancelled();
        running = false;
        main.removeCallbacksAndMessages(null);
        SpeechPlayback.detach(speechLease);
        if (speechOutput != null) speechOutput.close();
        if (understandingWorker != null) understandingWorker.close();
        if (GuardianRuntime.snapshot().status != GuardianRuntime.Status.ERROR) {
            long stopped = GuardianRuntime.begin();
            GuardianRuntime.publish(stopped, new GuardianRuntime.Snapshot(GuardianRuntime.Status.STOPPED, Collections.emptyList(), "", "", 0,-96,false));
            sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
        }
        synchronized (audioLock) { if (record != null) { try { record.stop(); } catch (IllegalStateException ignored) { } } }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_BACKGROUND) {
            if (conversation!=null) conversation.stop("LOW_MEMORY");
            UnderstandingWorker worker = understandingWorker;
            if (worker != null) worker.trimMemory();
            LocalSpeechOutput output = speechOutput;
            if (output != null) output.trimMemory();
        }
    }
}

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

/** 单一采集线程：模型、事件规则和未来唤醒词共享同一个 PCM 流。 */
public final class GuardianService extends Service {
    public static final String ACTION = "anlan.guardian.update";
    // 新旧 Service 实例共用串行队列，确保快速重启时旧录音完全释放后才创建新录音。
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor();
    private final Object audioLock = new Object();
    private AudioRecord record;
    private volatile boolean running;
    private long token;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            token = GuardianRuntime.begin(); running = true;
            try { startForeground(17, notification()); }
            catch (RuntimeException error) { running = false; publish(GuardianRuntime.Status.ERROR, Collections.emptyList(), error.toString(), "", 0, -96, false); stopSelf(); return START_NOT_STICKY; }
            publish(GuardianRuntime.Status.STARTING, Collections.emptyList(), "", "", 0, -96, false);
            AUDIO.execute(() -> loop(startId));
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationChannel channel = new NotificationChannel("anlan_listen", getString(com.anlan.guardian.R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, "anlan_listen").setContentTitle("安澜正在守护")
                .setContentText("本地声音识别 · 不保存录音").setSmallIcon(R.drawable.ic_guardian)
                .setContentIntent(open).setOngoing(true).build();
    }

    private void loop(int startedId) {
        YamnetClassifier model = null;
        EventEngine events = null;
        EventStore store = null;
        AudioRecord audio = null;
        PowerManager.WakeLock wake = null;
        boolean failed = false;
        try {
            if (!running) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("未授予麦克风权限");
            model = new YamnetClassifier(this);
            AppConfig config = new AppConfig(this); events = new EventEngine(config.rules);
            store = new EventStore(this); store.finishInterrupted();
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
            short[] readBuffer = new short[SoundContract.HOP_SAMPLES]; float[] input = new float[SoundContract.INPUT_SAMPLES]; int filled = 0;
            long lastSave = 0, renewAt = SystemClock.elapsedRealtime() + 8 * 60 * 1000L;
            while (running) {
                int count = audio.read(readBuffer, 0, readBuffer.length);
                if (!running) break;
                if (count <= 0) throw new IllegalStateException("麦克风读取失败：" + count);
                if (SystemClock.elapsedRealtime() >= renewAt) { wake.release(); wake.acquire(10 * 60 * 1000L); renewAt = SystemClock.elapsedRealtime() + 8 * 60 * 1000L; }
                for (int i = 0; i < count && running; i++) { input[filled++] = readBuffer[i] / 32768f;
                    if (filled == input.length) {
                        long start = SystemClock.elapsedRealtimeNanos();
                        float[] scores = model.classify(input);
                        float elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1000000f;
                        events.update(scores, System.currentTimeMillis());
                        List<SoundEvent> rows = records(events);
                        int top = 0; boolean activity = false;
                        for (int j = 0; j < scores.length; j++) {
                            if (scores[j] > scores[top]) top = j;
                            if ((config.labels[j].equals("Speech") || config.labels[j].equals("Music")) && scores[j] >= .35f) activity = true;
                        }
                        double energy = 0; for (float sample : input) energy += sample * sample;
                        float db = (float)Math.max(-96, 20 * Math.log10(Math.max(1e-9, Math.sqrt(energy / input.length))));
                        List<GuardianRuntime.ModelResult> modelResults = modelResults(scores, config);
                        publish(GuardianRuntime.Status.LISTENING, rows, modelResults, "", config.name(config.labels[top]), config.labels[top], scores[top], elapsed, db, activity);
                        if (SystemClock.elapsedRealtime() - lastSave > 2000) { store.save(rows); lastSave = SystemClock.elapsedRealtime(); }
                        System.arraycopy(input, SoundContract.HOP_SAMPLES, input, 0, SoundContract.OVERLAP_SAMPLES); filled = SoundContract.OVERLAP_SAMPLES;
                    }
                }
            }
        } catch (Exception error) {
            failed = running;
            if (failed) { android.util.Log.e("Anlan", "声音守护失败", error); publish(GuardianRuntime.Status.ERROR, Collections.emptyList(), error.toString(), "", 0, -96, false); }
        } finally {
            running = false;
            // 只有工作线程释放资源，主线程只请求停止，不会与推理 close 竞争。
            synchronized (audioLock) {
                if (audio != null) { try { audio.stop(); } catch (IllegalStateException ignored) { } audio.release(); }
                record = null;
            }
            if (model != null) model.close();
            if (wake != null && wake.isHeld()) wake.release();
            if (events != null) {
                events.stop(System.currentTimeMillis());
                if (store != null) { try { store.save(records(events)); } catch (RuntimeException error) { android.util.Log.e("Anlan", "事件存储失败", error); } }
            }
            if (store != null) store.close();
            if (!failed) publish(GuardianRuntime.Status.STOPPED, Collections.emptyList(), "", "", 0, -96, false);
            new Handler(Looper.getMainLooper()).post(() -> stopSelf(startedId));
        }
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
        if (GuardianRuntime.publish(token, new GuardianRuntime.Snapshot(status, rows, modelResults, error, label, rawLabel, topScore, ms, db, activity)))
            sendBroadcast(new Intent(ACTION).setPackage(getPackageName()));
    }
    private List<SoundEvent> records(EventEngine events) {
        List<SoundEvent> list = new ArrayList<>();
        for (EventEngine.Event event : events.history) list.add(new SoundEvent(event.rule.index + ":" + event.start, event.rule.label, event.rule.zh, event.start, event.end, event.active));
        return list;
    }
    private List<GuardianRuntime.ModelResult> modelResults(float[] scores, AppConfig config) {
        int[] best = {-1, -1, -1};
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] < .25f) continue;
            for (int slot = 0; slot < best.length; slot++) {
                if (best[slot] < 0 || scores[i] > scores[best[slot]]) {
                    for (int move = best.length - 1; move > slot; move--) best[move] = best[move - 1];
                    best[slot] = i; break;
                }
            }
        }
        List<GuardianRuntime.ModelResult> result = new ArrayList<>(3);
        for (int index : best) if (index >= 0) result.add(new GuardianRuntime.ModelResult(config.labels[index], config.name(config.labels[index]), scores[index]));
        return result;
    }
    @Override public void onDestroy() {
        running = false;
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
}

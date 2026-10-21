package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.SherpaWakeWord;
import com.soundguardian.core.YamnetClassifier;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 在 Android 上运行真实 native 引擎，使用本机合成语音，不代表远场实测准确率。 */
@RunWith(AndroidJUnit4.class)
public final class WakeWordDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();

    @Test public void keywordHandlesFasterSyntheticSpeechWithoutKnownFalseTriggers() throws Exception {
        try (SherpaWakeWord detector = new SherpaWakeWord(app)) {
            for (String name : new String[]{"positive", "positive-fast", "positive-faster", "negative", "negative-fast", "similar", "similar-fast", "old-keyword"}) {
                detector.reset(); boolean detected = feed(detector, name);
                android.os.Bundle result = new android.os.Bundle(); result.putString("wake_speed_case", name + " detected=" + detected);
                instrumentation.sendStatus(0, result);
                assertEquals("仅为合成近讲语料，不能代表真人快语速命中率：" + name, name.startsWith("positive"), detected);
            }
        }
    }

    @Test public void configuredKeywordDetectsXiaoTangAndRejectsOtherPhrases() throws Exception {
        try (SherpaWakeWord detector = new SherpaWakeWord(app)) {
            assertTrue("未检出合成的小糖", feed(detector, "positive"));
            detector.reset();
            assertFalse("普通说话误唤醒", feed(detector, "negative"));
            detector.reset();
            assertFalse("相近称呼误唤醒", feed(detector, "similar"));
            detector.reset();
            assertTrue("重置后应支持再次唤醒", feed(detector, "positive"));
        }
        try (SherpaWakeWord restarted = new SherpaWakeWord(app)) {
            assertTrue("重启后应支持再次唤醒", feed(restarted, "positive"));
        }
    }

    @Test public void firstKeywordAfterLongBackgroundComparedWithAfterMatchAndReset() throws Exception {
        boolean probe=Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("wake_state_probe","false"));
        Assume.assumeTrue("仅显式传入 wake_state_probe=true 时运行首次状态诊断",probe);
        instrumentation.runOnMainSync(()->app.startActivity(new android.content.Intent(app,MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)));
        SystemClock.sleep(1000);
        app.stopService(new android.content.Intent(app,GuardianService.class));
        int primingMs=Integer.parseInt(InstrumentationRegistry.getArguments().getString("wake_priming_ms","0"));
        int misses=0;
        try {
            for(String background:new String[]{"fresh","silence60s","speech60s"}) {
                for(String phrase:new String[]{"positive","positive-fast","positive-faster"}) {
                    try(SherpaWakeWord detector=new SherpaWakeWord(app)) {
                        if(primingMs>0)detector.accept(new float[primingMs*16]);
                        if(background.equals("silence60s"))for(int i=0;i<600;i++)assertFalse(detector.accept(new float[1600]));
                        if(background.equals("speech60s")) {
                            short[] negative=fixture("negative");int received=0;
                            while(received<60*16000){
                                for(int offset=16000;offset<negative.length-16000;offset+=1600){
                                    int count=Math.min(1600,negative.length-16000-offset);float[] block=new float[count];
                                    for(int i=0;i<count;i++)block[i]=negative[offset+i]/32768f;
                                    assertFalse("背景普通说话不能误唤醒",detector.accept(block));received+=count;
                                }
                            }
                        }
                        short[] pcm=fixture(phrase);
                        // 去除样本自带的前导一秒静音，覆盖说话紧接流起点或背景讲话的情况。
                        int first=feedOnset(detector,pcm),afterMatch=feedOnset(detector,pcm);
                        detector.reset();if(primingMs>0)detector.accept(new float[primingMs*16]);int afterReset=feedOnset(detector,pcm);
                        android.os.Bundle result=new android.os.Bundle();result.putString("wake_first_compare",background+"/"+phrase
                                +" priming_ms="+primingMs+" first_end_sample="+first+" after_attempt_end_sample="+afterMatch+" after_reset_end_sample="+afterReset);
                        instrumentation.sendStatus(0,result);
                        if(first<0)misses++;if(afterMatch<0)misses++;if(afterReset<0)misses++;
                    }
                }
            }
            assertEquals("三种背景、三种语速、三种流状态的漏检总数",0,misses);
        }finally{app.stopService(new android.content.Intent(app,GuardianService.class));}
    }

    private int feedOnset(SherpaWakeWord detector,short[] pcm) {
        int first=-1;
        for(int offset=16000;offset<pcm.length;offset+=1600){
            int count=Math.min(1600,pcm.length-offset);float[] block=new float[count];
            for(int i=0;i<count;i++)block[i]=pcm[offset+i]/32768f;
            if(detector.accept(block)&&first<0)first=offset+count-16000;
        }
        return first;
    }

    @Test public void wakeWorkerUsesSamePcmWhileYamnetContinuesAndCanRestart() throws Exception {
        short[] pcm = fixture("positive");
        for (int cycle = 0; cycle < 2; cycle++) {
            java.util.concurrent.atomic.AtomicInteger wakes = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> status = new java.util.concurrent.atomic.AtomicReference<>("");
            WakeWordWorker worker = new WakeWordWorker(app, status::set, at -> wakes.incrementAndGet());
            try (YamnetClassifier model = new YamnetClassifier(app)) {
                long deadline = SystemClock.elapsedRealtime() + 20000;
                while (status.get().isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                assertTrue(status.get(), status.get().startsWith("已就绪"));
                float[] window = new float[15600]; int filled = 0, inferences = 0;
                for (int offset = 0; offset < pcm.length; offset += 1600) {
                    int count = Math.min(1600, pcm.length - offset);
                    short[] chunk = java.util.Arrays.copyOfRange(pcm, offset, offset + count);
                    worker.acceptPcm(chunk, count, 16000);
                    for (short sample : chunk) {
                        window[filled++] = sample / 32768f;
                        if (filled == window.length) {
                            assertEquals(521, model.classify(window).length); inferences++;
                            System.arraycopy(window, 7680, window, 0, 7920); filled = 7920;
                        }
                    }
                    SystemClock.sleep(100);
                }
                deadline = SystemClock.elapsedRealtime() + 10000;
                while (wakes.get() == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
                assertTrue("YAMNet 未继续推理", inferences > 0);
                assertEquals("每段唤醒只应回应一次", 1, wakes.get());
            } finally { worker.close(); }
        }
    }

    @Test public void suppressedPlaybackAudioDoesNotWakeAndDetectionResumes() throws Exception {
        java.util.concurrent.atomic.AtomicInteger wakes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> status = new java.util.concurrent.atomic.AtomicReference<>("");
        WakeWordWorker worker = new WakeWordWorker(app, status::set, at -> wakes.incrementAndGet());
        try {
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (status.get().isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertTrue(status.get(), status.get().startsWith("已就绪"));
            short[] pcm = fixture("positive");
            worker.setSuppressed(true);
            feedWorker(worker, pcm);
            assertEquals("播报期间的声音不能再次唤醒", 0, wakes.get());
            worker.setSuppressed(false);
            feedWorker(worker, pcm);
            deadline = SystemClock.elapsedRealtime() + 10000;
            while (wakes.get() == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertEquals("解除抑制后应恢复唤醒", 1, wakes.get());
        } finally { worker.close(); }
    }

    private void feedWorker(WakeWordWorker worker, short[] pcm) {
        for (int offset = 0; offset < pcm.length; offset += 1600) {
            short[] chunk = java.util.Arrays.copyOfRange(pcm, offset, Math.min(offset + 1600, pcm.length));
            worker.acceptPcm(chunk, chunk.length, 16000); SystemClock.sleep(100);
        }
    }

    @Test public void wakeEventReachesFaceAndRestoresGuarding() throws Exception {
        AiSettings settings=new AiSettings(app);AiSettings.Profile previous=settings.load();
        settings.save(new AiSettings.Profile(false,previous.baseUrl,previous.token,previous.model,previous.disableThinking,previous.cue));
        for (String permission : new String[]{"android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS"}) {
            if (permission.endsWith("POST_NOTIFICATIONS") && android.os.Build.VERSION.SDK_INT < 33) continue;
            try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.getUiAutomation().executeShellCommand("pm grant com.anlan.guardian " + permission))) {
                while (in.read() != -1) { }
            }
        }
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new android.content.Intent(app, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (GuardianRuntime.snapshot().status != GuardianRuntime.Status.LISTENING && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(100);
            assertEquals(GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
            long at = SystemClock.elapsedRealtime();
            java.lang.reflect.Field tokenField = GuardianRuntime.class.getDeclaredField("generation");
            tokenField.setAccessible(true);
            assertTrue(GuardianRuntime.publishWake(tokenField.getLong(null), "已就绪 · 小糖", at));
            app.sendBroadcast(new android.content.Intent(GuardianService.ACTION).setPackage(app.getPackageName()));
            // 字幕跟随真实播报，不能只发布唤醒快照后假设已经播放。
            SpeechPlayback.speak(InteractionPorts.SpeechRequest.wake());
            awaitCaption(activity, "我在", 120000);
            SystemClock.sleep(1300);
            instrumentation.runOnMainSync(() -> {
                assertEquals("小糖 · 聆听", activity.findViewById(R.id.face).getContentDescription());
                // 倾听时不要求字幕可见；字幕已在上方按实际播放检查。
            });
            SystemClock.sleep(4500);
            instrumentation.runOnMainSync(() -> assertFalse("我在".contentEquals(
                    ((android.widget.TextView) activity.findViewById(R.id.emotion_name)).getText())));
            assertEquals(GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
        } finally {
            app.stopService(new android.content.Intent(app, GuardianService.class));
            instrumentation.runOnMainSync(activity::finish);
            settings.save(previous);
        }
    }

    private void awaitCaption(MainActivity activity, String expected, long timeout) {
        java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!found.get() && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync(() -> found.set(expected.contentEquals(
                    ((android.widget.TextView) activity.findViewById(R.id.emotion_name)).getText())));
            SystemClock.sleep(50);
        }
        assertTrue("唤醒提示未显示", found.get());
    }

    private boolean feed(SherpaWakeWord detector, String name) throws Exception {
        short[] pcm = fixture(name); boolean found = false;
        for (int offset = 0; offset < pcm.length; offset += 1600) {
            float[] samples = new float[Math.min(1600, pcm.length - offset)];
            for (int i = 0; i < samples.length; i++) samples[i] = pcm[offset + i] / 32768f;
            found |= detector.accept(samples);
        }
        return found;
    }

    private short[] fixture(String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = instrumentation.getContext().getAssets().open("wakeword-test/" + name + ".pcm")) {
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[buffer.remaining() / 2]; buffer.asShortBuffer().get(samples); return samples;
    }
}

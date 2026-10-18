package com.anlan.guardian;

import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.SherpaWakeWord;
import com.soundguardian.core.YamnetClassifier;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** 显式启用的合成 PCM 资源探针；不保存转写，不判定真人情绪准确率。 */
@RunWith(AndroidJUnit4.class)
public final class EmotionResourceDeviceTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private long start, previousWall, previousCpu, nextSample;
    private final AtomicInteger results = new AtomicInteger(), overloads = new AtomicInteger();
    private final Set<Long> seen = new HashSet<>();
    private final Map<String, Integer> emotions = new HashMap<>();
    private final ArrayList<short[]> fixtures = new ArrayList<>();
    private float[] window = new float[15600];
    private int filled, fixtureIndex, offset;
    private long guardWindows;

    private void report(String phase) throws Exception {
        long now = SystemClock.elapsedRealtime(), cpu = android.os.Process.getElapsedCpuTime();
        Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory);
        JSONObject row = new JSONObject().put("phase", phase).put("elapsed_ms", now-start)
                .put("pss_kib", memory.getTotalPss()).put("native_alloc_kib", Debug.getNativeHeapAllocatedSize()/1024)
                .put("java_used_kib", (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024)
                .put("cpu_one_core_percent", now==previousWall?0:(cpu-previousCpu)*100.0/(now-previousWall))
                .put("results", results.get()).put("overload_notifications", overloads.get()).put("guard_windows",guardWindows);
        synchronized (emotions) { row.put("emotions", new JSONObject(emotions)); }
        Bundle bundle = new Bundle(); bundle.putString("emotion_resource", row.toString()); instrumentation.sendStatus(0,bundle);
        previousWall=now; previousCpu=cpu;
    }

    private void accept(UnderstandingWorker worker, YamnetClassifier guard, SherpaWakeWord wake, short[] pcm) {
        worker.acceptPcm(pcm,pcm.length,16000);
        float[] input = new float[pcm.length];
        for(int i=0;i<pcm.length;i++)input[i]=pcm[i]/32768f;
        wake.accept(input);
        for(float sample:input){
            window[filled++]=sample;
            if(filled==window.length){
                assertEquals(521,guard.classify(window).length);guardWindows++;
                System.arraycopy(window,7680,window,0,7920);filled=7920;
            }
        }
    }

    private void feed(long duration, boolean silence, UnderstandingWorker worker, YamnetClassifier guard, SherpaWakeWord wake) throws Exception {
        long until=SystemClock.elapsedRealtime()+duration, tick=SystemClock.elapsedRealtime();
        while(SystemClock.elapsedRealtime()<until){
            short[] pcm=new short[1600];
            if(!silence){
                short[] source=fixtures.get(fixtureIndex);
                int count=Math.min(pcm.length,source.length-offset);
                System.arraycopy(source,offset,pcm,0,count);offset+=count;
                if(offset==source.length){offset=0;fixtureIndex=(fixtureIndex+1)%fixtures.size();}
            }
            accept(worker,guard,wake,pcm);
            long now=SystemClock.elapsedRealtime();
            if(now>=nextSample){report(silence?"idle_silence":"continuous_recognition");nextSample=now+5000;}
            tick+=100;SystemClock.sleep(Math.max(0,tick-SystemClock.elapsedRealtime()));
        }
    }

    @Test public void continuousEmotionResourceProbe() throws Exception {
        Bundle args=InstrumentationRegistry.getArguments();
        assumeTrue("资源探针须显式启用", "true".equals(args.getString("emotion_resource")));
        int seconds=Integer.parseInt(args.getString("duration_seconds","600"));
        assertTrue(seconds>=60&&seconds<=3600);
        File directory=new File(args.getString("fixture_directory"));
        File[] files=directory.listFiles((dir,name)->name.endsWith(".pcm"));
        assertNotNull("缺少合成样本目录",files);assertTrue(files.length>=6);
        java.util.Arrays.sort(files);
        for(File file:files){
            byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());
            ByteBuffer data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            short[] samples=new short[bytes.length/2];for(int i=0;i<samples.length;i++)samples[i]=data.getShort();
            fixtures.add(samples);
        }
        android.content.Context target=instrumentation.getTargetContext();
        target.stopService(new android.content.Intent(target,GuardianService.class));SystemClock.sleep(1500);
        try(CareAcceptanceContext app=new CareAcceptanceContext(target)){
        PerformanceSettings.save(app,false);
        start=previousWall=SystemClock.elapsedRealtime();previousCpu=android.os.Process.getElapsedCpuTime();report("process_baseline");
        try(YamnetClassifier guard=new YamnetClassifier(app);SherpaWakeWord wake=new SherpaWakeWord(app)){
            report("guard_wake_loaded");
            try(UnderstandingWorker worker=new UnderstandingWorker(app,true,state->{
                if(state.status.contains("过载")||state.status.contains("跳过"))overloads.incrementAndGet();
                if(state.result!=null){synchronized(emotions){if(seen.add(state.result.id)){
                    results.incrementAndGet();String label=state.result.emotion;
                    emotions.put(label,emotions.getOrDefault(label,0)+1);
                }}}
            })){
                SystemClock.sleep(2000);report("vad_ready");
                CountDownLatch ready=new CountDownLatch(1);AtomicReference<String> failure=new AtomicReference<>("");
                worker.prepare(ready::countDown,error->{failure.set(error);ready.countDown();});
                assertTrue(ready.await(60,TimeUnit.SECONDS));assertEquals("",failure.get());report("sensevoice_loaded");
                nextSample=SystemClock.elapsedRealtime()+5000;
                feed(seconds*1000L,false,worker,guard,wake);report("recognition_finished");
                assertTrue("未产生足够真实识别结果",results.get()>=seconds/15);
                feed(65000,true,worker,guard,wake);report("idle_65s");
                java.lang.reflect.Field loaded=UnderstandingWorker.class.getDeclaredField("loaded");loaded.setAccessible(true);
                assertFalse("静音后应按现有空闲策略释放SenseVoice",loaded.getBoolean(worker));
            }
            SystemClock.sleep(3000);report("understanding_closed");
        }
        SystemClock.sleep(3000);report("all_models_closed");
        }
    }
}

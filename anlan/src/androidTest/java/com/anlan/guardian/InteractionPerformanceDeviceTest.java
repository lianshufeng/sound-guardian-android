package com.anlan.guardian;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 正式本地引擎与数字PCM；不冒充物理麦克风、真人可听或真实网络验收。 */
@RunWith(AndroidJUnit4.class)
public final class InteractionPerformanceDeviceTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final Handler main=new Handler(Looper.getMainLooper());
    private void sync(Runnable action){instrumentation.runOnMainSync(action);}
    private void await(BooleanSupplier condition,long timeout){long until=SystemClock.elapsedRealtime()+timeout;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);assertTrue("等待交互条件超时",condition.getAsBoolean());}
    private MainActivity foreground()throws Exception{
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new android.content.Intent(app,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        app.stopService(new android.content.Intent(app,GuardianService.class));await(()->GuardianRuntime.snapshot().status==GuardianRuntime.Status.STOPPED,10000);return activity;
    }
    private boolean cached(LocalSpeechOutput output,String text)throws Exception{
        java.lang.reflect.Field field=LocalSpeechOutput.class.getDeclaredField("cache");field.setAccessible(true);
        java.lang.reflect.Method key=LocalSpeechOutput.class.getDeclaredMethod("key",String.class);key.setAccessible(true);
        return ((SpeechAudioCache<?>)field.get(output)).get((String)key.invoke(output,text))!=null;
    }
    @Test public void cachedSpeechAndCueBypassBusyNativeWorkerAndCancelFiftyTimes()throws Exception{
        MainActivity activity=foreground();CountDownLatch release=new CountDownLatch(1),blocked=new CountDownLatch(1);
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app)){
            LocalSpeechOutput output=new LocalSpeechOutput(isolated,ignored->{});
            try{
                await(()->{try{return cached(output,"我在")&&cached(output,BehaviorEngine.SNAP_MESSAGE);}catch(Exception error){throw new IllegalStateException(error);}},120000);
                java.lang.reflect.Field field=LocalSpeechOutput.class.getDeclaredField("WORKER");field.setAccessible(true);
                ((java.util.concurrent.Executor)field.get(null)).execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});
                assertTrue(blocked.await(10,TimeUnit.SECONDS));List<Long> timings=new ArrayList<>(),stops=new ArrayList<>();
                for(int i=0;i<50;i++){
                    CountDownLatch started=new CountDownLatch(1),cancelled=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>("");long begin=System.nanoTime();
                    InteractionPorts.SpeechRequest request=i%2==0?InteractionPorts.SpeechRequest.fingerSnaps("cache-test"):new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.CUE,"提示音","");
                    sync(()->output.speak(request,new InteractionPorts.SpeechOutput.Progress(){
                        public void onStart(){timings.add((System.nanoTime()-begin)/1000000);started.countDown();}
                        public void onComplete(){cancelled.countDown();}
                        public void onCancelled(){cancelled.countDown();}
                        public void onError(String reason){error.set(reason);started.countDown();cancelled.countDown();}
                    }));
                    assertTrue("缓存与提示音不能排在native任务后",started.await(2,TimeUnit.SECONDS));assertEquals("",error.get());
                    long stopping=System.nanoTime();sync(output::stop);stops.add((System.nanoTime()-stopping)/1000000);
                    assertTrue(cancelled.await(2,TimeUnit.SECONDS));assertEquals("",error.get());
                }
                assertEquals(50,timings.size());java.util.Collections.sort(timings);java.util.Collections.sort(stops);
                android.os.Bundle metrics=new android.os.Bundle();metrics.putLong("cache_cue_start_median_ms",timings.get(24));metrics.putLong("cache_cue_start_p95_ms",timings.get(47));metrics.putLong("cache_cue_start_max_ms",timings.get(49));metrics.putLong("cancel_call_p95_ms",stops.get(47));instrumentation.sendStatus(0,metrics);
                java.lang.reflect.Field cacheField=LocalSpeechOutput.class.getDeclaredField("cache"),epochField=LocalSpeechOutput.class.getDeclaredField("resourceEpoch");cacheField.setAccessible(true);epochField.setAccessible(true);
                java.lang.reflect.Method key=LocalSpeechOutput.class.getDeclaredMethod("key",String.class);key.setAccessible(true);
                Object old=((SpeechAudioCache<?>)cacheField.get(output)).get((String)key.invoke(output,BehaviorEngine.SNAP_MESSAGE));long oldEpoch=epochField.getLong(output);
                sync(output::trimMemory);
                java.lang.reflect.Method remember=LocalSpeechOutput.class.getDeclaredMethod("remember",String.class,old.getClass(),long.class);remember.setAccessible(true);remember.invoke(output,BehaviorEngine.SNAP_MESSAGE,old,oldEpoch);
                assertFalse("回收后旧合成不得重新填入缓存",cached(output,BehaviorEngine.SNAP_MESSAGE));assertTrue(cached(output,"我在"));
                CountDownLatch playing=new CountDownLatch(1),trimmed=new CountDownLatch(1);
                sync(()->output.speak(InteractionPorts.SpeechRequest.wake(),new InteractionPorts.SpeechOutput.Progress(){public void onStart(){playing.countDown();}public void onComplete(){}public void onCancelled(){trimmed.countDown();}public void onError(String reason){throw new AssertionError(reason);}}));
                assertTrue(playing.await(2,TimeUnit.SECONDS));sync(output::trimMemory);assertTrue("内存回收必须明确取消普通播报",trimmed.await(2,TimeUnit.SECONDS));
            }finally{release.countDown();sync(output::close);}
        }finally{release.countDown();sync(activity::finish);}
    }
    private void feed(UnderstandingWorker worker,String name)throws Exception{
        try(java.io.InputStream input=instrumentation.getContext().getAssets().open("dialogue-test/"+name+".pcm");java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream()){
            byte[] block=new byte[4096];int count;while((count=input.read(block))!=-1)data.write(block,0,count);
            ByteBuffer bytes=ByteBuffer.wrap(data.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            while(bytes.remaining()>0){int n=Math.min(1600,bytes.remaining()/2);short[] pcm=new short[n];for(int i=0;i<n;i++)pcm[i]=bytes.getShort();worker.acceptPcm(pcm,n,16000);SystemClock.sleep(100);}
        }
    }
    @Test public void dynamicSpeechMeasuresTwentyColdAndFiftyHotRequests()throws Exception{
        MainActivity activity=foreground();
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app);LocalSpeechOutput output=new LocalSpeechOutput(isolated,ignored->{})){
            await(()->{try{return cached(output,BehaviorEngine.SNAP_MESSAGE);}catch(Exception error){throw new IllegalStateException(error);}},120000);
            InteractionTrace.clear();List<Long> cold=new ArrayList<>(),hot=new ArrayList<>();
            for(int i=0;i<70;i++){
                if(i<20)sync(output::trimMemory);
                CountDownLatch started=new CountDownLatch(1),terminal=new CountDownLatch(1);AtomicReference<String> failure=new AtomicReference<>("");
                long begin=System.nanoTime();
                sync(()->output.speak("你好，今天也很高兴见到你。",new InteractionPorts.SpeechOutput.Progress(){
                    public void onStart(){started.countDown();}public void onComplete(){terminal.countDown();}
                    public void onCancelled(){terminal.countDown();}public void onError(String error){failure.set(error);started.countDown();terminal.countDown();}
                }));
                assertTrue(started.await(60,TimeUnit.SECONDS));assertEquals("",failure.get());
                (i<20?cold:hot).add((System.nanoTime()-begin)/1000000);sync(output::stop);assertTrue(terminal.await(2,TimeUnit.SECONDS));
                if((i+1)%10==0){android.os.Bundle progress=new android.os.Bundle();progress.putInt("dynamic_requests_completed",i+1);instrumentation.sendStatus(0,progress);}
            }
            List<Double> rtf=new ArrayList<>();int loads=0;
            for(InteractionTrace.Sample sample:InteractionTrace.snapshot()){
                if(sample.stage==InteractionTrace.Stage.TTS_LOAD)loads++;
                if(sample.stage==InteractionTrace.Stage.TTS_SYNTHESIS&&sample.amount>0)rtf.add((double)sample.durationNanos/sample.amount);
            }
            assertEquals(20,loads);assertEquals(70,rtf.size());List<Double> hotRtf=new ArrayList<>(rtf.subList(20,70));
            java.util.Collections.sort(cold);java.util.Collections.sort(hot);java.util.Collections.sort(hotRtf);
            android.os.Bundle metrics=new android.os.Bundle();metrics.putLong("dynamic_cold_start_median_ms",cold.get(9));metrics.putLong("dynamic_cold_start_p95_ms",cold.get(18));metrics.putLong("dynamic_cold_start_max_ms",cold.get(19));metrics.putLong("dynamic_hot_start_median_ms",hot.get(24));metrics.putLong("dynamic_hot_start_p95_ms",hot.get(47));metrics.putLong("dynamic_hot_start_max_ms",hot.get(49));metrics.putDouble("dynamic_hot_rtf_median",hotRtf.get(24));metrics.putDouble("dynamic_hot_rtf_p95",hotRtf.get(47));metrics.putDouble("dynamic_hot_rtf_max",hotRtf.get(49));instrumentation.sendStatus(0,metrics);
        }finally{sync(activity::finish);}
    }
    @Test public void slowDatabaseDoesNotBlockSubmissionAndTerminalWins()throws Exception{
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app);EventStore blocker=new EventStore(isolated)){
            android.database.sqlite.SQLiteDatabase db=blocker.getWritableDatabase();db.beginTransaction();
            EventHistoryWriter writer=new EventHistoryWriter(isolated);
            try{
                long begin=SystemClock.elapsedRealtime();
                for(int i=0;i<200;i++)assertTrue(writer.submit(java.util.Collections.singletonList(new SoundEvent("slow-store","Finger snapping","响指",1,i+1,true))));
                assertTrue("写库阻塞不能传递给投递线程",SystemClock.elapsedRealtime()-begin<500);
                assertTrue(writer.submit(java.util.Collections.singletonList(new SoundEvent("slow-store","Finger snapping","响指",1,201,false))));
                assertTrue(writer.submit(java.util.Collections.singletonList(new SoundEvent("slow-store","Finger snapping","响指",1,201,true))));
            }finally{db.endTransaction();writer.close();}
            assertEquals(1,blocker.recent().size());assertFalse(blocker.recent().get(0).active);assertEquals(201,blocker.recent().get(0).end);
        }
    }
    @Test public void preparingCoalescesOwnersAndMemoryTrimCannotFailNewOwner()throws Exception{
        MainActivity activity=foreground();CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),ready=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();AtomicReference<String> failure=new AtomicReference<>("");
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app);UnderstandingWorker worker=new UnderstandingWorker(isolated,false,ignored->{})){
            java.lang.reflect.Field field=UnderstandingWorker.class.getDeclaredField("MODEL");field.setAccessible(true);
            ((java.util.concurrent.Executor)field.get(null)).execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            assertTrue(blocked.await(10,TimeUnit.SECONDS));
            sync(()->{
                for(int i=0;i<100;i++)worker.prepare(()->callbacks.incrementAndGet(),reason->{failure.set(reason);ready.countDown();});
                worker.trimMemory();
                worker.prepare(()->{callbacks.incrementAndGet();ready.countDown();},reason->{failure.set(reason);ready.countDown();});
                worker.prewarm();
            });
            release.countDown();assertTrue(ready.await(30,TimeUnit.SECONDS));assertEquals("",failure.get());assertEquals(1,callbacks.get());
        }finally{release.countDown();sync(activity::finish);}
    }
    @Test public void unifiedInputUsesActualPcmAndTtsThenExitsWithoutCloudRequest()throws Exception{
        MainActivity activity=foreground();
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app)){
            new AiSettings(isolated).save(new AiSettings.Profile(true,"https://example.invalid/v1","","local-only-test",true,true));
            AtomicReference<UnderstandingWorker> worker=new AtomicReference<>();AtomicReference<LocalSpeechOutput> output=new AtomicReference<>();
            AtomicReference<InputSessionController> input=new AtomicReference<>();AtomicReference<ConversationCoordinator> conversation=new AtomicReference<>();
            List<String> played=new java.util.concurrent.CopyOnWriteArrayList<>();long[] lease={0},audioEpoch={0};
            sync(()->{
                worker.set(new UnderstandingWorker(isolated,false,ignored->{}));output.set(new LocalSpeechOutput(isolated,ignored->{}));
                lease[0]=SpeechPlayback.attach(request->{
                    long epoch=++audioEpoch[0];worker.get().setSuppressed(true);
                    output.get().speak(request,new InteractionPorts.SpeechOutput.Progress(){
                        public void onStart(){if(request.source!=InteractionPorts.SpeechRequest.Source.CUE)played.add(request.text);}
                        public void onComplete(){main.postDelayed(()->{if(audioEpoch[0]==epoch)worker.get().setSuppressed(false);},500);if(request.observer!=null)request.observer.onComplete();}
                        public void onCancelled(){if(request.observer!=null)request.observer.onCancelled();}
                        public void onError(String error){if(request.observer!=null)request.observer.onError(error);}
                    });
                });
                input.set(new InputSessionController(new InputSessionController.Host(){
                    public UnderstandingWorker worker(){return worker.get();}public boolean available(){return true;}public boolean cueEnabled(){return true;}
                    public void cue(Runnable done,java.util.function.Consumer<String> error){SpeechPlayback.speak(new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.CUE,"提示音","","",new InteractionPorts.SpeechOutput.Progress(){public void onComplete(){done.run();}public void onCancelled(){error.accept("CANCELLED");}public void onError(String reason){error.accept(reason);}}));}
                },new DialogueConfig(isolated).tailMs));
                conversation.set(new ConversationCoordinator(isolated,new ConversationCoordinator.Host(){
                    public UnderstandingWorker input(){return worker.get();}public boolean available(){return true;}
                    public void startInput(InteractionPorts.SpeechInput.Result result,long timeout){input.get().start(result,timeout);}public void stopInput(){input.get().cancel();}
                    public void speak(String text,Runnable done,java.util.function.Consumer<String> error){SpeechPlayback.speak(new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.DIALOGUE,text,"","",new InteractionPorts.SpeechOutput.Progress(){public void onComplete(){done.run();}public void onCancelled(){error.accept("CANCELLED");}public void onError(String reason){error.accept(reason);}}));}
                    public void stopSpeech(){output.get().stop();}
                }));conversation.get().startAfterWake();
            });
            try{
                await(()->GuardianRuntime.inputPhase().equals("LISTENING"),120000);feed(worker.get(),"time-normal");
                await(()->!played.isEmpty(),120000);assertTrue("时间问题应在本地回答",played.get(0).startsWith("现在是"));
                await(()->GuardianRuntime.inputPhase().equals("LISTENING"),120000);feed(worker.get(),"stop-normal");await(()->!Conversation.active(),60000);
                assertEquals("IDLE",GuardianRuntime.inputPhase());
                android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);android.os.Bundle metrics=new android.os.Bundle();metrics.putInt("local_pcm_tts_pss_kib",memory.getTotalPss());instrumentation.sendStatus(0,metrics);
            }finally{sync(()->{conversation.get().close();input.get().close();worker.get().close();output.get().close();SpeechPlayback.detach(lease[0]);});}
        }finally{sync(activity::finish);}
    }
}

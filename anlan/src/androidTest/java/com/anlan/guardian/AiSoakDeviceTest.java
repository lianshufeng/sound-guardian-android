package com.anlan.guardian;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 需显式 -e soak_minutes 30；只记录资源/阶段，不保存凭据与用户原文。 */
@RunWith(AndroidJUnit4.class)
public final class AiSoakDeviceTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private void main(Runnable action){instrumentation.runOnMainSync(action);}
    private boolean waitFor(BooleanSupplier condition,long ms){long until=SystemClock.elapsedRealtime()+ms;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(50);return condition.getAsBoolean();}
    @Test public void thirtyMinuteGuardDialogueTimeoutAndCancellationSoak()throws Exception{
        int minutes=Integer.parseInt(InstrumentationRegistry.getArguments().getString("soak_minutes","0"));org.junit.Assume.assumeTrue(minutes>=30);
        File path=new File(app.getCacheDir(),"ai-runtime-profile.json");org.junit.Assume.assumeTrue(path.isFile());JSONObject profile=new JSONObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));
        AiSettings settings=new AiSettings(app);AiSettings.Profile previous=settings.load();settings.save(new AiSettings.Profile(true,profile.getString("url"),profile.getString("token"),profile.getString("model"),true,true));
        app.stopService(new android.content.Intent(app,GuardianService.class));SystemClock.sleep(1000);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new android.content.Intent(app,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        File result=new File(app.getCacheDir(),"ai-soak.jsonl");int rounds=0;long start=SystemClock.elapsedRealtime();long nextCycle=start,nextSample=start;
        long lastCpu=android.os.Process.getElapsedCpuTime(),lastWall=start;
        try(java.io.Writer log=new java.io.OutputStreamWriter(new java.io.FileOutputStream(result),StandardCharsets.UTF_8)){
            assertTrue(waitFor(()->GuardianRuntime.snapshot().status==GuardianRuntime.Status.LISTENING&&GuardianRuntime.speechStatus().startsWith("已就绪"),120000));
            start=SystemClock.elapsedRealtime();nextCycle=start;
            while(SystemClock.elapsedRealtime()-start<minutes*60000L){
                long now=SystemClock.elapsedRealtime();assertEquals(GuardianRuntime.Status.LISTENING,GuardianRuntime.snapshot().status);
                if(now>=nextCycle){
                    main(Conversation::start);assertTrue(waitFor(()->GuardianRuntime.inputPhase().equals("LISTENING"),40000));
                    if(rounds%3==0){
                        java.lang.reflect.Field endpoint=Conversation.class.getDeclaredField("endpoint");endpoint.setAccessible(true);ConversationCoordinator coordinator=(ConversationCoordinator)endpoint.get(null);
                        java.lang.reflect.Method handle=ConversationCoordinator.class.getDeclaredMethod("handle",String.class);handle.setAccessible(true);
                        main(()->{try{handle.invoke(coordinator,"今天整理好房间了，请用一句简短的话回应我。");}catch(Exception error){throw new IllegalStateException(error);}});
                        assertTrue(waitFor(()->GuardianRuntime.speechSnapshot().phase==GuardianRuntime.SpeechPhase.PLAYING,60000));
                        assertTrue(waitFor(()->GuardianRuntime.inputPhase().equals("LISTENING"),90000));
                        Conversation.cancel(GuardianRuntime.conversationId());
                    }else if(rounds%3==1){
                        // 模拟器仍连接真实环境输入；有VAD活动时不能把它当作20秒纯静默。
                        if(!waitFor(()->!Conversation.active(),65000)){
                            android.os.Bundle ambient=new android.os.Bundle();ambient.putString("soak_environment","background_input_during_idle_window");instrumentation.sendStatus(0,ambient);
                            Conversation.cancel(GuardianRuntime.conversationId());assertTrue(waitFor(()->!Conversation.active(),5000));
                        }
                    }
                    else{Conversation.cancel(GuardianRuntime.conversationId());assertTrue(waitFor(()->!Conversation.active(),5000));}
                    rounds++;nextCycle=SystemClock.elapsedRealtime()+30000;
                }
                now=SystemClock.elapsedRealtime();
                if(now>=nextSample){
                    android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);long cpu=android.os.Process.getElapsedCpuTime();
                    JSONObject row=new JSONObject().put("elapsed_ms",now-start).put("pss_kib",memory.getTotalPss()).put("native_alloc_kib",android.os.Debug.getNativeHeapAllocatedSize()/1024).put("threads",new File("/proc/self/task").list().length).put("cpu_one_core_percent",100.0*(cpu-lastCpu)/Math.max(1,now-lastWall)).put("phase",GuardianRuntime.conversationState()).put("rounds",rounds);
                    log.write(row.toString()+"\n");log.flush();lastCpu=cpu;lastWall=now;nextSample=now+10000;
                    android.os.Bundle progress=new android.os.Bundle();progress.putString("soak_progress","elapsed_ms="+(now-start)+" rounds="+rounds+" pss_kib="+memory.getTotalPss());instrumentation.sendStatus(0,progress);
                }
                SystemClock.sleep(100);
            }
            assertTrue(rounds>=20);
        }finally{try{Conversation.cancel(GuardianRuntime.conversationId());}catch(RuntimeException ignored){}settings.save(previous);main(activity::finish);app.stopService(new android.content.Intent(app,GuardianService.class));}
    }
}

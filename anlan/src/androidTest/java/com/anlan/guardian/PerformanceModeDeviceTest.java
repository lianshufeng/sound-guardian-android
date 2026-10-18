package com.anlan.guardian;

import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 两种驻留策略均使用真实识别、合成和播放；不更改用户偏好。 */
@RunWith(AndroidJUnit4.class)
public final class PerformanceModeDeviceTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final android.content.Context target=instrumentation.getTargetContext();
    private void sync(Runnable action){instrumentation.runOnMainSync(action);}
    private void await(BooleanSupplier condition,long timeout){
        long end=SystemClock.elapsedRealtime()+timeout;
        while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(50);
        assertTrue("等待模型状态超时",condition.getAsBoolean());
    }
    private Object field(Object object,String name)throws Exception{
        java.lang.reflect.Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private boolean loaded(Object object,String name){try{return field(object,name)!=null;}catch(Exception error){throw new AssertionError(error);}}
    private void barrier(Object object,String name)throws Exception{
        CountDownLatch done=new CountDownLatch(1);
        ((java.util.concurrent.Executor)field(object,name)).execute(done::countDown);
        assertTrue(done.await(120,TimeUnit.SECONDS));
    }
    private void prepare(UnderstandingWorker input)throws Exception{
        CountDownLatch ready=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>("");
        input.prepare(ready::countDown,reason->{error.set(reason);ready.countDown();});
        assertTrue(ready.await(60,TimeUnit.SECONDS));assertEquals("",error.get());
    }
    private void recognize(UnderstandingWorker input)throws Exception{
        CountDownLatch result=new CountDownLatch(1);AtomicReference<String> failure=new AtomicReference<>("");
        sync(()->input.start(new InteractionPorts.SpeechInput.Result(){
            public void onText(String text){if(!text.isEmpty())result.countDown();}
            public void onUnderstanding(com.soundguardian.core.VoiceUnderstandingResult value){
                if(value.emotion.isEmpty()||value.text.isEmpty())failure.set("EMPTY_RESULT");result.countDown();
            }
            public void onError(String reason){failure.set(reason);result.countDown();}
        },30000));
        byte[] bytes;
        try(java.io.InputStream in=instrumentation.getContext().getAssets().open("understanding-test/zh.pcm");
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] block=new byte[8192];int count;while((count=in.read(block))!=-1)out.write(block,0,count);bytes=out.toByteArray();
        }
        java.nio.ByteBuffer data=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while(data.hasRemaining()){
            short[] pcm=new short[Math.min(1600,data.remaining()/2)];for(int i=0;i<pcm.length;i++)pcm[i]=data.getShort();
            input.acceptPcm(pcm,pcm.length,16000);SystemClock.sleep(100);
        }
        for(int i=0;i<12;i++){input.acceptPcm(new short[1600],1600,16000);SystemClock.sleep(100);}
        assertTrue(result.await(30,TimeUnit.SECONDS));assertEquals("",failure.get());sync(input::cancel);
    }
    private void speak(LocalSpeechOutput output,boolean stream)throws Exception{
        CountDownLatch complete=new CountDownLatch(1);AtomicReference<String> failure=new AtomicReference<>("");
        InteractionPorts.SpeechOutput.Progress progress=new InteractionPorts.SpeechOutput.Progress(){
            public void onComplete(){complete.countDown();}
            public void onError(String reason){failure.set(reason);complete.countDown();}
        };
        if(stream){
            InteractionPorts.SpeechStream handle=output.openStream(InteractionPorts.SpeechRequest.Source.DIALOGUE,"",progress);
            assertTrue(handle.append("你好，我会安静地陪着你。"));handle.end();
        }else sync(()->output.speak("你好，今天也很高兴见到你。",progress));
        assertTrue("播报未完成",complete.await(60,TimeUnit.SECONDS));assertEquals("",failure.get());
    }
    private void report(String phase,boolean high)throws Exception{
        Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);
        Bundle status=new Bundle();status.putString("performance_mode",high?"high":"low_power");
        status.putString("phase",phase);status.putInt("pss_kib",memory.getTotalPss());instrumentation.sendStatus(0,status);
    }
    private android.view.View findText(android.view.View view,String value){
        if(view instanceof android.widget.TextView&&((android.widget.TextView)view).getText().toString().equals(value))return view;
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){android.view.View found=findText(group.getChildAt(i),value);if(found!=null)return found;}}
        return null;
    }
    private void showSettings(MainActivity activity){
        try{
            java.lang.reflect.Method method=ControlPanel.class.getDeclaredMethod("settings");method.setAccessible(true);
            method.invoke(new ControlPanel(activity,ignored->{},ignored->{}));
        }catch(Exception error){throw new AssertionError(error);}
    }
    @Test public void settingsUiSavesBothModesWithoutEnablingCompanion()throws Exception{
        android.content.SharedPreferences prefs=target.getSharedPreferences("interaction",0);
        String previous=prefs.getString("performance_mode",null);
        boolean companion=prefs.getBoolean("companion_listening",false);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new android.content.Intent(target,MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            target.stopService(new android.content.Intent(target,GuardianService.class));
            await(()->GuardianRuntime.snapshot().status==GuardianRuntime.Status.STOPPED,15000);
            prefs.edit().remove("performance_mode").commit();assertTrue(PerformanceSettings.highPerformance(target));
            sync(()->{
                showSettings(activity);android.view.View root=activity.getWindow().getDecorView();
                assertTrue(((android.widget.RadioButton)findText(root,"高性能")).isChecked());
                findText(root,"节省内存").performClick();assertFalse(PerformanceSettings.highPerformance(target));
                showSettings(activity);assertTrue(((android.widget.RadioButton)findText(root,"节省内存")).isChecked());
                findText(root,"高性能").performClick();assertTrue(PerformanceSettings.highPerformance(target));
                showSettings(activity);assertTrue(((android.widget.RadioButton)findText(root,"高性能")).isChecked());
                assertEquals(companion,prefs.getBoolean("companion_listening",false));
            });
        }finally{
            target.stopService(new android.content.Intent(target,GuardianService.class));sync(activity::finish);
            android.content.SharedPreferences.Editor edit=prefs.edit();
            if(previous==null)edit.remove("performance_mode");else edit.putString("performance_mode",previous);edit.commit();
        }
    }
    @Test public void selectedModeRecognizesSpeaksRetainsAndRecovers()throws Exception{
        boolean high=!"low_power".equals(InstrumentationRegistry.getArguments().getString("performance_mode","high"));
        target.stopService(new android.content.Intent(target,GuardianService.class));SystemClock.sleep(1500);
        try(CareAcceptanceContext app=new CareAcceptanceContext(target)){
            assertTrue(PerformanceSettings.highPerformance(app));PerformanceSettings.save(app,high);
            assertEquals(high,PerformanceSettings.highPerformance(app));
            assertFalse(app.getSharedPreferences("interaction",0).getBoolean("companion_listening",false));
            report("baseline",high);AtomicReference<String> status=new AtomicReference<>("");
            try(LocalSpeechOutput output=new LocalSpeechOutput(app,status::set);
                UnderstandingWorker input=new UnderstandingWorker(app,false,ignored->{})){
                barrier(output,"WORKER");sync(()->{});assertTrue(status.get(),status.get().startsWith("已就绪"));
                assertEquals("启动预热后的驻留策略",high,loaded(output,"synthesizer"));
                assertFalse("未准入倾听时不提前加载识别模型",loaded(input,"recognizer"));
                prepare(input);recognize(input);speak(output,false);speak(output,true);report("both_models_used",high);
                long idle=ResourceBudget.read(app).modelIdleMs;
                SystemClock.sleep(idle+2500);barrier(output,"WORKER");barrier(input,"MODEL");
                assertEquals("TTS空闲驻留",high,loaded(output,"synthesizer"));
                assertEquals("识别空闲驻留",high,loaded(input,"recognizer"));report("after_idle",high);
                sync(()->{output.trimMemory();input.trimMemory();});barrier(output,"WORKER");barrier(input,"MODEL");
                assertFalse(loaded(output,"synthesizer"));assertFalse(loaded(input,"recognizer"));report("trimmed",high);
                prepare(input);recognize(input);speak(output,false);report("recovered",high);
                PerformanceSettings.save(app,!high);
                sync(()->{output.performanceModeChanged();input.performanceModeChanged();});
                prepare(input);barrier(output,"WORKER");
                SystemClock.sleep(idle+2500);barrier(output,"WORKER");barrier(input,"MODEL");
                assertEquals("切换后TTS驻留",!high,loaded(output,"synthesizer"));
                assertEquals("切换后识别驻留",!high,loaded(input,"recognizer"));report("switched",!high);
                prepare(input);recognize(input);speak(output,true);
                sync(()->{input.close();output.close();});barrier(output,"WORKER");barrier(input,"MODEL");
                assertFalse(loaded(output,"synthesizer"));assertFalse(loaded(input,"recognizer"));report("closed",!high);
            }
        }
    }
    @Test public void serviceModeSwitchKeepsGuardAndSpeechWorking()throws Exception{
        android.content.SharedPreferences prefs=target.getSharedPreferences("interaction",0);
        String previous=prefs.getString("performance_mode",null);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new android.content.Intent(target,MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            await(()->GuardianRuntime.snapshot().status==GuardianRuntime.Status.LISTENING&&GuardianRuntime.speechStatus().startsWith("已就绪"),120000);
            java.lang.reflect.Field generation=GuardianRuntime.class.getDeclaredField("generation");generation.setAccessible(true);
            long session=generation.getLong(null);
            for(boolean high:new boolean[]{false,true}){
                sync(()->{
                    showSettings(activity);
                    findText(activity.getWindow().getDecorView(),high?"高性能":"节省内存").performClick();activity.closeDrawer();
                });
                assertEquals(high,PerformanceSettings.highPerformance(target));SystemClock.sleep(300);
                long before=GuardianRuntime.speechSnapshot().id;
                sync(()->SpeechPlayback.speak("模式切换后，我会继续陪伴你。"));
                await(()->GuardianRuntime.speechSnapshot().id>before&&
                        (GuardianRuntime.speechSnapshot().phase==GuardianRuntime.SpeechPhase.COMPLETE||
                         GuardianRuntime.speechSnapshot().phase==GuardianRuntime.SpeechPhase.ERROR),60000);
                assertEquals(GuardianRuntime.speechSnapshot().error,GuardianRuntime.SpeechPhase.COMPLETE,GuardianRuntime.speechSnapshot().phase);
                assertEquals(GuardianRuntime.Status.LISTENING,GuardianRuntime.snapshot().status);
                assertEquals("切换不应重启守护会话",session,generation.getLong(null));
            }
        }finally{
            target.stopService(new android.content.Intent(target,GuardianService.class));sync(activity::finish);
            android.content.SharedPreferences.Editor edit=prefs.edit();
            if(previous==null)edit.remove("performance_mode");else edit.putString("performance_mode",previous);edit.commit();
        }
    }
    @Test public void highContinuousListeningPreparesRetainsAndRecovers()throws Exception{
        target.stopService(new android.content.Intent(target,GuardianService.class));SystemClock.sleep(1500);
        try(CareAcceptanceContext app=new CareAcceptanceContext(target);
            UnderstandingWorker input=new UnderstandingWorker(app,true,ignored->{})){
            BooleanSupplier resident=()->{try{return Boolean.TRUE.equals(field(input,"loaded"));}catch(Exception error){throw new AssertionError(error);}};
            assertTrue(PerformanceSettings.highPerformance(app));await(resident,60000);
            recognize(input);SystemClock.sleep(ResourceBudget.read(app).modelIdleMs+2500);barrier(input,"MODEL");
            assertTrue("高性能持续倾听在静音后仍应驻留",resident.getAsBoolean());
            sync(input::trimMemory);barrier(input,"MODEL");assertFalse(resident.getAsBoolean());
            sync(()->input.setContinuous(true));await(resident,60000);recognize(input);
            sync(input::close);barrier(input,"MODEL");assertFalse(resident.getAsBoolean());
            assertFalse("性能策略不得修改用户陪伴开关",app.getSharedPreferences("interaction",0).getBoolean("companion_listening",false));
        }
    }
}

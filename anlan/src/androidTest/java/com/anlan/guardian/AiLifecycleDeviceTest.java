package com.anlan.guardian;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 会话生命周期验证，输入为结构化结果，播音使用确定性回调；不声明ASR或真实TTS。 */
@RunWith(AndroidJUnit4.class)
public final class AiLifecycleDeviceTest {
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private void main(Runnable action){instrumentation.runOnMainSync(action);}
    private void await(BooleanSupplier value,long duration){long until=SystemClock.elapsedRealtime()+duration;while(!value.getAsBoolean()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(10);assertTrue(value.getAsBoolean());}
    private final class Fixture implements AutoCloseable,ConversationCoordinator.Host {
        final CareAcceptanceContext isolated=new CareAcceptanceContext(app); final AtomicReference<InteractionPorts.SpeechInput.Result> input=new AtomicReference<>();
        final AtomicInteger spoken=new AtomicInteger();ConversationCoordinator coordinator;boolean autoReady=true,immediate;
        Fixture()throws Exception{new AiSettings(isolated).save(new AiSettings.Profile(true,"https://example.com/v1","","test",true,false));main(()->coordinator=new ConversationCoordinator(isolated,this));}
        public UnderstandingWorker input(){return null;}public boolean available(){return true;}
        public void startInput(InteractionPorts.SpeechInput.Result callback,long timeout){input.set(callback);if(autoReady)callback.onReady();}
        public void startInput(InteractionPorts.SpeechInput.Result callback,long timeout,boolean immediate){this.immediate=immediate;startInput(callback,timeout);}
        public void stopInput(){input.set(null);}public void stopSpeech(){}
        public void speak(String text,Runnable complete,java.util.function.Consumer<String> failed){spoken.incrementAndGet();complete.run();}
        void text(String text){InteractionPorts.SpeechInput.Result result=input.get();assertNotNull(result);result.onUnderstanding(new VoiceUnderstandingResult(SystemClock.elapsedRealtimeNanos(),SystemClock.elapsedRealtime(),text,"NEUTRAL","zh","Speech",1000,2));result.onUtteranceEnd();}
        public void close(){main(coordinator::close);SystemClock.sleep(100);isolated.close();}
    }
    @Test public void hundredCancelReplaceCyclesDoNotReviveOldInput()throws Exception{
        try(Fixture f=new Fixture()){
            for(int i=0;i<100;i++){
                AtomicReference<InteractionPorts.SpeechInput.Result> old=new AtomicReference<>();String[] id={""};
                main(()->{f.coordinator.startAfterWake();old.set(f.input.get());id[0]=GuardianRuntime.conversationId();f.coordinator.stop("ACCEPTANCE_CANCEL");old.get().onUnderstanding(new VoiceUnderstandingResult(500,0,"你是谁","NEUTRAL","zh","Speech",1,1));old.get().onUtteranceEnd();});
                assertFalse(Conversation.active());assertEquals(0,f.spoken.get());
                CountDownLatch barrier=new CountDownLatch(1);f.coordinator.query(id[0],value->barrier.countDown());assertTrue(barrier.await(5,TimeUnit.SECONDS));
            }
        }
    }
    @Test public void silenceTimeoutAndConfigChangeEndWithoutNewSpeech()throws Exception{
        try(Fixture f=new Fixture()){
            long start=SystemClock.elapsedRealtime();
            main(f.coordinator::startAfterWake);await(()->!Conversation.active(),5000);
            assertTrue(SystemClock.elapsedRealtime()-start>=3000);assertEquals("TIMEOUT",GuardianRuntime.conversationState());
            assertNull(f.input.get());assertEquals(0,f.spoken.get());
            main(f.coordinator::startAfterWake);f.isolated.getSharedPreferences("ai_model",0).edit().putBoolean("enabled",false).commit();await(()->!Conversation.active(),3000);assertNull(f.input.get());
        }
    }
    @Test public void backEndsRespondingConversationWithoutLeavingHome()throws Exception{
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new android.content.Intent(app,MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();app.stopService(new android.content.Intent(app,GuardianService.class));
        await(()->GuardianRuntime.snapshot().status==GuardianRuntime.Status.STOPPED,10000);
        try(Fixture f=new Fixture()){
            main(()->{
                f.coordinator.startAfterWake();
                GuardianRuntime.publishInput("IDLE");
                GuardianRuntime.publishConversation(GuardianRuntime.conversationId(),"SPEAKING");
                try {
                    java.lang.reflect.Method receive=MainActivity.class.getDeclaredMethod("receive",android.content.Intent.class);
                    receive.setAccessible(true);receive.invoke(activity,new Object[]{null});
                } catch(Exception error){throw new IllegalStateException(error);}
            });
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            await(()->!Conversation.active(),3000);
            assertFalse(activity.isFinishing());assertNull(f.input.get());
        }finally{main(activity::finish);}
    }
    @Test public void preparationDoesNotConsumeThreeSecondBudgetAndSpeechCanFinishLater()throws Exception{
        // vivo 会冻结后台验收进程；保持页面可见，仅停止真实采集，不把冻结时间算作输入超时。
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new android.content.Intent(app,MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();app.stopService(new android.content.Intent(app,GuardianService.class));
        await(()->GuardianRuntime.snapshot().status==GuardianRuntime.Status.STOPPED,10000);
        try(Fixture f=new Fixture()){
            f.autoReady=false;main(f.coordinator::startAfterWake);
            assertTrue("首轮使用快速输入",f.immediate);
            SystemClock.sleep(3400);assertTrue(Conversation.active());assertEquals("PREPARING",GuardianRuntime.conversationState());
            main(()->{f.input.get().onReady();f.input.get().onVoice(true);});
            SystemClock.sleep(3400);assertTrue(Conversation.active());assertNotNull(f.input.get());
            main(()->{f.input.get().onVoice(false);f.text("你是谁");});
            assertEquals(1,f.spoken.get());
            await(()->f.input.get()!=null,3000);
            assertFalse("后续轮次仍用正常输入流程",f.immediate);
            main(()->f.coordinator.stop("TEST_END"));
            assertFalse(Conversation.active());assertNull(f.input.get());
        }finally{main(activity::finish);}
    }
    @Test public void careIdContinuesWithoutChangingFirstReplyAndQueriesByCare()throws Exception{
        try(Fixture f=new Fixture()){
            InteractionPorts.CareRequest request=new InteractionPorts.CareRequest("care-link","S02","cough-event","需要休息吗？",InteractionPorts.CareRequest.Mode.ASK,20000,System.currentTimeMillis()+60000);
            Care.Result first=new Care.Result("care-link","S02","cough-event",Care.Status.REPLIED,"VOICE","我没事","OK",System.currentTimeMillis(),Collections.emptyList(),false);
            main(()->f.coordinator.careReply(request,first));assertEquals(Care.Status.REPLIED,first.status);assertFalse(Conversation.active());assertEquals(1,f.spoken.get());
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Conversation.Result> after=new AtomicReference<>();f.coordinator.query("care-link",value->{after.set(value);done.countDown();});assertTrue(done.await(5,TimeUnit.SECONDS));assertNotNull(after.get());assertEquals("care-link",after.get().careId);assertEquals("S02",after.get().scenarioId);
        }
    }
}

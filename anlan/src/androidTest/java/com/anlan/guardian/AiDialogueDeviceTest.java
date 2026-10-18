package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 运行时 profile 位于目标应用 cache，不在测试 APK 内；无凭据时仅跳过真实网络用例。 */
@RunWith(AndroidJUnit4.class)
public final class AiDialogueDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void await(BooleanSupplier value,long ms) { long until=SystemClock.elapsedRealtime()+ms; while(!value.getAsBoolean()&&SystemClock.elapsedRealtime()<until) SystemClock.sleep(20); assertTrue("等待AI验收条件超时",value.getAsBoolean()); }
    private AiSettings.Profile profile() throws Exception {
        File path=new File(app.getCacheDir(),"ai-runtime-profile.json"); org.junit.Assume.assumeTrue("未注入运行时凭据",path.isFile());
        JSONObject data=new JSONObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));
        return new AiSettings.Profile(true,data.getString("url"),data.getString("token"),data.getString("model"),true,false);
    }
    @Test public void settingsBlankByDefaultAndKeystoreRoundTrip() throws Exception {
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app)) {
            AiSettings settings=new AiSettings(isolated); AiSettings.Profile blank=settings.load();
            assertFalse(blank.enabled); assertEquals("",blank.baseUrl); assertEquals("",blank.token); assertEquals("",blank.model);
            settings.save(new AiSettings.Profile(true,"https://example.com/v1/","dummy-secret-for-encryption-only","test-model",true,true));
            assertEquals("https://example.com/v1",settings.load().baseUrl); assertEquals("dummy-secret-for-encryption-only",settings.load().token);
            assertFalse(settings.preferences.getString("token","").contains("dummy-secret"));
            settings.preferences.edit().putString("token","invalid").commit();
            try { settings.load(); fail(); } catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("重新填写")); }
            settings.clear(); assertFalse(settings.load().enabled);
        }
    }
    @Test public void actualModelReturnsStreamingChineseWithoutThinking() throws Exception {
        AiSettings.Profile profile=profile(); CountDownLatch finished=new CountDownLatch(1); AtomicReference<String> error=new AtomicReference<>("");
        List<String> chunks=Collections.synchronizedList(new ArrayList<>()); long start=SystemClock.elapsedRealtime(); long[] first={0};
        try(OpenAiCompatibleAgent client=new OpenAiCompatibleAgent(profile,new DialogueConfig(app))) {
            List<InteractionPorts.DialogueMessage> messages=new ArrayList<>(); messages.add(new InteractionPorts.DialogueMessage("system","你是小安，温暖自然的智能守护音箱。只回答一至两句中文，总计不超过60字，不追问。")); messages.add(new InteractionPorts.DialogueMessage("user","今天终于做完了一件拖了很久的事，感觉好轻松。"));
            client.stream("runtime-smoke",messages,180,new InteractionPorts.DialogueAgent.Reply(){
                public void onDelta(String text) { if(first[0]==0) first[0]=SystemClock.elapsedRealtime()-start; chunks.add(text); }
                public void onComplete(String reason) { finished.countDown(); }
                public void onError(String reason) { error.set(reason); finished.countDown(); }
            });
            assertTrue(finished.await(70,TimeUnit.SECONDS)); assertEquals("",error.get()); assertTrue(chunks.size()>1);
            String text=String.join("",chunks); assertTrue(text.matches("(?s).*[\\u4e00-\\u9fff].*")); assertFalse(text.contains("<think>")); assertTrue(text.length()<=160);
            android.os.Bundle result=new android.os.Bundle(); result.putString("ai_network_metrics","first_content_ms="+first[0]+" total_ms="+(SystemClock.elapsedRealtime()-start)+" chunks="+chunks.size()+" chars="+text.length()); instrumentation.sendStatus(0,result);
        }
    }
    @Test public void actualModelFiveRoundsAndLocalExitKeepPrivacy() throws Exception {
        AiSettings.Profile profile=profile();
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app)) {
            new AiSettings(isolated).save(profile); AtomicReference<InteractionPorts.SpeechInput.Result> input=new AtomicReference<>();
            List<String> played=new java.util.concurrent.CopyOnWriteArrayList<>(); AtomicReference<ConversationCoordinator> coordinator=new AtomicReference<>();
            long[] speechLease={0};
            main(() -> {
                speechLease[0]=SpeechPlayback.attach(request -> { played.add(request.text); if(request.observer!=null)request.observer.onComplete(); },(source,event,listener)->new InteractionPorts.SpeechStream(){
                    boolean done; final StringBuilder text=new StringBuilder();
                    public boolean append(String delta) { if(done)throw new IllegalStateException(); text.append(delta); return true; }
                    public void end() { if(done)return;done=true;new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{ String value=text.toString();played.add(value);listener.onSegmentStarted(new InteractionPorts.SpeechRequest(source,value,event));listener.onComplete(); }); }
                    public void cancel() { if(done)return;done=true;new android.os.Handler(android.os.Looper.getMainLooper()).post(listener::onCancelled); }
                });
                coordinator.set(new ConversationCoordinator(isolated,new ConversationCoordinator.Host(){
                    public UnderstandingWorker input(){return null;}
                    public boolean available(){return true;}
                    public void startInput(InteractionPorts.SpeechInput.Result result,long timeout){input.set(result);result.onReady();}
                    public void stopInput(){input.set(null);}
                    public void speak(String text,Runnable complete,java.util.function.Consumer<String> failed){played.add(text);complete.run();}
                    public void stopSpeech(){}
                }));coordinator.get().startAfterWake();
            });
            String[] questions={"我今天整理好了书桌","现在想安静休息一下，但也想听你说一句话","请给我一句鼓励","你还记得我刚刚整理了什么吗","给我讲一个不超过五十字的小故事"};
            try {
                for(int i=0;i<questions.length;i++) {
                    await(()->input.get()!=null,15000); int before=played.size(); final int index=i;
                    main(()->{InteractionPorts.SpeechInput.Result callback=input.get();callback.onUnderstanding(new VoiceUnderstandingResult(index+100,SystemClock.elapsedRealtime(),questions[index],"NEUTRAL","zh","Speech",1000,10));callback.onUtteranceEnd();});
                    await(()->played.size()>before,70000); assertFalse(played.get(played.size()-1).contains("网络暂时"));
                }
                await(()->input.get()!=null,15000); String id=GuardianRuntime.conversationId();
                main(()->{InteractionPorts.SpeechInput.Result callback=input.get();callback.onUnderstanding(new VoiceUnderstandingResult(999,SystemClock.elapsedRealtime(),"再见","NEUTRAL","zh","Speech",1000,10));callback.onUtteranceEnd();});
                await(()->!Conversation.active(),5000); CountDownLatch queried=new CountDownLatch(1);AtomicReference<Conversation.Result> stored=new AtomicReference<>();
                coordinator.get().query(id,value->{stored.set(value);queried.countDown();});assertTrue(queried.await(5,TimeUnit.SECONDS));assertNotNull(stored.get());assertTrue(stored.get().contentStored);assertFalse(stored.get().turns.isEmpty());
            } finally { main(()->{coordinator.get().close();SpeechPlayback.detach(speechLease[0]);}); }
        }
    }
    @Test public void conversationStoreAutomaticallySavesAndExpiresAfterThreeDays() throws Exception {
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app);ConversationStore store=new ConversationStore(isolated)) {
            List<Conversation.Turn> turns=Collections.singletonList(new Conversation.Turn("我没事","我在这里。","我在这里。","COMPLETED",Collections.emptyList()));
            Conversation.Result result=new Conversation.Result("session","care-test","S02","event","ENDED","USER_END",System.currentTimeMillis(),turns,false);
            store.save(result);assertEquals("我没事",store.query("care-test").turns.get(0).userText);
            isolated.getSharedPreferences("care",0).edit().putBoolean("save_replies",false).commit();store.save(result);assertEquals("我没事",store.query("session").turns.get(0).userText);
            store.clean(result.at+3*86400000L-1);assertFalse(store.query("session").turns.isEmpty());
            store.clean(result.at+3*86400000L+1);assertNull(store.query("session"));
        }
    }
}

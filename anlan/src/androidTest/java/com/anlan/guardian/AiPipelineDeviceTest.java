package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 数字PCM→真实VAD/SenseVoice→真实网络→统一流式TTS。不是物理麦克风/可听验收。 */
@RunWith(AndroidJUnit4.class)
public final class AiPipelineDeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final Handler main=new Handler(Looper.getMainLooper());
    private void sync(Runnable action){instrumentation.runOnMainSync(action);}
    private void await(BooleanSupplier condition,long ms){long until=SystemClock.elapsedRealtime()+ms;while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);assertTrue("等待完整链路超时",condition.getAsBoolean());}
    private byte[] asset(String name)throws Exception{try(java.io.InputStream input=instrumentation.getContext().getAssets().open(name);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){byte[] b=new byte[4096];int count;while((count=input.read(b))!=-1)output.write(b,0,count);return output.toByteArray();}}
    private void feed(UnderstandingWorker worker,String name)throws Exception{
        ByteBuffer bytes=ByteBuffer.wrap(asset("dialogue-test/"+name+".pcm")).order(ByteOrder.LITTLE_ENDIAN);
        while(bytes.remaining()>0){int count=Math.min(1600,bytes.remaining()/2);short[] pcm=new short[count];for(int i=0;i<count;i++)pcm[i]=bytes.getShort();worker.acceptPcm(pcm,count,16000);SystemClock.sleep(100);}
    }
    @Test public void twentyFourSyntheticSamplesReachActualSenseVoice()throws Exception{
        app.stopService(new android.content.Intent(app,GuardianService.class));SystemClock.sleep(1500);
        org.json.JSONArray manifest=new org.json.JSONArray(new String(asset("dialogue-test/manifest.json"),StandardCharsets.UTF_8));int recognized=0;
        try(com.soundguardian.core.SherpaVoiceUnderstanding model=new com.soundguardian.core.SherpaVoiceUnderstanding(app,new com.soundguardian.core.SherpaVoiceUnderstanding.Settings(app))){
            for(int i=0;i<manifest.length();i++){
                JSONObject entry=manifest.getJSONObject(i);ByteBuffer bytes=ByteBuffer.wrap(asset("dialogue-test/"+entry.getString("name")+".pcm")).order(ByteOrder.LITTLE_ENDIAN);float[] samples=new float[bytes.remaining()/2];for(int j=0;j<samples.length;j++)samples[j]=bytes.getShort()/32768f;
                com.soundguardian.core.VoiceUnderstandingResult result=model.recognize(samples,i+1);if(!result.text.trim().isEmpty()&&result.event.contains("Speech"))recognized++;
                android.os.Bundle status=new android.os.Bundle();status.putString("synthetic_asr_case",entry.getString("name")+" nonempty="+!result.text.isEmpty()+" inference_ms="+result.inferenceMs);instrumentation.sendStatus(0,status);
            }
        }
        assertEquals(24,manifest.length());assertEquals(24,recognized);
    }
    @Test public void serviceWakePcmModelSpeechCaptionAndExit()throws Exception{
        File path=new File(app.getCacheDir(),"ai-runtime-profile.json");org.junit.Assume.assumeTrue(path.isFile());
        JSONObject data=new JSONObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));
        AiSettings settings=new AiSettings(app);AiSettings.Profile previous=settings.load();
        settings.save(new AiSettings.Profile(true,data.getString("url"),data.getString("token"),data.getString("model"),true,true));
        android.content.Intent launch=new android.content.Intent(app,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(launch);
        try{
            await(()->GuardianRuntime.snapshot().status==GuardianRuntime.Status.LISTENING&&GuardianRuntime.speechStatus().startsWith("已就绪"),120000);
            java.lang.reflect.Field endpoint=Conversation.class.getDeclaredField("endpoint");endpoint.setAccessible(true);
            ConversationCoordinator coordinator=(ConversationCoordinator)endpoint.get(null);
            java.lang.reflect.Field hostField=ConversationCoordinator.class.getDeclaredField("host");hostField.setAccessible(true);
            ConversationCoordinator.Host host=(ConversationCoordinator.Host)hostField.get(coordinator);
            java.lang.reflect.Field outer=host.getClass().getDeclaredField("this$0");outer.setAccessible(true);GuardianService service=(GuardianService)outer.get(host);
            java.lang.reflect.Method wake=GuardianService.class.getDeclaredMethod("onWakeDetected",long.class,long.class);wake.setAccessible(true);
            java.lang.reflect.Field token=GuardianService.class.getDeclaredField("token");token.setAccessible(true);
            sync(()->{try{wake.invoke(service,token.getLong(service),SystemClock.elapsedRealtime());}catch(Exception error){throw new IllegalStateException(error);}});
            await(()->GuardianRuntime.inputPhase().equals("LISTENING")&&Conversation.active(),45000);
            android.graphics.Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();
            if(bitmap!=null){try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(app.getCacheDir(),"ai-listening.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}bitmap.recycle();}
            feed(host.input(),"happy-normal");
            await(()->GuardianRuntime.speechSnapshot().phase==GuardianRuntime.SpeechPhase.PLAYING&&GuardianRuntime.speechSnapshot().request.source==InteractionPorts.SpeechRequest.Source.DIALOGUE,120000);
            String spoken=GuardianRuntime.speechSnapshot().request.text;assertFalse(spoken.isEmpty());
            sync(()->assertEquals(spoken,((android.widget.TextView)activity.findViewById(R.id.emotion_name)).getText().toString()));
            await(()->GuardianRuntime.inputPhase().equals("LISTENING"),120000);
            feed(host.input(),"stop-normal");await(()->!Conversation.active(),60000);
            assertEquals(GuardianRuntime.Status.LISTENING,GuardianRuntime.snapshot().status);
        }finally{settings.save(previous);sync(activity::finish);app.stopService(new android.content.Intent(app,GuardianService.class));}
    }
    @Test public void pcmToActualModelToActualTtsAndVoiceExit()throws Exception{
        File path=new File(app.getCacheDir(),"ai-runtime-profile.json");org.junit.Assume.assumeTrue(path.isFile());
        app.stopService(new android.content.Intent(app,GuardianService.class));SystemClock.sleep(1500);
        JSONObject profile=new JSONObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8));
        try(CareAcceptanceContext isolated=new CareAcceptanceContext(app)){
            new AiSettings(isolated).save(new AiSettings.Profile(true,profile.getString("url"),profile.getString("token"),profile.getString("model"),true,true));
            AtomicReference<UnderstandingWorker> worker=new AtomicReference<>();AtomicReference<LocalSpeechOutput> output=new AtomicReference<>();AtomicReference<InputSessionController> input=new AtomicReference<>();AtomicReference<ConversationCoordinator> coordinator=new AtomicReference<>();
            AtomicReference<String> status=new AtomicReference<>(""),transcript=new AtomicReference<>("");List<String> played=new CopyOnWriteArrayList<>();long[] lease={0};long begun=SystemClock.elapsedRealtime();
            sync(()->{
                worker.set(new UnderstandingWorker(isolated,false,state->{if(state.result!=null)transcript.set(state.result.text);}));
                output.set(new LocalSpeechOutput(isolated,status::set));
                lease[0]=SpeechPlayback.attach(request->{
                    worker.get().setSuppressed(true);output.get().speak(request,new InteractionPorts.SpeechOutput.Progress(){
                        public void onStart(){if(request.observer!=null)request.observer.onStart();}
                        public void onComplete(){main.postDelayed(()->worker.get().setSuppressed(false),500);if(request.observer!=null)request.observer.onComplete();}
                        public void onError(String reason){if(request.observer!=null)request.observer.onError(reason);}
                        public void onCancelled(){if(request.observer!=null)request.observer.onCancelled();}
                    });
                },(source,event,listener)->{
                    worker.get().setSuppressed(true);
                    try{return output.get().openStream(source,event,new InteractionPorts.SpeechOutput.Progress(){
                        public void onSegmentStart(InteractionPorts.SpeechRequest segment){played.add(segment.text);listener.onSegmentStarted(segment);}
                        public void onComplete(){main.postDelayed(()->worker.get().setSuppressed(false),500);listener.onComplete();}
                        public void onError(String reason){status.set("stream_error:"+reason);listener.onError(reason);}
                        public void onCancelled(){listener.onCancelled();}
                    });}catch(Exception error){throw new IllegalStateException(error);}
                });
                input.set(new InputSessionController(new InputSessionController.Host(){
                    public UnderstandingWorker worker(){return worker.get();}
                    public boolean available(){return true;}
                    public boolean cueEnabled(){return true;}
                    public void cue(Runnable complete,java.util.function.Consumer<String> error){SpeechPlayback.speak(new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.CUE,"提示音","","",new InteractionPorts.SpeechOutput.Progress(){public void onComplete(){complete.run();}public void onError(String reason){error.accept(reason);}public void onCancelled(){error.accept("CANCELLED");}}));}
                },650));
                coordinator.set(new ConversationCoordinator(isolated,new ConversationCoordinator.Host(){
                    public UnderstandingWorker input(){return worker.get();}
                    public boolean available(){return true;}
                    public void startInput(InteractionPorts.SpeechInput.Result callback,long timeout){input.get().start(callback,timeout);}
                    public void stopInput(){input.get().cancel();}
                    public void speak(String text,Runnable complete,java.util.function.Consumer<String> failed){SpeechPlayback.speak(new InteractionPorts.SpeechRequest(InteractionPorts.SpeechRequest.Source.DIALOGUE,text,"","",new InteractionPorts.SpeechOutput.Progress(){public void onComplete(){complete.run();}public void onError(String reason){failed.accept(reason);}public void onCancelled(){failed.accept("CANCELLED");}}));}
                    public void stopSpeech(){output.get().stop();}
                }));
            });
            try{
                await(()->status.get().startsWith("已就绪"),120000);sync(()->coordinator.get().startAfterWake());
                await(()->GuardianRuntime.inputPhase().equals("LISTENING"),45000);long ready=SystemClock.elapsedRealtime();
                feed(worker.get(),"happy-normal");
                await(()->!played.isEmpty(),120000);long first=SystemClock.elapsedRealtime();
                await(()->GuardianRuntime.inputPhase().equals("LISTENING"),120000);
                assertTrue(transcript.get(),!transcript.get().isEmpty());assertTrue(String.join("",played).length()<=160);
                android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);
                android.os.Bundle metrics=new android.os.Bundle();metrics.putString("ai_pipeline_metrics","ready_ms="+(ready-begun)+" first_audio_since_ready_ms="+(first-ready)+" pss_kib="+memory.getTotalPss()+" segments="+played.size());instrumentation.sendStatus(0,metrics);
                feed(worker.get(),"stop-normal");await(()->!Conversation.active(),60000);
            }finally{sync(()->{coordinator.get().close();input.get().close();worker.get().close();output.get().close();SpeechPlayback.detach(lease[0]);});}
        }
    }
}

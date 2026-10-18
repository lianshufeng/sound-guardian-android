package com.anlan.guardian;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 可控响应覆盖正式网络适配器的解析/错误处理，不请求公网。 */
@RunWith(AndroidJUnit4.class)
public final class AiProtocolDeviceTest {
    private final Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static String event(String json){return "data: "+json+"\n\n";}
    private static final String CONTENT="{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好。\"},\"finish_reason\":null}]}";
    private static final String STOP="{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
    private String run(String data,int code,String type,StringBuilder body)throws Exception{
        CountDownLatch finished=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>("");ByteArrayOutputStream request=new ByteArrayOutputStream();
        AiSettings.Profile profile=new AiSettings.Profile(true,"https://example.com/v1","not-a-real-key","mock",true,true);
        try(OpenAiCompatibleAgent agent=new OpenAiCompatibleAgent(profile,new DialogueConfig(app),url->new HttpURLConnection(new URL(url)){
            public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
            public int getResponseCode(){return code;}public String getContentType(){return type;}
            public java.io.OutputStream getOutputStream(){return request;}
            public java.io.InputStream getInputStream(){return new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));}
        })){
            agent.stream("mock",Collections.singletonList(new InteractionPorts.DialogueMessage("user","你好")),100,new InteractionPorts.DialogueAgent.Reply(){
                public void onDelta(String text){body.append(text);}public void onComplete(String reason){result.set("OK");finished.countDown();}public void onError(String reason){result.set(reason);finished.countDown();}
            });assertTrue(finished.await(5,TimeUnit.SECONDS));
            if(code==200){String payload=request.toString("UTF-8");assertTrue(payload.contains("\"enable_thinking\":false"));assertTrue(payload.contains("\"stream\":true"));}
            return result.get();
        }
    }
    @Test public void twentyProtocolCases()throws Exception{
        String good=event(CONTENT)+event(STOP)+event("[DONE]");
        String[][] cases={
            {good,"OK"},{good.replace("\n","\r\n"),"OK"},{": heartbeat\n\n"+good,"OK"},{event("{\"choices\":[]}")+good,"OK"},
            {event("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null},\"finish_reason\":null}]}")+good,"OK"},
            {event("{\"extra\":1}")+good,"OK"},{event(CONTENT)+event("{\"choices\":[]}")+event(STOP)+event("[DONE]"),"OK"},
            {event(CONTENT),"STREAM_TRUNCATED"},{event("[DONE]"),"STREAM_TRUNCATED"},{event(STOP)+event("[DONE]"),"EMPTY_REPLY"},
            {event(CONTENT)+event(STOP.replace("stop","length"))+event("[DONE]"),"FINISH_LENGTH"},
            {event(CONTENT)+event(STOP.replace("stop","content_filter"))+event("[DONE]"),"FINISH_FILTERED_OR_UNSUPPORTED"},
            {event("{\"error\":{\"message\":\"private\"}}"),"MODEL_ERROR"},
            {event("{broken}"),"NETWORK_OR_PROTOCOL_ERROR"},
            {event("{\"choices\":[{\"delta\":{\"tool_calls\":[]}}]}"),"UNSUPPORTED_TOOL"},
            {event(CONTENT)+event(STOP)+event(CONTENT),"INVALID_STREAM_ORDER"},
            {"data: "+"x".repeat(65537),"PROTOCOL_TOO_LARGE"},
            {event("{\"choices\":[{\"index\":1,\"delta\":{\"content\":\"其他候选\"}}]}")+good,"OK"},
            {event("{\"choices\":[{\"delta\":{\"reasoning_content\":\"秘密推理\"}}]}")+good,"OK"},
            {event(CONTENT)+event(STOP)+"data: [DONE]","STREAM_TRUNCATED"}
        };
        for(int i=0;i<cases.length;i++){StringBuilder text=new StringBuilder();String actual=run(cases[i][0],200,"text/event-stream; charset=utf-8",text);assertEquals("case="+i,cases[i][1],actual);assertFalse(text.toString().contains("秘密"));if(actual.equals("OK"))assertEquals("你好。",text.toString());}
        for(int code:new int[]{401,403,404,429,500})assertEquals("HTTP_"+code,run("",code,"application/json",new StringBuilder()));
        assertEquals("NOT_STREAMING",run("{}",200,"application/json",new StringBuilder()));
    }
}

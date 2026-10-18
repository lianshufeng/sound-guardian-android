package com.anlan.guardian;

import java.io.StringReader;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DialoguePolicyTest {
    @Test public void fortyTextCasesRouteWithoutFuzzySideEffects() {
        String[][] cases = {
            {"停止","END"},{"取消","END"},{"退出","END"},{"结束","END"},{"不聊了","END"},{"再见","END"},{"谢谢","END"},{"谢谢你","END"},{"安静","END"},{"别听了","END"},
            {"不用了","END"},{"不用","END"},{"没事","END"},{"我没事","END"},{"是电视","END"},{"晚安","END"},{"我要睡了","END"},
            {"停止关怀","CARE_OFF"},{"关闭主动关怀","CARE_OFF"},{"不要提醒了","CARE_OFF"},{"稍后","SNOOZE"},{"等会儿","SNOOZE"},{"晚点","SNOOZE"},
            {"今晚晚点睡","BEDTIME"},{"今天晚点睡","BEDTIME"},{"现在几点","TIME"},{"现在几点了","TIME"},{"几点了？","TIME"},{"天气怎么样","WEATHER"},{"外面冷吗","WEATHER"},
            {"需要帮助","HELP"},{"帮帮我","HELP"},{"你是谁","IDENTITY"},{"你叫什么名字","IDENTITY"},
            {"不要取消","MODEL"},{"我不想停止关怀","MODEL"},{"电视里说再见","MODEL"},{"今天终于完成工作了","MODEL"},{"陪我聊聊","MODEL"},{"给我讲个短故事","MODEL"},{"我想放松一下","MODEL"},{"我有点难过","MODEL"}
        };
        for (String[] entry : cases) assertEquals(entry[0],entry[1],ConversationRouter.route(entry[0]).name());
    }
    @Test public void twentySseCasesHandleChunkBoundariesAndHeartbeats() throws Exception {
        for (int i=0;i<20;i++) {
            String newline=i%2==0?"\n":"\r\n";
            String data=": heartbeat"+newline+newline+"data: {\"中文\":\"你好\"}"+newline+newline+"data: [DONE]"+newline+newline;
            List<String> values=new ArrayList<>();
            SseReader.read(new InputStreamReader(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)) {
                @Override public synchronized int read(byte[] b,int off,int len) { return super.read(b,off,Math.min(len,1)); }
            },StandardCharsets.UTF_8),()->true, value->{ values.add(value); return !value.equals("[DONE]"); });
            assertEquals(2,values.size()); assertEquals("{\"中文\":\"你好\"}",values.get(0));
        }
    }
    @Test public void sseSupportsMultilineAndIgnoresUnknownFields() throws Exception {
        List<String> values=new ArrayList<>();
        SseReader.read(new StringReader("event: completion\nid: 1\ndata: a\ndata: b\n\n"),()->true,value->{values.add(value);return true;});
        assertEquals("a\nb",values.get(0));
    }
    @Test public void sseRefusesTruncatedEvent() throws Exception {
        try { SseReader.read(new StringReader("data: {"),()->true,value->true); fail(); } catch (java.io.IOException expected) { assertEquals("STREAM_TRUNCATED",expected.getMessage()); }
    }
    @Test public void sseRefusesUnboundedLine() throws Exception {
        try { SseReader.read(new StringReader("data: "+"x".repeat(65537)),()->true,value->true); fail(); } catch (java.io.IOException expected) { assertEquals("PROTOCOL_TOO_LARGE",expected.getMessage()); }
    }
    @Test public void textFiltersThinkAcrossEverySplit() {
        String value="<think>不应朗读的内部推理</think>我在这里。慢慢说就好。";
        for (int split=1;split<value.length();split++) { DialogueText filter=new DialogueText(160); String text=filter.append(value.substring(0,split),false)+filter.append(value.substring(split),false)+filter.append("",true); assertEquals("split="+split,"我在这里。慢慢说就好。",text); }
    }
    @Test public void textFiltersMiddleThinkAndMarkdown() {
        DialogueText filter=new DialogueText(160);
        assertEquals("你好。慢慢说。",filter.append("**你好。**<think>内部推理</think>慢慢说。",true));
    }
    @Test public void textNeverExceedsHardLimit() {
        DialogueText filter=new DialogueText(30);
        String text=filter.append("很长".repeat(100),false)+filter.append("",true);
        assertTrue(text.codePointCount(0,text.length())<=30); assertTrue(filter.limited());
    }
    @Test public void textKeepsSentenceOrderAndUnicode() {
        String source="听起来真不错😊。也替你开心！"; DialogueText filter=new DialogueText(160); StringBuilder out=new StringBuilder();
        for(int i=0;i<source.length();i++) out.append(filter.append(source.substring(i,i+1),false)); out.append(filter.append("",true)); assertEquals(source,out.toString());
    }
    @Test public void contextCropsWholeRoundsAndKeepsCurrentQuestion() {
        ConversationContext context=new ConversationContext(2,100);
        for(int i=0;i<10;i++) context.completed("问题"+i,"回答"+i);
        List<InteractionPorts.DialogueMessage> messages=context.request("规则","天气缺失","现在的问题");
        assertEquals(6,messages.size()); assertEquals("问题8",messages.get(1).text); assertEquals("现在的问题",messages.get(5).text);
    }
    @Test public void contextBudgetRefusesOversizedCurrentInput() {
        try { new ConversationContext(6,100).request("规则","场景","字".repeat(100)); fail(); } catch(IllegalArgumentException expected) { assertEquals("CONTEXT_TOO_LONG",expected.getMessage()); }
    }
}

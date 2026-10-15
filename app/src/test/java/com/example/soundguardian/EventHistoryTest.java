package com.example.soundguardian;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventHistoryTest {
  @Test public void shortCoughAppearsClosesAndMergesInHistory(){
    EventEngine.Rule cough=new EventEngine.Rule(0,"Cough","咳嗽",.08f,.30f,.12f,1,650,1100);
    EventEngine engine=new EventEngine(Collections.singletonList(cough));
    engine.update(new float[]{.29f},10000);
    assertEquals("暂无已确认关注事件",engine.recent());
    engine.update(new float[]{.31f},10480);
    assertTrue(engine.recent().contains("咳嗽 · 已确认（进行中）"));
    engine.update(new float[]{0},11440);
    assertEquals("当前未确认关注事件",engine.current());
    assertTrue(engine.recent().contains("咳嗽 · 已结束"));
    engine.update(new float[]{.4f},11500);
    assertEquals(1,engine.history.size());
    engine.stop(12000);
    assertTrue(engine.recent().contains("已结束"));
  }
}

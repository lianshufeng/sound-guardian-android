package com.soundguardian.core;


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

  @Test public void longRunningActiveEventSurvivesHistoryLimit(){
    EventEngine.Rule alarm=new EventEngine.Rule(0,"Alarm","警报",.1f,.3f,.1f,1,650,0);
    EventEngine.Rule tap=new EventEngine.Rule(1,"Tap","轻敲",.1f,.3f,.1f,1,650,0);
    EventEngine engine=new EventEngine(java.util.Arrays.asList(alarm,tap));
    engine.update(new float[]{.9f,0},10000);
    for(int i=0;i<110;i++){
      long now=12000+i*2000L;
      engine.update(new float[]{.9f,.9f},now);
      engine.update(new float[]{.9f,0},now+1000);
    }
    assertTrue(engine.history.stream().anyMatch(e->e.rule.label.equals("Alarm")&&e.active));
    assertTrue(engine.history.size()<=100);
  }

  @Test public void longEventRequiresTwoFramesWithinEvidenceWindow(){
    EventEngine.Rule alarm=new EventEngine.Rule(0,"Alarm","警报",.08f,.32f,.14f,2,1100,1300);
    EventEngine engine=new EventEngine(Collections.singletonList(alarm));
    engine.update(new float[]{.40f},10000);
    assertTrue(engine.history.isEmpty());
    engine.update(new float[]{.40f},12000);
    assertTrue(engine.history.isEmpty());
    engine.update(new float[]{0},12480);
    engine.update(new float[]{.40f},12960);
    assertEquals(1,engine.history.size());
    assertTrue(engine.history.get(0).active);
    engine.update(new float[]{.20f},13440);
    engine.update(new float[]{0},14000);
    assertTrue(engine.history.get(0).active);
    engine.update(new float[]{0},14600);
    assertFalse(engine.history.get(0).active);
  }

  @Test public void fingerSnapsNeedTwoHitsWithinFifteenSeconds(){
    EventEngine.Rule snap=new EventEngine.Rule(0,"Finger snapping","打响指",.08f,.30f,.12f,2,15000,true,650,900);
    EventEngine engine=new EventEngine(Collections.singletonList(snap));
    engine.update(new float[]{.40f},10000);
    engine.update(new float[]{0},11000);
    assertTrue(engine.history.isEmpty());
    engine.update(new float[]{.40f},24000);
    assertEquals(1,engine.history.size());
    assertTrue(engine.history.get(0).active);
    engine.update(new float[]{0},25000);
    engine.update(new float[]{0},25800);
    assertFalse(engine.history.get(0).active);
    engine.update(new float[]{.40f},40000);
    engine.update(new float[]{0},41000);
    assertEquals(1,engine.history.size());
  }

  @Test public void moderateSnapEdgesConfirmButWeakScoresAndSingleOverlapDoNot(){
    EventEngine.Rule snap=new EventEngine.Rule(0,"Finger snapping","打响指",.08f,.20f,.12f,2,15000,true,650,900,1000);
    EventEngine engine=new EventEngine(Collections.singletonList(snap));
    for(int i=0;i<8;i++)engine.update(new float[]{.19f},10000+i*480);
    assertTrue(engine.history.isEmpty());
    engine.update(new float[]{.24f},14000);
    engine.update(new float[]{.24f},14480);
    engine.update(new float[]{.24f},14960);
    assertTrue(engine.history.isEmpty());
    engine.update(new float[]{.11f},15440);
    engine.update(new float[]{.23f},18000);
    assertEquals(1,engine.history.size());
    assertTrue(engine.history.get(0).active);
  }

  @Test public void sustainedSnapScoresAreOneHitAndStopClearsPartialSequence(){
    EventEngine.Rule snap=new EventEngine.Rule(0,"Finger snapping","打响指",.08f,.30f,.12f,2,15000,true,650,900);
    EventEngine engine=new EventEngine(Collections.singletonList(snap));
    for(int i=0;i<8;i++)engine.update(new float[]{.9f},10000+i*480);
    assertTrue(engine.history.isEmpty());
    engine.update(new float[]{0},14000);
    assertTrue(engine.history.isEmpty());
    engine.stop(16000);engine.update(new float[]{.9f},17000);
    assertTrue(engine.history.isEmpty());
  }

  @Test public void snapWindowIncludesBoundaryAndExpiresOlderHits(){
    EventEngine.Rule snap=new EventEngine.Rule(0,"Finger snapping","打响指",.08f,.30f,.12f,2,15000,true,650,900);
    for(long second:new long[]{25000,25001}){
      EventEngine engine=new EventEngine(Collections.singletonList(snap));
      engine.update(new float[]{.9f},10000);engine.update(new float[]{0},11000);
      assertTrue(engine.history.isEmpty());engine.update(new float[]{.9f},second);
      assertEquals(second==25000?1:0,engine.history.size());
    }
  }

  @Test public void continuousSnapSegmentConfirmsWithoutScoreFallingAndOnlyOnce(){
    EventEngine.Rule snap=new EventEngine.Rule(0,"Finger snapping","打响指",.08f,.30f,.12f,2,15000,true,650,900,1000);
    EventEngine engine=new EventEngine(Collections.singletonList(snap));
    for(int i=0;i<3;i++){engine.update(new float[]{.8f},10000+i*480);assertTrue(engine.history.isEmpty());}
    engine.update(new float[]{.8f},11440);
    assertEquals(1,engine.history.size());assertTrue(engine.history.get(0).active);
    for(int i=4;i<35;i++)engine.update(new float[]{.8f},10000+i*480);
    assertEquals(1,engine.history.size());
    engine.update(new float[]{0},28000);assertFalse(engine.history.get(0).active);
  }

  @Test public void continuousSnapsResetOnLowScoreStaleFramesAndStop(){
    EventEngine.Rule snap=new EventEngine.Rule(0,"Finger snapping","打响指",.08f,.30f,.12f,2,15000,true,650,900,1000);
    EventEngine engine=new EventEngine(Collections.singletonList(snap));
    engine.update(new float[]{.8f},10000);engine.update(new float[]{.8f},10480);
    engine.update(new float[]{.2f},10960); // 低于开启阈值，仍未低于关闭阈值：不构成第二次边沿。
    engine.update(new float[]{.8f},11440);engine.update(new float[]{.8f},11920);engine.update(new float[]{.8f},12400);
    assertTrue(engine.history.isEmpty());
    engine.update(new float[]{.8f},15000);assertTrue(engine.history.isEmpty());
    engine.update(new float[]{.8f},15480);engine.stop(15600);
    engine.update(new float[]{.8f},16000);engine.update(new float[]{.8f},16480);engine.update(new float[]{.8f},16960);
    assertTrue(engine.history.isEmpty());engine.update(new float[]{.8f},17440);
    assertEquals(1,engine.history.size());
  }
}

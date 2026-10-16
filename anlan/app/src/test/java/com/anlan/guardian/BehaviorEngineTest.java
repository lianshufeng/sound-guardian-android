package com.anlan.guardian;

import java.util.Collections;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BehaviorEngineTest {
    @Test public void quietTimeBecomesDrowsyThenSleeping() {
        BehaviorEngine engine = new BehaviorEngine(new BehaviorEngine.Timing(100, 200, 100, 20, 5000), new Random(1), 0);
        engine.setGuarding(true, 0);
        assertEquals(Emotion.CONTENT, engine.state(50).emotion);
        assertEquals(Emotion.DROWSY, engine.state(150).emotion);
        assertEquals(Emotion.SLEEPING, engine.state(250).emotion);
    }

    @Test public void highPriorityEventOverridesCareEvent() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(), new Random(1), 0);
        engine.setGuarding(true, 0);
        SoundEvent cough = new SoundEvent("c", "Cough", "咳嗽", 100, 100, true);
        SoundEvent crash = new SoundEvent("x", "Smash, crash", "撞击声", 200, 200, true);
        engine.events(Collections.singletonList(cough), 100);
        engine.events(java.util.Arrays.asList(cough, crash), 200);
        assertEquals(Emotion.STARTLED, engine.state(200).emotion);
    }

    @Test public void sustainedAlarmDoesNotRestartSurpriseAndBlocksPreview() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(),new Random(2),0);
        engine.setGuarding(true,0);
        SoundEvent alarm = event("alarm","Alarm",true);
        engine.events(Collections.singletonList(alarm),100);
        engine.events(Collections.singletonList(alarm),3000);
        engine.preview(Emotion.HAPPY,3100);
        assertEquals(Emotion.ALERT,engine.state(3100).emotion);
    }

    @Test public void endedEventRelaxesThenReturnsToCalm() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(),new Random(2),0);
        engine.setGuarding(true,0);
        engine.events(Collections.singletonList(event("alarm","Alarm",true)),100);
        engine.events(Collections.emptyList(),1000);
        assertEquals(Emotion.RELIEVED,engine.state(6000).emotion);
        Emotion recovered = engine.state(9000).emotion;
        assertTrue(recovered == Emotion.CALM || recovered == Emotion.CONTENT);
    }

    @Test public void conversationWakesSleepingFaceWithoutStoppingGuard() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(),new Random(2),0);
        engine.setGuarding(true,0);
        assertEquals(Emotion.SLEEPING,engine.state(190000).emotion);
        engine.activity(191000);
        assertEquals(Emotion.LISTENING,engine.state(191100).emotion);
        assertTrue(engine.state(191100).guarding);
    }

    @Test public void modelSoundMapsToDailyExpressionBeforeGuardEvent() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(), new Random(2), 0);
        engine.setGuarding(true, 0);
        engine.model("Speech", "说话", .42f, 100);
        assertEquals(Emotion.LISTENING, engine.state(200).emotion);
        engine.model("Music", "音乐", .42f, 500);
        assertEquals(Emotion.CONTENT, engine.state(600).emotion);
        engine.model("Small room", "小房间室内", .42f, 900);
        assertEquals(Emotion.CALM, engine.state(1000).emotion);
    }

    @Test public void lowPriorityTapCannotOverrideActiveAlarm() {
        BehaviorEngine engine = new BehaviorEngine(BehaviorEngine.Timing.defaults(),new Random(2),0);
        engine.setGuarding(true,0);
        SoundEvent alarm=event("alarm","Alarm",true), tap=event("tap","Tap",true);
        engine.events(Collections.singletonList(alarm),100);
        engine.events(java.util.Arrays.asList(alarm,tap),3000);
        assertEquals(Emotion.ALERT,engine.state(3100).emotion);
        engine.setGuarding(false,3200);
        assertFalse(engine.state(3300).guarding);
        assertEquals(Emotion.CALM,engine.state(3300).emotion);
    }

    @Test public void newSessionRejectsLateFramesFromPreviousSession() {
        long old=GuardianRuntime.begin();
        long latest=GuardianRuntime.begin();
        GuardianRuntime.Snapshot stopped=new GuardianRuntime.Snapshot(GuardianRuntime.Status.STOPPED,Collections.emptyList(),"","",0,-96,false);
        GuardianRuntime.Snapshot listening=new GuardianRuntime.Snapshot(GuardianRuntime.Status.LISTENING,Collections.emptyList(),"","",0,-96,false);
        assertTrue(GuardianRuntime.publish(latest,stopped));
        assertFalse(GuardianRuntime.publish(old,listening));
        assertEquals(GuardianRuntime.Status.STOPPED,GuardianRuntime.snapshot().status);
    }

    private SoundEvent event(String id,String label,boolean active) { return new SoundEvent(id,label,label,0,0,active); }
}

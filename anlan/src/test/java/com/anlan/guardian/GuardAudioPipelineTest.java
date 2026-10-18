package com.anlan.guardian;

import com.soundguardian.core.SoundContract;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class GuardAudioPipelineTest {
    @Test public void slowInferenceDoesNotBlockCaptureAndGapResetsEvidence() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),gap=new CountDownLatch(1),ended=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<>();AtomicInteger windows=new AtomicInteger();
        GuardAudioPipeline pipe=new GuardAudioPipeline(new GuardAudioPipeline.Processor(){
            public void start(){}
            public void window(float[] input,long at)throws Exception{if(windows.incrementAndGet()==1){entered.countDown();release.await();}}
            public void gap(long at){gap.countDown();}
            public void stop(){ended.countDown();}
        },error::set);
        try{
            short[] first=new short[SoundContract.INPUT_SAMPLES];pipe.accept(first,first.length,0,1000);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            long sequence=first.length,start=System.nanoTime();
            for(int i=0;i<100;i++){pipe.accept(new short[1600],1600,sequence,1100+i*100);sequence+=1600;}
            assertTrue("投递不应等待慢推理",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);
            assertTrue(pipe.pending()<=8);release.countDown();assertTrue(gap.await(2,TimeUnit.SECONDS));
        }finally{release.countDown();pipe.close();pipe.awaitStopped();}
        assertTrue(ended.await(1,TimeUnit.SECONDS));assertNull(error.get());
    }
    @Test public void captureBufferIsCopiedAndWindowStrideIsPreserved() throws Exception {
        CountDownLatch init=new CountDownLatch(1),ready=new CountDownLatch(2);AtomicReference<Throwable> error=new AtomicReference<>();
        GuardAudioPipeline pipe=new GuardAudioPipeline(new GuardAudioPipeline.Processor(){
            public void start()throws Exception{init.await();}
            public void window(float[] input,long at){assertEquals(.5f,input[0],.0001f);ready.countDown();}
            public void gap(long at){throw new AssertionError("连续输入不能丢帧");}
            public void stop(){}
        },error::set);
        try{
            short[] data=new short[SoundContract.INPUT_SAMPLES+SoundContract.HOP_SAMPLES];java.util.Arrays.fill(data,(short)16384);
            pipe.accept(data,data.length,0,2000);java.util.Arrays.fill(data,(short)0);init.countDown();
            assertTrue(ready.await(2,TimeUnit.SECONDS));
        }finally{init.countDown();pipe.close();pipe.awaitStopped();}
        assertNull(error.get());
    }
    @Test public void traceIsBoundedAndStoresOnlyNumericMetadata(){
        InteractionTrace.clear();for(int i=0;i<700;i++)InteractionTrace.record(InteractionTrace.Stage.CAPTURE,i,System.nanoTime(),1600);
        assertEquals(512,InteractionTrace.snapshot().size());assertEquals(188,InteractionTrace.snapshot().get(0).id);InteractionTrace.clear();
    }
}

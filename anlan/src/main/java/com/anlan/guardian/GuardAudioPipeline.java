package com.anlan.guardian;

import com.soundguardian.core.SoundContract;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 唯一采集流的有界守护分支；模型及事件资源只由此工作器访问。 */
final class GuardAudioPipeline implements AutoCloseable {
    interface Processor {
        void start() throws Exception;
        void window(float[] samples,long wallTime) throws Exception;
        void gap(long wallTime);
        void stop();
    }
    private static final class Chunk {
        final short[] samples; final long sequence,wallTime;
        Chunk(short[] samples,long sequence,long wallTime){this.samples=samples;this.sequence=sequence;this.wallTime=wallTime;}
    }
    private final ArrayBlockingQueue<Chunk> queue=new ArrayBlockingQueue<>(8);
    private final Thread worker;
    private volatile boolean closed;
    GuardAudioPipeline(Processor processor,Consumer<Throwable> failure) {
        worker=new Thread(()->{
            try {
                processor.start();
                float[] input=new float[SoundContract.INPUT_SAMPLES];int filled=0;long next=-1;
                while(!closed){
                    Chunk chunk=queue.poll(100,TimeUnit.MILLISECONDS);if(chunk==null)continue;
                    if(next>=0&&chunk.sequence!=next){filled=0;processor.gap(chunk.wallTime);InteractionTrace.record(InteractionTrace.Stage.GUARD_GAP,chunk.sequence,System.nanoTime(),1);}
                    next=chunk.sequence+chunk.samples.length;
                    for(int i=0;i<chunk.samples.length&&!closed;i++){
                        input[filled++]=chunk.samples[i]/32768f;
                        if(filled==input.length){
                            long at=chunk.wallTime-(chunk.samples.length-i-1)*1000L/SoundContract.SAMPLE_RATE;
                            long start=System.nanoTime();processor.window(input,at);
                            InteractionTrace.record(InteractionTrace.Stage.GUARD,chunk.sequence,start,input.length);
                            System.arraycopy(input,SoundContract.HOP_SAMPLES,input,0,SoundContract.OVERLAP_SAMPLES);filled=SoundContract.OVERLAP_SAMPLES;
                        }
                    }
                }
            }catch(InterruptedException stopped){if(!closed)failure.accept(stopped);}
            catch(Exception|LinkageError error){if(!closed)failure.accept(error);}
            finally{closed=true;queue.clear();Thread.interrupted();processor.stop();}
        },"anlan-sound-events");worker.start();
    }
    void accept(short[] samples,int count,long sequence,long wallTime){
        if(closed)return;
        if(count<1||count>samples.length)throw new IllegalArgumentException("守护音频长度不合法");
        Chunk chunk=new Chunk(Arrays.copyOf(samples,count),sequence,wallTime);
        if(!queue.offer(chunk)){queue.clear();queue.offer(chunk);}
    }
    int pending(){return queue.size();}
    @Override public void close(){closed=true;queue.clear();worker.interrupt();}
    void awaitStopped() throws InterruptedException {worker.join();}
}

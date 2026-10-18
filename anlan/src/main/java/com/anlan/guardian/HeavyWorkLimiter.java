package com.anlan.guardian;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 同时最多一个额外重型作业；不把YAMNet/KWS/VAD排在该许可之后。 */
final class HeavyWorkLimiter {
    private final Semaphore permit=new Semaphore(1,true);
    Lease acquire(BooleanSupplier active) throws InterruptedException {
        long start=System.nanoTime();
        while(active.getAsBoolean()){
            if(permit.tryAcquire(100,TimeUnit.MILLISECONDS)){
                if(!active.getAsBoolean()){permit.release();throw new CancellationException();}
                InteractionTrace.record(InteractionTrace.Stage.RESOURCE_WAIT,0,start,0);return new Lease();
            }
            if(System.nanoTime()-start>TimeUnit.SECONDS.toNanos(30))throw new IllegalStateException("语音资源繁忙，请稍后重试");
        }
        throw new CancellationException();
    }
    final class Lease implements AutoCloseable {
        private boolean released;
        @Override public void close(){if(!released){released=true;permit.release();}}
    }
}

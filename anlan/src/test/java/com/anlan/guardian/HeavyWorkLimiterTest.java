package com.anlan.guardian;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HeavyWorkLimiterTest {
    @Test public void cancelledWaiterDoesNotStealPermitAndFailureReleasesIt() throws Exception {
        HeavyWorkLimiter limiter=new HeavyWorkLimiter();AtomicBoolean active=new AtomicBoolean(true);
        CountDownLatch begun=new CountDownLatch(1),ended=new CountDownLatch(1);AtomicReference<Throwable> result=new AtomicReference<>();
        try(HeavyWorkLimiter.Lease first=limiter.acquire(()->true)){
            Thread waiter=new Thread(()->{begun.countDown();try(HeavyWorkLimiter.Lease ignored=limiter.acquire(active::get)){result.set(new AssertionError("取消任务不能取得许可"));}catch(Throwable error){result.set(error);}finally{ended.countDown();}});
            waiter.start();assertTrue(begun.await(1,TimeUnit.SECONDS));active.set(false);
            assertTrue(ended.await(2,TimeUnit.SECONDS));assertTrue(result.get() instanceof java.util.concurrent.CancellationException);
        }
        try(HeavyWorkLimiter.Lease ignored=limiter.acquire(()->true)){throw new IllegalStateException("模拟推理失败");}
        catch(IllegalStateException expected){assertEquals("模拟推理失败",expected.getMessage());}
        try(HeavyWorkLimiter.Lease ignored=limiter.acquire(()->true)){assertNotNull(ignored);}
    }
}

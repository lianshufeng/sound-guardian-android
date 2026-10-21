package com.anlan.guardian;

import android.app.ActivityManager;
import android.content.Context;

/** 红糖的设备调度预算；不改变共享模型、识别阈值或用户启用的能力。 */
final class ResourceBudget {
    private static final HeavyWorkLimiter HEAVY=new HeavyWorkLimiter();
    final boolean constrained;
    final long modelIdleMs;
    final int speechCacheBytes, speechCacheEntries=4;

    ResourceBudget(long totalBytes, boolean lowRam) {
        // 预留厂商内存统计差异，将物理内存约 4 GB 及以下设备归入节省档。
        constrained = lowRam || totalBytes <= 9L * 1024 * 1024 * 1024 / 2;
        modelIdleMs = constrained ? 10000 : 60000;
        speechCacheBytes=constrained?512*1024:2*1024*1024;
    }

    static ResourceBudget read(Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        return new ResourceBudget(memory.totalMem, manager.isLowRamDevice());
    }
    static <T> T heavy(Context context,long estimatedLoadBytes,java.util.function.BooleanSupplier active,
                       java.util.concurrent.Callable<T> operation) throws Exception {
        try(HeavyWorkLimiter.Lease ignored=HEAVY.acquire(active)){
            if(estimatedLoadBytes>0){
                ActivityManager.MemoryInfo memory=new ActivityManager.MemoryInfo();
                context.getSystemService(ActivityManager.class).getMemoryInfo(memory);
                // 保守初始估计，待真机P0校准；不把此准入检查当作不会OOM的保证。
                if(memory.lowMemory||memory.availMem-estimatedLoadBytes<Math.max(memory.threshold,128L*1024*1024))
                    throw new IllegalStateException("语音资源不足，请稍后重试");
            }
            return operation.call();
        }
    }
}

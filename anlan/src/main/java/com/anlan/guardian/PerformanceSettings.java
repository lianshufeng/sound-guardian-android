package com.anlan.guardian;

import android.content.Context;

/** 仅控制重型模型驻留，不改变倾听授权或推理并发。 */
final class PerformanceSettings {
    private PerformanceSettings() { }
    static boolean highPerformance(Context context) {
        return !"low_power".equals(context.getSharedPreferences("interaction", Context.MODE_PRIVATE)
                .getString("performance_mode", "high"));
    }
    static void save(Context context, boolean high) {
        context.getSharedPreferences("interaction", Context.MODE_PRIVATE).edit()
                .putString("performance_mode", high ? "high" : "low_power").apply();
    }
}

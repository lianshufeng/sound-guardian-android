package com.anlan.guardian;

import android.content.Context;
import android.content.SharedPreferences;
import com.soundguardian.core.AppConfig;
import org.json.JSONObject;

final class CareSettings {
    final SharedPreferences preferences;
    final long replyTimeoutMs, replyMaxMs, graceMs, sessionMaxMs, storageBytes, cooldownMs;
    final int replyMaxChars, recordsMax, recordsDays, repliesMax, repliesDays, hourlyMax, dailyMax;
    final long minimumGapMs, retryMs, recentInteractionMs, emotionWindowMs, emotionValidMs, safetyGapMs;
    final double coldTemperature;
    final double hotTemperature, temperatureDrop;
    final long snoozeMs;
    CareSettings(Context context) {
        preferences = context.getSharedPreferences("care", Context.MODE_PRIVATE);
        try {
            JSONObject json = new JSONObject(AppConfig.read(context, "care/policy.json"));
            replyTimeoutMs = json.getLong("reply_timeout_ms");
            replyMaxChars = json.getInt("reply_max_chars"); replyMaxMs = json.getLong("reply_max_ms");
            graceMs = json.getLong("decode_grace_ms"); sessionMaxMs = json.getLong("session_max_ms");
            recordsMax = json.getInt("records_max"); recordsDays = json.getInt("records_days");
            repliesMax = json.getInt("replies_max"); repliesDays = json.getInt("replies_days");
            storageBytes = json.getLong("storage_bytes"); cooldownMs = json.getLong("cooldown_ms");
            hourlyMax = json.getInt("hourly_max"); dailyMax = json.getInt("daily_max");
            minimumGapMs = json.getLong("minimum_gap_ms"); retryMs = json.getLong("retry_ms");
            recentInteractionMs = json.getLong("recent_interaction_ms"); emotionWindowMs = json.getLong("emotion_window_ms");
            emotionValidMs = json.getLong("emotion_valid_ms"); coldTemperature = json.getDouble("cold_temperature_c");
            safetyGapMs = json.getLong("safety_gap_ms");
            snoozeMs = json.getLong("snooze_ms"); hotTemperature = json.getDouble("hot_temperature_c"); temperatureDrop = json.getDouble("temperature_drop_c");
            if (replyTimeoutMs < 5000 || replyTimeoutMs > 60000
                    || replyMaxChars < 1 || replyMaxChars > 4000 || replyMaxMs < 1000 || replyMaxMs > 60000
                    || graceMs < 0 || graceMs > 15000 || sessionMaxMs < 60000 || sessionMaxMs > 300000
                    || recordsMax < 1 || recordsMax > 10000 || repliesMax < 1 || repliesMax > recordsMax
                    || recordsDays < 1 || repliesDays < 1 || repliesDays > recordsDays
                    || storageBytes < 65536 || storageBytes > 67108864 || cooldownMs < 60000
                    || hourlyMax < 1 || dailyMax < hourlyMax || minimumGapMs < 60000 || retryMs < 1000
                    || recentInteractionMs < 1000 || emotionValidMs < 1000 || emotionWindowMs < emotionValidMs
                    || !Double.isFinite(coldTemperature) || coldTemperature < -30 || coldTemperature > 30 || safetyGapMs < 10000
                    || snoozeMs < 60000 || snoozeMs > 86400000 || !Double.isFinite(hotTemperature) || !Double.isFinite(temperatureDrop)
                    || hotTemperature < coldTemperature || temperatureDrop < 1 || temperatureDrop > 30) throw new IllegalArgumentException("关怀参数越界");
        } catch (Exception error) { throw new IllegalStateException("关怀配置读取失败", error); }
    }
    boolean enabled() { return preferences.getBoolean("enabled", false); }
    boolean saveReplies() { return preferences.getBoolean("save_replies", false); }
}

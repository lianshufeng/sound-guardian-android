package com.anlan.guardian;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.soundguardian.core.AppConfig;
import com.soundguardian.core.VoiceUnderstandingResult;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.UUID;

/** 只读取已有契约快照；不在采集线程执行规则、网络或播报。 */
final class LocalCarePolicy implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CareSettings settings;
    private final JSONArray rules, emotions, safetyRules;
    private final WeatherRepository weather;
    private final Runnable weatherObserver = () -> { };
    private boolean closed, pending, watchingWeather;
    private long lastResult, lastEmotionAt, lastActivity = Long.MIN_VALUE / 2, lastPhraseId;
    private String emotion = "";
    private int emotionHits;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            try { evaluate(); }
            catch (RuntimeException error) { android.util.Log.w("AnlanCare", "本地关怀暂不可用", error); }
            main.postDelayed(this, 2000);
        }
    };
    LocalCarePolicy(Context context) {
        this(context, WeatherRepository.get(context));
    }
    LocalCarePolicy(Context context, WeatherRepository weatherRepository) {
        settings = new CareSettings(context); weather = weatherRepository;
        try {
            JSONObject json = new JSONObject(AppConfig.read(context, "care/scenarios.json"));
            rules = json.getJSONArray("events"); emotions = json.getJSONArray("emotions");
            safetyRules = json.getJSONArray("safety");
        } catch (Exception error) { throw new IllegalStateException("关怀场景配置读取失败", error); }
        main.post(tick);
    }
    private boolean group(String name) { return settings.preferences.getBoolean("group_" + name, false); }
    void evaluate() {
        boolean wantWeather = settings.enabled() && group("weather");
        if (wantWeather != watchingWeather) {
            watchingWeather = wantWeather;
            if (wantWeather) weather.start(weatherObserver); else weather.stop(weatherObserver);
        }
        GuardianRuntime.Snapshot snapshot = GuardianRuntime.snapshot();
        InteractionPorts.UnderstandingState understanding = GuardianRuntime.understandingSnapshot();
        long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        if (snapshot.activity) lastActivity = elapsed;
        VoiceUnderstandingResult result = understanding.result;
        if (result != null && result.id == Conversation.consumedResult()) { lastResult = result.id; lastPhraseId = result.id; result = null; }
        if (result != null && result.id > lastResult && elapsed >= result.at && elapsed - result.at < settings.emotionValidMs) {
            lastResult = result.id;
            String value = result.emotion.replace("<|", "").replace("|>", "").trim();
            emotionHits = value.equals(emotion) && elapsed - lastEmotionAt < settings.emotionWindowMs ? emotionHits + 1 : 1;
            emotion = value; lastEmotionAt = result.at;
        }
        if (!settings.enabled()) { if (!Care.activeId().isEmpty()) Care.cancel(Care.activeId()); return; }
        if (result != null && result.id != lastPhraseId && elapsed >= result.at && elapsed - result.at < settings.emotionValidMs) {
            String control = CareSession.normalize(result.text);
            if (control.equals("停止关怀") || control.equals("不要提醒了")) {
                lastPhraseId = result.id; settings.preferences.edit().putBoolean("enabled", false).apply();
                if (!Care.activeId().isEmpty()) Care.cancel(Care.activeId()); return;
            }
            if (control.equals("今天晚点睡") || control.equals("今晚晚点睡")) {
                lastPhraseId = result.id;
                String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(now));
                settings.preferences.edit().putString("bedtime_skip_day", day).apply(); return;
            }
        }
        if (snapshot.status == GuardianRuntime.Status.LISTENING && !pending && Care.activeId().isEmpty() && group("safety")) {
            java.util.List<SoundEvent> safetyEvents = new java.util.ArrayList<>(snapshot.events);
            safetyEvents.sort(java.util.Comparator.comparingInt(SoundEvent::priority).reversed()
                    .thenComparingInt(event -> event.label.equals("Alarm") ? 1 : 0));
            for (SoundEvent event : safetyEvents) {
                if (!event.active || event.priority() < 3) continue;
                for (int i = 0; i < safetyRules.length(); i++) {
                    JSONObject rule = safetyRules.optJSONObject(i);
                    if (rule == null || !event.label.equals(rule.optString("label"))) continue;
                    String id = rule.optString("id");
                    if (event.id.equals(settings.preferences.getString("safety_event." + id, ""))
                            || now < settings.preferences.getLong("last_safety", 0) + settings.safetyGapMs
                                    && event.priority() <= settings.preferences.getInt("last_safety_priority", 0)
                            || now < settings.preferences.getLong("retry." + id, 0)) continue;
                    send(id, event.id, rule.optString("text"), InteractionPorts.CareRequest.Mode.NOTICE, now, true); return;
                }
            }
        }
        if (snapshot.status != GuardianRuntime.Status.LISTENING || pending || !Care.activeId().isEmpty() || Conversation.active()
                || understanding.voice && elapsed - understanding.at < 2000 || elapsed - Care.lastInteractionAt() < settings.recentInteractionMs
                || snapshot.events.stream().anyMatch(value -> value.active && value.priority() >= 3)) return;
        GuardianRuntime.SpeechPhase phase = GuardianRuntime.speechSnapshot().phase;
        if (phase == GuardianRuntime.SpeechPhase.PREPARING || phase == GuardianRuntime.SpeechPhase.PLAYING || phase == GuardianRuntime.SpeechPhase.WAITING) return;
        Calendar local = Calendar.getInstance(); int minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
        int quietStart = settings.preferences.getInt("quiet_start", 22 * 60), quietEnd = settings.preferences.getInt("quiet_end", 7 * 60);
        boolean quiet = settings.preferences.getBoolean("quiet_enabled", true) && inPeriod(minute, quietStart, quietEnd);
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(now));
        // 用户明确设置的就寝提醒允许在安静时段边界执行一次，仍不在没有活动证据时叫醒用户。
        if (group("schedule") && settings.preferences.getBoolean("bedtime_enabled", false)) {
            int bedtime = settings.preferences.getInt("bedtime", 22 * 60);
            if (!today.equals(settings.preferences.getString("bedtime_skip_day", ""))
                    && (minute - bedtime + 1440) % 1440 < 5 && elapsed - lastActivity < 15000 && allowed("S18", now)) {
                send("S18", "", "到你设定的休息时间了，要不要准备休息？", InteractionPorts.CareRequest.Mode.ASK, now); return;
            }
        }
        boolean goodnight = group("companion") && result != null && elapsed >= result.at && elapsed - result.at < settings.emotionValidMs
                && (CareSession.normalize(result.text).equals("晚安") || CareSession.normalize(result.text).equals("我要睡了"));
        if (quiet && !goodnight) return;
        if (now < settings.preferences.getLong("snooze_until", 0) && !goodnight) return;
        if (result != null && result.id != lastPhraseId && elapsed >= result.at && elapsed - result.at < settings.emotionValidMs) {
            if (group("companion") && allowed("S64", now) && (CareSession.normalize(result.text).equals("晚安") || CareSession.normalize(result.text).equals("我要睡了"))) {
                lastPhraseId = result.id;
                send("S64", "", "晚安，我会安静陪着你。", InteractionPorts.CareRequest.Mode.NOTICE, now); return;
            }
            lastPhraseId = result.id;
            String phrase = CareSession.normalize(result.text);
            if (group("companion")) {
                if ((phrase.equals("我回来了") || phrase.equals("我到家了")) && allowed("S58", now)) {
                    send("S58", "", "欢迎回来，要不要歇一会儿？", InteractionPorts.CareRequest.Mode.ASK, now); return;
                }
                if ((phrase.equals("我好无聊") || phrase.equals("好无聊")) && allowed("S60", now)) {
                    send("S60", "", "想听一个小故事，还是让我安静陪着？", InteractionPorts.CareRequest.Mode.ASK, now); return;
                }
                if ((phrase.equals("陪我聊聊") || phrase.equals("我想找人说话")) && allowed("S61", now)) {
                    send("S61", "", "我在，你想说些什么？", InteractionPorts.CareRequest.Mode.ASK, now); return;
                }
                if ((phrase.equals("陪我放松一下") || phrase.equals("我想放松一下")) && allowed("S63", now)) {
                    send("S63", "", "先放下手边的事，按你舒服的节奏休息一会儿。", InteractionPorts.CareRequest.Mode.NOTICE, now); return;
                }
            }
        }
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i); if (rule == null || !group(rule.optString("group"))) continue;
            String event = CareEvidence.match(snapshot.events, rule.optString("label"), rule.optInt("hits"), rule.optLong("window_ms"), rule.optLong("duration_ms"), now);
            if (event.isEmpty() || !allowed(rule.optString("id"), now)) continue;
            String text = rule.optString("text"); String id = rule.optString("id");
            if (id.equals("S02") && group("weather")) {
                WeatherForecast forecast = weather.snapshot(); WeatherForecast.Hour hour = forecast == null ? null : forecast.current(now);
                if (hour != null && Double.isFinite(hour.temperature) && hour.temperature < settings.coldTemperature) {
                    id = "S03"; text = "听到几声咳嗽，需要歇一会儿吗？外面有些冷，出门记得添衣。";
                    if (!allowed(id, now)) continue;
                }
            }
            send(id, event, text, InteractionPorts.CareRequest.Mode.valueOf(rule.optString("mode")), now); return;
        }
        if (group("emotion") && emotionHits >= 2 && elapsed - lastEmotionAt < settings.emotionValidMs) {
            for (int i = 0; i < emotions.length(); i++) {
                JSONObject rule = emotions.optJSONObject(i);
                if (rule != null && emotion.equals(rule.optString("label")) && allowed(rule.optString("id"), now)) {
                    send(rule.optString("id"), "", rule.optString("text"), InteractionPorts.CareRequest.Mode.ASK, now); return;
                }
            }
        }
        if (group("weather") && result != null && elapsed >= result.at && elapsed - result.at < settings.emotionValidMs && goingOut(result.text)) {
            WeatherForecast forecast = weather.snapshot();
            if (forecast == null || forecast.current(now) == null) return;
            for (WeatherForecast.Hour hour : forecast.hours) {
                if (hour.at + WeatherForecast.HOUR_MS <= now) continue;
                if (hour.condition == WeatherForecast.Condition.RAIN && allowed("S28", now)) {
                    send("S28", "", "你准备出门的话，记得带伞。", InteractionPorts.CareRequest.Mode.NOTICE, now); return;
                }
                if (hour.condition == WeatherForecast.Condition.THUNDERSTORM && allowed("S29", now)) {
                    send("S29", "", "近期预报有雷雨，出门前请留意天气。", InteractionPorts.CareRequest.Mode.NOTICE, now); return;
                }
                if (hour.condition == WeatherForecast.Condition.SNOW && allowed("S30", now)) {
                    send("S30", "", "近期预报有雨雪，出门留意路面。", InteractionPorts.CareRequest.Mode.NOTICE, now); return;
                }
                if (hour.condition == WeatherForecast.Condition.FOG && allowed("S31", now)) {
                    send("S31", "", "近期预报有雾，出行请多留意。", InteractionPorts.CareRequest.Mode.NOTICE, now); return;
                }
            }
            WeatherForecast.Hour hour = forecast.current(now);
            if (Double.isFinite(hour.temperature) && hour.temperature < settings.coldTemperature && allowed("S26", now))
                send("S26", "", "外面有些冷，出门记得添衣。", InteractionPorts.CareRequest.Mode.NOTICE, now);
            else if (Double.isFinite(hour.apparentTemperature) && hour.apparentTemperature >= settings.hotTemperature && allowed("S27", now))
                send("S27", "", "外面体感比较热，出门留意防暑。", InteractionPorts.CareRequest.Mode.NOTICE, now);
            else if (Double.isFinite(hour.temperature) && allowed("S26", now)) for (WeatherForecast.Hour future : forecast.hours) {
                if (future.at > now && Double.isFinite(future.temperature) && hour.temperature - future.temperature >= settings.temperatureDrop) {
                    send("S26", "", "接下来几小时可能降温，出门留意添衣。", InteractionPorts.CareRequest.Mode.NOTICE, now); break;
                }
            }
        }
    }
    static boolean inPeriod(int now, int start, int end) { return start < end ? now >= start && now < end : start > end && (now >= start || now < end); }
    static boolean goingOut(String text) {
        String value = CareSession.normalize(text);
        return value.equals("我要出门") || value.equals("我要出门了") || value.equals("准备出门") || value.equals("我要出去");
    }
    private boolean allowed(String id, long now) {
        if (id.equals("S03") && now < settings.preferences.getLong("last.S02", 0) + settings.cooldownMs) return false;
        if (now < settings.preferences.getLong("retry." + id, 0)
                || now < settings.preferences.getLong("last_global", 0) + settings.minimumGapMs) return false;
        if (now < settings.preferences.getLong("last." + id, 0) + settings.cooldownMs) return false;
        long hour = now / 3600000L;
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(now));
        return (!day.equals(settings.preferences.getString("budget_day", "")) || settings.preferences.getInt("budget_day_count", 0) < settings.dailyMax)
                && (hour != settings.preferences.getLong("budget_hour", -1) || settings.preferences.getInt("budget_hour_count", 0) < settings.hourlyMax);
    }
    private void send(String id, String event, String text, InteractionPorts.CareRequest.Mode mode, long now) {
        send(id, event, text, mode, now, false);
    }
    private void send(String id, String event, String text, InteractionPorts.CareRequest.Mode mode, long now, boolean safety) {
        pending = true;
        InteractionPorts.CareRequest request = new InteractionPorts.CareRequest(UUID.randomUUID().toString(), id, event, text,
                mode, mode == InteractionPorts.CareRequest.Mode.ASK ? settings.replyTimeoutMs : 0, now + 15000, safety);
        try {
            Care.submit(request, new Care.Listener() {
                private boolean charged;
                @Override public void onState(String careId, String state) {
                    if (!state.equals("SPEAKING") || charged) return;
                    charged = true; long at = System.currentTimeMillis(), hour = at / 3600000L;
                    if (safety) {
                        settings.preferences.edit().putString("safety_event." + id, event).putLong("last_safety", at)
                                .putInt("last_safety_priority", id.equals("S36") ? 3 : 4).apply(); return;
                    }
                    String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(at));
                    int dayCount = day.equals(settings.preferences.getString("budget_day", "")) ? settings.preferences.getInt("budget_day_count", 0) : 0;
                    int hourCount = hour == settings.preferences.getLong("budget_hour", -1) ? settings.preferences.getInt("budget_hour_count", 0) : 0;
                    android.content.SharedPreferences.Editor edit = settings.preferences.edit().putLong("last." + id, at).putLong("last_global", at)
                            .putString("budget_day", day).putInt("budget_day_count", dayCount + 1).putLong("budget_hour", hour).putInt("budget_hour_count", hourCount + 1);
                    if (id.equals("S03")) edit.putLong("last.S02", at);
                    edit.apply();
                }
                @Override public void onResult(Care.Result result) {
                    pending = false;
                    if (closed) return;
                    if (id.equals("S64") && result.status == Care.Status.COMPLETED)
                        settings.preferences.edit().putLong("snooze_until", System.currentTimeMillis() + settings.snoozeMs).apply();
                    String phrase = CareSession.normalize(result.replyText);
                    if (phrase.equals("今天晚点睡") || phrase.equals("今晚晚点睡")) {
                        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
                        settings.preferences.edit().putString("bedtime_skip_day", day).apply();
                    }
                    if (phrase.equals("停止关怀") || phrase.equals("不要提醒了")) settings.preferences.edit().putBoolean("enabled", false).apply();
                    if (result.replyIntent.equals("SNOOZE")) settings.preferences.edit().putLong("snooze_until", System.currentTimeMillis() + settings.snoozeMs).apply();
                    if (result.replyIntent.equals("DECLINE") || result.replyIntent.equals("BACKGROUND")) settings.preferences.edit().putLong("last." + id, System.currentTimeMillis()).apply();
                    if (!charged) settings.preferences.edit().putLong("retry." + id, System.currentTimeMillis() + settings.retryMs).apply();
                }
            });
        } catch (RuntimeException error) { pending = false; }
    }
    @Override public void close() { closed = true; main.removeCallbacks(tick); if (watchingWeather) weather.stop(weatherObserver); }
}

package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.VoiceUnderstandingResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 确定性上下文驱动真实本地策略和关怀协调器；不宣称声音/情绪模型准确率。 */
@RunWith(AndroidJUnit4.class)
public final class CareScenarioAcceptanceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context application = instrumentation.getTargetContext();
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void await(BooleanSupplier condition) {
        long until = SystemClock.elapsedRealtime() + 8000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10);
        assertTrue("等待验收条件超时", condition.getAsBoolean());
    }
    private void record(String name, String expectation) {
        Bundle bundle = new Bundle(); bundle.putString("care_case", name + " | " + expectation); instrumentation.sendStatus(0, bundle);
    }
    private final class Fixture implements AutoCloseable, CareCoordinator.Host {
        final CareAcceptanceContext context = new CareAcceptanceContext(application);
        final SharedPreferences prefs = context.getSharedPreferences("care", 0);
        final CopyOnWriteArrayList<InteractionPorts.SpeechRequest> spoken = new CopyOnWriteArrayList<>();
        final long generation;
        long speechLease;
        CareCoordinator coordinator;
        LocalCarePolicy policy;
        WeatherRepository weather;
        InteractionPorts.SpeechInput.Result input;
        Fixture(boolean withWeather) throws Exception {
            prefs.edit().putBoolean("enabled", true).putBoolean("quiet_enabled", false).commit();
            generation = GuardianRuntime.begin();
            publish(Collections.emptyList(), false, null);
            main(() -> {
                if (withWeather) {
                    WeatherProvider offline = new WeatherProvider() {
                        @Override public WeatherForecast forecast(WeatherForecast.Place place) throws Exception { throw new java.io.IOException("验收中不请求公网"); }
                        @Override public List<WeatherForecast.Place> searchCities(String query) { return Collections.emptyList(); }
                    };
                    weather = new WeatherRepository((android.app.Application)application.getApplicationContext(), offline, context.getSharedPreferences("weather", 0));
                }
                speechLease = SpeechPlayback.attach(request -> { spoken.add(request); request.observer.onStart(); });
                coordinator = new CareCoordinator(context, this);
                policy = new LocalCarePolicy(context, weather);
            });
        }
        void groups(String... names) { SharedPreferences.Editor edit = prefs.edit(); for (String name : names) edit.putBoolean("group_" + name, true); edit.commit(); }
        void publish(List<SoundEvent> events, boolean activity, VoiceUnderstandingResult result) {
            GuardianRuntime.publish(generation, new GuardianRuntime.Snapshot(GuardianRuntime.Status.LISTENING, events, "", "", 1, -45, activity));
            GuardianRuntime.publishUnderstanding(generation, new InteractionPorts.UnderstandingState("验收输入", false, SystemClock.elapsedRealtime(), result, 0));
        }
        void evaluate() { main(policy::evaluate); }
        void weather(WeatherForecast.Condition condition, double temperature, double apparent, boolean expired, Double futureTemperature) throws Exception {
            long at = System.currentTimeMillis() / WeatherForecast.HOUR_MS * WeatherForecast.HOUR_MS;
            org.json.JSONObject place = new org.json.JSONObject().put("name", "验收城市").put("lat", 30).put("lon", 120);
            org.json.JSONArray hours = new org.json.JSONArray();
            hours.put(new org.json.JSONObject().put("at", expired ? at - WeatherForecast.HOUR_MS : at).put("condition", condition.name()).put("day", true)
                    .put("temperature", temperature).put("apparent_temperature", apparent));
            if (futureTemperature != null) hours.put(new org.json.JSONObject().put("at", at + WeatherForecast.HOUR_MS).put("condition", condition.name()).put("day", true)
                    .put("temperature", futureTemperature).put("apparent_temperature", futureTemperature));
            String cache = new org.json.JSONObject().put("place", place).put("source", "验收固定预报").put("source_url", "").put("hours", hours).toString();
            SharedPreferences weatherPrefs = context.getSharedPreferences("weather", 0);
            weatherPrefs.edit().putString("forecast", cache).putString("manual_place", place.toString()).putLong("last_attempt", System.currentTimeMillis()).commit();
            main(() -> {
                policy.close();
                weather = new WeatherRepository((android.app.Application)application.getApplicationContext(), new WeatherProvider() {
                    @Override public WeatherForecast forecast(WeatherForecast.Place value) throws Exception { throw new java.io.IOException("验收中不请求公网"); }
                    @Override public List<WeatherForecast.Place> searchCities(String query) { return Collections.emptyList(); }
                }, weatherPrefs);
                policy = new LocalCarePolicy(context, weather);
            });
        }
        String scenario() {
            await(() -> !spoken.isEmpty());
            try (EventStore store = new EventStore(context); android.database.Cursor row = store.getReadableDatabase().rawQuery("SELECT scenario FROM care_records WHERE id=?", new String[]{spoken.get(spoken.size() - 1).careId})) {
                assertTrue(row.moveToFirst()); return row.getString(0);
            }
        }
        void assertSilent() { instrumentation.waitForIdleSync(); assertTrue("不应产生语音关怀", spoken.isEmpty()); }
        void complete() {
            InteractionPorts.SpeechRequest request = spoken.get(spoken.size() - 1); main(request.observer::onComplete);
            await(() -> input != null || Care.phase().equals("IDLE"));
            if (input != null) { main(() -> Care.respond(request.careId, "我没事")); }
            await(() -> Care.phase().equals("IDLE"));
        }
        @Override public boolean available() { return true; }
        @Override public boolean blocked() { return false; }
        @Override public String admission(InteractionPorts.CareRequest request) {
            return System.currentTimeMillis() < prefs.getLong("snooze_until", 0) ? "SNOOZED" : "";
        }
        @Override public void startInput(InteractionPorts.SpeechInput.Result value, long timeout) { input = value; }
        @Override public void stopInput() { input = null; }
        @Override public void stopSpeech(String id) { }
        @Override public void close() {
            main(() -> { policy.close(); coordinator.close(); SpeechPlayback.detach(speechLease); });
            await(coordinator::released); context.close();
            long stopped = GuardianRuntime.begin(); GuardianRuntime.publish(stopped, new GuardianRuntime.Snapshot(GuardianRuntime.Status.STOPPED, Collections.emptyList(), "", "", 0, -96, false));
        }
    }
    private List<SoundEvent> events(String label, int hits, boolean active, long duration) {
        long now = System.currentTimeMillis(); List<SoundEvent> values = new ArrayList<>();
        for (int i = 0; i < hits; i++) values.add(new SoundEvent("e" + i, label, label, now - 2000 - duration - i * 4000, now - 2000 - i * 4000, active));
        return values;
    }
    private VoiceUnderstandingResult voice(String text, String emotion, long id) {
        return new VoiceUnderstandingResult(id, SystemClock.elapsedRealtime(), text, emotion, "zh", "Speech", 1000, 20);
    }
    @Test public void allConfiguredBodyFamilyAndCryRules() throws Exception {
        String[][] cases = {{"Cough","body","S02","3"},{"Throat clearing","body","S05","3"},{"Sneeze","body","S06","3"},
                {"Groan","body","S07","2"},{"Wail, moan","body","S07","2"},{"Crying, sobbing","emotion","S10","2"},
                {"Knock","family","S41","3"},{"Baby cry, infant cry","family","S43","2"}};
        for (String[] value : cases) try (Fixture fixture = new Fixture(false)) {
            fixture.groups(value[1]); fixture.publish(events(value[0], Integer.parseInt(value[3]), false, 1000), false, null); fixture.evaluate();
            assertEquals(value[2], fixture.scenario()); fixture.complete(); record(value[2] + ":" + value[0], "已确认事件→统一关怀→终态");
        }
    }
    @Test public void isolatedDuplicateExpiredAndDisabledEventsStaySilent() throws Exception {
        for (String label : new String[]{"Cough", "Throat clearing", "Sneeze", "Knock"}) try (Fixture fixture = new Fixture(false)) {
            fixture.groups("body", "family"); SoundEvent event = events(label, 1, false, 1000).get(0);
            fixture.publish(Arrays.asList(event, event, event), false, null); fixture.evaluate(); fixture.assertSilent(); record("NEG:" + label, "同事件重复帧不累计命中");
        }
        try (Fixture fixture = new Fixture(false)) {
            fixture.publish(events("Cough", 3, false, 1000), false, null); fixture.evaluate(); fixture.assertSilent();
            fixture.groups("body"); fixture.publish(Collections.singletonList(new SoundEvent("old", "Cough", "咳嗽", 1, 2, false)), false, null); fixture.evaluate(); fixture.assertSilent();
        }
    }
    @Test public void sustainedSoundsNeedNoArtificialSecondEvent() throws Exception {
        for (String[] value : new String[][]{{"Groan","body","S07"},{"Crying, sobbing","emotion","S10"},{"Baby cry, infant cry","family","S43"}})
            try (Fixture fixture = new Fixture(false)) {
                fixture.groups(value[1]); fixture.publish(events(value[0], 1, true, 7000), false, null); fixture.evaluate();
                assertEquals(value[2], fixture.scenario()); fixture.complete(); record(value[2] + ":sustained", "单次持续证据生效");
            }
    }
    @Test public void allEmotionLabelsRequireFreshConsecutiveEvidence() throws Exception {
        for (String[] value : new String[][]{{"SAD","S09"},{"ANGRY","S11"},{"FEARFUL","S12"}}) try (Fixture fixture = new Fixture(false)) {
            fixture.groups("emotion"); fixture.publish(Collections.emptyList(), false, voice("你好", "<|" + value[0] + "|>", 1)); fixture.evaluate(); fixture.assertSilent();
            fixture.publish(Collections.emptyList(), false, voice("我想说会儿话", value[0], 2)); fixture.evaluate(); assertEquals(value[1], fixture.scenario()); fixture.complete();
            record(value[1], "连续声音情绪→询问，保留原标签");
        }
        for (String label : new String[]{"NEUTRAL", "HAPPY", "SURPRISED", "DISGUSTED"}) try (Fixture fixture = new Fixture(false)) {
            fixture.groups("emotion"); fixture.publish(Collections.emptyList(), false, voice("你好", label, 1)); fixture.evaluate();
            fixture.publish(Collections.emptyList(), false, voice("你好", label, 2)); fixture.evaluate(); fixture.assertSilent(); record("EMOTION:" + label, "未配置主动询问时保持静默");
        }
    }
    @Test public void allWeatherAndCombinedCoughBranches() throws Exception {
        Object[][] cases = {{WeatherForecast.Condition.CLEAR, 5d, 5d, null, "S26"}, {WeatherForecast.Condition.CLEAR, 35d, 36d, null, "S27"},
                {WeatherForecast.Condition.RAIN, 20d, 20d, null, "S28"}, {WeatherForecast.Condition.THUNDERSTORM, 20d, 20d, null, "S29"},
                {WeatherForecast.Condition.SNOW, 0d, 0d, null, "S30"}, {WeatherForecast.Condition.FOG, 20d, 20d, null, "S31"},
                {WeatherForecast.Condition.CLEAR, 25d, 25d, 15d, "S26"}};
        for (Object[] value : cases) try (Fixture fixture = new Fixture(true)) {
            fixture.weather((WeatherForecast.Condition)value[0], (double)value[1], (double)value[2], false, (Double)value[3]);
            fixture.groups("weather"); fixture.publish(Collections.emptyList(), false, voice("我要出门了", "NEUTRAL", 1)); fixture.evaluate();
            assertEquals(value[4], fixture.scenario()); fixture.complete(); record(value[4] + ":" + value[0], "有效预报与明确出行意图");
        }
        try (Fixture fixture = new Fixture(true)) {
            fixture.weather(WeatherForecast.Condition.CLEAR, 5, 5, false, null); fixture.groups("body", "weather");
            fixture.publish(events("Cough", 3, false, 1000), false, null); fixture.evaluate(); assertEquals("S03", fixture.scenario()); fixture.complete(); record("S03", "低温与反复咳嗽合并一次关怀");
        }
    }
    @Test public void unavailableWeatherAndNegatedIntentsDoNotInventAdvice() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.weather(WeatherForecast.Condition.RAIN, 5, 5, false, null); fixture.groups("weather");
            fixture.publish(Collections.emptyList(), false, new VoiceUnderstandingResult(1, SystemClock.elapsedRealtime() + 60000,
                    "我要出门了", "NEUTRAL", "zh", "Speech", 1000, 20)); fixture.evaluate(); fixture.assertSilent();
        }
        try (Fixture fixture = new Fixture(true)) {
            fixture.weather(WeatherForecast.Condition.RAIN, 5, 5, true, null); fixture.groups("weather");
            fixture.publish(Collections.emptyList(), false, voice("我要出门了", "NEUTRAL", 1)); fixture.evaluate(); fixture.assertSilent();
        }
        for (String phrase : new String[]{"我不准备出门", "电视里说我要出门", "我不想出去"}) try (Fixture fixture = new Fixture(true)) {
            fixture.weather(WeatherForecast.Condition.RAIN, 5, 5, false, null); fixture.groups("weather");
            fixture.publish(Collections.emptyList(), false, voice(phrase, "NEUTRAL", 1)); fixture.evaluate(); fixture.assertSilent();
        }
        record("WEATHER:negative", "过期预报和否定/转述不触发");
    }
    @Test public void allCompanionPhrasesAndGoodnightPauseAfterPlayback() throws Exception {
        for (String[] value : new String[][]{{"我回来了","S58"},{"我好无聊","S60"},{"陪我聊聊","S61"},{"陪我放松一下","S63"},{"晚安","S64"}})
            try (Fixture fixture = new Fixture(false)) {
                fixture.groups("companion"); fixture.publish(Collections.emptyList(), false, voice(value[0], "NEUTRAL", 1)); fixture.evaluate();
                assertEquals(value[1], fixture.scenario());
                if (value[1].equals("S64")) assertEquals(0, fixture.prefs.getLong("snooze_until", 0));
                fixture.complete(); if (value[1].equals("S64")) assertTrue(fixture.prefs.getLong("snooze_until", 0) > System.currentTimeMillis());
                record(value[1], "有限短语走统一播报/回复");
            }
    }
    @Test public void allSafetyLabelsDeduplicateSameActiveEvent() throws Exception {
        for (String[] value : new String[][]{{"Smoke detector, smoke alarm","S33"},{"Fire alarm","S34"},{"Alarm","S35"},{"Shatter","S36"},{"Smash, crash","S36"}})
            try (Fixture fixture = new Fixture(false)) {
                fixture.groups("safety"); fixture.publish(events(value[0], 1, true, 2000), false, null); fixture.evaluate();
                assertEquals(value[1], fixture.scenario()); fixture.complete(); fixture.evaluate(); instrumentation.waitForIdleSync(); assertEquals(1, fixture.spoken.size());
                record(value[1] + ":" + value[0], "同一活动事件不反复播报");
            }
    }
    @Test public void quotasQuietSnoozeAndColdContextSwitchStayBounded() throws Exception {
        for (String limit : new String[]{"hour", "day", "gap", "cooldown", "quiet", "snooze"}) try (Fixture fixture = new Fixture(false)) {
            fixture.groups("body"); long now = System.currentTimeMillis();
            if (limit.equals("hour")) fixture.prefs.edit().putLong("budget_hour", now / 3600000).putInt("budget_hour_count", 3).commit();
            if (limit.equals("day")) fixture.prefs.edit().putString("budget_day", new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date(now))).putInt("budget_day_count", 8).commit();
            if (limit.equals("gap")) fixture.prefs.edit().putLong("last_global", now).commit();
            if (limit.equals("cooldown")) fixture.prefs.edit().putLong("last.S02", now).commit();
            if (limit.equals("snooze")) fixture.prefs.edit().putLong("snooze_until", now + 60000).commit();
            if (limit.equals("quiet")) { Calendar local = Calendar.getInstance(); int minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE); fixture.prefs.edit().putBoolean("quiet_enabled", true).putInt("quiet_start", minute).putInt("quiet_end", (minute + 3) % 1440).commit(); }
            fixture.publish(events("Cough", 3, false, 1000), false, null); fixture.evaluate(); fixture.assertSilent(); record("LIMIT:" + limit, "不产生额外关怀");
        }
        try (Fixture fixture = new Fixture(true)) {
            fixture.weather(WeatherForecast.Condition.CLEAR, 5, 5, false, null); fixture.groups("body", "weather");
            fixture.prefs.edit().putLong("last.S02", System.currentTimeMillis()).commit(); fixture.publish(events("Cough", 3, false, 1000), false, null);
            fixture.evaluate(); fixture.assertSilent(); record("S02→S03", "天气切换不绕过同一咳嗽冷却");
        }
    }
    @Test public void stopAndTonightOverrideWorkDuringQuietAndSnooze() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.prefs.edit().putBoolean("quiet_enabled", true).putInt("quiet_start", 0).putInt("quiet_end", 1439).putLong("snooze_until", System.currentTimeMillis() + 60000).commit();
            fixture.publish(Collections.emptyList(), false, voice("停止关怀", "NEUTRAL", 1)); fixture.evaluate(); assertFalse(fixture.prefs.getBoolean("enabled", true)); fixture.assertSilent();
        }
        try (Fixture fixture = new Fixture(false)) {
            fixture.groups("schedule"); fixture.publish(Collections.emptyList(), false, voice("今晚晚点睡", "NEUTRAL", 1)); fixture.evaluate();
            assertFalse(fixture.prefs.getString("bedtime_skip_day", "").isEmpty()); fixture.assertSilent();
        }
        record("CONTROL:quiet", "停止和今晚覆盖独立于普通提醒抑制");
    }
    @Test public void bedtimeRequiresRealRecentActivity() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            Calendar now = Calendar.getInstance(); fixture.groups("schedule");
            fixture.prefs.edit().putBoolean("bedtime_enabled", true).putInt("bedtime", now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)).commit();
            fixture.publish(Collections.emptyList(), false, null); fixture.evaluate(); fixture.assertSilent();
            fixture.publish(Collections.emptyList(), true, null); fixture.evaluate(); assertEquals("S18", fixture.scenario()); fixture.complete(); record("S18", "只有实际近期活动才发起就寝询问");
        }
    }
    @Test public void allEightUserPlansCanRunInSameMinuteWithoutStarvation() throws Exception {
        CareSchedules[] scheduler = new CareSchedules[1];
        try (Fixture fixture = new Fixture(false)) {
            Calendar now = Calendar.getInstance(); int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            for (int id = 49; id <= 56; id++) CareSchedules.add(fixture.context, "S" + id, "用户设置的提醒 " + id, minute, 127);
            List<CareSchedules.Plan> plans = CareSchedules.list(fixture.context);
            main(() -> scheduler[0] = new CareSchedules(fixture.context)); fixture.groups("schedule");
            for (int i = 0; i < 8; i++) {
                int before = fixture.spoken.size(); main(() -> scheduler[0].due(plans)); await(() -> fixture.spoken.size() > before);
                String id = fixture.scenario(); assertTrue(id, Integer.parseInt(id.substring(1)) >= 49 && Integer.parseInt(id.substring(1)) <= 56);
                fixture.complete(); record(id, "用户计划真实提交并完成；同分钟不重复且不阻塞其他计划");
            }
            main(() -> scheduler[0].due(plans)); instrumentation.waitForIdleSync(); assertEquals(8, fixture.spoken.size());
        } finally { main(() -> { if (scheduler[0] != null) scheduler[0].close(); }); }
    }
    @Test public void failedPlanDoesNotBlockNextAndWrongDayDoesNotRun() throws Exception {
        CareSchedules[] scheduler = new CareSchedules[1];
        try (Fixture fixture = new Fixture(false)) {
            Calendar now = Calendar.getInstance(); int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int otherDay = (now.get(Calendar.DAY_OF_WEEK) + 6) % 7;
            CareSchedules.Plan wrong = new CareSchedules.Plan("wrong-day", "S49", "不应播放", minute, 1 << otherDay, "");
            main(() -> scheduler[0] = new CareSchedules(fixture.context)); fixture.groups("schedule");
            main(() -> scheduler[0].due(Collections.singletonList(wrong))); fixture.assertSilent();
            CareSchedules.add(fixture.context, "S49", "第一条失败", minute, 127); CareSchedules.add(fixture.context, "S50", "第二条仍执行", minute, 127);
            List<CareSchedules.Plan> plans = CareSchedules.list(fixture.context); main(() -> scheduler[0].due(plans)); await(() -> fixture.spoken.size() == 1);
            main(() -> fixture.spoken.get(0).observer.onError("验收注入合成错误")); await(() -> Care.phase().equals("IDLE"));
            main(() -> scheduler[0].due(plans)); await(() -> fixture.spoken.size() == 2); assertEquals("S50", fixture.scenario()); fixture.complete();
            record("PLAN:failure", "失败计划不阻塞其余计划；星期掩码生效");
        } finally { main(() -> { if (scheduler[0] != null) scheduler[0].close(); }); }
    }
    @Test public void planLimitAndRemovalBoundStoredMetadata() throws Exception {
        try (CareAcceptanceContext context = new CareAcceptanceContext(application)) {
            for (int i = 0; i < 100; i++) CareSchedules.add(context, "manual", "有界计划 " + i, i, 127);
            try { CareSchedules.add(context, "manual", "超限不保存", 120, 127); fail("应拒绝第 101 个计划"); } catch (IllegalStateException expected) { }
            List<CareSchedules.Plan> plans = CareSchedules.list(context); assertEquals(100, plans.size());
            String id = plans.get(0).id; context.getSharedPreferences("care", 0).edit().putString("plan_done." + id, "test").commit();
            CareSchedules.remove(context, id); assertEquals(99, CareSchedules.list(context).size());
            assertFalse(context.getSharedPreferences("care", 0).contains("plan_done." + id)); record("PLAN:storage", "最多 100 项，删除同步清理执行标记");
        }
    }
}

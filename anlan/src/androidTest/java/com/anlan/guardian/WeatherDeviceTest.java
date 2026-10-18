package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WeatherDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();

    @Test public void forecastParsingKeepsUtcAndRejectsMissingFields() throws Exception {
        WeatherForecast.Place place = new WeatherForecast.Place("杭州", 30.27, 120.15);
        WeatherForecast value = OpenMeteoProvider.parseForecast(place, new JSONObject(
                "{\"hourly\":{\"time\":[3600,7200],\"weather_code\":[0,95],\"is_day\":[1,0]}}"));
        assertEquals(3_600_000, value.hours.get(0).at);
        assertTrue(value.hours.get(0).day); assertFalse(value.hours.get(1).day);
        assertEquals(WeatherForecast.Condition.THUNDERSTORM, value.hours.get(1).condition);
        assertTrue(Double.isNaN(value.hours.get(0).temperature));
        WeatherForecast temperatures = OpenMeteoProvider.parseForecast(place, new JSONObject(
                "{\"hourly\":{\"time\":[3600,7200],\"weather_code\":[0,95],\"is_day\":[1,0],\"temperature_2m\":[26.5,0],\"apparent_temperature\":[null,\"bad\"]}}"));
        assertEquals(26.5, temperatures.hours.get(0).temperature, 0);
        assertEquals(0, temperatures.hours.get(1).temperature, 0);
        assertTrue(Double.isNaN(temperatures.hours.get(0).apparentTemperature));
        assertTrue(Double.isNaN(temperatures.hours.get(1).apparentTemperature));
        for (String invalid : new String[]{
                "{\"hourly\":{\"time\":[3600],\"weather_code\":[],\"is_day\":[1]}}",
                "{\"hourly\":{\"time\":[3600],\"weather_code\":[null],\"is_day\":[1]}}"}) {
            try { OpenMeteoProvider.parseForecast(place, new JSONObject(invalid)); fail("必须拒绝不完整预报"); }
            catch (java.io.IOException expected) { }
        }
    }
    @Test public void chinaCityDictionaryIsOfflineAndPrefectureLevel() throws Exception {
        ChinaCityCatalog catalog = ChinaCityCatalog.load(app);
        assertEquals(31, catalog.provinces.size());
        int cityCount = 0; boolean chongqing = false;
        for (ChinaCityCatalog.Province province : catalog.provinces) {
            assertFalse(province.cities.isEmpty());
            for (ChinaCityCatalog.City city : province.cities) {
                cityCount++; assertFalse(city.name.contains("县级行政区划"));
                assertTrue(Double.isFinite(city.latitude)); assertTrue(Double.isFinite(city.longitude));
                if (province.name.equals("重庆市") && city.name.equals("重庆")) chongqing = true;
            }
        }
        assertEquals(337, cityCount); assertTrue(chongqing);
    }
    @Test public void liveApiReturnsFourHoursAndCitySearch() throws Exception {
        OpenMeteoProvider provider = new OpenMeteoProvider();
        WeatherForecast forecast = provider.forecast(new WeatherForecast.Place("杭州", 30.27, 120.15));
        assertEquals(4, forecast.hours.size());
        assertNotNull(forecast.current(System.currentTimeMillis()));
        assertTrue(Double.isFinite(forecast.hours.get(0).temperature));
        assertFalse(provider.searchCities("Hangzhou").isEmpty());
    }
    @Test public void networkAndMalformedResponseFailuresStayIsolatedAndCanRecover() throws Exception {
        android.content.SharedPreferences prefs = app.getSharedPreferences("weather_failure_test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        WeatherProvider failing = new WeatherProvider() {
            @Override public WeatherForecast forecast(WeatherForecast.Place place) throws Exception {
                int attempt = requests.incrementAndGet();
                if (attempt == 1) throw new java.net.UnknownHostException("offline");
                if (attempt == 2) throw new java.net.SocketTimeoutException("timeout");
                if (attempt == 3) throw new org.json.JSONException("malformed");
                long at = System.currentTimeMillis() / WeatherForecast.HOUR_MS * WeatherForecast.HOUR_MS;
                return new WeatherForecast(place, java.util.Collections.singletonList(
                        new WeatherForecast.Hour(at, WeatherForecast.Condition.RAIN, true, 24, Double.NaN)), "测试", "");
            }
            @Override public java.util.List<WeatherForecast.Place> searchCities(String query) throws Exception {
                throw new java.io.IOException("offline");
            }
        };
        WeatherRepository[] repository = new WeatherRepository[1]; Runnable observer = () -> {};
        WeatherForecast.Place place = new WeatherForecast.Place("重庆", 29.56, 106.55);
        try {
            instrumentation.runOnMainSync(() -> {
                repository[0] = new WeatherRepository((android.app.Application) app.getApplicationContext(), failing, prefs);
                repository[0].selectPlace(place); repository[0].start(observer);
            });
            for (int attempt = 1; attempt <= 3; attempt++) {
                await(() -> {
                    boolean[] failed = {false};
                    instrumentation.runOnMainSync(() -> failed[0] = repository[0].statusText().contains("查询失败")); return failed[0];
                });
                assertEquals(attempt, requests.get());
                instrumentation.runOnMainSync(() -> { assertNull(repository[0].snapshot()); repository[0].start(observer); });
                instrumentation.waitForIdleSync(); assertEquals(attempt, requests.get());
                instrumentation.runOnMainSync(() -> repository[0].selectPlace(place));
            }
            await(() -> {
                boolean[] ready = {false}; instrumentation.runOnMainSync(() -> ready[0] = repository[0].snapshot() != null); return ready[0];
            });
            assertEquals(4, requests.get());
            java.util.concurrent.CountDownLatch searched = new java.util.concurrent.CountDownLatch(1);
            boolean[] searchOk = {true};
            instrumentation.runOnMainSync(() -> repository[0].searchCities("重庆", (results, ok) -> { searchOk[0] = ok; searched.countDown(); }));
            assertTrue(searched.await(5, java.util.concurrent.TimeUnit.SECONDS)); assertFalse(searchOk[0]);
            instrumentation.runOnMainSync(() -> assertNotNull(repository[0].snapshot()));
        } finally {
            instrumentation.runOnMainSync(() -> { if (repository[0] != null) repository[0].stop(observer); });
            prefs.edit().clear().commit();
        }
    }
    @Test public void localIconsRenderDayNightAndAllConditions() throws Exception {
        Bitmap sheet = Bitmap.createBitmap(640, 160, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet); canvas.drawColor(0xFF0B141C); int index = 0;
        for (WeatherForecast.Condition condition : WeatherForecast.Condition.values()) {
            for (boolean day : new boolean[]{true, false}) {
                WeatherIconDrawable icon = new WeatherIconDrawable(condition, day);
                icon.setBounds(index % 8 * 80 + 16, index / 8 * 80 + 16, index % 8 * 80 + 64, index / 8 * 80 + 64);
                icon.draw(canvas); index++;
            }
        }
        try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), "weather-icons.png"))) {
            assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { sheet.recycle(); }
    }
    @Test public void selectedCityLoadsAndSurvivesPageRecreation() throws Exception {
        MainActivity activity = launch(); MainActivity[] current = {activity};
        WeatherRepository weather = WeatherRepository.get(app);
        android.content.SharedPreferences preferences = app.getSharedPreferences("weather", Context.MODE_PRIVATE);
        java.util.Map<String, ?> originalWeather = new java.util.HashMap<>(preferences.getAll());
        String[] names = {"show_emotion_name", "show_sound_level", "show_event_name"}; boolean[] previous = new boolean[names.length];
        try {
            instrumentation.runOnMainSync(() -> {
                for (int i = 0; i < names.length; i++) { previous[i] = activity.isNameVisible(names[i]); activity.setNameVisible(names[i], false); }
                weather.selectPlace(new WeatherForecast.Place("杭州（联调城市）", 30.27, 120.15));
            });
            await(() -> {
                boolean[] ready = {false};
                instrumentation.runOnMainSync(() -> ready[0] = weather.snapshot() != null && weather.snapshot().current(System.currentTimeMillis()) != null);
                return ready[0];
            }, 25_000);
            instrumentation.runOnMainSync(() -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.weather_icon).getVisibility());
                activity.recreate();
            });
            await(() -> {
                instrumentation.runOnMainSync(() -> {
                    for (android.app.Activity item : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                        if (item instanceof MainActivity && item != activity) current[0] = (MainActivity) item;
                });
                return current[0] != activity;
            });
            instrumentation.runOnMainSync(() -> {
                assertEquals(View.VISIBLE, current[0].findViewById(R.id.weather_icon).getVisibility());
                assertNull(current[0].findViewById(R.id.menu));
                assertEquals("杭州（联调城市）", weather.snapshot().place.name);
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    View header = current[0].findViewById(R.id.header); int[] position = new int[2]; header.getLocationOnScreen(position);
                    int safeTop = header.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars()).top;
                    assertTrue("顶部信息须避让可见状态栏", position[1] >= safeTop);
                }
            });
            SystemClock.sleep(800);
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), "weather-home.png"))) {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
            } finally { screenshot.recycle(); }
        } finally {
            instrumentation.runOnMainSync(() -> {
                for (int i = 0; i < names.length; i++) current[0].setNameVisible(names[i], previous[i]);
                current[0].finish();
            });
            app.stopService(new Intent(app, GuardianService.class));
            instrumentation.waitForIdleSync();
            // 测试城市和缓存不能覆盖用户配置；下次页面按原配置重新读取。
            android.content.SharedPreferences.Editor restore = preferences.edit().clear();
            for (java.util.Map.Entry<String, ?> entry : originalWeather.entrySet()) {
                if (entry.getValue() instanceof String) restore.putString(entry.getKey(), (String) entry.getValue());
                else if (entry.getValue() instanceof Long) restore.putLong(entry.getKey(), (Long) entry.getValue());
                else if (entry.getValue() instanceof Boolean) restore.putBoolean(entry.getKey(), (Boolean) entry.getValue());
            }
            assertTrue(restore.commit());
            java.lang.reflect.Field singleton = WeatherRepository.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, null);
        }
    }
    @Test public void clockOpensMenuAndRegionSettingsReturnsToSettings() throws Exception {
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                View clock = activity.findViewById(R.id.clock);
                assertNull(activity.findViewById(R.id.menu));
                assertEquals("HH:mm", ((TextClock) clock).getFormat24Hour());
                assertNull(activity.findViewById(R.id.date));
                clock.performClick();
                androidx.drawerlayout.widget.DrawerLayout drawer = (androidx.drawerlayout.widget.DrawerLayout)
                        ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
                drawer.openDrawer(android.view.Gravity.RIGHT, false); assertTrue(drawer.isDrawerVisible(android.view.Gravity.RIGHT));
                assertNotNull(findText(drawer, "小安 · 智能守护"));
                findText(drawer, "设置").performClick();
                findText(drawer, "地区设置").performClick();
                assertNotNull(findText(drawer, "自动定位城市"));
                findText(drawer, "选择城市").performClick();
                assertNotNull(findText(drawer, "选择省份")); assertNull(findText(drawer, "搜索城市"));
                findText(drawer, "重庆市").performClick(); assertNotNull(findText(drawer, "选择地级市"));
                findText(drawer, "重庆").performClick(); assertNotNull(findText(drawer, "自动定位城市"));
                activity.onBackPressed(); assertNotNull(findText(drawer, "显示声音强度"));
                activity.onBackPressed(); assertNotNull(findText(drawer, "小安 · 智能守护"));
                activity.closeDrawer();
            });
        } finally {
            app.stopService(new Intent(app, GuardianService.class));
            instrumentation.runOnMainSync(activity::finish);
        }
    }
    private View findText(View view, String value) {
        if (view instanceof android.widget.TextView && value.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { View found = findText(group.getChildAt(i), value); if (found != null) return found; }
        }
        return null;
    }
    private MainActivity launch() throws Exception {
        for (String permission : new String[]{"android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_COARSE_LOCATION"})
            instrumentation.getUiAutomation().executeShellCommand("pm grant com.anlan.guardian " + permission).close();
        try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand("am start -W -n com.anlan.guardian/.MainActivity"))) {
            byte[] buffer = new byte[1024]; while (in.read(buffer) != -1) { }
        }
        MainActivity[] result = new MainActivity[1];
        await(() -> {
            instrumentation.runOnMainSync(() -> {
                for (android.app.Activity activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if (activity instanceof MainActivity) result[0] = (MainActivity) activity;
            });
            return result[0] != null;
        });
        return result[0];
    }
    private void await(BooleanSupplier condition) {
        await(condition, 10_000);
    }
    private void await(BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertTrue("等待界面超时", condition.getAsBoolean());
    }
}

package com.anlan.guardian;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** 仅主屏可见时调度；定位与网络都不进入音频线程。实例不持有 Activity。 */
final class WeatherRepository {
    private static WeatherRepository instance;
    static WeatherRepository get(Context context) {
        if (instance == null) instance = new WeatherRepository((android.app.Application) context.getApplicationContext(), new OpenMeteoProvider());
        return instance;
    }
    private final android.app.Application context;
    private final SharedPreferences preferences;
    private final WeatherProvider provider;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "anlan-weather"));
    private final List<Runnable> observers = new ArrayList<>();
    private final WeatherRefreshPolicy policy;
    private final LocationManager locations;
    private LocationListener locationListener;
    private WeatherForecast forecast;
    private WeatherForecast.Place manualPlace;
    private String status = "尚未取得天气，可检查定位或手动选择城市";
    private boolean inFlight, searching;
    private int generation;
    private final Runnable locationTimeout = () -> { cancelLocation(); fail("未取得位置，请设置设备位置或手动选择城市"); };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            notifyObservers(); refresh();
            if (!observers.isEmpty()) main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };
    private WeatherRepository(android.app.Application context, WeatherProvider provider) {
        this(context, provider, context.getSharedPreferences("weather", Context.MODE_PRIVATE));
    }
    WeatherRepository(android.app.Application context, WeatherProvider provider, SharedPreferences preferences) {
        this.context = context; this.provider = provider;
        this.preferences = preferences;
        locations = context.getSystemService(LocationManager.class);
        policy = new WeatherRefreshPolicy(preferences.getLong("last_attempt", 0), System.currentTimeMillis(), SystemClock.elapsedRealtime());
        try {
            String manual = preferences.getString("manual_place", "");
            if (!manual.isEmpty()) manualPlace = decodePlace(new JSONObject(manual));
            String cache = preferences.getString("forecast", "");
            if (!cache.isEmpty()) {
                JSONObject json = new JSONObject(cache); List<WeatherForecast.Hour> hours = new ArrayList<>();
                JSONArray rows = json.getJSONArray("hours");
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    hours.add(new WeatherForecast.Hour(row.getLong("at"), WeatherForecast.Condition.valueOf(row.getString("condition")), row.getBoolean("day"),
                            row.optDouble("temperature", Double.NaN), row.optDouble("apparent_temperature", Double.NaN)));
                }
                forecast = new WeatherForecast(decodePlace(json.getJSONObject("place")), hours, json.getString("source"), json.getString("source_url"));
            }
        } catch (Exception error) { forecast = null; android.util.Log.w("AnlanWeather", "忽略无效天气缓存"); }
        // 没有任何有效天气时，重新进入前台应立即补试一次；已有缓存仍遵守每小时节流。
        if (forecast == null) policy.reset();
    }
    void start(Runnable observer) {
        if (!observers.contains(observer)) observers.add(observer);
        main.removeCallbacks(tick); tick.run();
    }
    void stop(Runnable observer) {
        observers.remove(observer);
        if (observers.isEmpty()) {
            main.removeCallbacks(tick);
            if (locationListener != null) { cancelLocation(); inFlight = false; generation++; }
        }
    }
    WeatherForecast snapshot() { return forecast; }
    boolean automatic() { return manualPlace == null; }
    String statusText() {
        return automatic() && !hasLocationPermission() ? "需要位置权限，或手动选择城市" : status;
    }
    boolean hasLocationPermission() { return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED; }
    void useAutomatic() { selectPlace(null); }
    void selectPlace(WeatherForecast.Place place) {
        generation++; cancelLocation(); manualPlace = place; forecast = null;
        status = place == null ? "正在等待设备位置" : "正在等待所选城市的天气更新";
        // 定位尚未提交网络任务时，可以立即开始新城市查询。
        if (!networkRunning) inFlight = false;
        preferences.edit().remove("forecast").remove("last_attempt")
                .putString("manual_place", place == null ? "" : encodePlace(place).toString()).apply();
        policy.reset(); notifyObservers(); refresh();
    }
    private boolean networkRunning;
    private void refresh() {
        if (observers.isEmpty() || inFlight || !policy.due(SystemClock.elapsedRealtime())) return;
        if (manualPlace == null && !hasLocationPermission()) return;
        inFlight = true; policy.attempted(SystemClock.elapsedRealtime());
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply();
        int token = generation;
        status = manualPlace == null ? "正在获取设备位置" : "正在查询天气"; notifyObservers();
        if (manualPlace != null) { fetch(manualPlace, token, false); return; }
        locate(token);
    }
    @SuppressWarnings("deprecation")
    private void locate(int token) {
        try {
            String provider = locations == null ? null : selectLocationProvider(locations.getProviders(true));
            if (provider == null) { fail("设备未提供可用定位，请开启定位或手动选择城市"); return; }
            Location last = locations.getLastKnownLocation(provider);
            if (last != null && fresh(last)) { located(last, token); return; }
            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (token != generation || locationListener != this) return;
                    cancelLocation();
                    if (fresh(location)) located(location, token); else fail("位置已过期，请设置设备位置或手动选择城市");
                }
                @Override public void onProviderDisabled(String name) {
                    if (token == generation && locationListener == this) { cancelLocation(); fail("定位已关闭，可手动选择城市"); }
                }
                @Override public void onProviderEnabled(String name) { }
                @Override public void onStatusChanged(String name, int status, Bundle extras) { }
            };
            locations.requestSingleUpdate(provider, locationListener, Looper.getMainLooper());
            main.postDelayed(locationTimeout, 15_000);
        } catch (SecurityException error) { cancelLocation(); fail("位置权限不可用，可手动选择城市"); }
        catch (RuntimeException error) { cancelLocation(); fail("设备定位不可用，可手动选择城市"); }
    }
    static String selectLocationProvider(List<String> enabled) {
        if (enabled.contains(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
        // 只使用系统确实列出的融合定位，不要求额外的 Google SDK 或精确定位权限。
        return enabled.contains("fused") ? "fused" : null;
    }
    private void fail(String message) {
        inFlight = false; status = message; android.util.Log.w("AnlanWeather", message); notifyObservers();
    }
    private static boolean fresh(Location location) {
        long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return age >= 0 && age < WeatherForecast.HOUR_MS * 1_000_000L;
    }
    private void located(Location location, int token) {
        try { fetch(new WeatherForecast.Place("当前位置", location.getLatitude(), location.getLongitude()), token, true); }
        catch (IllegalArgumentException error) { inFlight = false; }
    }
    @SuppressWarnings("deprecation")
    private void fetch(WeatherForecast.Place place, int token, boolean resolveCity) {
        networkRunning = true; status = "正在查询天气"; notifyObservers();
        worker.execute(() -> {
            WeatherForecast result = null;
            try {
                WeatherForecast.Place named = place;
                if (resolveCity && Geocoder.isPresent()) {
                    try {
                        List<Address> addresses = new Geocoder(context, Locale.CHINA).getFromLocation(place.latitude, place.longitude, 1);
                        if (addresses != null && !addresses.isEmpty()) {
                            Address address = addresses.get(0);
                            String name = address.getLocality();
                            if (name == null || name.isEmpty()) name = address.getSubAdminArea();
                            if (name != null && !name.isEmpty()) named = new WeatherForecast.Place(name, place.latitude, place.longitude);
                        }
                    } catch (Exception ignored) { /* 城市名解析失败仍可按坐标取天气。 */ }
                }
                result = provider.forecast(named);
            } catch (Exception error) { android.util.Log.w("AnlanWeather", "天气更新失败，下个小时再试", error); }
            WeatherForecast completed = result;
            main.post(() -> {
                networkRunning = false; inFlight = false;
                if (token != generation) { refresh(); return; }
                if (completed != null) { forecast = completed; status = ""; saveForecast(completed); }
                else status = "天气查询失败，下个小时再试";
                notifyObservers();
            });
        });
    }
    void searchCities(String query, BiConsumer<List<WeatherForecast.Place>, Boolean> callback) {
        if (searching) { callback.accept(new ArrayList<>(), false); return; }
        searching = true;
        worker.execute(() -> {
            List<WeatherForecast.Place> results = new ArrayList<>(); boolean success = false;
            try { results = provider.searchCities(query); success = true; }
            catch (Exception error) { android.util.Log.w("AnlanWeather", "城市搜索失败"); }
            List<WeatherForecast.Place> completed = results; boolean ok = success;
            main.post(() -> { searching = false; callback.accept(completed, ok); });
        });
    }
    private void cancelLocation() {
        main.removeCallbacks(locationTimeout);
        if (locationListener != null) {
            try { locations.removeUpdates(locationListener); } catch (RuntimeException ignored) { }
            locationListener = null;
        }
    }
    private void notifyObservers() { for (Runnable observer : new ArrayList<>(observers)) observer.run(); }
    private void saveForecast(WeatherForecast value) {
        try {
            JSONObject json = new JSONObject().put("place", encodePlace(value.place)).put("source", value.source).put("source_url", value.sourceUrl);
            JSONArray rows = new JSONArray();
            for (WeatherForecast.Hour hour : value.hours) {
                JSONObject row = new JSONObject().put("at", hour.at).put("condition", hour.condition.name()).put("day", hour.day);
                if (Double.isFinite(hour.temperature)) row.put("temperature", hour.temperature);
                if (Double.isFinite(hour.apparentTemperature)) row.put("apparent_temperature", hour.apparentTemperature);
                rows.put(row);
            }
            preferences.edit().putString("forecast", json.put("hours", rows).toString()).apply();
        } catch (Exception error) { android.util.Log.w("AnlanWeather", "天气缓存保存失败"); }
    }
    private static JSONObject encodePlace(WeatherForecast.Place place) {
        try { return new JSONObject().put("name", place.name).put("lat", place.latitude).put("lon", place.longitude); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
    }
    private static WeatherForecast.Place decodePlace(JSONObject json) throws Exception {
        return new WeatherForecast.Place(json.getString("name"), json.getDouble("lat"), json.getDouble("lon"));
    }
}

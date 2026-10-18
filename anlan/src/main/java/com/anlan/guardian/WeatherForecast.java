package com.anlan.guardian;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 界面只依赖这些数据，不接触供应商的天气代码。时间统一为 UTC 毫秒。 */
final class WeatherForecast {
    static final long HOUR_MS = 3_600_000L;
    enum Condition {
        CLEAR("晴"), PARTLY_CLOUDY("多云"), CLOUDY("阴"), FOG("雾"),
        RAIN("雨"), SNOW("雪"), THUNDERSTORM("雷雨"), UNKNOWN("未知天气");
        final String title;
        Condition(String title) { this.title = title; }
    }
    static final class Place {
        final String name;
        final double latitude, longitude;
        Place(String name, double latitude, double longitude) {
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180)
                throw new IllegalArgumentException("无效的位置坐标");
            this.name = name; this.latitude = latitude; this.longitude = longitude;
        }
    }
    static final class Hour {
        final long at;
        final Condition condition;
        final boolean day;
        final double temperature, apparentTemperature;
        Hour(long at, Condition condition, boolean day) {
            this(at, condition, day, Double.NaN, Double.NaN);
        }
        Hour(long at, Condition condition, boolean day, double temperature, double apparentTemperature) {
            this.at = at; this.condition = condition; this.day = day;
            this.temperature = validTemperature(temperature);
            this.apparentTemperature = validTemperature(apparentTemperature);
        }
        private static double validTemperature(double value) {
            return Double.isFinite(value) && value >= -100 && value <= 100 ? value : Double.NaN;
        }
    }
    final Place place;
    final List<Hour> hours;
    final String source, sourceUrl;
    WeatherForecast(Place place, List<Hour> hours, String source, String sourceUrl) {
        if (hours.isEmpty() || hours.size() > 4) throw new IllegalArgumentException("预报须包含 1 至 4 小时");
        long previous = Long.MIN_VALUE;
        for (Hour hour : hours) {
            if (hour.at <= previous) throw new IllegalArgumentException("预报时间须递增");
            previous = hour.at;
        }
        this.place = place; this.hours = Collections.unmodifiableList(new ArrayList<>(hours));
        this.source = source; this.sourceUrl = sourceUrl;
    }
    Hour current(long now) {
        for (Hour hour : hours) if (now >= hour.at && now - hour.at < HOUR_MS) return hour;
        return null;
    }
}

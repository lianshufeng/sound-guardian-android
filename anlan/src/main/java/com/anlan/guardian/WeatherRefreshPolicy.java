package com.anlan.guardian;

/** 失败也计入一次尝试；界面重建、重启不能触发密集重试。 */
final class WeatherRefreshPolicy {
    private long nextElapsed;
    WeatherRefreshPolicy(long lastAttemptWall, long nowWall, long nowElapsed) {
        long age = nowWall - lastAttemptWall;
        nextElapsed = lastAttemptWall > 0 && age >= 0 && age < WeatherForecast.HOUR_MS
                ? nowElapsed + WeatherForecast.HOUR_MS - age : nowElapsed;
    }
    boolean due(long nowElapsed) { return nowElapsed >= nextElapsed; }
    void attempted(long nowElapsed) { nextElapsed = nowElapsed + WeatherForecast.HOUR_MS; }
    void reset() { nextElapsed = 0; }
}

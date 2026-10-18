package com.anlan.guardian;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WeatherTest {
    private static final long HOUR = WeatherForecast.HOUR_MS;
    private final WeatherForecast.Place place = new WeatherForecast.Place("杭州", 30.27, 120.15);

    @Test public void forecastSwitchesAtHourAndHidesExpiredOrFutureData() {
        WeatherForecast.Hour first = new WeatherForecast.Hour(HOUR, WeatherForecast.Condition.CLEAR, true);
        WeatherForecast.Hour second = new WeatherForecast.Hour(2 * HOUR, WeatherForecast.Condition.RAIN, false);
        WeatherForecast value = new WeatherForecast(place, Arrays.asList(first, second), "测试", "https://example.com");
        assertNull(value.current(HOUR - 1));
        assertSame(first, value.current(HOUR));
        assertSame(first, value.current(2 * HOUR - 1));
        assertSame(second, value.current(2 * HOUR));
        assertNull(value.current(3 * HOUR));
    }
    @Test public void missingHourIsNotFilledWithStaleWeather() {
        WeatherForecast value = new WeatherForecast(place, Arrays.asList(
                new WeatherForecast.Hour(HOUR, WeatherForecast.Condition.CLEAR, true),
                new WeatherForecast.Hour(3 * HOUR, WeatherForecast.Condition.SNOW, true)), "测试", "https://example.com");
        assertNull(value.current(2 * HOUR));
    }
    @Test public void failedAttemptStillWaitsOneHour() {
        WeatherRefreshPolicy policy = new WeatherRefreshPolicy(0, 1000, 100);
        assertTrue(policy.due(100));
        policy.attempted(100);
        assertFalse(policy.due(100 + HOUR - 1));
        assertTrue(policy.due(100 + HOUR));
    }
    @Test public void restartKeepsRemainingDelayAndClockRollbackDoesNotStall() {
        WeatherRefreshPolicy restored = new WeatherRefreshPolicy(1000, 61_000, 200);
        assertFalse(restored.due(200 + HOUR - 60_000 - 1));
        assertTrue(restored.due(200 + HOUR - 60_000));
        assertTrue(new WeatherRefreshPolicy(61_000, 1000, 200).due(200));
        assertTrue(new WeatherRefreshPolicy(1000, HOUR + 1000, 200).due(200));
        restored.reset(); assertTrue(restored.due(200));
    }
    @Test public void allDocumentedWmoCodesHaveIconsAndUnknownDoesNotBecomeSunny() {
        int[] codes = {0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,99};
        for (int code : codes) assertNotEquals("天气代码 " + code, WeatherForecast.Condition.UNKNOWN, OpenMeteoProvider.condition(code));
        assertEquals(WeatherForecast.Condition.SNOW, OpenMeteoProvider.condition(86));
        assertEquals(WeatherForecast.Condition.THUNDERSTORM, OpenMeteoProvider.condition(99));
        assertEquals(WeatherForecast.Condition.UNKNOWN, OpenMeteoProvider.condition(-1));
        assertEquals(WeatherForecast.Condition.UNKNOWN, OpenMeteoProvider.condition(999));
    }
    @Test(expected = IllegalArgumentException.class) public void invalidLocationIsRejected() {
        new WeatherForecast.Place("错误", Double.NaN, 120);
    }
    @Test(expected = IllegalArgumentException.class) public void unorderedForecastIsRejected() {
        new WeatherForecast(place, Arrays.asList(
                new WeatherForecast.Hour(2 * HOUR, WeatherForecast.Condition.CLEAR, true),
                new WeatherForecast.Hour(HOUR, WeatherForecast.Condition.CLEAR, true)), "测试", "https://example.com");
    }
    @Test(expected = IllegalArgumentException.class) public void emptyForecastIsRejected() {
        new WeatherForecast(place, Collections.emptyList(), "测试", "https://example.com");
    }
    @Test public void missingNetworkProviderFallsBackToSystemFusedLocation() {
        assertEquals("fused", WeatherRepository.selectLocationProvider(Arrays.asList("gps", "fused", "passive")));
        assertEquals("network", WeatherRepository.selectLocationProvider(Arrays.asList("fused", "network")));
        assertNull(WeatherRepository.selectLocationProvider(Arrays.asList("gps", "passive")));
        assertNull(WeatherRepository.selectLocationProvider(Collections.emptyList()));
    }
    @Test public void invalidTemperaturesAreAbsentAndValidZeroIsKept() {
        WeatherForecast.Hour missing = new WeatherForecast.Hour(HOUR, WeatherForecast.Condition.CLEAR, true, Double.POSITIVE_INFINITY, 999);
        assertTrue(Double.isNaN(missing.temperature)); assertTrue(Double.isNaN(missing.apparentTemperature));
        WeatherForecast.Hour valid = new WeatherForecast.Hour(HOUR, WeatherForecast.Condition.SNOW, false, 0, -15.4);
        assertEquals(0, valid.temperature, 0); assertEquals(-15.4, valid.apparentTemperature, 0);
    }
}

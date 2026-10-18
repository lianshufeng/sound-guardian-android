package com.anlan.guardian;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class OpenMeteoProvider implements WeatherProvider {
    @Override public WeatherForecast forecast(WeatherForecast.Place place) throws Exception {
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&hourly=weather_code,is_day,temperature_2m,apparent_temperature&forecast_hours=4&timeformat=unixtime&timezone=auto",
                place.latitude, place.longitude);
        return parseForecast(place, readJson(url));
    }
    static WeatherForecast parseForecast(WeatherForecast.Place place, JSONObject json) throws Exception {
        JSONObject hourly = json.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time"), codes = hourly.getJSONArray("weather_code"), days = hourly.getJSONArray("is_day");
        if (times.length() == 0 || times.length() != codes.length() || times.length() != days.length())
            throw new IOException("天气预报数组不完整");
        List<WeatherForecast.Hour> hours = new ArrayList<>();
        for (int i = 0; i < Math.min(4, times.length()); i++) {
            if (times.isNull(i) || codes.isNull(i) || days.isNull(i)) throw new IOException("天气预报缺少数据");
            hours.add(new WeatherForecast.Hour(Math.multiplyExact(times.getLong(i), 1000),
                    condition(codes.getInt(i)), days.getInt(i) == 1,
                    optionalTemperature(hourly.optJSONArray("temperature_2m"), i),
                    optionalTemperature(hourly.optJSONArray("apparent_temperature"), i)));
        }
        return new WeatherForecast(place, hours, "Open-Meteo", "https://open-meteo.com/");
    }
    private static double optionalTemperature(JSONArray values, int index) {
        return values == null ? Double.NaN : values.optDouble(index, Double.NaN);
    }
    static WeatherForecast.Condition condition(int code) {
        switch (code) {
            case 0: return WeatherForecast.Condition.CLEAR;
            case 1: case 2: return WeatherForecast.Condition.PARTLY_CLOUDY;
            case 3: return WeatherForecast.Condition.CLOUDY;
            case 45: case 48: return WeatherForecast.Condition.FOG;
            case 51: case 53: case 55: case 56: case 57: case 61: case 63: case 65:
            case 66: case 67: case 80: case 81: case 82: return WeatherForecast.Condition.RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86: return WeatherForecast.Condition.SNOW;
            case 95: case 96: case 99: return WeatherForecast.Condition.THUNDERSTORM;
            default: return WeatherForecast.Condition.UNKNOWN;
        }
    }
    @Override public List<WeatherForecast.Place> searchCities(String query) throws Exception {
        JSONObject json = readJson("https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(query, "UTF-8") + "&count=8&language=zh&format=json");
        List<WeatherForecast.Place> places = new ArrayList<>();
        JSONArray rows = json.optJSONArray("results");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String name = row.getString("name"), region = row.optString("admin1", ""), country = row.optString("country", "");
            if (!region.isEmpty() && !name.equals(region)) name += " · " + region;
            if (!country.isEmpty()) name += " · " + country;
            places.add(new WeatherForecast.Place(name, row.getDouble("latitude"), row.getDouble("longitude")));
        }
        return places;
    }
    private static JSONObject readJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (connection.getResponseCode() != 200) throw new IOException("天气服务暂不可用");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 262144) throw new IOException("天气响应过大");
                    out.write(buffer, 0, count);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}

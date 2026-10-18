package com.anlan.guardian;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 离线省市字典；天气页不依赖网络搜索或用户输入。 */
final class ChinaCityCatalog {
    static final class City {
        final String name; final double latitude, longitude;
        City(String name, double latitude, double longitude) { this.name = name; this.latitude = latitude; this.longitude = longitude; }
        WeatherForecast.Place place(String province) { return new WeatherForecast.Place(name + " · " + province, latitude, longitude); }
    }
    static final class Province {
        final String name; final List<City> cities;
        Province(String name, List<City> cities) { this.name = name; this.cities = Collections.unmodifiableList(cities); }
    }
    final List<Province> provinces;
    private ChinaCityCatalog(List<Province> provinces) { this.provinces = Collections.unmodifiableList(provinces); }
    static ChinaCityCatalog load(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("weather/china_cities.json")) {
            byte[] bytes = readAll(input); JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray provinceRows = root.getJSONArray("provinces"); List<Province> provinces = new ArrayList<>();
            for (int i = 0; i < provinceRows.length(); i++) {
                JSONObject province = provinceRows.getJSONObject(i); JSONArray cityRows = province.getJSONArray("cities");
                List<City> cities = new ArrayList<>();
                for (int j = 0; j < cityRows.length(); j++) {
                    JSONObject city = cityRows.getJSONObject(j);
                    cities.add(new City(city.getString("name"), city.getDouble("lat"), city.getDouble("lon")));
                }
                if (cities.isEmpty()) throw new IllegalArgumentException("省份城市为空");
                provinces.add(new Province(province.getString("name"), cities));
            }
            if (provinces.size() != 31) throw new IllegalArgumentException("省份字典不完整");
            return new ChinaCityCatalog(provinces);
        }
    }
    private static byte[] readAll(InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > 1_000_000) throw new IllegalArgumentException("城市字典过大");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}

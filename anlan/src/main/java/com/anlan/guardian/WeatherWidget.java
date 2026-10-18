package com.anlan.guardian;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 顶部常驻天气与时钟；更多信息只在点击后展示。 */
final class WeatherWidget extends LinearLayout {
    private final MainActivity activity;
    private final WeatherRepository weather;
    private final ChinaCityCatalog catalog;
    private final ImageView icon;
    private final TextView temperature;
    private final Runnable observer = this::update;
    private WeatherForecast.Hour displayed;
    private boolean detailsVisible;
    private TextView detailStatus;
    private LinearLayout detailHours;
    private Runnable settingsBack;
    public WeatherWidget(android.content.Context context) {
        super(context); this.activity = (MainActivity) context; weather = WeatherRepository.get(context);
        ChinaCityCatalog loaded;
        try { loaded = ChinaCityCatalog.load(context); }
        catch (Exception error) { loaded = null; android.util.Log.e("AnlanWeather", "城市字典加载失败", error); }
        catalog = loaded;
        setOrientation(VERTICAL); setGravity(Gravity.END | Gravity.CENTER_VERTICAL); setId(R.id.weather_clock);
        setPadding(0, dp(6), 0, 0);
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        icon = new ImageView(activity); icon.setId(R.id.weather_icon); icon.setVisibility(INVISIBLE);
        icon.setAlpha(.8f); row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        temperature = label("", 23, 0xFFB0CDC2); temperature.setId(R.id.weather_temperature);
        temperature.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); temperature.setIncludeFontPadding(false);
        temperature.setSingleLine(true); temperature.setVisibility(GONE);
        temperature.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams temperatureParams = new LinearLayout.LayoutParams(-2, dp(34)); temperatureParams.leftMargin = dp(4);
        row.addView(temperature, temperatureParams); addView(row, new LinearLayout.LayoutParams(-2, dp(36)));
        TextClock clock = new TextClock(activity); clock.setId(R.id.clock);
        clock.setFormat12Hour("HH:mm"); clock.setFormat24Hour("HH:mm"); clock.setTextSize(13); clock.setLetterSpacing(.02f);
        clock.setTextColor(0xFF7B9189); clock.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        clock.setIncludeFontPadding(false); clock.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); clock.setSingleLine(true);
        clock.setContentDescription("时间，点击打开菜单"); clock.setOnClickListener(v -> activity.showPanel());
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(-2, dp(34)); clockParams.leftMargin = dp(8);
        row.addView(clock, clockParams);
        setOnClickListener(v -> activity.showPanel());
    }
    void start() { weather.start(observer); }
    void stop() { weather.stop(observer); }
    void drawerClosed() { detailsVisible = false; }
    private void update() {
        WeatherForecast value = weather.snapshot();
        WeatherForecast.Hour hour = value == null ? null : value.current(System.currentTimeMillis());
        boolean available = hour != null && hour.condition != WeatherForecast.Condition.UNKNOWN;
        icon.setVisibility(available ? VISIBLE : INVISIBLE);
        boolean hasTemperature = hour != null && Double.isFinite(hour.temperature);
        temperature.setVisibility(hasTemperature ? VISIBLE : GONE);
        if (hasTemperature) {
            android.text.SpannableString valueText = new android.text.SpannableString(degrees(hour.temperature) + "°");
            int degreeAt = valueText.length() - 1;
            valueText.setSpan(new android.text.style.RelativeSizeSpan(.55f), degreeAt, valueText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            valueText.setSpan(new android.text.style.SuperscriptSpan(), degreeAt, valueText.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            temperature.setText(valueText);
        }
        if (hour != displayed) {
            displayed = hour;
            if (available) icon.setImageDrawable(new WeatherIconDrawable(hour.condition, hour.day));
        }
        if (available) {
            icon.setContentDescription(hour.condition.title);
        }
        setContentDescription(hour != null ? value.place.name + "，" + hour.condition.title
                + (hasTemperature ? "，" + degrees(hour.temperature) + "摄氏度" : "")
                + "，点击打开菜单" : "时间，点击打开菜单");
        if (detailsVisible && detailStatus != null && detailStatus.isAttachedToWindow()) renderDetails();
    }
    void showRegionSettings(Runnable back) {
        settingsBack = back;
        LinearLayout body = body();
        detailStatus = label("", 17, 0xFFD2E5DC); detailStatus.setPadding(0, 0, 0, dp(16)); body.addView(detailStatus);
        detailHours = new LinearLayout(activity); detailHours.setOrientation(VERTICAL); body.addView(detailHours);
        Button city = new Button(activity); city.setText("选择城市"); city.setOnClickListener(v -> showCitySearch()); body.addView(city);
        Button automatic = new Button(activity); automatic.setText("自动定位城市");
        automatic.setOnClickListener(v -> activity.requestWeatherLocation()); body.addView(automatic);
        TextView note = label("每小时更新；失败后下个小时再试。\n自动定位需要位置权限，也可手动选择城市。", 13, 0xFF8BA69C);
        note.setPadding(0, dp(16), 0, dp(12)); body.addView(note);
        activity.showDrawerPage("地区设置", true, back, body);
        detailsVisible = true; renderDetails();
    }
    private void renderDetails() {
        WeatherForecast value = weather.snapshot(); long now = System.currentTimeMillis();
        boolean valid = value != null && value.current(now) != null;
        detailStatus.setText(valid ? value.place.name : weather.statusText());
        detailHours.removeAllViews();
        if (!valid) return;
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.CHINA);
        for (WeatherForecast.Hour hour : value.hours) {
            if (now - hour.at >= WeatherForecast.HOUR_MS) continue;
            LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView image = new ImageView(activity); image.setImageDrawable(new WeatherIconDrawable(hour.condition, hour.day));
            image.setContentDescription(hour.condition.title); row.addView(image, new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView description = label(format.format(new Date(hour.at))
                    + (Double.isFinite(hour.temperature) ? "    " + degrees(hour.temperature) + "°" : ""), 17, 0xFFB9D0C6);
            description.setPadding(dp(16), 0, 0, 0); row.addView(description); detailHours.addView(row, new LinearLayout.LayoutParams(-1, dp(50)));
        }
    }
    private void showCitySearch() {
        detailsVisible = false;
        LinearLayout body = body();
        if (catalog == null) {
            body.addView(label("城市列表暂时不可用，请稍后重试。", 16, 0xFFD2E5DC));
        } else {
            TextView note = label("选择省份或直辖市", 14, 0xFF8BA69C); note.setPadding(0, 0, 0, dp(12)); body.addView(note);
            LinearLayout provinces = new LinearLayout(activity); provinces.setOrientation(VERTICAL);
            for (ChinaCityCatalog.Province province : catalog.provinces) {
                TextView item = label(province.name, 17, 0xFFD2E5DC); item.setGravity(Gravity.CENTER_VERTICAL); item.setMinHeight(dp(56));
                item.setPadding(dp(8), 0, 0, 0); item.setOnClickListener(v -> showProvinceCities(province));
                provinces.addView(item, new LinearLayout.LayoutParams(-1, dp(56)));
            }
            ScrollView scroll = new ScrollView(activity); scroll.addView(provinces); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        activity.showDrawerPage("选择省份", true, () -> showRegionSettings(settingsBack), body);
    }
    private void showProvinceCities(ChinaCityCatalog.Province province) {
        LinearLayout body = body(); TextView note = label("选择地级市", 14, 0xFF8BA69C); note.setPadding(0, 0, 0, dp(12)); body.addView(note);
        LinearLayout cities = new LinearLayout(activity); cities.setOrientation(VERTICAL);
        for (ChinaCityCatalog.City city : province.cities) {
            TextView item = label(city.name, 17, 0xFFD2E5DC); item.setGravity(Gravity.CENTER_VERTICAL); item.setMinHeight(dp(56)); item.setPadding(dp(8), 0, 0, 0);
            item.setOnClickListener(v -> { weather.selectPlace(city.place(province.name)); showRegionSettings(settingsBack); });
            cities.addView(item, new LinearLayout.LayoutParams(-1, dp(56)));
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(cities); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        activity.showDrawerPage(province.name, true, this::showCitySearch, body);
    }
    private LinearLayout body() {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(VERTICAL); body.setPadding(dp(24), dp(20), dp(24), dp(20)); return body;
    }
    private TextView label(String value, float size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String degrees(double value) { return Long.toString(Math.round(value)); }
}

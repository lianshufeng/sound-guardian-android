package com.anlan.guardian;

import java.util.List;

/** 更换天气来源时，只替换此适配及其天气代码映射。 */
interface WeatherProvider {
    WeatherForecast forecast(WeatherForecast.Place place) throws Exception;
    List<WeatherForecast.Place> searchCities(String query) throws Exception;
}

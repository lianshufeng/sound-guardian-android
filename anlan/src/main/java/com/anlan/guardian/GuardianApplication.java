package com.anlan.guardian;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** 固定应用语言，让会议组件及系统控件统一使用简体中文。 */
public final class GuardianApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        onConfigurationChanged(getResources().getConfiguration());
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getSystemService(android.app.LocaleManager.class).setApplicationLocales(
                    android.os.LocaleList.forLanguageTags("zh-CN"));
        }
    }

    @Override protected void attachBaseContext(Context base) {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        Configuration chinese = new Configuration(base.getResources().getConfiguration());
        chinese.setLocale(Locale.SIMPLIFIED_CHINESE);
        super.attachBaseContext(base.createConfigurationContext(chinese));
    }

    @SuppressWarnings("deprecation")
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        Configuration chinese = new Configuration(configuration);
        chinese.setLocale(Locale.SIMPLIFIED_CHINESE);
        getResources().updateConfiguration(chinese, getResources().getDisplayMetrics());
    }
}

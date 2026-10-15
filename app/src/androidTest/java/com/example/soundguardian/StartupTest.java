package com.example.soundguardian;

import android.content.Intent;
import android.widget.Button;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 启动须真正完成布局加载和 Activity 重建，不能只检查 am start 的返回码。 */
@RunWith(AndroidJUnit4.class)
public class StartupTest {
    @Test public void launchAndRecreateMainActivity() {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(), MainActivity.class);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertEquals("开始守护", ((Button) activity.findViewById(R.id.toggle)).getText().toString());
                assertTrue(((TextView) activity.findViewById(R.id.status)).getText().toString().contains("尚未开始"));
                assertNotNull(activity.findViewById(R.id.candidates));
            });
            scenario.recreate();
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.level)));
        }
    }
}

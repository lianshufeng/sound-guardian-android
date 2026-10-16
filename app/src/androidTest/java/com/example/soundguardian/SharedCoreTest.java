package com.example.soundguardian;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.soundguardian.core.AppConfig;
import com.soundguardian.core.EventEngine;
import com.soundguardian.core.SoundContract;
import com.soundguardian.core.YamnetClassifier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 验证原应用实际打包的共享配置与模型，而非测试副本。 */
@RunWith(AndroidJUnit4.class)
public final class SharedCoreTest {
    @Test public void originalAppLoadsSharedAssetsAndInference() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppConfig config = new AppConfig(context);
        assertEquals(SoundContract.CLASSES,config.labels.length);
        assertEquals(18,config.rules.size());
        EventEngine.Rule cough=config.rules.stream().filter(r->r.label.equals("Cough")).findFirst().get();
        assertEquals(.30f,cough.on,.00001f);
        assertEquals(.12f,cough.off,.00001f);
        assertEquals("咳嗽",config.name("Cough"));
        try (YamnetClassifier model=new YamnetClassifier(context)) {
            float[] scores=model.classify(new float[SoundContract.INPUT_SAMPLES]);
            assertEquals(SoundContract.CLASSES,scores.length);
            int silence=java.util.Arrays.asList(config.labels).indexOf("Silence");
            assertTrue(scores[silence]>.5f);
        }
    }
}

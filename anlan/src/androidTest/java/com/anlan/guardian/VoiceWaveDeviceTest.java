package com.anlan.guardian;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 使用真实绘制验证幅度响应和过期回落，不替代物理麦克风验收。 */
@RunWith(AndroidJUnit4.class)
public final class VoiceWaveDeviceTest {
    @Test public void voiceRaisesCrestsAndStaleLevelSettlesBackToBreathing() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        VoiceWaveView[] wave = new VoiceWaveView[1];
        Bitmap bitmap = Bitmap.createBitmap(720, 180, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        instrumentation.runOnMainSync(() -> {
            wave[0] = new VoiceWaveView(instrumentation.getTargetContext());
            wave[0].layout(0, 0, 720, 180); wave[0].phase("LISTENING");
        });
        int[] peaks = new int[3];
        try {
            for (int stage = 0; stage < 3; stage++) {
                final int current = stage;
                for (int frame = 0; frame < 40; frame++) {
                    final int index = frame;
                    instrumentation.runOnMainSync(() -> {
                        bitmap.eraseColor(0);
                        wave[0].level(current == 0 ? 0 : 1, SystemClock.elapsedRealtime() - (current == 2 ? 1000 : 0));
                        wave[0].draw(canvas);
                        if (index >= 25) {
                            for (int y = 0; y < bitmap.getHeight(); y++) {
                                boolean hit = false;
                                for (int x = 0; x < bitmap.getWidth(); x += 3) {
                                    if ((bitmap.getPixel(x, y) >>> 24) > 10) { hit = true; break; }
                                }
                                if (hit) { peaks[current] = Math.max(peaks[current], bitmap.getHeight() - y); break; }
                            }
                        }
                    });
                    SystemClock.sleep(20);
                }
            }
            assertTrue("强音量应明显抬高波峰", peaks[1] > peaks[0] + 15);
            assertTrue("过期音量应回落", peaks[2] < peaks[1] - 15);
            instrumentation.runOnMainSync(() -> {
                wave[0].phase("IDLE"); bitmap.eraseColor(0); wave[0].draw(canvas);
                assertEquals(0, bitmap.getPixel(360, 179));
            });
        } finally { bitmap.recycle(); }
    }
}

package com.anlan.guardian;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 真正经过服务、唯一 FP32 TTS 和倾听窗口；模拟器不代表真人可听与远场效果验收。 */
@RunWith(AndroidJUnit4.class)
public final class CarePlaybackIntegrationTest {
    @Test public void realNoticeCompletesAndAskStartsTimeoutAfterPlayback() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation(); Context app = instrumentation.getTargetContext();
        android.media.AudioManager audio = app.getSystemService(android.media.AudioManager.class);
        boolean previousMute = audio.isMicrophoneMute();
        android.content.SharedPreferences preferences = app.getSharedPreferences("care", 0);
        boolean previous = preferences.getBoolean("enabled", false);
        boolean previousQuiet = preferences.getBoolean("quiet_enabled", true);
        int previousStart = preferences.getInt("quiet_start", 1320), previousEnd = preferences.getInt("quiet_end", 420);
        preferences.edit().putBoolean("enabled", true).putBoolean("quiet_enabled", false).commit();
        try (android.os.ParcelFileDescriptor permission = instrumentation.getUiAutomation().executeShellCommand("pm grant com.anlan.guardian android.permission.RECORD_AUDIO");
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(permission)) {
            byte[] buffer = new byte[256]; while (input.read(buffer) != -1) { }
        }
        MainActivity activity = (MainActivity)instrumentation.startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (GuardianRuntime.snapshot().status != GuardianRuntime.Status.LISTENING && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
            assertEquals(GuardianRuntime.Status.LISTENING, GuardianRuntime.snapshot().status);
            AtomicReference<Care.Result> result = new AtomicReference<>(); CountDownLatch notice = new CountDownLatch(1);
            Care.submit(new InteractionPorts.CareRequest(java.util.UUID.randomUUID().toString(), "integration-test", "", "我在这里，陪着你。",
                    InteractionPorts.CareRequest.Mode.NOTICE, 0, System.currentTimeMillis() + 60000), value -> { result.set(value); notice.countDown(); });
            assertTrue(notice.await(45, TimeUnit.SECONDS)); assertEquals(Care.Status.COMPLETED, result.get().status);
            SystemClock.sleep(700);
            // 使用平台静音保证本用例确实没有外部输入；文字/情绪回传由固定 PCM 的独立用例验证。
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                instrumentation.getUiAutomation().adoptShellPermissionIdentity(android.Manifest.permission.MODIFY_AUDIO_SETTINGS);
                try { audio.setMicrophoneMute(true); } finally { instrumentation.getUiAutomation().dropShellPermissionIdentity(); }
                assertTrue("超时用例需要明确的静音输入", audio.isMicrophoneMute());
            }
            CountDownLatch ask = new CountDownLatch(1), listening = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicLong inputAt = new java.util.concurrent.atomic.AtomicLong();
            Care.submit(new InteractionPorts.CareRequest(java.util.UUID.randomUUID().toString(), "integration-test", "", "你还好吗？",
                    InteractionPorts.CareRequest.Mode.ASK, 5000, System.currentTimeMillis() + 60000), new Care.Listener() {
                @Override public void onState(String id, String phase) {
                    if (phase.equals("LISTENING")) { inputAt.set(SystemClock.elapsedRealtime()); listening.countDown(); }
                }
                @Override public void onResult(Care.Result value) { result.set(value); ask.countDown(); }
            });
            assertTrue(listening.await(45, TimeUnit.SECONDS)); assertTrue(ask.await(10, TimeUnit.SECONDS));
            assertEquals(Care.Status.TIMEOUT, result.get().status);
            assertTrue(SystemClock.elapsedRealtime() - inputAt.get() >= 4900);
            java.util.Calendar local = java.util.Calendar.getInstance();
            int minute = local.get(java.util.Calendar.HOUR_OF_DAY) * 60 + local.get(java.util.Calendar.MINUTE);
            preferences.edit().putBoolean("quiet_enabled", true).putInt("quiet_start", minute).putInt("quiet_end", (minute + 2) % 1440).commit();
            CountDownLatch quiet = new CountDownLatch(1);
            Care.submit(new InteractionPorts.CareRequest(java.util.UUID.randomUUID().toString(), "external-caller", "", "不应在安静时段播放。",
                    InteractionPorts.CareRequest.Mode.NOTICE, 0, System.currentTimeMillis() + 60000), value -> { result.set(value); quiet.countDown(); });
            assertTrue(quiet.await(5, TimeUnit.SECONDS)); assertEquals("QUIET_HOURS", result.get().reason);
        } finally {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                instrumentation.getUiAutomation().adoptShellPermissionIdentity(android.Manifest.permission.MODIFY_AUDIO_SETTINGS);
                try { audio.setMicrophoneMute(previousMute); } finally { instrumentation.getUiAutomation().dropShellPermissionIdentity(); }
            }
            app.stopService(new Intent(app, GuardianService.class)); instrumentation.runOnMainSync(activity::finish);
            preferences.edit().putBoolean("enabled", previous).putBoolean("quiet_enabled", previousQuiet)
                    .putInt("quiet_start", previousStart).putInt("quiet_end", previousEnd).commit();
        }
    }
}

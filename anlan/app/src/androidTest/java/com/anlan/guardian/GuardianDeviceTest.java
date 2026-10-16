package com.anlan.guardian;
import com.soundguardian.core.YamnetClassifier;
import com.soundguardian.core.AppConfig;
import com.soundguardian.core.SoundContract;
import com.soundguardian.core.EventEngine;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class GuardianDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context app = instrumentation.getTargetContext();

    @Test public void modelMatchesDesktopFixture() throws Exception {
        byte[] pcm;
        try (InputStream in = instrumentation.getContext().getAssets().open("parity.pcm")) { pcm = readAll(in); }
        ByteBuffer buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        float[] input = new float[15600]; for (int i=0;i<input.length;i++) input[i] = buffer.getShort()/32768f;
        float[] expected;
        try (InputStream in=instrumentation.getContext().getAssets().open("parity_scores.txt")) {
            String[] lines = new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8).trim().split("\\s+");
            expected = new float[lines.length]; for (int i=0;i<lines.length;i++) expected[i]=Float.parseFloat(lines[i]);
        }
        try (YamnetClassifier model = new YamnetClassifier(app)) {
            assertEquals(521, expected.length);
            assertArrayEquals(expected, model.classify(input), .002f);
        }
    }

    @Test public void sharedAssetsAndContractLoadFromApplication() throws Exception {
        AppConfig config = new AppConfig(app);
        org.json.JSONObject manifest = new org.json.JSONObject(AppConfig.read(app,"model_manifest.json"));
        assertEquals(SoundContract.SAMPLE_RATE, manifest.getInt("sample_rate"));
        assertEquals(SoundContract.INPUT_SAMPLES, manifest.getInt("input_samples"));
        assertEquals(SoundContract.HOP_SAMPLES, manifest.getInt("hop_samples"));
        assertEquals(SoundContract.CLASSES,config.labels.length);
        assertEquals(18,config.rules.size());
        EventEngine.Rule cough=config.rules.stream().filter(r->r.label.equals("Cough")).findFirst().get();
        assertEquals(.30f,cough.on,.00001f);
        assertEquals(.12f,cough.off,.00001f);
        assertEquals(1,cough.hits);
        assertEquals("咳嗽",config.name("Cough"));
        try { config.rules.clear(); fail("共享规则必须只读"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void historySurvivesReopen() {
        String id="test:"+System.currentTimeMillis();
        try (EventStore store = new EventStore(app)) {
            store.save(Collections.singletonList(new SoundEvent(id,"Cough","测试咳嗽",1000,1600,false)));
        }
        try (EventStore store = new EventStore(app)) {
            assertTrue(store.recent().stream().anyMatch(e -> id.equals(e.id) && e.name.equals("测试咳嗽")));
            store.getWritableDatabase().delete("events","id=?",new String[]{id});
        }
    }

    @Test public void unchangedHistoryDoesNotRewriteDatabase() {
        String id = "write-test:" + System.currentTimeMillis();
        try (EventStore store = new EventStore(app)) {
            android.database.sqlite.SQLiteDatabase db = store.getWritableDatabase();
            db.execSQL("CREATE TEMP TABLE write_count (n INTEGER)");
            db.execSQL("INSERT INTO write_count VALUES (0)");
            db.execSQL("CREATE TEMP TRIGGER count_event_insert AFTER INSERT ON main.events BEGIN UPDATE write_count SET n=n+1; END");
            try {
                store.save(Collections.singletonList(new SoundEvent(id, "Cough", "测试咳嗽", 1000, 1600, true)));
                for (int i = 0; i < 5; i++) store.save(Collections.singletonList(new SoundEvent(id, "Cough", "测试咳嗽", 1000, 1600, true)));
                assertEquals(1, android.database.DatabaseUtils.longForQuery(db, "SELECT n FROM write_count", null));
                store.save(Collections.singletonList(new SoundEvent(id, "Cough", "测试咳嗽", 1000, 2000, false)));
                assertEquals(2, android.database.DatabaseUtils.longForQuery(db, "SELECT n FROM write_count", null));
                assertTrue(store.recent().stream().anyMatch(e -> id.equals(e.id) && e.end == 2000 && !e.active));
            } finally {
                db.execSQL("DROP TRIGGER count_event_insert");
                db.execSQL("DROP TABLE write_count");
                db.delete("events", "id=?", new String[]{id});
            }
        }
    }

    @Test public void automaticListeningStopsWithTaskAndRestoresPage() throws Exception {
        instrumentation.getUiAutomation().executeShellCommand("pm grant com.anlan.guardian android.permission.RECORD_AUDIO").close();
        MainActivity[] activity = {launch()};
        try {
            // 启动后无需点击便进入真实推理，并保持前台窗口常亮。
            await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.LISTENING, 20000);
            instrumentation.runOnMainSync(() -> assertTrue((activity[0].getWindow().getAttributes().flags
                    & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0));
            for (int cycle=0;cycle<3;cycle++) {
                await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.LISTENING, 20000);
                assertTrue(GuardianRuntime.snapshot().inferenceMs > 0);
                // 等同于从最近任务划掉应用，服务必须随任务停止。
                instrumentation.runOnMainSync(() -> app.getSystemService(android.app.ActivityManager.class)
                        .getAppTasks().forEach(android.app.ActivityManager.AppTask::finishAndRemoveTask));
                await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.STOPPED, 6000);
                activity[0] = launch();
            }
            await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.LISTENING,20000);
            instrumentation.runOnMainSync(activity[0]::recreate);
            SystemClock.sleep(900);
            assertEquals(GuardianRuntime.Status.LISTENING,GuardianRuntime.snapshot().status);
            app.stopService(new Intent(app,GuardianService.class));
            await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.STOPPED,6000);
        } finally {
            app.stopService(new Intent(app,GuardianService.class));
            instrumentation.runOnMainSync(() -> app.getSystemService(android.app.ActivityManager.class)
                    .getAppTasks().forEach(android.app.ActivityManager.AppTask::finishAndRemoveTask));
        }
    }

    @Test public void renderAllExpressionsAndIcon() throws Exception {
        MainActivity activity=launch();
        try {
            final Bitmap[] output = new Bitmap[1];
            instrumentation.runOnMainSync(() -> {
                Bitmap sheet=Bitmap.createBitmap(1280,7*210,Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(sheet); canvas.drawColor(0xFF0B141C);
                Paint text=new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(0xFFA6C1B8); text.setTextSize(17); text.setTextAlign(Paint.Align.CENTER);
                int i=0;
                for(Emotion emotion:Emotion.values()) {
                    int x=i%4*320,y=i/4*210;
                    FaceView face=new FaceView(activity); face.layout(0,0,320,180);
                    face.setState(new BehaviorEngine.State(emotion,"",0,false,""),SystemClock.elapsedRealtime()-600);
                    canvas.save(); canvas.clipRect(x,y,x+320,y+180); canvas.translate(x,y); face.draw(canvas); canvas.restore();
                    canvas.drawText(emotion.title,x+160,y+198,text); i++;
                }
                android.graphics.drawable.Drawable icon=activity.getDrawable(R.mipmap.ic_launcher);
                icon.setBounds(860,1290,980,1410); icon.draw(canvas);
                canvas.drawText("安澜 · 安心相伴",1080,1360,text);
                output[0]=sheet;
            });
            try(FileOutputStream out=new FileOutputStream(new File(app.getExternalFilesDir(null),"expressions.png"))) { output[0].compress(Bitmap.CompressFormat.PNG,100,out); }
            output[0].recycle();
        } finally { app.stopService(new Intent(app,GuardianService.class)); instrumentation.runOnMainSync(activity::finish); }
    }

    @Test public void modelBubblesFloatFadeAndThrottleDuplicates() throws Exception {
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                ModelResultsView panel = activity.findViewById(R.id.event_name);
                GuardianRuntime.ModelResult speech = new GuardianRuntime.ModelResult("Speech", "说话", .68f);
                GuardianRuntime.ModelResult music = new GuardianRuntime.ModelResult("Music", "音乐", .40f);
                long now = SystemClock.elapsedRealtime();
                GuardianRuntime.Snapshot both = new GuardianRuntime.Snapshot(GuardianRuntime.Status.LISTENING,
                        Collections.emptyList(), java.util.Arrays.asList(speech, music), "", "说话", "Speech", .68f, 1, -35, true);
                panel.render(both, now);
                assertEquals(1, panel.getChildCount());
                assertNotNull(findText(panel, "说话"));
                assertNotNull(findText(panel, "68"));
                android.view.View speechBubble = panel.getChildAt(0);
                android.animation.ValueAnimator motion = (android.animation.ValueAnimator) speechBubble.getTag();
                motion.setCurrentPlayTime(2000);
                assertTrue(speechBubble.getTranslationY() < 0);
                assertTrue(speechBubble.getAlpha() > 0 && speechBubble.getAlpha() < 1);
                panel.render(both, now + 500);
                assertEquals(1, panel.getChildCount());
                GuardianRuntime.Snapshot onlyMusic = new GuardianRuntime.Snapshot(GuardianRuntime.Status.LISTENING,
                        Collections.emptyList(), Collections.singletonList(music), "", "音乐", "Music", .40f, 1, -35, true);
                panel.render(onlyMusic, now + 1000);
                assertEquals(2, panel.getChildCount());
                assertNotNull(findText(panel, "音乐"));
                assertNotNull(findText(panel, "40"));
                motion.end();
                assertEquals(1, panel.getChildCount());
                assertNull(findText(panel, "说话"));
                panel.setVisibility(android.view.View.GONE);
                assertEquals(0, panel.getChildCount());
                panel.setVisibility(android.view.View.VISIBLE);
            });
        } finally {
            app.stopService(new Intent(app, GuardianService.class));
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void drawerReturnsToParentWithoutOpeningDialogs() throws Exception {
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                android.view.ViewGroup content = activity.findViewById(android.R.id.content);
                assertTrue(content.getChildAt(0) instanceof androidx.drawerlayout.widget.DrawerLayout);
                androidx.drawerlayout.widget.DrawerLayout drawer = (androidx.drawerlayout.widget.DrawerLayout) content.getChildAt(0);
                activity.findViewById(R.id.menu).performClick();
                drawer.openDrawer(android.view.Gravity.RIGHT, false);
                findText(drawer, "设置").performClick();
                assertNull(findText(drawer, "显示设置"));
                assertTrue(findText(drawer, "显示声音强度") instanceof android.widget.CheckBox);
                assertNotNull(findText(drawer, "屏幕亮度"));
                findText(drawer, "屏幕亮度").performClick();
                activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
                assertTrue(findText(drawer, "表情名称") instanceof android.widget.CheckBox);
                assertTrue(drawer.isDrawerOpen(android.view.Gravity.RIGHT));
                activity.onBackPressed();
                assertNotNull(findText(drawer, "小安 · 智能守护"));
                findText(drawer, "预览").performClick();
                assertNotNull(findText(drawer, "模拟声音事件"));
                findText(drawer, "事件记录").performClick();
                findDescription(drawer, "返回上一级").performClick();
                assertNotNull(findText(drawer, "表情预览"));
                activity.closeDrawer();
            });
        } finally {
            app.stopService(new Intent(app, GuardianService.class));
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private android.view.View findText(android.view.View view, String value) {
        if (view instanceof android.widget.TextView && value.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { android.view.View found = findText(group.getChildAt(i), value); if (found != null) return found; }
        }
        return null;
    }

    private android.view.View findDescription(android.view.View view, String value) {
        if (value.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { android.view.View found = findDescription(group.getChildAt(i), value); if (found != null) return found; }
        }
        return null;
    }

    private MainActivity launch() throws Exception {
        // 从测试 shell 前台启动，避免厂商后台启动限制干扰测试本身。
        try (InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand("am start -W -n com.anlan.guardian/.MainActivity"))) { readAll(in); }
        final MainActivity[] result = new MainActivity[1];
        await(() -> {
            instrumentation.runOnMainSync(() -> {
                for (android.app.Activity a : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                    if (a instanceof MainActivity) result[0] = (MainActivity)a;
                }
            });
            return result[0] != null;
        }, 10000);
        return result[0];
    }
    private void await(BooleanSupplier condition,long timeout) {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(!condition.getAsBoolean() && SystemClock.elapsedRealtime()<end) SystemClock.sleep(100);
        assertTrue("等待状态超时："+GuardianRuntime.snapshot().status+" "+GuardianRuntime.snapshot().error,condition.getAsBoolean());
    }
    private byte[] readAll(InputStream in)throws Exception {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int count;
        while((count=in.read(buffer))!=-1) out.write(buffer,0,count); return out.toByteArray();
    }
}

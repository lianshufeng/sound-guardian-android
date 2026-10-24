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
        assertEquals(19,config.rules.size());
        EventEngine.Rule snapping=config.rules.stream().filter(r->r.label.equals("Finger snapping")).findFirst().get();
        assertEquals(2,snapping.hits); assertEquals(15000,snapping.window);
        assertEquals(.20f,snapping.on,0.0001f);
        assertEquals(1000,snapping.continuousMs);
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
        // 图标和表情绘制不依赖麦克风服务或 Activity 启动。
            final Bitmap[] output = new Bitmap[1];
            instrumentation.runOnMainSync(() -> {
                int rows = (Emotion.values().length + 3) / 4;
                Bitmap sheet=Bitmap.createBitmap(1280,rows*210+170,Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(sheet); canvas.drawColor(0xFF0B141C);
                Paint text=new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(0xFFA6C1B8); text.setTextSize(17); text.setTextAlign(Paint.Align.CENTER);
                int i=0;
                for(Emotion emotion:Emotion.values()) {
                    int x=i%4*320,y=i/4*210;
                    FaceView face=new FaceView(app); face.layout(0,0,320,180);
                    face.setState(new BehaviorEngine.State(emotion,"",0,false,""),SystemClock.elapsedRealtime()-600);
                    canvas.save(); canvas.clipRect(x,y,x+320,y+180); canvas.translate(x,y); face.draw(canvas); canvas.restore();
                    canvas.drawText(emotion.title,x+160,y+198,text); i++;
                }
                android.graphics.drawable.Drawable icon=app.getDrawable(R.mipmap.ic_launcher);
                icon.setBounds(860,rows*210+20,980,rows*210+140); icon.draw(canvas);
                canvas.drawText("红糖 · 安心相伴",1080,rows*210+90,text);
                output[0]=sheet;
            });
            try(FileOutputStream out=new FileOutputStream(new File(app.getExternalFilesDir(null),"expressions.png"))) { output[0].compress(Bitmap.CompressFormat.PNG,100,out); }
            output[0].recycle();
            Bitmap iconPreview=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);
            android.graphics.drawable.Drawable rectangle=app.getDrawable(R.drawable.ic_guardian_launcher);
            rectangle.setBounds(0,0,512,512); rectangle.draw(new Canvas(iconPreview));
            try(FileOutputStream out=new FileOutputStream(new File(app.getExternalFilesDir(null),"icon-rectangle.png"))) { iconPreview.compress(Bitmap.CompressFormat.PNG,100,out); }
            iconPreview.recycle();
    }

    @Test public void modelBubblesFloatFadeAndThrottleDuplicates() throws Exception {
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                ModelResultsView panel = activity.findViewById(R.id.event_name);
                // 不依赖设备保留的显示偏好或麦克风产生的已有气泡。
                panel.setVisibility(android.view.View.GONE);
                panel.setVisibility(android.view.View.VISIBLE);
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

    @Test public void soundStrengthDisplaysLouderPcmWithoutModelResults() throws Exception {
        MainActivity activity = launch();
        boolean previous = activity.isNameVisible("show_sound_level");
        java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("render"); render.setAccessible(true);
        try {
            instrumentation.runOnMainSync(() -> {
                long token = GuardianRuntime.begin();
                android.widget.TextView meter = activity.findViewById(R.id.sound_level);
                activity.setNameVisible("show_sound_level", true);
                for (short amplitude : new short[]{0, 32767}) {
                    GuardianRuntime.publishSoundLevel(token, new short[]{amplitude}, 1, SystemClock.elapsedRealtime());
                    try { render.invoke(activity); } catch (Exception error) { throw new IllegalStateException(error); }
                    assertEquals(amplitude == 0 ? "声音强度 0" : "声音强度 100", meter.getText().toString());
                    assertEquals(android.view.View.VISIBLE, meter.getVisibility());
                }
                activity.setNameVisible("show_sound_level", false);
                assertEquals(android.view.View.GONE, meter.getVisibility());
            });
        } finally {
            instrumentation.runOnMainSync(() -> { activity.setNameVisible("show_sound_level", previous); activity.finish(); });
            app.stopService(new Intent(app, GuardianService.class));
        }
    }

    @Test public void careCategorySelectionPersistsAndLeavesOtherSwitchesUnchanged() throws Exception {
        MainActivity activity = launch();
        android.content.SharedPreferences preferences = app.getSharedPreferences("care", Context.MODE_PRIVATE);
        String[] groups = {"group_body", "group_emotion", "group_companion", "group_family", "group_safety", "group_weather", "group_schedule"};
        java.util.Map<String, ?> previous = preferences.getAll();
        try {
            preferences.edit().putBoolean("enabled", false).putBoolean("quiet_enabled", true).putBoolean("bedtime_enabled", false).commit();
            instrumentation.runOnMainSync(() -> {
                new CarePanel(activity, () -> { }).show();
                android.view.View root = activity.getWindow().getDecorView();
                findText(root, "全选").performClick();
                for (String key : groups) assertTrue(preferences.getBoolean(key, false));
                findText(root, "反选").performClick();
                for (String key : groups) assertFalse(preferences.getBoolean(key, true));
                ((android.widget.CheckBox)findText(root, "身体声音关怀")).setChecked(true);
                findText(root, "反选").performClick();
                assertFalse(preferences.getBoolean(groups[0], true));
                for (int i = 1; i < groups.length; i++) assertTrue(preferences.getBoolean(groups[i], false));
                new CarePanel(activity, () -> { }).show();
                assertFalse(((android.widget.CheckBox)findText(root, "身体声音关怀")).isChecked());
                assertTrue(((android.widget.CheckBox)findText(root, "情绪陪伴关怀")).isChecked());
                assertFalse(preferences.getBoolean("enabled", true));
                assertTrue(preferences.getBoolean("quiet_enabled", false));
                assertFalse(preferences.getBoolean("bedtime_enabled", true));
            });
        } finally {
            android.content.SharedPreferences.Editor editor = preferences.edit();
            java.util.List<String> keys = new java.util.ArrayList<>(java.util.Arrays.asList(groups));
            keys.addAll(java.util.Arrays.asList("enabled", "quiet_enabled", "bedtime_enabled"));
            for (String key : keys) { if (previous.containsKey(key)) editor.putBoolean(key, (Boolean)previous.get(key)); else editor.remove(key); }
            editor.commit(); app.stopService(new Intent(app, GuardianService.class));
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
                activity.findViewById(R.id.clock).performClick();
                drawer.openDrawer(android.view.Gravity.RIGHT, false);
                findText(drawer, "设置").performClick();
                assertNull(findText(drawer, "显示设置"));
                assertTrue(findText(drawer, "显示声音强度") instanceof android.widget.CheckBox);
                assertNotNull(findText(drawer, "屏幕亮度"));
                findText(drawer, "屏幕亮度").performClick();
                android.widget.SeekBar brightness = findSeekBar(drawer);
                assertNotNull(brightness);
                brightness.measure(android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(64, android.view.View.MeasureSpec.EXACTLY));
                brightness.layout(0, 0, 600, 64);
                int before = brightness.getProgress();
                long down = SystemClock.uptimeMillis();
                brightness.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, 8, brightness.getHeight() / 2f, 0));
                brightness.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down + 80, android.view.MotionEvent.ACTION_MOVE, brightness.getWidth() - 8, brightness.getHeight() / 2f, 0));
                brightness.dispatchTouchEvent(android.view.MotionEvent.obtain(down, down + 120, android.view.MotionEvent.ACTION_UP, brightness.getWidth() - 8, brightness.getHeight() / 2f, 0));
                assertTrue("亮度滑动条应支持拖动", brightness.getProgress() > before);
                activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
                assertTrue(findText(drawer, "表情名称") instanceof android.widget.CheckBox);
                assertTrue(drawer.isDrawerOpen(android.view.Gravity.RIGHT));
                activity.onBackPressed();
                assertNotNull(findText(drawer, "小糖 · 智能守护"));
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

    @Test public void careSettingsNavigateAndPersistExplicitChoices() throws Exception {
        android.content.SharedPreferences prefs = app.getSharedPreferences("care", 0);
        String[] keys = {"enabled", "group_body"};
        java.util.Map<String, Boolean> saved = new java.util.HashMap<>();
        for (String key : keys) if (prefs.contains(key)) saved.put(key, prefs.getBoolean(key, false));
        prefs.edit().putBoolean("enabled", false).putBoolean("group_body", false).commit();
        MainActivity activity = launch();
        try {
            instrumentation.runOnMainSync(() -> {
                android.view.ViewGroup root = activity.findViewById(android.R.id.content);
                activity.findViewById(R.id.clock).performClick();
                findText(root, "设置").performClick(); findText(root, "主动关怀").performClick();
                assertNotNull(findText(root, "关怀记录及对话文字自动保存，最多保留3天。"));
                String[] labels = {"允许主动关怀", "身体声音关怀"};
                for (String label : labels) {
                    android.widget.CheckBox box = (android.widget.CheckBox)findText(root, label);
                    assertNotNull(label, box); assertFalse(box.isChecked()); box.performClick(); assertTrue(box.isChecked());
                }
                for (String key : keys) assertTrue(key, prefs.getBoolean(key, false));
                findText(root, "我的定时提醒").performClick(); assertNotNull(findText(root, "添加每日提醒"));
                findDescription(root, "返回上一级").performClick();
                assertTrue(((android.widget.CheckBox)findText(root, "允许主动关怀")).isChecked());
                findText(root, "关怀记录").performClick();
                findDescription(root, "返回上一级").performClick();
                assertNotNull(findText(root, "停止当前关怀"));
                activity.closeDrawer();
            });
        } finally {
            app.stopService(new Intent(app, GuardianService.class)); instrumentation.runOnMainSync(activity::finish);
            android.content.SharedPreferences.Editor edit = prefs.edit();
            for (String key : keys) { if (saved.containsKey(key)) edit.putBoolean(key, saved.get(key)); else edit.remove(key); }
            edit.commit();
        }
    }

    @Test public void twoConfirmedSnapEdgesReachHappyFaceMessageAndPreview() throws Exception {
        AppConfig config = new AppConfig(app);
        EventEngine engine = new EventEngine(config.rules);
        EventEngine.Rule snap = config.rules.stream().filter(rule -> rule.label.equals("Finger snapping")).findFirst().get();
        long end = System.currentTimeMillis(); float[] scores = new float[SoundContract.CLASSES];
        for (int i = 0; i < 2; i++) {
            scores[snap.index] = .24f; engine.update(scores, end - 4000 + i * 4000);
            if (i < 1) { assertTrue(engine.history.isEmpty()); scores[snap.index] = 0; engine.update(scores, end - 3000 + i * 4000); }
        }
        assertEquals(1, engine.history.size()); assertTrue(engine.history.get(0).active);
        EventEngine continuous = new EventEngine(config.rules);
        for (int i = 0; i < 3; i++) { continuous.update(scores, end + i * 480); assertTrue(continuous.history.isEmpty()); }
        continuous.update(scores, end + 1440); assertEquals(1, continuous.history.size());
        BehaviorEngine behavior = new BehaviorEngine(BehaviorEngine.Timing.defaults(), new java.util.Random(1), 0);
        behavior.setGuarding(true, 0);
        EventEngine.Event event = engine.history.get(0);
        behavior.events(Collections.singletonList(new SoundEvent("snap", snap.label, snap.zh, event.start, event.end, true)), 100);
        assertEquals(Emotion.HAPPY, behavior.state(100).emotion);
        assertEquals(BehaviorEngine.SNAP_MESSAGE, behavior.state(100).message);
        MainActivity activity = launch();
        try {
            await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.LISTENING, 30000);
            instrumentation.runOnMainSync(() -> {
                android.view.ViewGroup root = activity.findViewById(android.R.id.content);
                activity.findViewById(R.id.clock).performClick(); findText(root, "预览").performClick();
                findText(root, "模拟声音事件").performClick();
                findText(root, "开心响指（连续或两次）").performClick();
                android.widget.TextView caption = activity.findViewById(R.id.emotion_name);
                assertEquals(GuardianRuntime.SpeechPhase.PREPARING, GuardianRuntime.speechSnapshot().phase);
                assertEquals(InteractionPorts.SpeechRequest.Source.INTERACTION, GuardianRuntime.speechSnapshot().request.source);
                assertEquals(Emotion.HAPPY.title, caption.getText().toString());
            });
        } finally {
            app.stopService(new Intent(app, GuardianService.class)); instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void inputUiDistinguishesPreparationReadyAndEndWithoutReplayingAudio() throws Exception {
        MainActivity activity = launch();
        try {
            app.stopService(new Intent(app, GuardianService.class));
            await(() -> GuardianRuntime.snapshot().status == GuardianRuntime.Status.STOPPED, 10000);
            java.lang.reflect.Method receive = MainActivity.class.getDeclaredMethod("receive", Intent.class);
            receive.setAccessible(true);
            instrumentation.runOnMainSync(() -> {
                long token = GuardianRuntime.begin();
                GuardianRuntime.publish(token, new GuardianRuntime.Snapshot(GuardianRuntime.Status.LISTENING,
                        Collections.emptyList(), "", "", 0, -96, false));
                GuardianRuntime.publishInput("PREPARING");
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
                android.widget.TextView hint = activity.findViewById(R.id.input_hint);
                assertEquals("正在准备，请稍候", hint.getText().toString());
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.conversation_bar).getVisibility());
                android.view.ViewGroup panel = activity.findViewById(R.id.conversation_bar);
                assertTrue(panel.getChildAt(0) instanceof VoiceWaveView);
                assertNull(findText(panel, "结束"));
                android.view.View header = activity.findViewById(R.id.header);
                assertTrue(findText(header, "红糖").hasOnClickListeners());
                assertNull(findText(header, "说话"));
                GuardianRuntime.publishInput("CUE");
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
                assertEquals("提示音结束后请说话", hint.getText().toString());
                GuardianRuntime.publishInput("LISTENING");
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
                assertEquals("请说话", hint.getText().toString());
                assertEquals(0, GuardianRuntime.speechSnapshot().id);
                GuardianRuntime.publishUnderstanding(token, new InteractionPorts.UnderstandingState("正在听你说话", true,
                        SystemClock.elapsedRealtime(), null, 0, .85f));
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
                assertEquals("正在听你说话", hint.getText().toString());
                GuardianRuntime.publishInput("MONITORING"); GuardianRuntime.publishConversation("ui-continuation", "THINKING");
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
                assertEquals("想一想", hint.getText().toString());
                GuardianRuntime.publishConversation("ui-continuation", "WAITING_REPLY");
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
                assertEquals("还在想，请稍候", hint.getText().toString());
                GuardianRuntime.publishConversation("ui-continuation", "PREPARING_SPEECH");
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
                assertEquals("正在准备声音", hint.getText().toString());
                GuardianRuntime.publishInput("LISTENING"); GuardianRuntime.publishConversation("", "IDLE");
            });
            SystemClock.sleep(300);
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                int[] headerBounds = new int[4];
                instrumentation.runOnMainSync(() -> {
                    android.view.View header = activity.findViewById(R.id.header);
                    int[] location = new int[2]; header.getLocationOnScreen(location);
                    headerBounds[0] = location[0]; headerBounds[1] = location[1];
                    headerBounds[2] = location[0] + header.getWidth(); headerBounds[3] = location[1] + header.getHeight();
                });
                int headerPixels = 0;
                for (int y = Math.max(0, headerBounds[1]); y < Math.min(screenshot.getHeight(), headerBounds[3]); y++) {
                    for (int x = Math.max(0, headerBounds[0]); x < Math.min(screenshot.getWidth(), headerBounds[2]); x++) {
                        if ((screenshot.getPixel(x, y) & 0xFFFFFF) == 0xA6C1B8) headerPixels++;
                    }
                }
                assertTrue("波浪和表情重绘后顶部栏文字仍须真实可见", headerPixels > 20);
                try (FileOutputStream output = new FileOutputStream(new File(app.getCacheDir(), "wake-listening-ready.png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
                } finally { screenshot.recycle(); }
            }
            instrumentation.runOnMainSync(() -> {
                GuardianRuntime.publishInput("IDLE");
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
            });
            SystemClock.sleep(250);
            instrumentation.runOnMainSync(() -> assertEquals(android.view.View.GONE, activity.findViewById(R.id.conversation_bar).getVisibility()));
            instrumentation.runOnMainSync(() -> {
                long token = GuardianRuntime.begin();
                GuardianRuntime.publishUnderstanding(token, new InteractionPorts.UnderstandingState("本地窗口", false,
                        SystemClock.elapsedRealtime(), null, 0, 0, true));
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
                assertEquals("请说话", ((android.widget.TextView)activity.findViewById(R.id.input_hint)).getText().toString());
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.conversation_bar).getVisibility());
            });
            SystemClock.sleep(800);
            instrumentation.runOnMainSync(() -> {
                try { receive.invoke(activity, new Object[]{null}); } catch (Exception error) { throw new IllegalStateException(error); }
            });
            SystemClock.sleep(250);
            instrumentation.runOnMainSync(() -> assertEquals(android.view.View.GONE, activity.findViewById(R.id.conversation_bar).getVisibility()));
        } finally {
            instrumentation.runOnMainSync(() -> {
                long token = GuardianRuntime.begin();
                GuardianRuntime.publish(token, new GuardianRuntime.Snapshot(GuardianRuntime.Status.STOPPED,
                        Collections.emptyList(), "", "", 0, -96, false));
                activity.finish();
            });
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
    private android.widget.SeekBar findSeekBar(android.view.View view) {
        if (view instanceof android.widget.SeekBar) return (android.widget.SeekBar)view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.SeekBar found = findSeekBar(group.getChildAt(i));
                if (found != null) return found;
            }
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
        instrumentation.getUiAutomation().executeShellCommand("pm grant com.anlan.guardian android.permission.RECORD_AUDIO").close();
        if (android.os.Build.VERSION.SDK_INT >= 33)
            instrumentation.getUiAutomation().executeShellCommand("pm grant com.anlan.guardian android.permission.POST_NOTIFICATIONS").close();
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

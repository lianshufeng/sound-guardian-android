package com.anlan.guardian;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** 所有辅助内容放入面板，主屏保持安静。 */
final class ControlPanel {
    private final MainActivity activity;
    private final Consumer<Emotion> preview;
    private final Consumer<SoundEvent> simulate;
    ControlPanel(MainActivity activity, Consumer<Emotion> preview, Consumer<SoundEvent> simulate) {
        this.activity = activity; this.preview = preview; this.simulate = simulate;
    }
    void show() {
        String[] items = {"设置", "预览", "设备状态", "AI模型", "关于"};
        drawer("小糖 · 智能守护", items, false, which -> {
            switch (which) {
                case 0: settings(); break;
                case 1: previews(); break;
                case 2: diagnostics(); break;
                case 3: new AiModelPanel(activity, this::show).show(); break;
                case 4: about(); break;
                default: break;
            }
        });
    }
    private void settings() {
        String[] names = {"表情名称", "声音事件", "显示声音强度"};
        String[] keys = {"show_emotion_name", "show_event_name", "show_sound_level"};
        boolean[] checked = {activity.isNameVisible(keys[0]), activity.isNameVisible(keys[1]), activity.isNameVisible(keys[2])};
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(18), dp(16), dp(18), 0);
        TextView note = text("选择主界面要显示的信息", 13, 0xFF8BA69C); body.addView(note, new LinearLayout.LayoutParams(-1, dp(44)));
        for (int i = 0; i < names.length; i++) {
            CheckBox box = new CheckBox(activity); box.setText(names[i]); box.setTextSize(16); box.setTextColor(0xFFD2E5DC); box.setChecked(checked[i]);
            final int index = i; box.setOnCheckedChangeListener((button, enabled) -> activity.setNameVisible(keys[index], enabled));
            body.addView(box, new LinearLayout.LayoutParams(-1, dp(62)));
        }
        sectionDivider(body);
        body.addView(text("性能模式", 17, 0xFFD2E5DC));
        android.widget.RadioGroup performance = new android.widget.RadioGroup(activity);
        android.widget.RadioButton high = new android.widget.RadioButton(activity), low = new android.widget.RadioButton(activity);
        high.setId(View.generateViewId()); low.setId(View.generateViewId());
        high.setText("高性能"); low.setText("节省内存");
        high.setTextColor(0xFFD2E5DC); low.setTextColor(0xFFD2E5DC);
        performance.addView(high); performance.addView(low);
        performance.check(PerformanceSettings.highPerformance(activity) ? high.getId() : low.getId());
        performance.setOnCheckedChangeListener((group, id) -> activity.setHighPerformance(id == high.getId()));
        body.addView(performance);
        TextView performanceNote = text("高性能：响应更快，占用更多内存。节省内存：空闲释放，再用需加载。", 13, 0xFF8BA69C);
        performanceNote.setPadding(dp(8), 0, dp(8), dp(12)); body.addView(performanceNote);
        sectionDivider(body);
        CheckBox companion = new CheckBox(activity); companion.setText("持续陪伴"); companion.setTextSize(16); companion.setTextColor(0xFFD2E5DC);
        companion.setChecked(activity.isCompanionEnabled()); companion.setOnCheckedChangeListener((button, enabled) -> activity.setCompanionEnabled(enabled));
        body.addView(companion, new LinearLayout.LayoutParams(-1, dp(62)));
        TextView companionNote = text("无需唤醒，感知讲话情绪并用表情回应；电视对白也可能触发。", 13, 0xFF8BA69C);
        companionNote.setPadding(dp(8), 0, dp(8), dp(12)); body.addView(companionNote);
        sectionDivider(body);
        TextView care = text("主动关怀", 17, 0xFFD2E5DC); care.setGravity(Gravity.CENTER_VERTICAL); care.setPadding(dp(8), 0, 0, 0);
        care.setOnClickListener(v -> new CarePanel(activity, this::settings).show());
        body.addView(care, new LinearLayout.LayoutParams(-1, dp(62)));
        sectionDivider(body);
        TextView brightness = text("屏幕亮度", 17, 0xFFD2E5DC); brightness.setGravity(Gravity.CENTER_VERTICAL); brightness.setPadding(dp(8), 0, 0, 0);
        brightness.setOnClickListener(v -> brightness()); body.addView(brightness, new LinearLayout.LayoutParams(-1, dp(68)));
        sectionDivider(body);
        TextView weather = text("地区设置", 17, 0xFFD2E5DC); weather.setGravity(Gravity.CENTER_VERTICAL); weather.setPadding(dp(8), 0, 0, 0);
        weather.setOnClickListener(v -> ((WeatherWidget) activity.findViewById(R.id.weather_clock)).showRegionSettings(this::settings));
        body.addView(weather, new LinearLayout.LayoutParams(-1, dp(62)));
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        sectionDivider(body);
        TextView communication = text("音视频通话", 17, 0xFFD2E5DC);
        communication.setGravity(Gravity.CENTER_VERTICAL);
        communication.setPadding(dp(8), 0, 0, 0);
        communication.setOnClickListener(v -> communication());
        body.addView(communication, new LinearLayout.LayoutParams(-1, dp(62)));
        activity.showDrawerPage("设置", true, this::show, scroll);
    }
    private void communication() {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(16));
        body.addView(text("服务器地址", 15, 0xFFD2E5DC));
        android.widget.EditText server = new android.widget.EditText(activity);
        server.setTextColor(0xFFE0FFF0); server.setSingleLine(true);
        server.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        server.setText(CallSettings.server(activity)); body.addView(server);
        body.addView(text("房间名称", 15, 0xFFD2E5DC));
        android.widget.EditText room = new android.widget.EditText(activity);
        room.setTextColor(0xFFE0FFF0); room.setSingleLine(true); room.setText(CallSettings.room(activity)); body.addView(room);
        room.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        TextView status = text("同一服务器、同一房间的成员可以互相通话。通话期间暂停声音守护。", 13, 0xFF8BA69C); body.addView(status);
        Button save = new Button(activity); save.setText("保存"); body.addView(save);
        save.setOnClickListener(v -> { try { CallSettings.save(activity, server.getText().toString(), room.getText().toString()); status.setText("已保存");
                ((android.view.inputmethod.InputMethodManager) activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(room.getWindowToken(), 0); }
            catch (IllegalArgumentException error) { status.setText(error.getMessage()); } });
        room.setOnEditorActionListener((view, action, event) -> { if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && event.getAction() == android.view.KeyEvent.ACTION_UP)) { save.performClick(); return true; } return false; });
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        activity.showDrawerPage("音视频通话", true, this::settings, scroll);
    }
    private void sectionDivider(LinearLayout body) {
        View divider = new View(activity); divider.setBackgroundColor(0xFF2E4148);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(dp(8), dp(8), dp(8), dp(8)); body.addView(divider, params);
    }
    private void previews() {
        String[] items = BuildConfig.DEBUG
                ? new String[]{"事件记录", "场景", "表情预览", "模拟声音事件"}
                : new String[]{"事件记录", "场景"};
        drawer("预览", items, true, which -> {
            if (which == 0) history();
            else if (which == 1) scenarios();
            else if (which == 2) expressions();
            else if (which == 3) simulations();
        });
    }

    private void scenarios() {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(24)); scroll.addView(body);
        try {
            org.json.JSONObject catalog = new org.json.JSONObject(com.soundguardian.core.AppConfig.read(activity, "scenario_catalog.json"));
            TextView note = text(catalog.getString("note"), 13, 0xFF8BA69C);
            note.setLineSpacing(dp(4), 1); body.addView(note);
            org.json.JSONArray sections = catalog.getJSONArray("sections");
            for (int i = 0; i < sections.length(); i++) {
                org.json.JSONObject section = sections.getJSONObject(i);
                TextView heading = text(section.getString("title"), 17, 0xFFA6C1B8);
                heading.setPadding(0, dp(24), 0, dp(10)); body.addView(heading);
                org.json.JSONArray entries = section.getJSONArray("scenarios");
                for (int j = 0; j < entries.length(); j++) {
                    org.json.JSONObject entry = entries.getJSONObject(j);
                    TextView name = text(entry.getString("name"), 16, 0xFFD2E5DC);
                    name.setPadding(0, dp(10), 0, dp(4)); body.addView(name);
                    TextView description = text(entry.getString("description"), 13, 0xFF8BA69C);
                    description.setLineSpacing(dp(4), 1); body.addView(description);
                }
            }
        } catch (Exception error) {
            android.util.Log.w("Anlan", "场景说明读取失败", error);
            body.removeAllViews(); body.addView(text("场景说明暂时无法显示，请稍后重试。", 15, 0xFFD2E5DC));
        }
        activity.showDrawerPage("场景", true, this::previews, scroll);
    }

    private void drawer(String title, String[] items, boolean nested, Consumer<Integer> select) {
        activity.showDrawer(title, items, nested, this::show, select);
    }
    private void about() {
        TextView body = text("小糖是一款拟人化的智能守护音响，陪伴在你的身边，关注日常环境变化，用自然、温和的方式回应生活。\n\n红糖，让守护更有温度。", 17, 0xFFD2E5DC);
        body.setGravity(Gravity.TOP); body.setPadding(dp(24), dp(28), dp(24), 0);
        activity.showDrawerPage("关于红糖", true, this::show, body);
    }
    private void history() {
        TextView body = text("正在读取…", 15, 0xFFD2E5DC); body.setGravity(Gravity.TOP); body.setPadding(dp(24), dp(24), dp(24), 0);
        activity.showDrawerPage("事件记录", true, this::previews, body);
        new Thread(() -> {
            String text;
            try (EventStore store = new EventStore(activity.getApplicationContext())) {
                List<SoundEvent> rows = store.recent(); StringBuilder buffer = new StringBuilder();
                SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
                for (SoundEvent event : rows) buffer.append(event.name).append(" · ").append(time.format(new Date(event.start)))
                        .append(event.active && GuardianRuntime.snapshot().status == GuardianRuntime.Status.LISTENING ? " · 进行中" : " · 已结束")
                        .append(String.format(Locale.CHINA, "\n约 %.1f 秒\n\n", Math.max(0, event.end-event.start)/1000.0));
                text = buffer.length() == 0 ? "暂无已确认事件\n\n模拟事件不会写入记录。" : buffer.toString();
            } catch (RuntimeException error) { text = "读取记录失败，请稍后重试。"; }
            String result = text;
            activity.runOnUiThread(() -> { if (!activity.isDestroyed()) body.setText(result); });
        }, "anlan-history").start();
    }
    private void brightness() {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(24),dp(12),dp(24),dp(18));
        TextView note = text("仅调整红糖窗口亮度\n打盹只改变表情，监听持续工作。", 14, 0xFF8BA69C); note.setLineSpacing(dp(6), 1); body.addView(note);
        SeekBar bar = new SeekBar(activity); bar.setMax(90); bar.setProgress(Math.round(activity.getWindow().getAttributes().screenBrightness*100)-10); body.addView(bar);
        bar.setOnTouchListener((view, event) -> {
            SeekBar seekBar = (SeekBar)view;
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE)
                view.getParent().requestDisallowInterceptTouchEvent(true);
            else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL)
                view.getParent().requestDisallowInterceptTouchEvent(false);
            if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_MOVE || action == android.view.MotionEvent.ACTION_UP) {
                float fraction = Math.max(0f, Math.min(1f, event.getX() / Math.max(1f, seekBar.getWidth())));
                int value = Math.round(fraction * seekBar.getMax());
                seekBar.setProgress(value);
                activity.applyBrightness((value + 10) / 100f);
                return true;
            }
            return false;
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar,int value,boolean user){ if(user) activity.applyBrightness((value+10)/100f); }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        activity.showDrawerPage("屏幕亮度", true, this::settings, body);
    }
    private void diagnostics() {
        GuardianRuntime.Snapshot snapshot = GuardianRuntime.snapshot();
        String guarding;
        switch (snapshot.status) {
            case LISTENING: guarding = "守护中"; break;
            case STARTING: guarding = "正在准备"; break;
            case ERROR: guarding = "暂不可用，请稍后重试"; break;
            default: guarding = "未开启"; break;
        }
        String speech;
        switch (GuardianRuntime.speechSnapshot().phase) {
            case PREPARING: speech = "正在准备"; break;
            case PLAYING: speech = "正在说话"; break;
            case WAITING: speech = "正在聆听"; break;
            case ERROR: speech = "暂不可用，请稍后重试"; break;
            default: speech = "待命中"; break;
        }
        String text = "声音守护：" + guarding + "\n语音互动：" + speech
                + "\n陪伴倾听：" + (activity.isCompanionEnabled() ? "已开启" : "唤醒后开启");
        InteractionPorts.UnderstandingState understanding = GuardianRuntime.understandingSnapshot();
        if (understanding.result != null) {
            com.soundguardian.core.VoiceUnderstandingResult result = understanding.result;
            text += "\n\n最近听到：" + result.text;
        }
        text += "\n\n关怀及对话文字最多保留3天；其他倾听文字仅临时使用，不保存录音。";
        TextView body = text(text, 14, 0xFFD2E5DC); body.setGravity(Gravity.TOP); body.setPadding(dp(24), dp(24), dp(24), 0);
        activity.showDrawerPage("设备状态", true, this::show, body);
    }
    private void expressions() {
        ScrollView scroll = new ScrollView(activity); LinearLayout grid = new LinearLayout(activity); grid.setOrientation(LinearLayout.VERTICAL); grid.setPadding(dp(12),dp(8),dp(12),dp(12)); scroll.addView(grid);
        LinearLayout row = null; int n=0;
        for (Emotion emotion : Emotion.values()) {
            if (n++%4 == 0) { row = new LinearLayout(activity); grid.addView(row); }
            Button button = new Button(activity); button.setText(emotion.title); button.setTextSize(13);
            row.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));
            button.setOnClickListener(v -> preview.accept(emotion));
        }
        activity.showDrawerPage("表情预览 · " + Emotion.values().length + " 种", true, this::previews, scroll);
    }
    private void simulations() {
        String[] names={"咳嗽", "哭泣", "呻吟", "撞击声", "玻璃破碎", "警报", "轻敲", "开心响指（连续或两次）"};
        String[] labels={"Cough", "Crying, sobbing", "Groan", "Smash, crash", "Shatter", "Alarm", "Tap", "Finger snapping"};
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(12), dp(8), dp(12), dp(12));
        TextView note = text("不写入事件记录；响指项同时体验表情与语音，其余项仅模拟表情", 13, 0xFF8BA69C); note.setPadding(dp(8), dp(8), dp(8), dp(16)); body.addView(note);
        for (int i = 0; i < names.length; i++) {
            final int which = i; TextView row = text(names[i], 16, 0xFFD2E5DC); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), 0, 0, 0);
            row.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            simulate.accept(new SoundEvent("demo:"+now,labels[which],names[which],now,now,false));
            }); body.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
        }
        activity.showDrawerPage("模拟声音事件", true, this::previews, body);
    }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private int dp(float n) { return Math.round(n*activity.getResources().getDisplayMetrics().density); }
}

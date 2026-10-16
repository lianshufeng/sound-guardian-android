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
        String[] items = {"设置", "预览", "设备状态", "关于"};
        drawer("小安 · 智能守护", items, false, which -> {
            switch (which) {
                case 0: settings(); break;
                case 1: previews(); break;
                case 2: diagnostics(); break;
                case 3: about(); break;
                default: break;
            }
        });
    }
    private void settings() {
        String[] names = {"表情名称", "模型事件", "显示声音强度"};
        String[] keys = {"show_emotion_name", "show_event_name", "show_sound_level"};
        boolean[] checked = {activity.isNameVisible(keys[0]), activity.isNameVisible(keys[1]), activity.isNameVisible(keys[2])};
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(18), dp(16), dp(18), 0);
        TextView note = text("选择主界面要显示的信息", 13, 0xFF8BA69C); body.addView(note, new LinearLayout.LayoutParams(-1, dp(44)));
        for (int i = 0; i < names.length; i++) {
            CheckBox box = new CheckBox(activity); box.setText(names[i]); box.setTextSize(16); box.setTextColor(0xFFD2E5DC); box.setChecked(checked[i]);
            final int index = i; box.setOnCheckedChangeListener((button, enabled) -> activity.setNameVisible(keys[index], enabled));
            body.addView(box, new LinearLayout.LayoutParams(-1, dp(62)));
        }
        View divider = new View(activity); divider.setBackgroundColor(0xFF2E4148); body.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        TextView brightness = text("屏幕亮度", 17, 0xFFD2E5DC); brightness.setGravity(Gravity.CENTER_VERTICAL); brightness.setPadding(dp(8), 0, 0, 0);
        brightness.setOnClickListener(v -> brightness()); body.addView(brightness, new LinearLayout.LayoutParams(-1, dp(68)));
        activity.showDrawerPage("设置", true, this::show, body);
    }
    private void previews() {
        String[] items = BuildConfig.DEBUG
                ? new String[]{"事件记录", "表情预览", "模拟声音事件"}
                : new String[]{"事件记录"};
        drawer("预览", items, true, which -> {
            if (which == 0) history();
            else if (which == 1) expressions();
            else if (which == 2) simulations();
        });
    }

    private void drawer(String title, String[] items, boolean nested, Consumer<Integer> select) {
        activity.showDrawer(title, items, nested, this::show, select);
    }
    private void about() {
        TextView body = text("小安是一款拟人化的智能守护音响，陪伴在你的身边，关注日常环境变化，用自然、温和的方式回应生活。\n\n安澜，让守护更有温度。", 17, 0xFFD2E5DC);
        body.setGravity(Gravity.TOP); body.setPadding(dp(24), dp(28), dp(24), 0);
        activity.showDrawerPage("关于安澜", true, this::show, body);
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
        TextView note = text("仅调整安澜窗口亮度\n打盹只改变表情，监听持续工作。", 14, 0xFF8BA69C); note.setLineSpacing(dp(6), 1); body.addView(note);
        SeekBar bar = new SeekBar(activity); bar.setMax(90); bar.setProgress(Math.round(activity.getWindow().getAttributes().screenBrightness*100)-10); body.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar,int value,boolean user){ if(user) activity.applyBrightness((value+10)/100f); }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        activity.showDrawerPage("屏幕亮度", true, this::settings, body);
    }
    private void diagnostics() {
        GuardianRuntime.Snapshot snapshot = GuardianRuntime.snapshot();
        String text = "监听状态：" + snapshot.status + "\n最近类别：" + snapshot.topLabel
                + String.format(Locale.CHINA,"\n推理耗时：%.1f ms\n输入电平：%.1f dBFS",snapshot.inferenceMs,snapshot.dbfs)
                + "\n模型：YAMNet · 16 kHz 单声道\n推理窗口：0.975 秒\n更新步长：约 0.48 秒\n包名：" + activity.getPackageName()
                + "\n\n模型分数并非准确率；输入电平未经声压校准。"
                + (snapshot.error.isEmpty() ? "" : "\n\n异常：" + snapshot.error);
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
        activity.showDrawerPage("表情预览 · 26 种", true, this::previews, scroll);
    }
    private void simulations() {
        String[] names={"咳嗽", "哭泣", "呻吟", "撞击声", "玻璃破碎", "警报", "轻敲"};
        String[] labels={"Cough", "Crying, sobbing", "Groan", "Smash, crash", "Shatter", "Alarm", "Tap"};
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(12), dp(8), dp(12), dp(12));
        TextView note = text("仅模拟表情，不写入事件记录", 13, 0xFF8BA69C); note.setPadding(dp(8), dp(8), dp(8), dp(16)); body.addView(note);
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

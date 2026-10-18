package com.anlan.guardian;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import java.util.List;
import java.util.Locale;

/** 关怀设置与明确的屏幕反馈；不保存 Activity 到服务。 */
final class CarePanel {
    private final MainActivity activity;
    private final Runnable back;
    private final CareSettings settings;
    CarePanel(MainActivity activity, Runnable back) { this.activity = activity; this.back = back; settings = new CareSettings(activity); }
    void show() {
        LinearLayout body = column();
        note(body, "按你的选择主动提醒或询问；需要回复时会临时倾听。默认不保存录音和回复文字。");
        toggle(body, "允许主动关怀", "enabled", false);
        toggle(body, "身体声音关怀", "group_body", false);
        toggle(body, "情绪陪伴关怀", "group_emotion", false);
        toggle(body, "简单陪伴回应", "group_companion", false);
        toggle(body, "家庭声音提醒（含婴儿哭声）", "group_family", false);
        toggle(body, "警报与异常声音语音提示", "group_safety", false);
        toggle(body, "天气与出行关怀", "group_weather", false);
        toggle(body, "作息提醒", "group_schedule", false);
        action(body, "我的定时提醒", this::schedules);
        toggle(body, "启用就寝时间提醒", "bedtime_enabled", false);
        time(body, "就寝时间", "bedtime", 22 * 60);
        toggle(body, "安静时段暂停普通关怀", "quiet_enabled", true);
        time(body, "安静时段开始", "quiet_start", 22 * 60);
        time(body, "安静时段结束", "quiet_end", 7 * 60);
        toggle(body, "保存关怀及对话文字（最多七天）", "save_replies", false);
        action(body, "关怀记录", this::history);
        action(body, "停止当前关怀", () -> { try { Care.cancel(Care.activeId()); } catch (IllegalStateException ignored) { } });
        if (Care.phase().equals("LISTENING")) for (String value : new String[]{"我没事", "需要帮助", "不用", "稍后", "是电视"})
            action(body, value, () -> { try { Care.respond(Care.activeId(), value); show(); } catch (IllegalStateException ignored) { } });
        if (BuildConfig.DEBUG) {
            action(body, "试用：保暖提醒", () -> demo(false));
            action(body, "试用：关怀询问", () -> demo(true));
        }
        page("主动关怀", body, back);
    }
    private void demo(boolean ask) {
        try {
            long now = System.currentTimeMillis();
            Care.submit(new InteractionPorts.CareRequest(java.util.UUID.randomUUID().toString(), "manual", "",
                    ask ? "你还好吗？需要我陪你一会儿吗？" : "外面有些冷，出门记得添衣。",
                    ask ? InteractionPorts.CareRequest.Mode.ASK : InteractionPorts.CareRequest.Mode.NOTICE,
                    ask ? settings.replyTimeoutMs : 0, now + 60000), result -> { });
        } catch (IllegalStateException error) { android.widget.Toast.makeText(activity, "请先开启声音守护", android.widget.Toast.LENGTH_SHORT).show(); }
    }
    private void schedules() {
        LinearLayout body = column();
        note(body, "仅在声音守护运行期间提醒，安静时段暂停；错过的提醒不补播。不能替代可靠闹钟或用药管理。");
        action(body, "添加每日提醒", () -> {
            String[] names = {"饮水", "用餐", "工作休息", "阅读休息", "活动计划", "已设置的用药事项", "预约事项", "出门清单", "其他"};
            String[] ids = {"S49", "S50", "S51", "S52", "S53", "S54", "S55", "S56", "manual"};
            new android.app.AlertDialog.Builder(activity).setTitle("提醒类型").setItems(names, (dialog, which) -> {
                android.widget.EditText input = new android.widget.EditText(activity); input.setHint("输入你希望小安提醒的内容");
                input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});
                new android.app.AlertDialog.Builder(activity).setTitle(names[which]).setView(input).setNegativeButton("取消", null)
                        .setPositiveButton("选择时间", (choice, ok) -> {
                            String text = input.getText().toString().trim(); if (text.isEmpty()) return;
                            new TimePickerDialog(activity, (picker, hour, minute) -> new Thread(() -> {
                                try { CareSchedules.add(activity.getApplicationContext(), ids[which], text, hour * 60 + minute, 127); }
                                catch (RuntimeException error) { activity.runOnUiThread(() -> android.widget.Toast.makeText(activity, error.getMessage(), android.widget.Toast.LENGTH_SHORT).show()); }
                                activity.runOnUiThread(this::schedules);
                            }, "anlan-care-add-plan").start(), 9, 0, true).show();
                        }).show();
            }).show();
        });
        page("我的定时提醒", body, this::show);
        new Thread(() -> {
            try {
                List<CareSchedules.Plan> plans = CareSchedules.list(activity.getApplicationContext());
                activity.runOnUiThread(() -> {
                    if (activity.isDestroyed()) return;
                    for (CareSchedules.Plan plan : plans) action(body, String.format(Locale.CHINA, "%02d:%02d  %s", plan.minute / 60, plan.minute % 60, plan.text),
                            () -> new android.app.AlertDialog.Builder(activity).setMessage("删除这个提醒？").setNegativeButton("取消", null)
                                    .setPositiveButton("删除", (dialog, which) -> new Thread(() -> {
                                        CareSchedules.remove(activity.getApplicationContext(), plan.id); activity.runOnUiThread(this::schedules);
                                    }, "anlan-care-remove-plan").start()).show());
                });
            } catch (RuntimeException error) { android.util.Log.w("AnlanCare", "读取提醒失败", error); }
        }, "anlan-care-plans-list").start();
    }
    private void toggle(LinearLayout body, String label, String key, boolean fallback) {
        CheckBox box = new CheckBox(activity); box.setText(label); box.setTextColor(0xFFD2E5DC);
        box.setChecked(settings.preferences.getBoolean(key, fallback));
        box.setOnCheckedChangeListener((view, checked) -> {
            settings.preferences.edit().putBoolean(key, checked).apply();
            try { activity.startForegroundService(new Intent(activity, GuardianService.class)); } catch (RuntimeException ignored) { }
            if (key.equals("save_replies") && !checked) new Thread(() -> {
                try (CareStore store = new CareStore(activity.getApplicationContext(), settings)) { store.clean(System.currentTimeMillis()); }
            }, "anlan-care-clean").start();
        });
        body.addView(box, new LinearLayout.LayoutParams(-1, dp(54)));
    }
    private void time(LinearLayout body, String label, String key, int fallback) {
        int value = settings.preferences.getInt(key, fallback);
        action(body, String.format(Locale.CHINA, "%s  %02d:%02d", label, value / 60, value % 60), () ->
                new TimePickerDialog(activity, (view, hour, minute) -> {
                    settings.preferences.edit().putInt(key, hour * 60 + minute).apply(); show();
                }, value / 60, value % 60, true).show());
    }
    private void history() {
        LinearLayout body = column(); note(body, "正在读取关怀记录…"); page("关怀记录", body, this::show);
        new Thread(() -> {
            StringBuilder text = new StringBuilder();
            try (CareStore store = new CareStore(activity.getApplicationContext(), settings)) {
                store.clean(System.currentTimeMillis()); List<Care.Result> rows = store.recent();
                java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
                for (Care.Result result : rows) text.append(date.format(new java.util.Date(result.completedAt))).append(" · ").append(scenarioTitle(result.scenarioId))
                        .append("\n").append(title(result.status))
                        .append(result.replyStored ? "\n回复：" + result.replyText : "\n未保存回复原文").append("\n\n");
            } catch (RuntimeException error) { text.append("读取失败，请稍后重试。"); }
            if (text.length() == 0) text.append("暂无关怀记录");
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) return;
                body.removeAllViews(); note(body, text.toString());
                action(body, "清空已结束关怀记录", () -> new Thread(() -> {
                    try (CareStore store = new CareStore(activity.getApplicationContext(), settings)) { store.clear(); }
                    activity.runOnUiThread(this::history);
                }, "anlan-care-clear").start());
            });
        }, "anlan-care-history").start();
    }
    private static String title(Care.Status value) {
        switch (value) {
            case COMPLETED: return "播报完成";
            case REPLIED: return "收到回复";
            case TIMEOUT: return "等待超时";
            case CANCELLED: return "已取消";
            case REJECTED: return "未执行";
            case EXPIRED: return "已过期";
            default: return "执行失败";
        }
    }
    private static String scenarioTitle(String id) {
        switch (id) {
            case "S02": case "S03": return "咳嗽关怀";
            case "S05": return "清嗓关怀";
            case "S06": return "喷嚏关怀";
            case "S07": return "不适声音关注";
            case "S09": case "S10": case "S11": case "S12": return "情绪陪伴";
            case "S18": return "休息提醒";
            case "S26": case "S27": case "S28": case "S29": case "S30": case "S31": return "天气出行提醒";
            case "S33": case "S34": case "S35": case "S36": return "异常声音提示";
            case "S41": return "敲门声提醒";
            case "S43": return "婴儿哭声提醒";
            case "S58": case "S60": case "S61": case "S63": case "S64": return "日常陪伴";
            case "S49": case "S50": case "S51": case "S52": case "S53": case "S54": case "S55": case "S56": return "定时提醒";
            default: return "关怀互动";
        }
    }
    private LinearLayout column() { LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(18), dp(12), dp(18), dp(12)); return body; }
    private void note(LinearLayout body, String value) { TextView text = new TextView(activity); text.setText(value); text.setTextColor(0xFFAAC4BA); text.setTextSize(14); text.setPadding(0, dp(8), 0, dp(12)); body.addView(text); }
    private void action(LinearLayout body, String label, Runnable action) {
        TextView view = new TextView(activity); view.setText(label); view.setTextSize(16); view.setTextColor(0xFFD2E5DC);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL); view.setOnClickListener(v -> action.run());
        body.addView(view, new LinearLayout.LayoutParams(-1, dp(52)));
    }
    private void page(String title, LinearLayout body, Runnable previous) { ScrollView scroll = new ScrollView(activity); scroll.addView(body); activity.showDrawerPage(title, true, previous, scroll); }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}

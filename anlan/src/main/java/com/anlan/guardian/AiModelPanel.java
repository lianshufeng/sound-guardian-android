package com.anlan.guardian;

import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** AI模型设置；不提供默认服务地址、模型或密钥。 */
final class AiModelPanel {
    private final MainActivity activity;
    private final Runnable back;
    AiModelPanel(MainActivity activity, Runnable back) { this.activity = activity; this.back = back; }
    void show() {
        AiSettings settings = new AiSettings(activity);
        AiSettings.Profile profile;
        String loadError = "";
        try { profile = settings.load(); } catch (RuntimeException error) { profile = new AiSettings.Profile(false, "", "", "", true, true); loadError = "无法读取已保存的设置，请重新填写。"; }
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(18), dp(12), dp(18), dp(20));
        TextView note = text("开启后，聊天文字会发送给你选择的AI服务，不上传录音。日常陪伴倾听不会自动发送内容。", 13, 0xFF8BA69C);
        note.setPadding(dp(8), dp(6), dp(8), dp(14)); body.addView(note);
        CheckBox enabled = new CheckBox(activity); enabled.setText("启用 AI 对话"); enabled.setTextSize(16); enabled.setTextColor(0xFFD2E5DC); enabled.setChecked(profile.enabled); body.addView(enabled);
        EditText url = field("服务地址", profile.baseUrl, false); body.addView(url);
        EditText token = field("访问密钥", profile.token.isEmpty() ? "" : "••••••••", true); token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); body.addView(token);
        EditText model = field("模型名称", profile.model, false); body.addView(model);
        CheckBox thinking = new CheckBox(activity); thinking.setText("优先快速回答"); thinking.setTextSize(15); thinking.setTextColor(0xFFD2E5DC); thinking.setChecked(profile.disableThinking); body.addView(thinking);
        CheckBox cue = new CheckBox(activity); cue.setText("后续对话和关怀回复前播放提示音"); cue.setTextSize(15); cue.setTextColor(0xFFD2E5DC); cue.setChecked(profile.cue); body.addView(cue);
        CheckBox webSearch = new CheckBox(activity); webSearch.setText("允许联网搜索（Qwen等支持的模型）"); webSearch.setTextSize(15); webSearch.setTextColor(0xFFD2E5DC); webSearch.setChecked(profile.webSearch); body.addView(webSearch);
        TextView status = text(loadError, 13, 0xFFE6A27E); status.setPadding(dp(8), dp(10), dp(8), dp(8)); body.addView(status);
        LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER); Button test = button("测试连接"), save = button("保存"), clear = button("清除");
        actions.addView(test, new LinearLayout.LayoutParams(0, dp(50), 1)); actions.addView(save, new LinearLayout.LayoutParams(0, dp(50), 1)); actions.addView(clear, new LinearLayout.LayoutParams(0, dp(50), 1)); body.addView(actions);
        save.setOnClickListener(v -> {
            try {
                String value = token.getText().toString();
                if ("••••••••".equals(value)) value = settings.load().token;
                settings.save(new AiSettings.Profile(enabled.isChecked(), url.getText().toString(), value, model.getText().toString(), thinking.isChecked(), cue.isChecked(), webSearch.isChecked()));
                status.setText("已保存"); status.setTextColor(0xFFA6C1B8);
            } catch (Exception error) { status.setText(error.getMessage() == null ? "保存失败" : error.getMessage()); status.setTextColor(0xFFE6A27E); }
        });
        clear.setOnClickListener(v -> { try { settings.clear(); enabled.setChecked(false); url.setText(""); token.setText(""); model.setText(""); status.setText("已清除设置"); } catch (Exception error) { status.setText("清除失败"); } });
        test.setOnClickListener(v -> {
            try {
                String value = token.getText().toString(); if ("••••••••".equals(value)) value = settings.load().token;
                AiSettings.Profile candidate = new AiSettings.Profile(true, url.getText().toString(), value, model.getText().toString(), thinking.isChecked(), cue.isChecked(), webSearch.isChecked());
                String normalized = AiSettings.normalizeUrl(candidate.baseUrl);
                if (candidate.model.trim().isEmpty()) throw new IllegalArgumentException("请填写模型名");
                status.setText("正在测试连接…"); status.setTextColor(0xFFA6C1B8); test.setEnabled(false);
                AiSettings.Profile ready = new AiSettings.Profile(true, normalized, candidate.token, candidate.model.trim(), candidate.disableThinking, candidate.cue);
                OpenAiCompatibleAgent agent = new OpenAiCompatibleAgent(ready, new DialogueConfig(activity));
                List<InteractionPorts.DialogueMessage> messages = new ArrayList<>(); messages.add(new InteractionPorts.DialogueMessage("system", "只回复一句简短中文，不超过30字。")); messages.add(new InteractionPorts.DialogueMessage("user", "请回复：连接测试成功"));
                agent.stream("test-" + System.currentTimeMillis(), messages, 60, new InteractionPorts.DialogueAgent.Reply() {
                    public void onDelta(String text) { }
                    public void onComplete(String reason) { activity.runOnUiThread(() -> { status.setText("连接成功"); test.setEnabled(true); agent.close(); }); }
                    public void onError(String reason) { activity.runOnUiThread(() -> { status.setText("连接失败，请检查服务地址、密钥和模型名称。"); test.setEnabled(true); agent.close(); }); }
                });
            } catch (Exception error) { status.setText(error.getMessage() == null ? "连接测试失败" : error.getMessage()); status.setTextColor(0xFFE6A27E); test.setEnabled(true); }
        });
        ScrollView scroll = new ScrollView(activity); scroll.addView(body); activity.showDrawerPage("AI模型", true, back, scroll);
    }
    private EditText field(String hint, String value, boolean password) { EditText field = new EditText(activity); field.setHint(hint); field.setHintTextColor(0xFF718B82); field.setTextColor(0xFFD2E5DC); field.setText(value); field.setSingleLine(true); field.setPadding(dp(8), dp(8), dp(8), dp(8)); field.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL)); return field; }
    private Button button(String value) { Button button = new Button(activity); button.setText(value); button.setTextColor(Color.WHITE); return button; }
    private TextView text(String value, float size, int color) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}

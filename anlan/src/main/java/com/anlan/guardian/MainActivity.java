package com.anlan.guardian;
import com.soundguardian.core.AppConfig;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import androidx.drawerlayout.widget.DrawerLayout;
import org.json.JSONObject;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FaceView face;
    private TextView emotionName, soundLevelName;
    private FrameLayout conversationBar;
    private android.widget.ImageButton callButton;
    private float callTouchX, callTouchY;
    private boolean callTouchMoved, callTouchOnButton, callTouchInDrawer;
    private final Runnable hideCallButton = () -> { if (callButton != null) callButton.setVisibility(View.INVISIBLE); };
    private TextView inputHint;
    private VoiceWaveView inputWave;
    private boolean inputPanelVisible;
    private WeatherWidget weatherWidget;
    private boolean permissionPending;
    private ModelResultsView modelResults;
    private DrawerLayout menuDrawer;
    private FrameLayout menuPanel;
    private Runnable drawerBack;
    private boolean drawerReturning;
    private OnBackInvokedCallback drawerBackCallback;
    private OnBackInvokedCallback conversationBackCallback;
    private boolean showEmotionName, showEventName, showSoundLevel;
    private BehaviorEngine behavior;
    private static BehaviorEngine retainedBehavior;
    private boolean listening;
    private boolean startupAttempted;
    private boolean registered;
    private boolean visible;
    private boolean touchAnimating;
    private GuardianRuntime.Status serviceStatus = GuardianRuntime.Status.STOPPED;
    private BroadcastReceiver receiver;
    private final Runnable speechObserver = () -> receive(null);
    private final Runnable ticker = new Runnable() { public void run() { render(); if (visible) handler.postDelayed(this, touchAnimating || showSoundLevel ? 100 : 500); } };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(11,20,28)); getWindow().setNavigationBarColor(Color.rgb(11,20,28));
        if (retainedBehavior == null) {
            BehaviorEngine.Timing timing = BehaviorEngine.Timing.defaults();
            try {
                JSONObject json = new JSONObject(AppConfig.read(this, "behavior.json"));
                timing = new BehaviorEngine.Timing(json.getLong("drowsy_ms"), json.getLong("sleep_ms"), json.getLong("peek_every_ms"), json.getLong("peek_ms"), json.getLong("event_hold_ms"));
            } catch (Exception error) { android.util.Log.e("Anlan", "行为配置读取失败，使用默认值", error); }
            retainedBehavior = new BehaviorEngine(timing, new Random(), SystemClock.elapsedRealtime());
        }
        behavior = retainedBehavior;
        try {
            JSONObject json = new JSONObject(AppConfig.read(this, "behavior.json"));
            behavior.configureTouch(json.getLong("touch_hold_ms"), json.getLong("touch_linger_ms"));
            behavior.configureEmpathy(json.getLong("empathy_hold_ms"), json.getLong("empathy_linger_ms"));
        } catch (Exception error) { android.util.Log.w("Anlan", "使用默认触摸时间", error); }
        startupAttempted = saved != null && saved.getBoolean("startupAttempted");
        permissionPending = saved != null && saved.getBoolean("permissionPending");
        buildUi();
        receiver = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { receive(intent); } };
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER); root.setPadding(dp(30), dp(8), dp(30), dp(12)); root.setBackgroundColor(0xFF0B141C);
        root.setClipChildren(false); root.setClipToPadding(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(dp(30) + safe.left, dp(8) + safe.top, dp(30) + safe.right, dp(12) + safe.bottom);
            } else view.setPadding(dp(30) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(30) + insets.getSystemWindowInsetRight(), dp(12) + Math.min(insets.getSystemWindowInsetBottom(), insets.getStableInsetBottom()));
            return insets;
        });
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("红糖", 18, 0xFFA6C1B8); header.addView(title, new LinearLayout.LayoutParams(-2, dp(44))); title.setGravity(Gravity.CENTER_VERTICAL);
        title.setContentDescription("红糖，点击开始与小糖对话");
        title.setOnClickListener(v -> { try { Conversation.start(); } catch (RuntimeException error) { android.widget.Toast.makeText(this,"请先开启守护并配置AI模型",android.widget.Toast.LENGTH_SHORT).show(); } });
        header.setId(R.id.header);
        soundLevelName = text("", 10, 0xFF718B82); soundLevelName.setId(R.id.sound_level); soundLevelName.setSingleLine(true); soundLevelName.setIncludeFontPadding(false);
        soundLevelName.setTypeface(android.graphics.Typeface.MONOSPACE); soundLevelName.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams levelParams = new LinearLayout.LayoutParams(-2, dp(44)); levelParams.leftMargin = dp(12); header.addView(soundLevelName, levelParams);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        weatherWidget = new WeatherWidget(this);
        LinearLayout.LayoutParams weatherParams = new LinearLayout.LayoutParams(dp(148), dp(64));
        weatherParams.rightMargin = dp(12);
        header.addView(weatherWidget, weatherParams);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
        FrameLayout center = new FrameLayout(this); face = new FaceView(this); face.setId(R.id.face); center.addView(face, new FrameLayout.LayoutParams(-1, -1));
        android.widget.ImageButton call = new android.widget.ImageButton(this);
        callButton = call; call.setVisibility(View.INVISIBLE);
        call.setImageResource(R.drawable.ic_video_call); call.setContentDescription("音视频通话");
        android.graphics.drawable.GradientDrawable callBackground = new android.graphics.drawable.GradientDrawable();
        callBackground.setColor(0xFF203B3B); callBackground.setCornerRadius(dp(28)); call.setBackground(callBackground);
        FrameLayout.LayoutParams callParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        callParams.leftMargin = dp(8); center.addView(call, callParams);
        call.setOnClickListener(v -> {
            if (GuardianService.callActive) return;
            GuardianService.callActive = true;
            try { startActivity(new Intent(this, CallActivity.class)); }
            catch (RuntimeException error) { GuardianService.callActive = false;
                android.widget.Toast.makeText(this, "无法打开通话页面", android.widget.Toast.LENGTH_SHORT).show(); }
        });
        face.setTouchListener((action, x, y, now) -> {
            Care.userInteraction();
            behavior.gesture(action, x, y, now); render();
            handler.removeCallbacks(ticker);
            if (visible) handler.postDelayed(ticker, touchAnimating || showSoundLevel ? 100 : 500);
        });
        LinearLayout captions = new LinearLayout(this); captions.setOrientation(LinearLayout.VERTICAL); captions.setGravity(Gravity.CENTER); captions.setPadding(0, 0, 0, dp(16));
        emotionName = text("", 11, 0xFF8BA69C); emotionName.setId(R.id.emotion_name); emotionName.setGravity(Gravity.CENTER);
        for (TextView label : new TextView[]{emotionName}) {
            label.setMaxLines(3); label.setIncludeFontPadding(false); label.setLetterSpacing(.04f);
            label.setMaxWidth(dp(540)); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            captions.addView(label, new LinearLayout.LayoutParams(-2, -2));
        }
        center.addView(captions, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        modelResults = new ModelResultsView(this); modelResults.setId(R.id.event_name);
        int panelWidth = Math.min(dp(220), Math.round(getResources().getDisplayMetrics().widthPixels * .29f));
        FrameLayout.LayoutParams resultsParams = new FrameLayout.LayoutParams(panelWidth, dp(180), Gravity.RIGHT | Gravity.BOTTOM);
        resultsParams.bottomMargin = dp(12); center.addView(modelResults, resultsParams);
        showEmotionName = isNameVisible("show_emotion_name"); showEventName = isNameVisible("show_event_name"); showSoundLevel = isNameVisible("show_sound_level");
        emotionName.setVisibility(View.GONE); modelResults.setVisibility(View.GONE); soundLevelName.setVisibility(View.GONE);
        updateCaptionSpace();
        root.addView(center, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout inputPanel = new FrameLayout(this); conversationBar = inputPanel; inputPanel.setId(R.id.conversation_bar);
        inputWave = new VoiceWaveView(this); inputPanel.addView(inputWave, new FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM));
        inputHint = text("",18,0xFFE0FFF0); inputHint.setId(R.id.input_hint); inputHint.setGravity(Gravity.CENTER_VERTICAL);
        inputHint.setGravity(Gravity.CENTER); inputHint.setPadding(dp(8), 0, dp(8), 0); inputHint.setTranslationY(dp(18));
        inputPanel.addView(inputHint, new FrameLayout.LayoutParams(-1, dp(32), Gravity.TOP));
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(-1, dp(104), Gravity.BOTTOM);
        inputParams.leftMargin = inputParams.rightMargin = -dp(30);
        inputParams.bottomMargin = -dp(12);
        center.setClipChildren(false); center.setClipToPadding(false);
        inputPanel.setVisibility(View.GONE); center.addView(inputPanel, inputParams);
        float brightness = getPreferences(MODE_PRIVATE).getFloat("brightness", .35f); applyBrightness(brightness);
        menuDrawer = new DrawerLayout(this);
        menuDrawer.addView(root, new DrawerLayout.LayoutParams(-1, -1));
        menuPanel = new FrameLayout(this); menuPanel.setBackgroundColor(0xFF16232A);
        DrawerLayout.LayoutParams drawerParams = new DrawerLayout.LayoutParams(Math.min(dp(360), Math.round(getResources().getDisplayMetrics().widthPixels * .78f)), -1);
        drawerParams.gravity = Gravity.RIGHT; menuDrawer.addView(menuPanel, drawerParams);
        menuDrawer.setScrimColor(0x66000000); menuDrawer.setDrawerElevation(dp(12));
        menuDrawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View drawerView) {
                if (Build.VERSION.SDK_INT >= 33 && drawerBackCallback == null) {
                    drawerBackCallback = () -> navigateDrawerBack();
                    getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, drawerBackCallback);
                }
            }
            @Override public void onDrawerClosed(View drawerView) {
                weatherWidget.drawerClosed();
                if (Build.VERSION.SDK_INT >= 33 && drawerBackCallback != null) {
                    getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(drawerBackCallback); drawerBackCallback = null;
                }
            }
        });
        setContentView(menuDrawer);
    }

    private void receive(Intent intent) {
        long now = SystemClock.elapsedRealtime();
        GuardianRuntime.Snapshot snapshot = GuardianRuntime.snapshot();
        serviceStatus = snapshot.status;
        listening = serviceStatus == GuardianRuntime.Status.LISTENING || serviceStatus == GuardianRuntime.Status.STARTING;
        if (serviceStatus == GuardianRuntime.Status.ERROR) behavior.fail(snapshot.error, now);
        else {
            behavior.setGuarding(listening, now);
            long wakeAt = GuardianRuntime.wakeSnapshot().at;
            if (wakeAt > 0) behavior.wakeWord(wakeAt, now);
            behavior.model(snapshot.topRawLabel, snapshot.topLabel, snapshot.topScore, now);
            if (snapshot.activity) behavior.activity(now);
            behavior.events(snapshot.events, now);
            InteractionPorts.UnderstandingState understanding = GuardianRuntime.understandingSnapshot();
            behavior.voiceActivity(understanding.voice, understanding.at, now);
            if (understanding.result != null) {
                com.soundguardian.core.VoiceUnderstandingResult result = understanding.result;
                behavior.understanding(result.id, result.emotion, result.at, now);
            }
            GuardianRuntime.SpeechSnapshot speech = GuardianRuntime.speechSnapshot();
            if (speech.phase == GuardianRuntime.SpeechPhase.PLAYING && speech.request.source != InteractionPorts.SpeechRequest.Source.CUE)
                behavior.speechStarted(speech.id, speech.request.text, speech.at);
            else if (speech.phase == GuardianRuntime.SpeechPhase.WAITING || speech.phase == GuardianRuntime.SpeechPhase.COMPLETE || speech.phase == GuardianRuntime.SpeechPhase.CANCELLED || speech.phase == GuardianRuntime.SpeechPhase.ERROR)
                behavior.speechFinished(speech.id, speech.at);
        }
        render();
    }

    private void render() {
        long now = SystemClock.elapsedRealtime();
        String input = GuardianRuntime.inputPhase(), dialogue = GuardianRuntime.conversationState();
        if (input.equals("MONITORING")) input = "IDLE";
        InteractionPorts.UnderstandingState understanding = GuardianRuntime.understandingSnapshot();
        boolean localWindow = input.equals("IDLE") && understanding.window && now >= understanding.at && now - understanding.at <= 500;
        if (localWindow) input = "LISTENING";
        behavior.inputPhase(input);
        BehaviorEngine.State state = behavior.state(now); face.setState(state, now);
        touchAnimating = state.touchGaze;
        boolean wakeResponse = state.message.equals("我在") || state.message.equals("我醒啦");
        GuardianRuntime.SpeechSnapshot speech = GuardianRuntime.speechSnapshot();
        boolean speechCaption = speech.phase == GuardianRuntime.SpeechPhase.PLAYING
                && speech.request.source != InteractionPorts.SpeechRequest.Source.CUE
                && (state.emotion == Emotion.ACKNOWLEDGE || speech.request.source == InteractionPorts.SpeechRequest.Source.CARE
                        || speech.request.source == InteractionPorts.SpeechRequest.Source.DIALOGUE
                        || speech.request.source == InteractionPorts.SpeechRequest.Source.INTERACTION && state.emotion == Emotion.HAPPY);
        boolean legacyWakeCaption = wakeResponse && (speech.phase == GuardianRuntime.SpeechPhase.IDLE || speech.phase == GuardianRuntime.SpeechPhase.ERROR);
        boolean careListening = Care.phase().equals("LISTENING");
        boolean waking = speech.request != null && speech.request.source == InteractionPorts.SpeechRequest.Source.WAKE
                && speech.phase == GuardianRuntime.SpeechPhase.PREPARING;
        emotionName.setText(speechCaption ? speech.request.text : careListening ? "正在听你回复"
                : waking ? "小糖已醒，正在准备回应" : legacyWakeCaption ? state.message : state.emotion.title);
        emotionName.setVisibility(showEmotionName || speechCaption || legacyWakeCaption || careListening || waking ? View.VISIBLE : View.GONE);
        boolean wakeSpeaking = speech.request != null && speech.request.source == InteractionPorts.SpeechRequest.Source.WAKE
                && (speech.phase == GuardianRuntime.SpeechPhase.PREPARING || speech.phase == GuardianRuntime.SpeechPhase.PLAYING);
        boolean interacting = Conversation.active() || !Care.activeId().isEmpty() || !input.equals("IDLE") || wakeSpeaking;
        if (interacting != inputPanelVisible) {
            inputPanelVisible = interacting;
            conversationBar.animate().cancel();
            if (interacting) {
                conversationBar.setAlpha(0); conversationBar.setTranslationY(dp(16)); conversationBar.setVisibility(View.VISIBLE);
                conversationBar.animate().alpha(1).translationY(0).setDuration(180).start();
            } else {
                conversationBar.animate().alpha(0).translationY(dp(16)).setDuration(180)
                        .withEndAction(() -> { if (!inputPanelVisible) { conversationBar.setVisibility(View.GONE); inputWave.phase("IDLE"); } }).start();
            }
        }
        emotionName.setTranslationY(interacting ? -dp(104) : 0);
        modelResults.setTranslationY(interacting ? -dp(104) : 0);
        updateConversationBack();
        String hint = dialogue.equals("CONTINUING_VOICE") ? "正在听你说话" : dialogue.equals("CONTINUING") ? "请继续说" : dialogue.equals("RECOGNIZING") ? "正在识别，请稍候" : input.equals("LISTENING")
                ? understanding.voice ? "正在听你说话" : localWindow ? "请说话" : Care.activeId().isEmpty() ? "请说话" : "请说出你的回复"
                : input.equals("CUE") ? "提示音结束后请说话" : input.equals("PREPARING") ? "正在准备，请稍候"
                : dialogue.equals("THINKING") ? "想一想" : dialogue.equals("WAITING_REPLY") ? "还在想，你也可以继续说"
                : dialogue.equals("PREPARING_SPEECH") ? "正在准备声音" : "小糖正在回应";
        inputHint.setText(hint);
        inputWave.phase(interacting ? input.equals("IDLE") ? wakeSpeaking ? "PREPARING" : dialogue : input
                : conversationBar.getVisibility() == View.VISIBLE ? "ENDING" : "IDLE");
        inputWave.level(input.equals("LISTENING") && understanding.voice ? understanding.level : 0f, understanding.at);
        GuardianRuntime.Snapshot snapshot = GuardianRuntime.snapshot();
        modelResults.setVisibility(showEventName ? View.VISIBLE : View.GONE);
        if (showEventName) modelResults.render(snapshot, now);
        if (showSoundLevel) {
            soundLevelName.setText(String.format(Locale.CHINA, "声音强度 %d", GuardianRuntime.soundStrength(now)));
        }
        soundLevelName.setVisibility(showSoundLevel ? View.VISIBLE : View.GONE);
    }

    private void updateCaptionSpace() {
        int rows = showEmotionName ? 1 : 0;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) face.getLayoutParams();
        params.bottomMargin = rows == 0 ? 0 : dp(rows * 20 + 16);
        face.setLayoutParams(params);
    }

    boolean isNameVisible(String key) { return getPreferences(MODE_PRIVATE).getBoolean(key, false); }
    boolean isCompanionEnabled() { return getSharedPreferences("interaction", MODE_PRIVATE).getBoolean("companion_listening", false); }
    void setHighPerformance(boolean high) {
        PerformanceSettings.save(this, high);
        if (listening) startForegroundService(new Intent(this, GuardianService.class));
    }
    void setCompanionEnabled(boolean enabled) {
        getSharedPreferences("interaction", MODE_PRIVATE).edit().putBoolean("companion_listening", enabled).apply();
        if (listening) startForegroundService(new Intent(this, GuardianService.class));
    }
    void setNameVisible(String key, boolean enabled) {
        getPreferences(MODE_PRIVATE).edit().putBoolean(key, enabled).apply();
        showEmotionName = isNameVisible("show_emotion_name"); showEventName = isNameVisible("show_event_name"); showSoundLevel = isNameVisible("show_sound_level");
        updateCaptionSpace();
        render();
    }

    private TextView text(String value, float size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t; }

    private void startListening() {
        if (GuardianService.callActive) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!startupAttempted) { startupAttempted = true; permissionPending = true; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 9); }
            return;
        }
        startupAttempted = true;
        behavior.setGuarding(true, SystemClock.elapsedRealtime());
        try { startForegroundService(new Intent(this, GuardianService.class)); listening = true; serviceStatus = GuardianRuntime.Status.STARTING; }
        catch (RuntimeException error) { behavior.fail(error.toString(), SystemClock.elapsedRealtime()); serviceStatus = GuardianRuntime.Status.ERROR; listening = false; }
        if (!permissionPending && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true; requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        } else requestWeatherPermission();
    }
    private void requestWeatherPermission() {
        WeatherRepository weather = WeatherRepository.get(this);
        if (!permissionPending && weather.automatic() && !weather.hasLocationPermission()
                && !getSharedPreferences("weather", MODE_PRIVATE).getBoolean("location_prompted", false)) requestWeatherLocation();
    }
    void requestWeatherLocation() {
        if (permissionPending) return;
        WeatherRepository weather = WeatherRepository.get(this); weather.useAutomatic();
        if (!weather.hasLocationPermission()) {
            getSharedPreferences("weather", MODE_PRIVATE).edit().putBoolean("location_prompted", true).apply();
            permissionPending = true; requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 11);
        }
    }
    void showPanel() {
        behavior.touch(SystemClock.elapsedRealtime());
        new ControlPanel(this, emotion -> { behavior.preview(emotion, SystemClock.elapsedRealtime()); render(); },
                event -> {
                    behavior.simulate(event, SystemClock.elapsedRealtime());
                    if (event.label.equals("Finger snapping")) {
                        try { SpeechPlayback.speak(InteractionPorts.SpeechRequest.fingerSnaps(event.id)); }
                        catch (IllegalStateException error) { android.widget.Toast.makeText(this, "请先开启声音守护", android.widget.Toast.LENGTH_SHORT).show(); }
                    }
                    render();
                }).show();
    }
    void showDrawer(String title, String[] items, boolean nested, Runnable back, Consumer<Integer> select) {
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView row = text(items[i], 17, 0xFFD2E5DC); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(32), 0, dp(20), 0); row.setMinHeight(dp(62)); row.setContentDescription(items[i]);
            row.setOnClickListener(v -> select.accept(index));
            rows.addView(row, new LinearLayout.LayoutParams(-1, dp(62)));
            if (i + 1 < items.length) { View line = new View(this); line.setBackgroundColor(0xFF22333A); rows.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
        }
        showDrawerPage(title, nested, back, rows);
    }
    void showDrawerPage(String title, boolean nested, Runnable back, View content) {
        boolean animate = menuDrawer.isDrawerOpen(menuPanel);
        float direction = drawerReturning ? -1 : 1; drawerReturning = false;
        drawerBack = nested ? back : null;
        menuPanel.removeAllViews();
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setBackgroundColor(0xFF16232A);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(18), 0, dp(8), 0);
        TextView left = text(nested ? "‹" : "", 32, 0xFF9EB9AD); left.setGravity(Gravity.CENTER); left.setContentDescription(nested ? "返回上一级" : "菜单");
        heading.addView(left, new LinearLayout.LayoutParams(dp(52), dp(60)));
        if (nested) left.setOnClickListener(v -> navigateDrawerBack());
        else left.setVisibility(View.GONE);
        TextView headingText = text(title, 20, 0xFFE0EFE8); headingText.setGravity(Gravity.CENTER_VERTICAL); heading.addView(headingText, new LinearLayout.LayoutParams(0, dp(60), 1));
        body.addView(heading); View divider = new View(this); divider.setBackgroundColor(0xFF2E4148); body.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        if (!(content instanceof ScrollView)) {
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(content, new ScrollView.LayoutParams(-1, -2)); content = scroll;
        }
        body.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        menuPanel.addView(body, new FrameLayout.LayoutParams(-1, -1));
        if (animate) {
            body.setTranslationX(direction * dp(36)); body.setAlpha(0);
            body.animate().translationX(0).alpha(1).setDuration(180).start();
        }
        if (!menuDrawer.isDrawerOpen(menuPanel)) menuDrawer.openDrawer(menuPanel);
    }
    private void navigateDrawerBack() {
        if (drawerBack == null) closeDrawer();
        else { drawerReturning = true; drawerBack.run(); }
    }
    @Override public void onBackPressed() {
        if (menuDrawer != null && menuDrawer.isDrawerVisible(menuPanel)) navigateDrawerBack();
        else if (Conversation.active() || !Care.activeId().isEmpty()) endInteraction();
        else super.onBackPressed();
    }
    private void endInteraction() {
        if (Conversation.active()) Conversation.cancel(GuardianRuntime.conversationId());
        if (!Care.activeId().isEmpty()) Care.cancel(Care.activeId());
    }
    private void updateConversationBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean active = visible && (Conversation.active() || !Care.activeId().isEmpty());
        if (active && conversationBackCallback == null) {
            conversationBackCallback = this::endInteraction;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, conversationBackCallback);
        } else if (!active && conversationBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(conversationBackCallback);
            conversationBackCallback = null;
        }
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK && menuDrawer != null && menuDrawer.isDrawerVisible(menuPanel)) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) navigateDrawerBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            callTouchX = event.getRawX(); callTouchY = event.getRawY(); callTouchMoved = false;
            callTouchInDrawer = menuDrawer != null && menuDrawer.isDrawerVisible(menuPanel);
            android.graphics.Rect bounds = new android.graphics.Rect();
            callTouchOnButton = callButton != null && callButton.isShown()
                    && callButton.getGlobalVisibleRect(bounds) && bounds.contains((int) callTouchX, (int) callTouchY);
            handler.removeCallbacks(hideCallButton);
        } else if (action == android.view.MotionEvent.ACTION_MOVE) {
            int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            if (Math.abs(event.getRawX() - callTouchX) > slop || Math.abs(event.getRawY() - callTouchY) > slop) callTouchMoved = true;
        } else if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) callTouchMoved = true;
        boolean handled = super.dispatchTouchEvent(event);
        if (callButton != null && (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL)) {
            boolean drawerVisible = menuDrawer != null && menuDrawer.isDrawerVisible(menuPanel);
            if (callTouchInDrawer || drawerVisible || !visible || GuardianService.callActive) hideCallButton.run();
            else {
                if (action == android.view.MotionEvent.ACTION_UP && !callTouchMoved && !callTouchOnButton)
                    callButton.setVisibility(callButton.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
                if (callButton.getVisibility() == View.VISIBLE) handler.postDelayed(hideCallButton, 3000);
            }
        }
        return handled;
    }
    void closeDrawer() { if (menuDrawer != null) menuDrawer.closeDrawer(menuPanel); }
    void applyBrightness(float brightness) { android.view.WindowManager.LayoutParams params = getWindow().getAttributes(); params.screenBrightness = brightness; getWindow().setAttributes(params); getPreferences(MODE_PRIVATE).edit().putFloat("brightness", brightness).apply(); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onStart() {
        super.onStart(); visible = true;
        GuardianRuntime.addSpeechObserver(speechObserver);
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, new IntentFilter(GuardianService.ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true; receive(null); handler.post(ticker);
        weatherWidget.start();
        if (GuardianService.callActive) handler.post(new Runnable() {
            @Override public void run() {
                if (!visible) return;
                if (GuardianService.callActive) { handler.postDelayed(this, 200); return; }
                startListening();
            }
        });
        if (!listening && (!startupAttempted || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)) startListening();
        else requestWeatherPermission();
        if (listening) startupAttempted = true;
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("startupAttempted", startupAttempted);
        out.putBoolean("permissionPending", permissionPending);
        super.onSaveInstanceState(out);
    }
    @Override protected void onStop() {
        handler.removeCallbacks(hideCallButton); hideCallButton.run();
        behavior.gesture(TouchInteraction.CANCEL, 0, 0, SystemClock.elapsedRealtime());
        visible = false; handler.removeCallbacks(ticker);
        inputWave.phase("IDLE"); updateConversationBack();
        weatherWidget.stop();
        GuardianRuntime.removeSpeechObserver(speechObserver);
        if (registered) { unregisterReceiver(receiver); registered = false; }
        super.onStop();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(android.view.WindowInsets.Type.systemBars());
                }
            } else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 9 || code == 10 || code == 11) permissionPending = false;
        if (code == 10) requestWeatherPermission();
        if (code == 11 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) WeatherRepository.get(this).useAutomatic();
        if (code == 9) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListening();
            else { startupAttempted = false; behavior.fail("需要麦克风权限才能守护", SystemClock.elapsedRealtime()); render(); new android.app.AlertDialog.Builder(this).setMessage("请允许红糖使用麦克风，小糖才能自动开始守护。").setPositiveButton("打开设置", (d,w) -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:"+getPackageName())))).setNegativeButton("稍后",null).show(); }
        }
    }
}

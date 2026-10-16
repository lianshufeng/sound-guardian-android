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
    private ModelResultsView modelResults;
    private DrawerLayout menuDrawer;
    private FrameLayout menuPanel;
    private Runnable drawerBack;
    private boolean drawerReturning;
    private OnBackInvokedCallback drawerBackCallback;
    private boolean showEmotionName, showEventName, showSoundLevel;
    private BehaviorEngine behavior;
    private static BehaviorEngine retainedBehavior;
    private boolean listening;
    private boolean startupAttempted;
    private boolean registered;
    private boolean visible;
    private GuardianRuntime.Status serviceStatus = GuardianRuntime.Status.STOPPED;
    private BroadcastReceiver receiver;
    private final Runnable ticker = new Runnable() { public void run() { render(); if (visible) handler.postDelayed(this, 500); } };

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
        startupAttempted = saved != null && saved.getBoolean("startupAttempted");
        buildUi();
        receiver = new BroadcastReceiver() { @Override public void onReceive(Context context, Intent intent) { receive(intent); } };
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER); root.setPadding(dp(30), dp(8), dp(30), dp(12)); root.setBackgroundColor(0xFF0B141C);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("安澜", 18, 0xFFA6C1B8); header.addView(title, new LinearLayout.LayoutParams(-2, dp(44))); title.setGravity(Gravity.CENTER_VERTICAL);
        soundLevelName = text("", 10, 0xFF718B82); soundLevelName.setId(R.id.sound_level); soundLevelName.setSingleLine(true); soundLevelName.setIncludeFontPadding(false);
        soundLevelName.setTypeface(android.graphics.Typeface.MONOSPACE); soundLevelName.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams levelParams = new LinearLayout.LayoutParams(-2, dp(44)); levelParams.leftMargin = dp(12); header.addView(soundLevelName, levelParams);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView menu = text("···", 24, 0xFFB9D7CA); menu.setGravity(Gravity.CENTER); menu.setContentDescription("设置与表情预览"); menu.setId(R.id.menu);
        header.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(44))); menu.setOnClickListener(v -> showPanel());
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));
        FrameLayout center = new FrameLayout(this); face = new FaceView(this); face.setId(R.id.face); center.addView(face, new FrameLayout.LayoutParams(-1, -1));
        face.setOnClickListener(v -> behavior.touch(SystemClock.elapsedRealtime()));
        LinearLayout captions = new LinearLayout(this); captions.setOrientation(LinearLayout.VERTICAL); captions.setGravity(Gravity.CENTER); captions.setPadding(0, 0, 0, dp(16));
        emotionName = text("", 11, 0xFF8BA69C); emotionName.setId(R.id.emotion_name); emotionName.setGravity(Gravity.CENTER);
        for (TextView label : new TextView[]{emotionName}) {
            label.setSingleLine(true); label.setIncludeFontPadding(false); label.setLetterSpacing(.08f);
            label.setMaxWidth(dp(280)); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            captions.addView(label, new LinearLayout.LayoutParams(-2, dp(20)));
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
            behavior.model(snapshot.topRawLabel, snapshot.topLabel, snapshot.topScore, now);
            if (snapshot.activity) behavior.activity(now);
            behavior.events(snapshot.events, now);
        }
        render();
    }

    private void render() {
        long now = SystemClock.elapsedRealtime();
        BehaviorEngine.State state = behavior.state(now); face.setState(state, now);
        emotionName.setText(state.emotion.title);
        emotionName.setVisibility(showEmotionName ? View.VISIBLE : View.GONE);
        GuardianRuntime.Snapshot snapshot = GuardianRuntime.snapshot();
        modelResults.setVisibility(showEventName ? View.VISIBLE : View.GONE);
        if (showEventName) modelResults.render(snapshot, now);
        soundLevelName.setText(String.format(Locale.CHINA, "%.1f dBFS", GuardianRuntime.snapshot().dbfs));
        soundLevelName.setVisibility(showSoundLevel ? View.VISIBLE : View.GONE);
    }

    private void updateCaptionSpace() {
        int rows = showEmotionName ? 1 : 0;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) face.getLayoutParams();
        params.bottomMargin = rows == 0 ? 0 : dp(rows * 20 + 16);
        face.setLayoutParams(params);
    }

    boolean isNameVisible(String key) { return getPreferences(MODE_PRIVATE).getBoolean(key, false); }
    void setNameVisible(String key, boolean enabled) {
        getPreferences(MODE_PRIVATE).edit().putBoolean(key, enabled).apply();
        showEmotionName = isNameVisible("show_emotion_name"); showEventName = isNameVisible("show_event_name"); showSoundLevel = isNameVisible("show_sound_level");
        updateCaptionSpace();
        render();
    }

    private TextView text(String value, float size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t; }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!startupAttempted) { startupAttempted = true; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 9); }
            return;
        }
        startupAttempted = true;
        behavior.setGuarding(true, SystemClock.elapsedRealtime());
        try { startForegroundService(new Intent(this, GuardianService.class)); listening = true; serviceStatus = GuardianRuntime.Status.STARTING; }
        catch (RuntimeException error) { behavior.fail(error.toString(), SystemClock.elapsedRealtime()); serviceStatus = GuardianRuntime.Status.ERROR; listening = false; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
    }
    private void showPanel() {
        behavior.touch(SystemClock.elapsedRealtime());
        new ControlPanel(this, emotion -> { behavior.preview(emotion, SystemClock.elapsedRealtime()); render(); },
                event -> { behavior.simulate(event, SystemClock.elapsedRealtime()); render(); }).show();
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
        else super.onBackPressed();
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK && menuDrawer != null && menuDrawer.isDrawerVisible(menuPanel)) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) navigateDrawerBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    void closeDrawer() { if (menuDrawer != null) menuDrawer.closeDrawer(menuPanel); }
    void applyBrightness(float brightness) { android.view.WindowManager.LayoutParams params = getWindow().getAttributes(); params.screenBrightness = brightness; getWindow().setAttributes(params); getPreferences(MODE_PRIVATE).edit().putFloat("brightness", brightness).apply(); }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onStart() {
        super.onStart(); visible = true;
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, new IntentFilter(GuardianService.ACTION), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true; receive(null); handler.post(ticker);
        if (!listening && (!startupAttempted || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)) startListening();
        if (listening) startupAttempted = true;
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("startupAttempted", startupAttempted);
        super.onSaveInstanceState(out);
    }
    @Override protected void onStop() {
        visible = false; handler.removeCallbacks(ticker);
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
        if (code == 9) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListening();
            else { startupAttempted = false; behavior.fail("需要麦克风权限才能守护", SystemClock.elapsedRealtime()); render(); new android.app.AlertDialog.Builder(this).setMessage("请允许安澜使用麦克风，小安才能自动开始守护。").setPositiveButton("打开设置", (d,w) -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:"+getPackageName())))).setNegativeButton("稍后",null).show(); }
        }
    }
}

package com.anlan.guardian;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ProgressBar;
import java.net.URL;
import java.util.HashMap;
import org.jitsi.meet.sdk.JitsiMeetActivity;
import org.jitsi.meet.sdk.JitsiMeetConferenceOptions;
import org.jitsi.meet.sdk.JitsiMeetUserInfo;

/** SDK会议外壳：延迟加入、确认退出和唯一麦克风交接。 */
public final class CallActivity extends JitsiMeetActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout shell;
    private TextView status;
    private Button hangup, retry;
    private TextView title;
    private boolean joined, leaving, ready, started, destroyed, foreground;
    private long prepareAt, joinedAt;
    private int attempt;
    private android.app.AlertDialog confirmation;

    @Override protected void initialize() { /* 由准备流程显式加入。 */ }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        GuardianService.prepareCall(this);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View meeting = getJitsiView(); ((ViewGroup) meeting.getParent()).removeView(meeting);
        shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(0xFF0B141C);
        shell.setPadding(dp(12), dp(4), dp(12), dp(4));
        title = new TextView(this); title.setText("红糖 · " + CallSettings.room(this));
        title.setTextColor(0xFFD2E5DC); title.setTextSize(20); title.setGravity(Gravity.CENTER); shell.addView(title);
        status = new TextView(this); status.setTextColor(0xFF9DDCCB); status.setTextSize(15); status.setGravity(Gravity.CENTER);
        status.setText("正在准备通话…"); shell.addView(status);
        LinearLayout stage = new LinearLayout(this); stage.setGravity(Gravity.CENTER); stage.addView(new ProgressBar(this));
        shell.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        hangup = new Button(this); hangup.setText("取消"); hangup.setTextColor(0xFFFFFFFF); hangup.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFBB354B));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(150), dp(48)); hp.gravity = Gravity.CENTER; shell.addView(hangup, hp);
        retry = new Button(this); retry.setText("重试"); retry.setVisibility(View.GONE); shell.addView(retry);
        retry.setOnClickListener(v -> { if (started && !ready) return; retry.setVisibility(View.GONE); started = false; ready = false;
            prepareAt = SystemClock.elapsedRealtime(); handler.post(prepare); });
        hangup.setOnClickListener(v -> requestExit()); setContentView(shell);
        prepareAt = SystemClock.elapsedRealtime();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 61);
        } else handler.post(prepare);
    }

    private final Runnable prepare = new Runnable() {
        @Override public void run() {
            if (leaving || destroyed || started || !foreground) return;
            long elapsed = SystemClock.elapsedRealtime() - prepareAt;
            if (elapsed > 20000) { failure("资源准备超时"); return; }
            if (elapsed < 3000 || !GuardianService.audioReleased()) { handler.postDelayed(this, 100); return; }
            try {
                started = true; status.setText("正在连接房间…");
                final int joiningAttempt = ++attempt;
                if (BuildConfig.DEBUG) android.util.Log.d("AnlanCall", "join delayMs=" + elapsed + " guardianAudioReleased=" + GuardianService.audioReleased());
                showConference();
                JitsiMeetUserInfo user = new JitsiMeetUserInfo(); user.setDisplayName("红糖");
                Bundle speakerStats = new Bundle(); speakerStats.putBoolean("disabled", true);
                join(new JitsiMeetConferenceOptions.Builder().setServerURL(new URL(CallSettings.server(CallActivity.this)))
                        .setRoom(CallSettings.room(CallActivity.this)).setAudioMuted(false).setVideoMuted(false)
                        .setUserInfo(user).setConfigOverride("defaultLocalDisplayName", "红糖")
                        .setConfigOverride("defaultRemoteDisplayName", "参会者")
                        .setConfigOverride("hideConferenceSubject", true).setConfigOverride("hideConferenceTimer", true)
                        .setConfigOverride("speakerStats", speakerStats)
                        .setFeatureFlag("prejoinpage.enabled", false).setFeatureFlag("welcomepage.enabled", false)
                        .setFeatureFlag("toolbox.enabled", true).setFeatureFlag("toolbox.alwaysVisible", false)
                        .setFeatureFlag("filmstrip.enabled", true).setFeatureFlag("tile-view.enabled", true)
                        .setFeatureFlag("pip.enabled", false).setFeatureFlag("invite.enabled", false)
                        .setFeatureFlag("meeting-password.enabled", false).setFeatureFlag("recording.enabled", false)
                        .setFeatureFlag("live-streaming.enabled", false).setFeatureFlag("hangup-menu.enabled", false)
                        .setFeatureFlag("car-mode.enabled", false).setFeatureFlag("video-share.enabled", false)
                        .setFeatureFlag("breakout-rooms.enabled", false).setFeatureFlag("settings.enabled", true)
                        .setConfigOverride("toolbarButtons", new String[]{"microphone", "camera", "raisehand", "tileview", "overflowmenu", "participants-pane", "toggle-camera"})
                        .build());
                handler.postDelayed(() -> { if (joiningAttempt == attempt && !joined && !leaving && !ready) { failure("连接超时"); leave(); } }, 45000);
            } catch (Exception error) { started = false; failure("无法启动通话，请检查通讯设置"); }
        }
    };

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 61) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                prepareAt = SystemClock.elapsedRealtime(); handler.post(prepare);
            } else status.setText("需要摄像头和麦克风权限，请返回后重试");
        }
    }
    @Override protected void onConferenceJoined(HashMap<String, Object> data) {
        super.onConferenceJoined(data); joined = true; joinedAt = SystemClock.elapsedRealtime();
        title.setVisibility(View.GONE); status.setTextSize(16); status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END); hangup.setText("挂断"); handler.post(clock);
    }
    private final Runnable clock = new Runnable() {
        @Override public void run() { if (destroyed || leaving || !joined) return;
            long seconds = (SystemClock.elapsedRealtime() - joinedAt) / 1000;
            status.setText(String.format(java.util.Locale.ROOT, "%s · %02d:%02d", CallSettings.room(CallActivity.this), seconds / 60, seconds % 60));
            handler.postDelayed(this, 1000);
        }
    };
    @Override protected void onConferenceTerminated(HashMap<String, Object> data) {
        handler.removeCallbacks(clock);
        joined = false; if (!leaving) failure(data.containsKey("error") ? "连接已中断" : "通话已结束");
    }
    @Override protected void onReadyToClose() {
        ready = true; retry.setEnabled(true);
        if (leaving) super.onReadyToClose();
        else { joined = false; handler.removeCallbacks(clock);
            if (!status.getText().toString().contains("可重试")) failure("会议已结束"); }
    }
    private void failure(String message) { status.setSingleLine(false); status.setText(message + "，可重试或返回主页"); retry.setVisibility(View.VISIBLE); retry.setEnabled(!started || ready); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showConference() {
        View meeting = getJitsiView();
        if (meeting.getParent() != null) ((ViewGroup) meeting.getParent()).removeView(meeting);
        for (View view : new View[]{title, status, hangup, retry})
            if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
        android.widget.FrameLayout full = new android.widget.FrameLayout(this);
        full.setBackgroundColor(0xFF0B141C); full.addView(meeting, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(4), dp(8), dp(4));
        title.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);
        status.setShadowLayer(dp(2), 0, dp(1), 0xCC000000);
        title.setTextSize(16); title.setGravity(Gravity.LEFT); status.setTextSize(12); status.setGravity(Gravity.LEFT);
        title.setVisibility(View.VISIBLE); status.setSingleLine(false);
        header.addView(title); header.addView(status);
        full.addView(header, new android.widget.FrameLayout.LayoutParams(dp(320), dp(56), Gravity.TOP | Gravity.LEFT));
        android.widget.FrameLayout.LayoutParams hp = new android.widget.FrameLayout.LayoutParams(dp(112), dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = dp(4); full.addView(hangup, hp);
        android.widget.FrameLayout.LayoutParams rp = new android.widget.FrameLayout.LayoutParams(dp(112), dp(48), Gravity.CENTER);
        full.addView(retry, rp); setContentView(full);
    }
    @Override public void onResume() {
        super.onResume(); foreground = true;
        if (!started && !leaving && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            prepareAt = SystemClock.elapsedRealtime(); handler.removeCallbacks(prepare); handler.post(prepare);
        }
    }
    @Override public void onStop() { foreground = false; handler.removeCallbacks(prepare); super.onStop(); }
    @android.annotation.SuppressLint("MissingSuperCall")
    @Override protected void onUserLeaveHint() { /* SDK父类尝试进入PiP；此页不启用悬浮小窗。 */ }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private void requestExit() {
        if (leaving) return;
        if (!joined) { exit(); return; }
        if (confirmation != null && confirmation.isShowing()) return;
        confirmation = new android.app.AlertDialog.Builder(this).setTitle("结束本次通话？")
                .setMessage("挂断后返回红糖主页并恢复声音守护。")
                .setNegativeButton("继续通话", null).setPositiveButton("挂断", (dialog, which) -> exit()).show();
    }
    private void exit() {
        leaving = true; handler.removeCallbacksAndMessages(null); status.setText("正在结束通话…");
        if (!started || ready) finish();
        else { leave(); handler.postDelayed(this::finish, 5000); }
    }
    // SDK默认返回行为直接分发给React Native；外壳必须先让用户确认挂断。
    @android.annotation.SuppressLint("MissingSuperCall")
    @Override public void onBackPressed() { requestExit(); }
    @Override public void onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null);
        if (confirmation != null) confirmation.dismiss();
        org.jitsi.meet.sdk.JitsiMeetView meeting = getJitsiView();
        super.onDestroy();
        if (meeting != null) meeting.dispose();
        GuardianService.releaseCall(this);
    }
}

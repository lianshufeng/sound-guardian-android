package com.anlan.guardian;

import android.content.Context;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** 模型事件文字与置信度角标向上漂浮并淡出；限制数量与重复频率，不写入事件记录。 */
final class ModelResultsView extends FrameLayout {
    private static final long LIFETIME_MS = 2400, REPEAT_MS = 4800, INTERVAL_MS = 500;
    private static final long PENDING_MS = 3200;
    private static final int MAX_PENDING = 12, MAX_VISIBLE = 6;
    private final Map<String, Long> recent = new HashMap<>();
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private static final class Pending {
        final GuardianRuntime.ModelResult result;
        final long seenAt;
        Pending(GuardianRuntime.ModelResult result, long seenAt) { this.result = result; this.seenAt = seenAt; }
    }
    private long nextBubbleAt;
    private GuardianRuntime.Snapshot lastSnapshot;

    ModelResultsView(Context context) {
        super(context);
        setClipChildren(true); setClipToPadding(true);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void render(GuardianRuntime.Snapshot snapshot, long now) {
        if (snapshot.status != GuardianRuntime.Status.LISTENING) {
            clearBubbles(); lastSnapshot = snapshot; return;
        }
        recent.entrySet().removeIf(entry -> now - entry.getValue() >= REPEAT_MS);
        pending.entrySet().removeIf(entry -> now - entry.getValue().seenAt >= PENDING_MS);
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot;
            for (GuardianRuntime.ModelResult result : snapshot.modelResults) {
                if (!Float.isFinite(result.score) || result.score < .25f || recent.containsKey(result.label)) continue;
                // 更新分数但保留排队次序，持续高分项不能挤掉已等待的其他声音。
                if (pending.containsKey(result.label) || pending.size() < MAX_PENDING)
                    pending.put(result.label, new Pending(result, now));
            }
        }
        if (now < nextBubbleAt || getChildCount() >= MAX_VISIBLE || pending.isEmpty()) return;
        String label = pending.keySet().iterator().next();
        GuardianRuntime.ModelResult result = pending.remove(label).result;
        recent.put(label, now); nextBubbleAt = now + INTERVAL_MS;
        floatBubble(result);
    }

    private void floatBubble(GuardianRuntime.ModelResult result) {
        LinearLayout bubble = new LinearLayout(getContext()); bubble.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(0xFFACC9BD); name.setText(result.name);
        name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(Math.max(dp(40), (getWidth() > 0 ? getWidth() : dp(220)) - dp(34)));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, dp(24)); nameParams.topMargin = dp(6);
        bubble.addView(name, nameParams);
        TextView badge = text(0xFF93B4A6); badge.setText(String.format(Locale.CHINA, "%.0f", result.score * 100));
        badge.setTextSize(8); badge.setGravity(Gravity.CENTER); badge.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, dp(14)); badgeParams.leftMargin = dp(2);
        bubble.addView(badge, badgeParams); bubble.setAlpha(0);
        addView(bubble, new FrameLayout.LayoutParams(-2, dp(30), Gravity.RIGHT | Gravity.BOTTOM));
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1); animator.setDuration(LIFETIME_MS);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            bubble.setTranslationY(-dp(150) * progress);
            bubble.setAlpha(progress < .1f ? progress / .1f : progress < .4f ? 1 : (1 - progress) / .6f);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { removeView(bubble); }
        });
        bubble.setTag(animator); animator.start();
    }

    private void clearBubbles() {
        for (int i = getChildCount() - 1; i >= 0; i--) ((ValueAnimator) getChildAt(i).getTag()).cancel();
        removeAllViews(); recent.clear(); pending.clear(); nextBubbleAt = 0; lastSnapshot = null;
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != View.VISIBLE && recent != null) { clearBubbles(); lastSnapshot = null; }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE && recent != null) { clearBubbles(); lastSnapshot = null; }
    }
    @Override protected void onDetachedFromWindow() { clearBubbles(); super.onDetachedFromWindow(); }

    private TextView text(int color) {
        TextView view = new TextView(getContext()); view.setTextSize(11); view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL); view.setIncludeFontPadding(false); return view;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

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
import java.util.Map;
import java.util.Locale;

/** 模型事件文字与置信度角标向上漂浮并淡出；限制数量与重复频率，不写入事件记录。 */
final class ModelResultsView extends FrameLayout {
    private static final long LIFETIME_MS = 3200, REPEAT_MS = 2400, INTERVAL_MS = 750;
    private final Map<String, Long> recent = new HashMap<>();
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
        if (snapshot == lastSnapshot) return;
        lastSnapshot = snapshot;
        if (now < nextBubbleAt || getChildCount() >= 4) return;
        recent.entrySet().removeIf(entry -> now - entry.getValue() >= REPEAT_MS);
        for (GuardianRuntime.ModelResult result : snapshot.modelResults) {
            if (result.score < .25f || recent.containsKey(result.label)) continue;
            recent.put(result.label, now); nextBubbleAt = now + INTERVAL_MS;
            floatBubble(result); break;
        }
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
            bubble.setTranslationY(-dp(130) * progress);
            bubble.setAlpha(progress < .1f ? progress / .1f : progress < .4f ? 1 : (1 - progress) / .6f);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { removeView(bubble); }
        });
        bubble.setTag(animator); animator.start();
    }

    private void clearBubbles() {
        for (int i = getChildCount() - 1; i >= 0; i--) ((ValueAnimator) getChildAt(i).getTag()).cancel();
        removeAllViews(); recent.clear(); nextBubbleAt = 0;
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

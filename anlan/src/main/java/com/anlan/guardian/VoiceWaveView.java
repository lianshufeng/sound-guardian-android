package com.anlan.guardian;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** 底部语音波浪：待输入时呼吸，收到短时音量后平滑抬高。 */
final class VoiceWaveView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private LinearGradient gradient;
    private String phase = "IDLE";
    private float level, displayedLevel;
    private long levelAt;
    private long lastFrame;
    private final Runnable frame = this::invalidate;

    VoiceWaveView(Context context) {
        super(context);
        setContentDescription("语音输入波浪");
    }

    void phase(String value) {
        String next = value == null ? "IDLE" : value;
        if (!phase.equals(next)) {
            phase = next;
            if ("IDLE".equals(next)) { removeCallbacks(frame); level = displayedLevel = 0; lastFrame = 0; }
            invalidate();
        }
    }

    void level(float value, long at) {
        float next = Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
        levelAt = at;
        if (Math.abs(next - level) > .01f) { level = next; invalidate(); }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        gradient = new LinearGradient(0, h * .35f, 0, h,
                new int[]{0xDDFFFFFF, 0xBB9ADFFF, 0xAA326DFF}, null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        if ("IDLE".equals(phase)) { removeCallbacks(frame); return; }
        int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        long now = SystemClock.elapsedRealtime();
        float dt = lastFrame == 0 ? .033f : Math.min(.08f, Math.max(.008f, (now - lastFrame) / 1000f));
        lastFrame = now;
        float target = now >= levelAt && now - levelAt <= 300 ? level : 0f;
        displayedLevel += (target - displayedLevel) * (1f - (float)Math.exp(-dt * (target > displayedLevel ? 11 : 5)));
        float breath = .5f + .5f * (float)Math.sin(now / 900.0);
        float audio = "LISTENING".equals(phase) ? displayedLevel : 0f;
        float scale = height / 108f;
        for (int layer = 0; layer < 3; layer++) {
            path.reset(); path.moveTo(0, height);
            for (int x = 0; x <= width; x += 3) {
                float u = x / (float)width;
                float envelope = (float)Math.pow(Math.sin(Math.PI * u), 2.25);
                float drift = .55f + .18f * (float)Math.sin(u * 12 - now / 1100.0 + layer * 1.8)
                        + .10f * (float)Math.sin(u * 25 + now / 1400.0 + layer);
                float detail = audio * (.16f * (float)Math.sin(u * 35 - now / 200.0 + layer)
                        + .10f * (float)Math.sin(u * 52 + now / 280.0));
                float crest = 8f + envelope * (18f + breath * 6f + audio * 62f) * (drift + detail) * (.82f + layer * .10f);
                path.lineTo(x, height - crest * scale);
            }
            path.lineTo(width, height); path.close();
            paint.setShader(gradient);
            paint.setAlpha(105 + layer * 35);
            canvas.drawPath(path, paint);
        }
        paint.setShader(null); paint.setAlpha(255);
        if (isShown()) { removeCallbacks(frame); postDelayed(frame, 33); }
    }

    @Override protected void onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(frame);
        if (visibility == VISIBLE) invalidate();
    }
}

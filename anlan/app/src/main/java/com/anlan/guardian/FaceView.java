package com.anlan.guardian;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.os.SystemClock;
import java.util.Random;

/** 安澜的极简脸。几何参数随尺寸缩放，不使用位图，适合横屏设备常驻显示。 */
public final class FaceView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Emotion emotion = Emotion.CALM;
    private int variant;
    private long changedAt;
    private float gazeX, gazeY;
    private final Random random = new Random();
    private long nextBlink, blinkStart = -10000, nextLook;
    private float targetX, targetY;
    private float[] from = pose(Emotion.CALM), target = pose(Emotion.CALM);
    private final float[] current = pose(Emotion.CALM);
    private final Runnable frame = () -> { invalidate(); };
    private final Path path = new Path();
    private final RectF bounds = new RectF();

    public FaceView(Context context) {
        super(context); setContentDescription("小安的表情");
        nextBlink = SystemClock.elapsedRealtime() + 3000;
    }

    public void setState(BehaviorEngine.State state, long now) {
        if (state.emotion != emotion || state.variant != variant) {
            System.arraycopy(current, 0, from, 0, current.length);
            emotion = state.emotion; variant = state.variant; changedAt = now;
            target = pose(emotion);
            target[0] *= variant == 1 ? .94f : variant == 2 ? 1.05f : 1;
            target[4] *= variant == 1 ? 1.10f : variant == 2 ? .90f : 1;
            setContentDescription("小安 · " + emotion.title);
        }
        invalidate();
    }

    public void setGaze(float x, float y) { gazeX = Math.max(-1, Math.min(1, x)); gazeY = Math.max(-1, Math.min(1, y)); invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFF0B141C);
        float w = getWidth(), h = getHeight(), s = Math.min(w / 1050f, h / 500f);
        float cx = w / 2f, cy = h / 2f;
        long now = SystemClock.elapsedRealtime();
        float progress = Math.min(1, Math.max(0, (now - changedAt) / 330f));
        progress = progress * progress * (3 - 2 * progress);
        for (int i=0; i<current.length; i++) current[i] = from[i] + (target[i] - from[i]) * progress;
        if (now >= nextLook) { targetX = (random.nextFloat() - .5f) * 1.2f; targetY = (random.nextFloat() - .5f) * .5f; nextLook = now + 5000 + random.nextInt(7000); }
        if (emotion == Emotion.LISTENING || emotion == Emotion.ALERT) { targetX = 0; targetY = 0; }
        gazeX += (targetX - gazeX) * .06f; gazeY += (targetY - gazeY) * .06f;
        // 每隔数分钟轻微偏移，打破长期固定像素；不替代屏幕本身的防烧屏措施。
        float drift = (float)Math.sin(now / 170000.0) * 7*s;
        canvas.save(); canvas.translate(gazeX*18*s + drift, gazeY*10*s + (float)Math.sin(now/1700.0)*3*s);
        float eyeW = current[0] * s, eyeH = current[1] * s, gap = 164 * s;
        float eyeY = cy - 38 * s;
        float leftX = cx - gap, rightX = cx + gap;
        int color = emotion == Emotion.UNAVAILABLE ? 0xFFB97B77 : 0xFFB9E8D3;
        paint.setColor(color); paint.setStyle(Paint.Style.FILL); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        float blink = blinkAmount(now);
        drawEye(canvas, leftX, eyeY, eyeW, eyeH, blink, false, s);
        paint.setColor(color);
        drawEye(canvas, rightX, eyeY, eyeW, eyeH, blink, true, s);
        drawMouth(canvas, cx, cy + 148 * s, s, color);
        if (emotion == Emotion.CRYING) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5 * s); paint.setColor(0xFF8EC7E5);
            float drop = (now % 1400) / 1400f * 80*s;
            bounds.set(leftX-7*s,eyeY+40*s+drop,leftX+7*s,eyeY+65*s+drop); canvas.drawOval(bounds,paint);
            bounds.set(rightX-7*s,eyeY+40*s+drop,rightX+7*s,eyeY+65*s+drop); canvas.drawOval(bounds,paint);
        }
        if (emotion == Emotion.ALERT) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4*s); paint.setColor(0xFFE6A27E);
            canvas.drawCircle(cx, cy - 210*s, 10*s, paint); canvas.drawCircle(cx, cy - 210*s, 20*s, paint);
        }
        canvas.restore();
        if (getWindowVisibility() == VISIBLE && isShown()) { removeCallbacks(frame); postDelayed(frame, emotion == Emotion.SLEEPING ? 66 : 33); }
    }

    private void drawEye(Canvas c, float x, float y, float width, float height, float blink, boolean right, float s) {
        float h = height * (1 - blink); if (h < 5*s) h = 5*s;
        float tilt = current[2] * (right ? -1 : 1);
        int style = Math.round(current[3]);
        if (emotion == Emotion.DOUBTFUL && right) { h *= .55f; y -= 10*s; }
        if (emotion == Emotion.PLAYFUL && !right) h = 5*s;
        if (emotion == Emotion.WAITING && right) y -= 8*s;
        if (variant == 1 && right) h *= .94f;
        paint.setStyle(Paint.Style.FILL);
        c.save(); c.rotate(tilt, x, y);
        bounds.set(x - width/2, y - h/2, x + width/2, y + h/2);
        if (style == 1 && blink < .5f) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(10*s);
            path.reset(); path.moveTo(x-width/2,y+10*s); path.quadTo(x,y-h*.65f,x+width/2,y+10*s); c.drawPath(path,paint);
        } else if (style == 2 && blink < .5f) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(9*s);
            float sign = right ? -1 : 1;
            path.reset(); path.moveTo(x-sign*width/2,y-h/2); path.lineTo(x+sign*width/3,y); path.lineTo(x-sign*width/2,y+h/2); c.drawPath(path,paint);
        } else if (style == 3 && blink < .5f) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(9*s); c.drawOval(bounds,paint);
        } else {
            c.drawRoundRect(bounds, Math.min(width,h)/2, Math.min(width,h)/2,paint);
        }
        c.restore();
    }

    private void drawMouth(Canvas c, float x, float y, float s, int color) {
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(8*s); paint.setStrokeCap(Paint.Cap.ROUND);
        float width = current[4]*s, open = current[5]*s, curve = current[6]*s;
        path.reset(); path.moveTo(x-width/2,y);
        path.quadTo(x,y+curve-open,x+width/2,y);
        if (open > 1*s) { path.quadTo(x,y+curve+open,x-width/2,y); path.close(); }
        c.drawPath(path, paint);
    }

    private float blinkAmount(long now) {
        if (emotion == Emotion.SLEEPING) return 1f;
        if (now >= nextBlink) { blinkStart = now; nextBlink = now + 2800 + random.nextInt(4500); }
        long age = now-blinkStart;
        return age < 180 ? (float)Math.sin(Math.PI*age/180.0) : 0;
    }

    // 宽、高、倾斜、眼型（实心/弧线/> </空心）、嘴宽、嘴开合、嘴曲率。
    private static float[] pose(Emotion e) {
        switch(e) {
            case SMILE: return new float[]{94,94,0,0,88,0,38};
            case HAPPY: return new float[]{106,72,0,1,105,32,38};
            case CONTENT: return new float[]{108,50,0,1,70,0,22};
            case CURIOUS: return new float[]{88,114,-4,0,28,16,0};
            case ATTENTIVE: return new float[]{90,124,0,0,45,0,0};
            case LISTENING: return new float[]{82,122,0,0,60,0,15};
            case DOUBTFUL: return new float[]{92,105,8,0,54,0,-12};
            case THINKING: return new float[]{110,44,0,0,30,0,0};
            case WAITING: return new float[]{80,92,0,0,64,0,8};
            case STARTLED: return new float[]{128,134,0,3,48,60,0};
            case SURPRISED: return new float[]{116,112,0,3,35,42,0};
            case TENSE: return new float[]{100,74,-12,0,72,14,-8};
            case WORRIED: return new float[]{110,60,15,0,75,0,-30};
            case CARING: return new float[]{92,100,7,0,65,0,14};
            case SAD: return new float[]{108,42,14,0,72,0,-34};
            case HURT: return new float[]{118,100,8,3,38,0,-20};
            case CRYING: return new float[]{108,68,12,2,82,20,-25};
            case DROWSY: return new float[]{118,25,0,0,45,8,0};
            case SLEEPING: return new float[]{118,5,0,0,28,13,0};
            case WAKING: return new float[]{112,65,0,0,40,25,0};
            case RELIEVED: return new float[]{110,40,0,1,90,0,25};
            case PLAYFUL: return new float[]{100,105,-3,0,90,0,34};
            case ACKNOWLEDGE: return new float[]{90,90,0,1,78,0,30};
            case ALERT: return new float[]{106,108,-7,0,48,12,0};
            case UNAVAILABLE: return new float[]{110,68,0,2,72,0,-10};
            default: return new float[]{100,96,0,0,64,0,6};
        }
    }

    @Override protected void onDetachedFromWindow() { removeCallbacks(frame); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) invalidate(); else removeCallbacks(frame);
    }
}

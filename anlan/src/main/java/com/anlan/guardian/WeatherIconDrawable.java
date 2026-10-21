package com.anlan.guardian;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** 红糖自绘线条图标，不下载图片，不依赖字体或 Emoji。 */
final class WeatherIconDrawable extends Drawable {
    private final WeatherForecast.Condition condition;
    private final boolean day;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    WeatherIconDrawable(WeatherForecast.Condition condition, boolean day) {
        this.condition = condition; this.day = day;
        paint.setColor(0xFFAAC8BC); paint.setStrokeWidth(1.6f);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save(); canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 32f, getBounds().height() / 32f);
        paint.setStyle(Paint.Style.STROKE);
        switch (condition) {
            case CLEAR: celestial(canvas); break;
            case PARTLY_CLOUDY:
                canvas.save(); canvas.translate(-2, -3); canvas.scale(.8f, .8f); celestial(canvas); canvas.restore();
                cloud(canvas, 3); break;
            case CLOUDY: cloud(canvas, 0); break;
            case FOG:
                cloud(canvas, -3); line(canvas, 5, 23, 27, 23); line(canvas, 8, 27, 24, 27); break;
            case RAIN:
                cloud(canvas, -3);
                for (int x = 10; x <= 24; x += 7) line(canvas, x, 23, x - 2, 27);
                break;
            case SNOW:
                cloud(canvas, -4);
                for (int x = 10; x <= 24; x += 7) {
                    line(canvas, x, 23, x, 28); line(canvas, x - 2, 24, x + 2, 27); line(canvas, x - 2, 27, x + 2, 24);
                }
                break;
            case THUNDERSTORM:
                cloud(canvas, -4);
                Path bolt = new Path(); bolt.moveTo(18, 21); bolt.lineTo(13, 26); bolt.lineTo(18, 26); bolt.lineTo(15, 30); canvas.drawPath(bolt, paint); break;
            default: canvas.drawCircle(16, 16, 8, paint); line(canvas, 16, 12, 16, 17); canvas.drawPoint(16, 21, paint); break;
        }
        canvas.restoreToCount(save);
    }
    private void celestial(Canvas canvas) {
        if (day) {
            canvas.drawCircle(16, 16, 5, paint);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                line(canvas, 16 + (float)Math.cos(angle) * 9, 16 + (float)Math.sin(angle) * 9,
                        16 + (float)Math.cos(angle) * 12, 16 + (float)Math.sin(angle) * 12);
            }
        } else {
            Path moon = new Path(); moon.moveTo(20, 5);
            moon.cubicTo(4, 3, 2, 25, 17, 27); moon.cubicTo(23, 28, 27, 24, 28, 20);
            moon.cubicTo(16, 23, 11, 12, 20, 5); moon.close(); canvas.drawPath(moon, paint);
        }
    }
    private void cloud(Canvas canvas, float dy) {
        Path cloud = new Path(); cloud.moveTo(9, 23 + dy);
        cloud.cubicTo(1, 22 + dy, 3, 12 + dy, 10, 14 + dy);
        cloud.cubicTo(12, 4 + dy, 25, 7 + dy, 25, 15 + dy);
        cloud.cubicTo(32, 15 + dy, 32, 23 + dy, 25, 23 + dy); cloud.close();
        paint.setStyle(Paint.Style.FILL); int color = paint.getColor(); paint.setColor(0xFF0B141C); canvas.drawPath(cloud, paint);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); canvas.drawPath(cloud, paint);
    }
    private void line(Canvas c, float x1, float y1, float x2, float y2) { c.drawLine(x1, y1, x2, y2, paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

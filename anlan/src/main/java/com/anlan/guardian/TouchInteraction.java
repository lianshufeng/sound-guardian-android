package com.anlan.guardian;

/** 归一化坐标手势，不依赖 Android；只有持续、往返移动才算抚摸。 */
final class TouchInteraction {
    static final int DOWN = 0, MOVE = 1, UP = 2, CANCEL = 3;
    private boolean pressed, stroked, moved;
    private long downAt, releasedAt = -10000, wakingUntil;
    private float x, y, startX, startY, anchorX, anchorY, distance, direction;
    private int turns;
    private long holdMs = 800, lingerMs = 1400;

    void configure(long hold, long linger) {
        if (hold < 300 || linger < 300 || hold > 3000 || linger > 5000)
            throw new IllegalArgumentException("触摸时间配置不合法");
        holdMs = hold; lingerMs = linger;
    }
    void accept(int action, float px, float py, long now, boolean waking) {
        if (action == CANCEL) { clear(); return; }
        px = Math.max(-1, Math.min(1, px)); py = Math.max(-1, Math.min(1, py));
        if (action == DOWN) {
            pressed = true; stroked = moved = false; downAt = now; distance = direction = 0; turns = 0;
            x = startX = anchorX = px; y = startY = anchorY = py; wakingUntil = waking ? now + 300 : 0;
        } else if (pressed) {
            float dx = px - x, dy = py - y;
            distance += (float) Math.hypot(dx, dy);
            float ax = px - anchorX, ay = py - anchorY;
            float step = Math.abs(ax) > Math.abs(ay) ? ax : ay;
            if (Math.abs(step) > .04f) {
                if (direction != 0 && Math.signum(step) != direction) turns++;
                direction = Math.signum(step);
                anchorX = px; anchorY = py;
            }
            x = px; y = py;
            moved |= Math.hypot(x - startX, y - startY) > .06;
            stroked |= moved && now - downAt >= 250 && distance >= .35f && turns >= 1;
            if (action == UP) { pressed = false; releasedAt = now; }
        }
    }
    boolean active(long now) { return pressed || now - releasedAt < lingerMs; }
    Emotion emotion(long now) {
        if (now < wakingUntil) return Emotion.WAKING;
        if (stroked) return Emotion.NUZZLE;
        if (pressed) return !moved && now - downAt >= holdMs ? Emotion.SHY : Emotion.CURIOUS;
        return !moved && releasedAt - downAt < holdMs && now - releasedAt < 220 ? Emotion.SURPRISED : Emotion.SMILE;
    }
    float gazeX(long now) { return x * releaseScale(now); }
    float gazeY(long now) { return y * releaseScale(now); }
    private float releaseScale(long now) { return pressed ? 1 : Math.max(0, 1 - (now - releasedAt) / 600f); }
    void clear() { pressed = false; releasedAt = -10000; wakingUntil = 0; }
}

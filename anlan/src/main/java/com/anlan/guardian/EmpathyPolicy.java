package com.anlan.guardian;

import java.util.Locale;

/** 纯 Java 共情策略：标签映射、去重、连续证据和保持时间，不负责最终表情优先级。 */
final class EmpathyPolicy {
    private Emotion current, pending;
    private long changedAt, until, lastId, holdMs = 2000, lingerMs = 3500;
    private String candidate = "";
    private int hits;

    void configure(long hold, long linger) {
        if (hold < 1000 || linger < hold || linger > 10000) throw new IllegalArgumentException("共情时间配置不合法");
        holdMs = hold; lingerMs = linger;
    }
    boolean accept(long id, String label, long at, long now) {
        if (id <= lastId || at > now || now - at > 6000) return false;
        lastId = id;
        String value = label == null ? "" : label.replace("<|", "").replace("|>", "").trim().toUpperCase(Locale.ROOT);
        hits = value.equals(candidate) && now < until + 4000 ? hits + 1 : 1;
        candidate = value;
        Emotion mapped;
        switch (value) {
            case "HAPPY": mapped = hits >= 2 ? Emotion.HAPPY : Emotion.SMILE; break;
            case "SAD": case "FEARFUL": case "ANGRY": case "DISGUSTED": mapped = Emotion.CARING; break;
            case "SURPRISED": mapped = Emotion.ATTENTIVE; break;
            default: mapped = Emotion.LISTENING;
        }
        if (current != null && now < until && mapped != current && now < changedAt + holdMs) pending = mapped;
        else { if (mapped != current || now >= until) changedAt = now; current = mapped; pending = null; }
        until = now + lingerMs; return true;
    }
    Emotion emotion(long now) {
        if (pending != null && now >= changedAt + holdMs) {
            if (now < until) { current = pending; changedAt = now; }
            pending = null;
        }
        return now < until ? current : null;
    }
    void clear() { current = pending = null; until = 0; candidate = ""; hits = 0; }
    void reset() { clear(); lastId = 0; }
}

package com.anlan.guardian;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 关怀只消费共享层已经确认的事件，不重复实现模型阈值和事件确认算法。 */
final class CareEvidence {
    static String match(List<SoundEvent> events, String label, int hits, long window, long duration, long now) {
        Set<String> ids = new HashSet<>(); String latest = ""; long latestAt = Long.MIN_VALUE;
        boolean sustained = false;
        for (SoundEvent event : events) {
            if (!event.label.equals(label) || event.start > now || event.end > now || now - event.end > window
                    || !event.active && now - event.start > window) continue;
            ids.add(event.id);
            sustained |= duration > 0 && event.end - event.start >= duration;
            if (event.start > latestAt) { latestAt = event.start; latest = event.id; }
        }
        return ids.size() >= hits || sustained ? latest : "";
    }
}

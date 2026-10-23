package com.anlan.guardian;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CallSettingsTest {
    @Test public void normalizesServerAndRoom() {
        assertEquals("https://meet.jpy.wang", CallSettings.normalizeServer(" https://meet.jpy.wang/ "));
        assertEquals("家庭01", CallSettings.normalizeRoom(" 家庭01 "));
    }
    @Test public void rejectsUnsafeOrMalformedSettings() {
        for (String server : new String[]{"http://meet.jpy.wang", "https://user:secret@meet.jpy.wang", "https://", "https://meet.jpy.wang?token=x", "https://meet.jpy.wang:99999"}) {
            assertThrows(IllegalArgumentException.class, () -> CallSettings.normalizeServer(server));
        }
        for (String room : new String[]{"", "a/b", "a?b", "a#b", "a b", "a\nb", "a\\b"}) {
            assertThrows(IllegalArgumentException.class, () -> CallSettings.normalizeRoom(room));
        }
    }
}

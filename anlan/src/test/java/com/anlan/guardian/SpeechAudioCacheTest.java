package com.anlan.guardian;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SpeechAudioCacheTest {
    @Test public void bytesAndEntriesStayBoundedAndRevisionDoesNotReuseOldAudio(){
        SpeechAudioCache<String> cache=new SpeechAudioCache<>(2,10);
        assertTrue(cache.put("v1/wake","first",4));assertTrue(cache.put("v1/snap","snap",4));
        assertEquals("first",cache.get("v1/wake"));assertTrue(cache.put("v2/wake","second",5));
        assertNull(cache.get("v1/snap"));assertEquals("second",cache.get("v2/wake"));assertTrue(cache.bytes()<=10);
        assertFalse(cache.put("large","oversized",11));assertEquals("second",cache.get("v2/wake"));
        cache.retainOnly("v2/wake");assertEquals(5,cache.bytes());assertNull(cache.get("v1/wake"));
        cache.clear();assertEquals(0,cache.bytes());assertNull(cache.get("v2/wake"));
    }
}

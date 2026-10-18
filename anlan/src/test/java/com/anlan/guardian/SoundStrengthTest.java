package com.anlan.guardian;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SoundStrengthTest {
    @Test public void louderPcmIncreasesStrengthWithinBounds() {
        long token = GuardianRuntime.begin();
        int previous = -1;
        for (short amplitude : new short[]{0, 64, 512, 4096, 32767}) {
            GuardianRuntime.publishSoundLevel(token, new short[]{amplitude, (short)-amplitude}, 2, 1000);
            int strength = GuardianRuntime.soundStrength(1000);
            assertTrue(strength > previous); assertTrue(strength >= 0 && strength <= 100);
            previous = strength;
        }
        assertEquals(100, previous);
    }

    @Test public void captureBufferReuseAndOldSessionsCannotChangeDisplayedLevel() {
        long token = GuardianRuntime.begin();
        short[] samples = {32767, 32767, 0};
        GuardianRuntime.publishSoundLevel(token, samples, 2, 1000);
        java.util.Arrays.fill(samples, (short)0);
        assertEquals(100, GuardianRuntime.soundStrength(1000));
        assertEquals(0, GuardianRuntime.soundStrength(1501));
        GuardianRuntime.begin();
        GuardianRuntime.publishSoundLevel(token, new short[]{32767}, 1, 2000);
        assertEquals(0, GuardianRuntime.soundStrength(2000));
    }

    @Test public void stoppedServiceClearsLevelImmediately() {
        long token = GuardianRuntime.begin();
        GuardianRuntime.publishSoundLevel(token, new short[]{32767}, 1, 1000);
        GuardianRuntime.publish(token, new GuardianRuntime.Snapshot(GuardianRuntime.Status.STOPPED,
                java.util.Collections.emptyList(), "", "", 0, -96, false));
        assertEquals(0, GuardianRuntime.soundStrength(1000));
    }
}

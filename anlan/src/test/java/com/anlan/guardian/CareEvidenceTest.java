package com.anlan.guardian;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CareEvidenceTest {
    @Test public void repeatedFramesOfSameEventCannotCountAsMultipleCoughs() {
        SoundEvent first = new SoundEvent("one", "Cough", "咳嗽", 1000, 2000, false);
        assertEquals("", CareEvidence.match(Arrays.asList(first, first, first), "Cough", 3, 120000, 0, 3000));
        assertEquals("three", CareEvidence.match(Arrays.asList(first,
                new SoundEvent("two", "Cough", "咳嗽", 3000, 4000, false),
                new SoundEvent("three", "Cough", "咳嗽", 6000, 7000, false)), "Cough", 3, 120000, 0, 8000));
    }
    @Test public void oneSustainedCryCanTriggerButExpiredAndFutureEvidenceCannot() {
        SoundEvent cry = new SoundEvent("cry", "Crying, sobbing", "哭泣", 1000, 8000, true);
        assertEquals("cry", CareEvidence.match(Collections.singletonList(cry), "Crying, sobbing", 2, 120000, 6000, 8000));
        assertEquals("", CareEvidence.match(Collections.singletonList(cry), "Crying, sobbing", 2, 120000, 6000, 0));
        assertEquals("", CareEvidence.match(Collections.singletonList(cry), "Crying, sobbing", 2, 120000, 6000, 200000));
    }
}

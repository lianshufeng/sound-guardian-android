package com.anlan.guardian;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ResourceBudgetTest {
    @Test public void fourGbAndExplicitLowRamUseShortModelRetention() {
        assertEquals(10000, new ResourceBudget(4L << 30, false).modelIdleMs);
        assertTrue(new ResourceBudget(8L << 30, true).constrained);
        assertEquals(10000, new ResourceBudget(0, false).modelIdleMs);
    }

    @Test public void sixGbDeviceKeepsNormalModelRetention() {
        ResourceBudget budget = new ResourceBudget(6L << 30, false);
        assertFalse(budget.constrained);
        assertEquals(60000, budget.modelIdleMs);
    }
}

package com.huaxia.inventory.data;

import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.ScanRecord;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatusDerivationTest {

    @Test
    public void allFound_whenEveryAssetMatched() {
        Set<Long> matched = new HashSet<>(Arrays.asList(1L, 2L, 3L));
        StatusDerivation.Stats s = StatusDerivation.compute(3, matched, 0);
        assertEquals(3, s.found);
        assertEquals(0, s.missing);
        assertEquals(0, s.extra);
    }

    @Test
    public void allMissing_whenNoScans() {
        StatusDerivation.Stats s = StatusDerivation.compute(5, Collections.emptySet(), 0);
        assertEquals(0, s.found);
        assertEquals(5, s.missing);
        assertEquals(0, s.extra);
    }

    @Test
    public void extrasCountedSeparately() {
        StatusDerivation.Stats s = StatusDerivation.compute(10, new HashSet<>(Arrays.asList(1L, 2L)), 3);
        assertEquals(2, s.found);
        assertEquals(8, s.missing);
        assertEquals(3, s.extra);
    }

    @Test
    public void invariant_foundPlusMissing_equalsExpected() {
        for (int expected = 1; expected <= 100; expected++) {
            int found = (int) (Math.random() * (expected + 1));
            Set<Long> matched = new HashSet<>();
            for (long i = 1; i <= found; i++) matched.add(i);
            StatusDerivation.Stats s = StatusDerivation.compute(expected, matched, 0);
            assertEquals(expected, s.found + s.missing);
        }
    }

    @Test
    public void classify_returnsCorrectLabel() {
        assertEquals("FOUND", StatusDerivation.classify(true, false).name());
        assertEquals("MISSING", StatusDerivation.classify(false, false).name());
        assertEquals("EXTRA", StatusDerivation.classify(false, true).name());
    }

    @Test
    public void classify_extraTakesPrecedence() {
        assertFalse(StatusDerivation.classify(true, true).name().equals("MISSING"));
    }
}

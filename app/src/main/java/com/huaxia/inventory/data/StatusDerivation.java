package com.huaxia.inventory.data;

import java.util.Set;

public final class StatusDerivation {

    public enum Status { FOUND, MISSING, EXTRA }

    public static class Stats {
        public final int found;
        public final int missing;
        public final int extra;

        public Stats(int found, int missing, int extra) {
            this.found = found;
            this.missing = missing;
            this.extra = extra;
        }
    }

    private StatusDerivation() {}

    public static Stats compute(int expectedCount, Set<Long> matchedAssetIds, int extrasCount) {
        int found = matchedAssetIds.size();
        int missing = Math.max(0, expectedCount - found);
        return new Stats(found, missing, extrasCount);
    }

    public static Status classify(boolean isMatchedAsset, boolean isExtraScan) {
        if (isExtraScan) return Status.EXTRA;
        if (isMatchedAsset) return Status.FOUND;
        return Status.MISSING;
    }
}

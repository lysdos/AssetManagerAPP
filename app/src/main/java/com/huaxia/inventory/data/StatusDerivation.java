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

    public static Stats compute(int expectedCount, int matchedCount, int extrasCount) {
        int found = matchedCount;
        int missing = Math.max(0, expectedCount - found);
        return new Stats(found, missing, extrasCount);
    }

    /** 旧签名 — 保留二进制兼容，委托到新 int 版本。 */
    public static Stats compute(int expectedCount, Set<Long> matchedAssetIds, int extrasCount) {
        return compute(expectedCount, matchedAssetIds.size(), extrasCount);
    }

    public static Status classify(boolean isMatchedAsset, boolean isExtraScan) {
        if (isExtraScan) return Status.EXTRA;
        if (isMatchedAsset) return Status.FOUND;
        return Status.MISSING;
    }
}

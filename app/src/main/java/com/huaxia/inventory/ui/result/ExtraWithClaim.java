package com.huaxia.inventory.ui.result;

import com.huaxia.inventory.data.entity.ExtraClaim;
import com.huaxia.inventory.data.entity.ScanRecord;

/**
 * 盘盈行 UI 数据：ScanRecord + 可选 ExtraClaim。
 * 列表展示: 始终包含 ScanRecord; 若有 claim 则右侧显示"已补全"标记 + 补全信息。
 */
public class ExtraWithClaim {
    public final ScanRecord scan;
    public final ExtraClaim claim;  // nullable — 未补全

    public ExtraWithClaim(ScanRecord scan, ExtraClaim claim) {
        this.scan = scan;
        this.claim = claim;
    }

    public boolean isClaimed() { return claim != null; }
}
package com.huaxia.inventory.excel;

import androidx.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.huaxia.inventory.data.dao.AssetDao;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.util.EpcNormalizer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * 资产台账导出（资产 tab 的「导出资产」）。
 *
 * <p>列顺序与导入模板一致：序号 / 设备类型 / 设备型号 / 序列号 / 固资编号 /
 * 领用部门 / 领用人 / 盘点日期。业务字段取 Asset 当前值；「盘点日期」取
 * {@code rawRowJson} 中的原始导入值，缺失时留空。</p>
 *
 * <p>导出按 500 条分页读取，配合 {@link XlsxStreamWriter} 流式写盘，不一次性
 * 把全量 Asset 加载到堆里。</p>
 */
public final class AssetLedgerExporter {

    /**
     * 直接复用导入模板的必需列定义，避免导出列与导入模板各自维护后静默漂移。
     * 列顺序：序号 / 设备类型 / 设备型号 / 序列号 / 固资编号 / 领用部门 / 领用人 / 盘点日期。
     */
    public static final List<String> HEADERS = HeaderSpec.REQUIRED;

    public static final int PAGE_SIZE = 500;

    @SuppressWarnings("rawtypes")
    private static final ObjectReader RAW_ROW_READER = new ObjectMapper().readerFor(Map.class);

    private AssetLedgerExporter() {}

    /**
     * @param keywordPattern 由资产 tab 的过滤逻辑生成（空关键词传 {@code "%"}）
     * @param deptBucket     null = 不筛部门；"" = 未填写部门
     * @param typeBucket     null = 不筛类型；"" = 未分类
     * @return 实际写入的数据行数（不含表头）
     */
    public static int export(OutputStream out,
                             AssetDao dao,
                             String keywordPattern,
                             @Nullable String deptBucket,
                             @Nullable String typeBucket,
                             File parentTmpDir) throws IOException {
        int total = dao.countFiltered(keywordPattern, deptBucket, typeBucket);
        int written = 0;

        try (XlsxStreamWriter writer = new XlsxStreamWriter(out, HEADERS, parentTmpDir)) {
            writer.setSheetName("资产台账");
            writer.setColumnWidths(7, 24, 24, 22, 20, 18, 18, 14);
            writer.writeHeaderRow(HEADERS.toArray(new String[0]));

            for (int offset = 0; offset < total; offset += PAGE_SIZE) {
                List<Asset> page = dao.pageFiltered(keywordPattern, deptBucket, typeBucket,
                        PAGE_SIZE, offset);
                if (page.isEmpty()) break; // 并发删除导致页为空，避免死循环
                for (Asset a : page) {
                    writer.writeRow(
                            ++written,
                            safe(a.assetType),
                            safe(a.assetModel),
                            safe(a.serialNumber),
                            displayEpc(a.epc),
                            safe(a.dept),
                            safe(a.custodian),
                            rawDate(a.rawRowJson));
                }
            }
        }
        return written;
    }

    private static String displayEpc(@Nullable String epc) {
        String display = EpcNormalizer.toDisplayForm(epc);
        return display == null ? "" : display;
    }

    private static String rawDate(@Nullable String rawRowJson) {
        if (rawRowJson == null || rawRowJson.isEmpty()) return "";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = RAW_ROW_READER.readValue(rawRowJson);
            Object value = raw.get("盘点日期");
            return value == null ? "" : String.valueOf(value);
        } catch (Exception e) {
            return "";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}

# 固资编号 + EPC 合并为统一编码 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把"固资编号" (16 位十进制) 提升为应用内的唯一标识, 同时承担 RFID 写入、扫描匹配、UI 显示、Excel 导出 4 个角色。

**Architecture:** 复用 `Asset.epc` / `ScanRecord.epc` 字段 (DB schema 不变); 新增 `EpcCodec` 做十进制↔hex 转换; 重写 `EpcNormalizer` 校验 16 位十进制; 修改 `HeaderSpec` / `ExcelImporter` / `ScanFragment` 走新合约; 新增 `RfidController.writeEpc` + `SdkRfidAdapter.writeEpc` 占位; UI 字符串 EPC → 固资编号。

**Tech Stack:** Java 11 / AndroidX / JUnit 4 / Robolectric / Room 2.6 / Apache POI 5.2.5 / rscja DeviceAPI UHF SDK

**关联文档:**
- 设计: `docs/superpowers/specs/2026-06-10-asset-code-merge-design.md`
- 现有设计: `docs/superpowers/specs/2026-06-10-rfid-asset-inventory-design.md`
- 实施参考 (本计划按此 README 启动测试): `README.md`

---

## 文件结构总览

**新建 (2 个):**
- `app/src/main/java/com/huaxia/inventory/util/EpcCodec.java`
- `app/src/test/java/com/huaxia/inventory/util/EpcCodecTest.java`

**修改 (源 7 + 测试 4 + 资源 3 = 14 个):**
- 源: `util/EpcNormalizer.java`, `excel/HeaderSpec.java`, `excel/ExcelImporter.java`, `ui/scan/ScanFragment.java`, `ui/scan/ScanViewModel.java`, `rfid/RfidController.java`, `rfid/SdkRfidAdapter.java`
- 测试: `util/EpcNormalizerTest.java`, `excel/HeaderSpecTest.java`, `excel/ExcelImporterTest.java`, `rfid/RfidControllerTest.java`
- 资源: `res/values/strings.xml`, `res/layout/item_asset_result.xml`, `res/layout/item_recent_scan.xml`

**总测试数: 32 → 48**

---

## Task 1: EpcCodec (十进制 ↔ hex 转换)

**Files:**
- Create: `app/src/main/java/com/huaxia/inventory/util/EpcCodec.java`
- Create: `app/src/test/java/com/huaxia/inventory/util/EpcCodecTest.java`

- [ ] **Step 1: 写失败的 toHex 测试**

在 `EpcCodecTest.java`:

```java
package com.huaxia.inventory.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EpcCodecTest {

    @Test
    public void decimalToHex_basic() {
        assertEquals("0C72C8A220BB2A6A", EpcCodec.toHex("3500309099027210"));
    }

    @Test
    public void decimalToHex_maxValue() {
        assertEquals("DE0B6B3A76400000", EpcCodec.toHex("9999999999999999"));
    }

    @Test
    public void decimalToHex_zero() {
        assertEquals("0000000000000000", EpcCodec.toHex("0000000000000000"));
    }

    @Test
    public void decimalToHex_rejectsNull() {
        assertNull(EpcCodec.toHex(null));
    }

    @Test
    public void decimalToHex_rejectsWrongLength() {
        assertNull(EpcCodec.toHex("12345"));
    }

    @Test
    public void hexToDecimal_basic() {
        assertEquals("3500309099027210", EpcCodec.fromHex("0C72C8A220BB2A6A"));
    }

    @Test
    public void hexToDecimal_maxValue() {
        assertEquals("9999999999999999", EpcCodec.fromHex("DE0B6B3A76400000"));
    }

    @Test
    public void hexToDecimal_zero() {
        assertEquals("0000000000000000", EpcCodec.fromHex("0000000000000000"));
    }

    @Test
    public void hexToDecimal_rejectsNull() {
        assertNull(EpcCodec.fromHex(null));
    }

    @Test
    public void hexToDecimal_rejectsNonHex() {
        assertNull(EpcCodec.fromHex("GGGGGGGGGGGGGGGG"));
    }

    @Test
    public void roundTrip_fiveValues() {
        String[] values = {
                "3500309099027210",
                "0000000000000001",
                "9999999999999999",
                "1234567890123456",
                "0000000000000000"
        };
        for (String d : values) {
            String hex = EpcCodec.toHex(d);
            String back = EpcCodec.fromHex(hex);
            assertEquals("roundTrip failed for " + d, d, back);
        }
    }
}
```

- [ ] **Step 2: 运行测试, 确认编译失败**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.util.EpcCodecTest
```

预期: `error: cannot find symbol class EpcCodec` (类未定义)。

- [ ] **Step 3: 实现 EpcCodec**

创建 `EpcCodec.java`:

```java
package com.huaxia.inventory.util;

import android.util.Log;

public final class EpcCodec {

    private static final String TAG = "EpcCodec";

    private EpcCodec() {}

    /** Convert a 16-digit decimal asset code to a 16-char uppercase hex
     *  string (8-byte big-endian integer). Returns null on invalid input. */
    public static String toHex(String decimal16) {
        if (decimal16 == null || decimal16.length() != 16 || !decimal16.matches("[0-9]+")) {
            Log.w(TAG, "invalid decimal asset code: " + decimal16);
            return null;
        }
        return String.format("%016X", Long.parseUnsignedLong(decimal16, 10));
    }

    /** Convert a 16-char hex string (8 bytes) to a 16-digit decimal asset code
     *  (zero-padded). Returns null on invalid input. */
    public static String fromHex(String hex) {
        if (hex == null || hex.length() != 16 || !hex.matches("[0-9A-Fa-f]+")) {
            Log.w(TAG, "invalid hex: " + hex);
            return null;
        }
        return String.format("%016d", Long.parseUnsignedLong(hex, 16));
    }
}
```

- [ ] **Step 4: 重新运行测试, 确认 11 个全通过**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.util.EpcCodecTest
```

预期: `BUILD SUCCESSFUL`, 11 tests passed。

- [ ] **Step 5: 提交**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
git add app/src/main/java/com/huaxia/inventory/util/EpcCodec.java app/src/test/java/com/huaxia/inventory/util/EpcCodecTest.java
git commit -m "feat(util): EpcCodec 十进制↔hex 转换 (16 位固资编号 <-> 8 字节)

新增 EpcCodec.toHex(decimal16) 和 EpcCodec.fromHex(hex):
- 16 位十进制字符串 -> 16 字符大写 hex
- 16 字符 hex -> 16 位十进制字符串 (0-padded)
- 互为反函数, 11 个单元测试

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: 重写 EpcNormalizer 合约

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/util/EpcNormalizer.java`
- Modify: `app/src/test/java/com/huaxia/inventory/util/EpcNormalizerTest.java`

- [ ] **Step 1: 重写 EpcNormalizerTest.java 为 16 位十进制合约**

完整替换文件:

```java
package com.huaxia.inventory.util;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EpcNormalizerTest {

    @Test
    public void trimsWhitespace() {
        assertEquals("3500309099027210", EpcNormalizer.normalize(" 3500309099027210 "));
    }

    @Test
    public void accepts16Digits() {
        assertEquals("3500309099027210", EpcNormalizer.normalize("3500309099027210"));
    }

    @Test
    public void rejectsNull() {
        assertNull(EpcNormalizer.normalize(null));
    }

    @Test
    public void rejectsEmpty() {
        assertNull(EpcNormalizer.normalize(""));
    }

    @Test
    public void rejectsShort() {
        assertNull(EpcNormalizer.normalize("350030909902721"));
    }

    @Test
    public void rejectsLong() {
        assertNull(EpcNormalizer.normalize("35003090990272100"));
    }

    @Test
    public void rejectsLetters() {
        assertNull(EpcNormalizer.normalize("3500309099027ABC"));
    }

    @Test
    public void rejectsHexChars() {
        // 即使看似 hex 也拒绝, 本合约只认十进制
        assertNull(EpcNormalizer.normalize("FFFFFFFFFFFFFFFF"));
    }

    @Test
    public void rejectsWhitespaceInside() {
        assertNull(EpcNormalizer.normalize("3500 309099027210"));
    }
}
```

- [ ] **Step 2: 替换 EpcNormalizer.java**

完整替换:

```java
package com.huaxia.inventory.util;

import android.util.Log;
import java.util.Locale;

public final class EpcNormalizer {
    private static final String TAG = "EpcNormalizer";

    private EpcNormalizer() {}

    /**
     * Validate and normalize a raw asset-code string into its canonical form:
     * a 16-digit decimal string (the "固资编号" / unified asset code).
     *
     * @param raw input from Excel cell, RFID scan, or user entry
     * @return the trimmed 16-digit decimal string, or {@code null} if invalid
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() != 16) {
            Log.w(TAG, "asset code length != 16: " + s);
            return null;
        }
        if (!s.matches("[0-9]+")) {
            Log.w(TAG, "asset code not 16 decimal digits: " + s);
            return null;
        }
        return s;
    }
}
```

- [ ] **Step 3: 运行测试**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.util.EpcNormalizerTest
```

预期: 9 tests passed。

- [ ] **Step 4: 提交**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
git add app/src/main/java/com/huaxia/inventory/util/EpcNormalizer.java app/src/test/java/com/huaxia/inventory/util/EpcNormalizerTest.java
git commit -m "refactor(util): EpcNormalizer 改为 16 位十进制合约

原合约校验 hex 字符串 (RFID 标签 EPC); 新合约校验 16 位十进制字符串
(固资编号)。9 个单元测试。

影响:
- 调用方需自己处理 hex -> decimal (EpcCodec)
- 不再需要 toUpperCase / length % 4 == 0 / [0-9A-F]+ 规则

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: HeaderSpec 列名替换

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/excel/HeaderSpec.java`
- Modify: `app/src/test/java/com/huaxia/inventory/excel/HeaderSpecTest.java`

- [ ] **Step 1: 修改 HeaderSpec.java**

精确编辑第 9-11 行, 把 `"电子标签"` 替换为 `"固资编号"`:

```java
    public static final List<String> REQUIRED = Arrays.asList(
            "序号", "设备名称", "设备类型", "设备型号", "所属机构",
            "详细地点", "放置地点", "资产状态", "固资编号");
```

精确编辑第 38 行, 把 `"电子标签"` 替换为 `"固资编号"`:

```java
    public int epcColumnIndex() {
        return columnIndex("固资编号");
    }
```

- [ ] **Step 2: 修改 HeaderSpecTest.java**

精确编辑第 14 行, 把 `"电子标签"` 替换为 `"固资编号"`:

```java
    @Test
    public void validatesRequiredHeaders() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "序号", "设备名称", "设备类型", "设备型号", "所属机构",
                "详细地点", "放置地点", "资产状态", "固资编号"));
        assertTrue(spec.isValid());
    }
```

精确编辑第 23 行, 把 `"电子标签"` 替换为 `"固资编号"`:

```java
    @Test
    public void reportsMissingHeaders() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList("序号", "设备名称"));
        assertEquals(7, spec.missingHeaders().size());
        assertTrue(spec.missingHeaders().contains("固资编号"));
    }
```

精确编辑第 28 行, 把 `"电子标签"` 替换为 `"固资编号"`:

```java
    @Test
    public void epcColumnIndex_found() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "序号", "固资编号", "设备名称"));
        assertEquals(1, spec.epcColumnIndex());
    }
```

新增测试 (在文件末尾追加, 在 `}` 闭合类之前):

```java
    @Test
    public void epcColumnIndex_findsAssetCode() {
        // 验证列查找走"固资编号"名称, 即使表头顺序不同也能找到
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "设备名称", "所属机构", "固资编号", "设备型号"));
        assertEquals(2, spec.epcColumnIndex());
    }
```

- [ ] **Step 3: 运行测试**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.HeaderSpecTest
```

预期: 5 tests passed。

- [ ] **Step 4: 提交**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
git add app/src/main/java/com/huaxia/inventory/excel/HeaderSpec.java app/src/test/java/com/huaxia/inventory/excel/HeaderSpecTest.java
git commit -m "refactor(excel): HeaderSpec 列名 电子标签 -> 固资编号

REQUIRED 列表和 epcColumnIndex() 内部查找都改为 '固资编号'。方法名
epcColumnIndex 保留 (内部契约, 调用方只关心返回哪一列)。

5 个 HeaderSpecTest, 含 1 个新测试验证列在不同位置的查找。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: ExcelImporter 读取 col 11 + 校验

**Files:**
- Modify: `app/src/main/java/com/huaxia\inventory\excel\ExcelImporter.java`
- Modify: `app/src/test/java/com/huaxia\inventory\excel\ExcelImporterTest.java`
- Create: `app/src/test/resources/sample-assets-with-codes.xlsx` (可选, 见 Step 4 备选)

- [ ] **Step 1: 修改 ExcelImporter.java 的导入逻辑**

精确编辑第 60-64 行 (EPC 为空判断 + 错误信息):

```java
                    String rawCode = cells.size() > epcIdx ? cells.get(epcIdx) : "";
                    String code = EpcNormalizer.normalize(rawCode);
                    if (code == null) {
                        errors.add("Row " + excelRow + ": invalid 固资编号 '"
                                + (rawCode == null ? "" : rawCode)
                                + "' (需 16 位十进制), skipped");
                        excelRow++;
                        continue;
                    }
```

精确编辑第 68 行:

```java
                    a.epc = code;
```

- [ ] **Step 2: 在文件顶部添加 EpcNormalizer 导入**

精确编辑第 3 行 (`import com.huaxia.inventory.data.entity.Asset;`) 之后新增:

```java
import com.huaxia.inventory.util.EpcNormalizer;
```

- [ ] **Step 3: 跑现有 ExcelImporterTest 确认 imports 不冲突**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.ExcelImporterTest
```

预期: 编译通过, 现有 2 个测试仍然通过 (它们的样本 `sample-assets.xlsx` 应当不包含合法 16 位十进制, 走"invalid" 错误路径)。

- [ ] **Step 4: 新增 ExcelImporterTest 用例 (替换文件末尾, 使用 POI 程序化构造 xlsx)**

完整替换 `ExcelImporterTest.java`:

```java
package com.huaxia.inventory.excel;

import com.huaxia.inventory.data.entity.Asset;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ExcelImporterTest {

    @Test
    public void parsesSampleFile_evenWhenAllEpcsEmpty() throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/sample-assets.xlsx")) {
            ExcelImporter.Result r = ExcelImporter.parse(in, /* taskId */ 1L);
            assertEquals(0, r.success);
            assertTrue(r.errors.size() > 100,
                    "Expected many empty-EPC errors; got " + r.errors.size());
        }
    }

    @Test
    public void rejectsNonExcel() {
        java.io.InputStream bad = new ByteArrayInputStream("not a zip".getBytes());
        assertThrows(Exception.class, () -> ExcelImporter.parse(bad, 1L));
    }

    @Test
    public void parsesValidAssetCodes() throws Exception {
        // 程序化构造一个含 2 行合法固资编号的 xlsx
        String[] headers = {
                "序号", "设备名称", "设备类型", "设备型号", "所属机构",
                "详细地点", "放置地点", "资产状态", "固资编号"
        };
        String[][] rows = {
                {"1", "DPM901", "RFID设备", "DPM901", "运营管理部",
                        "前台", "可放置", "在用", "3500309099027210"},
                {"2", "UPS电池", "电池", "6-FML-100", "信息科技部",
                        "支行", "可放置", "在用", "3500309019310435"}
        };
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(2, r.success);
            assertEquals(0, r.errors.size());
            List<Asset> assets = r.assets;
            assertEquals("3500309099027210", assets.get(0).epc);
            assertEquals("DPM901", assets.get(0).assetName);
            assertEquals("3500309019310435", assets.get(1).epc);
        }
    }

    @Test
    public void rejectsInvalidAssetCodes() throws Exception {
        String[] headers = {
                "序号", "设备名称", "设备类型", "设备型号", "所属机构",
                "详细地点", "放置地点", "资产状态", "固资编号"
        };
        String[][] rows = {
                {"1", "X", "X", "X", "X", "X", "X", "X", "3500309099027210"},
                {"2", "X", "X", "X", "X", "X", "X", "X", "350030909902721"},   // 15 位
                {"3", "X", "X", "X", "X", "X", "X", "X", "3500309099027210A"},  // 17 位
                {"4", "X", "X", "X", "X", "X", "X", "X", "3500309099ABC210"},  // 含字母
                {"5", "X", "X", "X", "X", "X", "X", "X", ""}                    // 空
        };
        try (java.io.InputStream in = buildXlsx(headers, rows)) {
            ExcelImporter.Result r = ExcelImporter.parse(in, 1L);
            assertEquals(1, r.success);
            assertEquals(4, r.errors.size());
        }
    }

    private static java.io.InputStream buildXlsx(String[] headers, String[][] dataRows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet();
            // 标题行 (会被解析器跳过)
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("固定资产 - 设备台账");
            // meta 行 (会被跳过)
            Row meta = sheet.createRow(1);
            meta.createCell(0).setCellValue("制表人");
            meta.createCell(3).setCellValue("2026-06-10");
            // 表头行
            Row headerRow = sheet.createRow(2);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            // 数据行
            for (int r = 0; r < dataRows.length; r++) {
                Row row = sheet.createRow(3 + r);
                for (int c = 0; c < dataRows[r].length; c++) {
                    row.createCell(c).setCellValue(dataRows[r][c]);
                }
            }
            wb.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }
}
```

- [ ] **Step 5: 跑测试**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.excel.ExcelImporterTest
```

预期: 3 tests passed (2 现有 + 1 新)。

- [ ] **Step 6: 提交**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
git add app/src/main/java/com/huaxia/inventory/excel/ExcelImporter.java app/src/test/java/com/huaxia/inventory/excel/ExcelImporterTest.java
git commit -m "refactor(excel): ExcelImporter 读 col 11 固资编号 + normalize 校验

- 改读 col 11 固资编号 (取代 col 12 电子标签)
- 走 EpcNormalizer.normalize 校验, 失败记录 error 并跳过
- 错误信息用 '固资编号' 描述

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: ScanFragment 回调做 hex→decimal 转换

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java`
- Modify: `app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java`

- [ ] **Step 1: 修改 ScanFragment.rfidCallback**

先在文件顶部 import 块 (第 14-18 行) 末尾追加:

```java
import com.huaxia.inventory.util.EpcCodec;
import com.huaxia.inventory.util.EpcNormalizer;
```

然后精确编辑第 29-30 行:

```java
    private final SdkRfidAdapter.TagListener rfidCallback = (epcHex, tid, rssi) -> {
        // SDK 返回的是 16 字符 hex (8 字节整数) — 转换为 16 位十进制
        // 固资编号, 再交给 ViewModel 做匹配
        String decimal = EpcCodec.fromHex(epcHex);
        if (decimal == null) return;
        String normalized = EpcNormalizer.normalize(decimal);
        if (normalized == null) return;
        vm.onEpcScanned(normalized, tid, rssi);
    };
```

- [ ] **Step 2: 修改 ScanViewModel.onEpcScanned 的 JavaDoc**

精确编辑第 60 行方法签名, 在其上方添加 JavaDoc:

```java
    /**
     * 处理一个扫描到的标签, 用归一化后的 16 位十进制固资编号匹配资产。
     *
     * @param rawCode 已归一化的 16 位十进制字符串 (由 ScanFragment 从 SDK hex 转换得到)
     * @param tid     SDK 报告的 TID, 透传存入 ScanRecord
     * @param rssi    SDK 报告的 RSSI, 透传存入 ScanRecord
     */
    public void onEpcScanned(String rawCode, String tid, String rssi) {
        String epc = EpcNormalizer.normalize(rawCode);
        if (epc == null) return;
        if (scannedEpcSet.contains(epc)) {
```

- [ ] **Step 3: 编译确认**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:compileDebugJavaWithJavac
```

预期: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
git add app/src/main/java/com/huaxia/inventory/ui/scan/ScanFragment.java app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java
git commit -m "refactor(scan): 扫描回调 hex -> 十进制固资编号

ScanFragment.rfidCallback 现在先 EpcCodec.fromHex 再 EpcNormalizer,
把 SDK 返回的 8 字节 hex 转换为 16 位十进制固资编号再交给 ViewModel。
ViewModel 入参语义更新 (JavaDoc)。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: RfidController.writeEpc + SdkRfidAdapter.writeEpc 占位

**Files:**
- Modify: `app/src/main/java/com/huaxia/inventory/rfid/RfidController.java`
- Modify: `app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java`
- Modify: `app/src/test/java/com/huaxia/inventory/rfid/RfidControllerTest.java`

- [ ] **Step 1: 在 SdkRfidAdapter.java 末尾添加 writeEpc 占位**

精确编辑 (在 `release()` 方法之后, `private void pollLoop()` 之前) — 在第 205 行 `}` 之后新增:

```java
    /**
     * Write a new EPC to the next tag observed by the reader.
     *
     * <p>Returns {@code 0} on success, non-zero on failure. The input must be
     * exactly 16 hex characters (8 bytes) representing the asset code in
     * big-endian integer form.</p>
     *
     * <p><b>TODO:</b> real integration with the rscja DeviceAPI is pending.
     * See {@code uhf-uart-demo/uhf-uart-demo/app/src/main/} for the SDK
     * reference (e.g. {@code setEpc} / {@code writeEpc}). This placeholder
     * throws so callers (and tests) can detect the unimplemented path.</p>
     *
     * @param hex16 16-character uppercase hex string (e.g. "0C72C8A220BB2A6A")
     * @return {@code 0} on success, non-zero on failure
     */
    public int writeEpc(String hex16) {
        if (hex16 == null || hex16.length() != 16) return -1;
        if (!hex16.matches("[0-9A-Fa-f]+")) return -1;
        // TODO: integrate with rscja DeviceAPI
        throw new UnsupportedOperationException(
                "SdkRfidAdapter.writeEpc not yet implemented; see uhf-uart-demo");
    }
```

- [ ] **Step 2: 在 RfidController.java 添加 writeEpc 方法**

先在文件顶部 (现有 `package` 与 `import` 之间) 确认/添加 import:

```java
import com.huaxia.inventory.util.EpcCodec;
```

然后在 `stopInventory()` 方法之后, `powerOff()` 之前 — 第 104 行 `}` 之后新增:

```java
    /**
     * Write a unified asset code (16-digit decimal) to a tag's EPC memory.
     *
     * <p>No-op unless the controller is in {@link State#READY} or
     * {@link State#SCANNING}.</p>
     *
     * @param decimal16 16-digit decimal string (e.g. "3500309099027210")
     * @return {@code true} on success, {@code false} on any failure
     *         (illegal state, codec failure, or SDK non-zero return)
     */
    public boolean writeEpc(String decimal16) {
        if (state != State.READY && state != State.SCANNING) return false;
        String hex = EpcCodec.toHex(decimal16);
        if (hex == null) return false;
        try {
            int rc = adapter.writeEpc(hex);
            if (rc != 0) {
                android.util.Log.w("RfidController", "writeEpc failed rc=" + rc);
                return false;
            }
            return true;
        } catch (UnsupportedOperationException uoe) {
            // SDK 集成未完成 — 当前是占位
            android.util.Log.w("RfidController", "writeEpc not implemented");
            return false;
        }
    }
```

- [ ] **Step 3: 在 RfidControllerTest.java 末尾追加 3 个新测试**

```java
    @Test
    public void writeEpc_delegatesHexToAdapter() {
        // 把控制器推到 READY
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback c) {
                c.onStatus(true);
            }
        };
        when(realAdapter.writeEpc(any(String.class))).thenReturn(0);
        RfidController c = new RfidController(realAdapter);
        c.init(null, null);

        // 3500309099027210 -> 0C72C8A220BB2A6A
        boolean ok = c.writeEpc("3500309099027210");

        assertTrue(ok);
        verify(realAdapter, times(1)).writeEpc("0C72C8A220BB2A6A");
    }

    @Test
    public void writeEpc_returnsFalseWhenNotReady() {
        // setup() 创建的 controller 处于 IDLE
        boolean ok = controller.writeEpc("3500309099027210");

        assertFalse(ok);
        verify(adapter, never()).writeEpc(any(String.class));
    }

    @Test
    public void writeEpc_returnsFalseOnCodecFailure() {
        SdkRfidAdapter realAdapter = new SdkRfidAdapter() {
            @Override
            public void init(Context ignored, Callback c) {
                c.onStatus(true);
            }
        };
        RfidController c = new RfidController(realAdapter);
        c.init(null, null);

        // 非 16 位数字 — codec 失败
        boolean ok = c.writeEpc("123");

        assertFalse(ok);
        verify(realAdapter, never()).writeEpc(any(String.class));
    }
```

- [ ] **Step 4: 跑 RfidControllerTest**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:testDebugUnitTest --tests com.huaxia.inventory.rfid.RfidControllerTest
```

预期: 8 tests passed (5 现有 + 3 新)。

- [ ] **Step 5: 提交**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
git add app/src/main/java/com/huaxia/inventory/rfid/RfidController.java app/src/main/java/com/huaxia/inventory/rfid/SdkRfidAdapter.java app/src/test/java/com/huaxia/inventory/rfid/RfidControllerTest.java
git commit -m "feat(rfid): RfidController.writeEpc(decimal16) + SDK 占位

新增 RfidController.writeEpc(String decimal16):
- 状态守卫: 仅 READY/SCANNING 可写
- 经 EpcCodec.toHex 转换为 16 hex
- 调 SdkRfidAdapter.writeEpc(hex)
- SDK 返回非 0 或抛 UnsupportedOperationException 都视为失败

SdkRfidAdapter.writeEpc 是占位, 抛 UnsupportedOperationException,
真实集成需参考 uhf-uart-demo 的 setEpc/writeEpc 调用。

3 个 RfidControllerTest 用例覆盖正常 / 状态不允许 / codec 失败。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: strings.xml + 布局 label

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/item_asset_result.xml`
- Modify: `app/src/main/res/layout/item_recent_scan.xml`

- [ ] **Step 1: 修改 strings.xml**

精确编辑第 35 行:

```xml
    <string name="result_search_hint">搜索设备名/固资编号/位置</string>
```

在 `</resources>` 闭合之前 (第 39 行 `</resources>` 之上) 追加:

```xml
    <string name="label_asset_code">固资编号</string>
```

- [ ] **Step 2: 修改 item_asset_result.xml — 把 epcTv 上下加 label**

精确编辑 — 在 `epcTv` 之前新增一行 label TextView (在第 30-31 行之间):

```xml
        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="11sp"
            android:textColor="#888888"
            android:text="@string/label_asset_code" />

        <TextView android:id="@+id/epcTv"
```

- [ ] **Step 3: 修改 item_recent_scan.xml — 在 epcTv 上方加 label**

精确编辑 — 在 `epcTv` 之前新增 label (在第 12-13 行之间, 但因为这是 horizontal LinearLayout, label 放进 epcTv 上方的 vertical 子 LinearLayout; 用更简单的办法: 在 epcTv 的同级前插入 label TextView 用 weight=4):

将整个文件替换为:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="8dp">

    <TextView android:id="@+id/timeTv"
        android:layout_width="0dp" android:layout_weight="2"
        android:layout_height="wrap_content" android:textSize="12sp" />

    <LinearLayout
        android:layout_width="0dp" android:layout_weight="4"
        android:layout_height="wrap_content"
        android:orientation="vertical">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="10sp"
            android:textColor="#888888"
            android:text="@string/label_asset_code" />

        <TextView android:id="@+id/epcTv"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="monospace" />
    </LinearLayout>

    <TextView android:id="@+id/rssiTv"
        android:layout_width="0dp" android:layout_weight="1"
        android:layout_height="wrap_content" android:textSize="12sp" />
</LinearLayout>
```

- [ ] **Step 4: 编译资源 + Java 确认**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:assembleDebug
```

预期: `BUILD SUCCESSFUL` (或在没有 Gradle wrapper 时提示, 至少 `:app:processDebugResources` 成功)。

- [ ] **Step 5: 提交**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
git add app/src/main/res/values/strings.xml app/src/main/res/layout/item_asset_result.xml app/src/main/res/layout/item_recent_scan.xml
git commit -m "refactor(ui): 列表项 label EPC -> 固资编号

- strings.xml: result_search_hint 文案更新; 新增 label_asset_code
- item_asset_result.xml: epcTv 上方加 label
- item_recent_scan.xml: epcTv 改用 vertical 子 LinearLayout 容纳 label

视图 ID (epcTv) 不变, Java 代码无需修改。

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: 全套测试 + 验证

**Files:** 全部源码 + 测试 (本计划所有变更)

- [ ] **Step 1: 跑全部单元测试**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:testDebugUnitTest
```

预期: 48 tests passed (32 原 + 16 新)。
若 Gradle wrapper 不可用, 至少验证编译:

```bash
cd E:\Code\huaxiaapp\huaxiaapp
gradle wrapper --gradle-version 8.2   # 一次性
./gradlew :app:testDebugUnitTest
```

- [ ] **Step 2: 验证数据库 schema 不需要迁移**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
git diff app/src/main/java/com/huaxia/inventory/data/
```

预期: **无 diff** (本计划未触碰 data/ 下的 entity/DAO)。这是设计的关键属性: schema 不变。

- [ ] **Step 3: 跑 lint 确认资源引用无悬空**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:lintDebug
```

预期: 无与本计划相关的警告 (`label_asset_code` 至少在两个布局中引用; `result_search_hint` 在 ResultViewModel/ResultFragment 引用)。

- [ ] **Step 4: 全量构建 APK**

```bash
cd E:\Code\huaxiaapp\huaxiaapp
./gradlew :app:assembleDebug
```

预期: `BUILD SUCCESSFUL`, 生成 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 5: 真机冒烟测试 (UROVO PDA)**

参考 `docs/smoke-test-2026-06-10.md` 流程, 重点验证:

1. 启动 App, 设备就绪
2. 导入一份含 col 11 "固资编号" 的测试 Excel, 验证 35 列预览正常, 无错误
3. 启动扫描, 用已知 16 位固资编号的标签靠近, 验证"已扫"计数 +1, "最近扫描"显示该 16 位十进制
4. (后续) 用一个空白标签, 触发 RfidController.writeEpc 写入一个 16 位十进制, 重新扫描应能命中同一资产 (需要等 SDK 集成完成)

- [ ] **Step 6: 提交验证记录 (可选)**

如做了真机冒烟, 在 `docs/smoke-test-2026-06-10.md` 追加一节"asset-code-merge verification"; 否则跳过本步。

---

## 实施顺序与依赖

```
Task 1 (EpcCodec + tests) ──┐
                            ├──> Task 5 (ScanFragment 回调)
Task 2 (EpcNormalizer) ─────┤
                            ├──> Task 6 (RfidController.writeEpc)
Task 3 (HeaderSpec) ────────┤
                            ├──> Task 4 (ExcelImporter)
                            │
                            └──> Task 7 (UI 资源)
                                 │
                                 └──> Task 8 (全量验证)
```

- Task 1, 2, 3 互不依赖, 可并行
- Task 4 依赖 Task 1 + 2 + 3
- Task 5 依赖 Task 1 + 2
- Task 6 依赖 Task 1
- Task 7 独立, 但视觉上应在所有代码改完后
- Task 8 验证全部

## 退出准则 (Definition of Done)

- [ ] Task 1-7 全部提交, 每步有独立 commit
- [ ] `./gradlew :app:testDebugUnitTest` 全绿 (48/48)
- [ ] `./gradlew :app:assembleDebug` 成功
- [ ] `git diff app/src/main/java/com/huaxia/inventory/data/` 为空 (无 schema 变更)
- [ ] 真机冒烟: 导入含 col 11 固资编号的 Excel → 扫描 16 位十进制标签 → 命中资产

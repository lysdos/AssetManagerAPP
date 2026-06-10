# 固资编号 + RFID EPC 合并为统一编码 — 设计

**日期:** 2026-06-10
**作者:** Claude (brainstorming + design)
**状态:** 待用户审阅
**关联设计:** [2026-06-10-rfid-asset-inventory-design.md](./2026-06-10-rfid-asset-inventory-design.md)

## 1. 背景与目标

应用当前把"电子标签 (EPC)"作为唯一标识:
- 从 Excel 第 12 列读取 EPC
- `Asset.epc` / `ScanRecord.epc` 字段存 hex 字符串
- 扫描匹配按 EPC 字符串

客户实际业务要求**用"固资编号"作为唯一标识**——它就是 16 位十进制字符串
(例如 `3500309099027210`), 来自客户资产管理系统。本设计把固资编号提升为
应用内的统一编码, 同时承担 4 个角色:

1. **RFID 标签物理写入** 内容
2. **扫描匹配键** (取代原 EPC)
3. **UI 显示字段** (列表、搜索)
4. **Excel 导出关联列**

## 2. 设计决策

| 维度 | 选择 |
|---|---|
| 统一编码的形态 | 16 位十进制字符串 |
| 数据库字段 | 复用 `Asset.epc` / `ScanRecord.epc` (字段名不变) |
| Excel 来源列 | col 11 "固资编号" (取代原 col 12 "电子标签") |
| RFID 标签存储 | 8 字节整数 (16 hex) |
| hex ↔ decimal 转换 | 新增 `EpcCodec` 类 |
| 校验类 | 改 `EpcNormalizer` 合约 |
| RFID 写入能力 | 新增 `RfidController.writeEpc(String)` + SDK 占位实现 |
| UI 标签 | "EPC" → "固资编号" |
| DB schema 迁移 | **不需要** (字段名不变, 语义变) |

## 3. 架构

### 3.1 数据流

**写入路径 (Excel → DB → 物理标签):**
```
Excel col 11 "3500309099027210"
  → ExcelImporter.parse
  → EpcNormalizer.normalize = "3500309099027210"
  → Asset.epc = "3500309099027210"   (DB)
  → (按需) RfidController.writeEpc("3500309099027210")
  → EpcCodec.toHex = "0C72C8A220BB2A6A"
  → SdkRfidAdapter.writeEpc(hex) → 物理标签 EPC 区 (8 字节)
```

**读取路径 (物理标签 → DB):**
```
物理标签 8 字节
  → SDK 回调 (hex 字符串 "0C72C8A220BB2A6A")
  → ScanFragment.rfidCallback
  → EpcCodec.fromHex = "3500309099027210"
  → EpcNormalizer.normalize (防御性校验)
  → ScanViewModel.onEpcScanned("3500309099027210", tid, rssi)
  → AssetDao.findByEpc(taskId, "3500309099027210") → 命中
  → ScanRecord 入库
```

### 3.2 组件

```
util/
  EpcNormalizer     [改]  严格 16 位十进制校验
  EpcCodec          [新]  16 位十进制 ↔ 16 hex
rfid/
  RfidController    [改]  新增 writeEpc(String decimal16)
  SdkRfidAdapter    [改]  新增 writeEpc(String hex) 占位
excel/
  HeaderSpec        [改]  REQUIRED "电子标签" → "固资编号"
  ExcelImporter     [改]  读取 col 11, 走新 normalizer
ui/
  scan/ScanFragment [改]  rfidCallback 内做 hex→decimal 转换
  scan/ScanViewModel[改]  onEpcScanned 入参语义更新
  (其他)            [改]  strings.xml + 布局 label
```

## 4. 详细合约

### 4.1 `EpcNormalizer` (修改)

```java
public static String normalize(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.length() != 16) return null;
    if (!s.matches("[0-9]+")) return null;
    return s;
}
```

- **唯一合法形态:** 16 位十进制字符串
- 返回 `null` 即视为非法, 不得入库
- 移除了原 hex 校验与 `toUpperCase` (十进制无大小写)

### 4.2 `EpcCodec` (新)

```java
public static String toHex(String decimal16) {
    if (decimal16 == null || decimal16.length() != 16) return null;
    if (!decimal16.matches("[0-9]+")) return null;
    return String.format("%016X", Long.parseUnsignedLong(decimal16, 10));
}

public static String fromHex(String hex) {
    if (hex == null || hex.length() != 16) return null;
    if (!hex.matches("[0-9A-Fa-f]+")) return null;
    return String.format("%016d", Long.parseUnsignedLong(hex, 16));
}
```

- **职责单一:** 只做进制转换, 不做校验
- **互为反函数:** `fromHex(toHex(d)) == d` (对所有合法 `d`)
- **无溢出:** 16 位十进制最大 9.99e15, unsigned long 上限 1.8e19
- **大小写:** `fromHex` 接受大小写; `toHex` 始终返回大写

### 4.3 `HeaderSpec`

```java
public static final List<String> REQUIRED = Arrays.asList(
        "序号", "设备名称", "设备类型", "设备型号", "所属机构",
        "详细地点", "放置地点", "资产状态", "固资编号");  // 改

public int epcColumnIndex() {
    return columnIndex("固资编号");  // 改
}
```

方法名 `epcColumnIndex` 保留 (内部契约, 调用方只关心"返回哪一列")。

### 4.4 `ExcelImporter.parse`

- 读取 `cells.get(spec.epcColumnIndex())` 拿到 col 11 的固资编号
- 调 `EpcNormalizer.normalize(value)` 校验
  - 失败时记录 `errors.add("Row N: invalid 固资编号 'xxx' (需要 16 位十进制), skipped")`, 跳过该行
- 成功时 `a.epc = normalizedDecimal16`
- 其他 35 列字段、`rawRowJson` 不变

### 4.5 `ScanFragment.rfidCallback`

```java
private final SdkRfidAdapter.TagListener rfidCallback = (epcHex, tid, rssi) -> {
    String decimal = EpcCodec.fromHex(epcHex);
    if (decimal == null) return;
    String normalized = EpcNormalizer.normalize(decimal);
    if (normalized == null) return;
    vm.onEpcScanned(normalized, tid, rssi);
};
```

### 4.6 `ScanViewModel.onEpcScanned`

入参从"raw EPC 字符串"变为"已归一化的 16 位十进制字符串"。JavaDoc 注释更新
(入参语义), 内部逻辑 (查表、insert ScanRecord、recompute stats) 不变。

### 4.7 `RfidController.writeEpc` (新)

```java
public boolean writeEpc(String decimal16) {
    if (state != State.READY && state != State.SCANNING) return false;
    String hex = EpcCodec.toHex(decimal16);
    if (hex == null) return false;
    int rc = adapter.writeEpc(hex);
    return rc == 0;
}
```

### 4.8 `SdkRfidAdapter.writeEpc` (新, 占位)

```java
public int writeEpc(String hex) {
    // TODO: integrate with rscja DeviceAPI (uhf-uart-demo reference)
    // 真实实现需要调用 rscja SDK 的写 EPC 命令 (例如 setEpc / writeEpc)
    throw new UnsupportedOperationException(
        "writeEpc not yet implemented; see uhf-uart-demo for SDK reference");
}
```

占位实现允许单测跑通, 真实集成留待 SDK 阶段。

### 4.9 UI 文案

`strings.xml`:
```xml
<string name="result_search_hint">搜索设备名/固资编号/位置</string>
<string name="label_asset_code">固资编号</string>
<string name="label_scan_recent_epc">固资编号</string>
```

`item_asset_result.xml` / `item_recent_scan.xml`: `epcTv` 视图 ID 不变,
上方/左侧 label 文字由新字符串提供。

## 5. 错误处理矩阵

| 场景 | 触发点 | 处理 |
|---|---|---|
| Excel 缺"固资编号"列 | `HeaderSpec.isValid()` false | 整文件抛 `IllegalStateException` |
| Excel 某行固资编号为空 | `normalize` 返回 null | 加入 `errors`, 跳过该行 |
| Excel 某行非 16 位 / 含字母 | `normalize` 返回 null | 加入 `errors`, 跳过该行 |
| SDK 回调 hex 长度非 16 | `fromHex` 返回 null | 静默丢弃, `Log.w` |
| SDK 回调 hex 含非法字符 | `fromHex` 返回 null | 静默丢弃, `Log.w` |
| `writeEpc` 状态非就绪 | 返回 false | UI Toast "写入失败" |
| `writeEpc` SDK 返回非 0 | 返回 false | UI Toast "写入失败" |
| 重复扫描 | `scannedEpcSet` 命中 | 现状: 入 unmatched record |

## 6. 日志规范

- `EpcNormalizer` 失败: `Log.w("EpcNormalizer", "invalid code: " + raw)`
- `EpcCodec` 失败: `Log.w("EpcCodec", "hex parse failed: " + hex)`
- `RfidController.writeEpc` 失败: `Log.w("RfidController", "write failed rc=" + rc)`

## 7. 测试策略

### 7.1 覆盖矩阵

| 测试类 | 变更 |
|---|---|
| `EpcNormalizerTest` | **重写**, 7 → 8 测试 |
| `EpcCodecTest` | **新增**, 10 测试 |
| `HeaderSpecTest` | **更新**, 4 → 5 测试 (列名替换 + 1 新增) |
| `ExcelImporterTest` | **新增 1 个** 用合法 16 位十进制的样本 |
| `RfidControllerTest` | **新增 3 个** `writeEpc_*` 用例 (mock adapter) |
| `ExcelExporterTest` | 不变 (6) |
| `AssetDaoTest` | 不变 (3) |
| `ScanRecordDaoTest` | 不变 (3) |
| `StatusDerivationTest` | 不变 (6) |

合计: 32 → 48 个测试 (基线按 README 现有 7+6+2+2+4+3+3+5=32 计)。

### 7.2 `EpcNormalizerTest` 合约

- `trimsWhitespace` — `" 3500309099027210 "` → `"3500309099027210"`
- `accepts16Digits` — 原样返回
- `rejectsNull` / `rejectsEmpty`
- `rejectsShort` (15 位) / `rejectsLong` (17 位)
- `rejectsLetters` (含 ABCDEF)
- `rejectsHexChars` (含 FFF) — 即使看似 hex 也不接受
- `rejectsWhitespaceInside` — `"3500 309099027210"` → null

### 7.3 `EpcCodecTest` 合约

toHex 5 个: basic / maxValue / zero / rejectsNull / rejectsWrongLength
fromHex 5 个: basic / maxValue / zero / rejectsNull / rejectsNonHex
往返 1 个: roundTrip (5 个不同值)

### 7.4 集成验证 (留待设备)

- 写入一个标签 → 扫描 → 命中 → 验证 DB ScanRecord.epc 与 Excel 一致
- 走 `docs/smoke-test-2026-06-10.md` 流程

## 8. 迁移与回退

### 8.1 不需要 DB 迁移

- 字段名不变 (`epc`)
- 现有数据是 hex 字符串; 升级后, 旧 hex 数据**不满足** 16 位十进制校验
  - 后果: 这些旧资产无法被扫描命中
  - 缓解: 旧任务 (`InventoryTask`) 在升级前已结束, 不参与新盘点
  - 回退: 保留老版本 APK, 新版本只用于新盘点任务

### 8.2 Excel 模板

- 新模板必须含 "固资编号" 列 (替换原 "电子标签")
- 缺失时导入直接失败, 用户得到清晰的错误信息

### 8.3 RFID 标签

- 旧标签内容为原 EPC hex, 升级后扫描会进入"非本系统标签"路径, 静默丢弃
- 新标签在写入时按 16 位十进制的 8 字节整数编码

## 9. 实施拆分 (概要, 详细 plan 留到 writing-plans 阶段)

1. 写 `EpcCodec` + 测试
2. 改 `EpcNormalizer` + 测试
3. 改 `HeaderSpec` + 测试
4. 改 `ExcelImporter` + 新测试样本
5. 改 `ScanFragment` / `ScanViewModel`
6. 加 `RfidController.writeEpc` + `SdkRfidAdapter.writeEpc` 占位 + 测试
7. 改 `strings.xml` + 布局
8. 全套测试 + 冒烟测试 (设备上)

## 10. 限制与未来工作

- **占位 SDK:** `SdkRfidAdapter.writeEpc` 是占位, 真实集成需参考
  `uhf-uart-demo/uhf-uart-demo/app/src/main/` 下 demo 源码
- **写入 UI 入口:** 本设计只做接口, 真实 UI 入口 (按钮、流程) 留待下一轮
- **历史任务回看:** 1.0 不实现 (沿用 OQ5 决策), 旧 hex 数据不会出现在新 UI
- **错误重试:** 写入失败当前只 Toast, 不重试; 未来可加指数退避

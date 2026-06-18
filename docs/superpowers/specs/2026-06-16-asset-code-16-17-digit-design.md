# 固资编号统一为 24 位十进制 canonical — 设计

- **日期**: 2026-06-16
- **作者**: brainstorm 输出
- **状态**: 已实现 (2026-06-17), 2026-06-17 简化：删除 hex 转换层
- **范围**: `EpcNormalizer` / `ExcelImporter` 错误文案 / `ScanFragment` 读卡

---

## 1. 背景

当前项目 `EpcNormalizer` 严格要求"固资编号"为 16 位或 17 位十进制字符串：

```java
// EpcNormalizer.normalize (最早)
if (len != 16 && len != 17) return null;
```

实际客户数据中，"固资编号"列**存在三种形式**：

- 16 位十进制（原始，如 `3500309099027210`）—— `固定资产测试.xlsx`
- 17 位十进制（原始，如 `35003090110722819`）—— `固定资产测试.xlsx`
- 24 位十进制（已高位补 0，如 `000000003500309099027210`）—— `固定资产测试2.xlsx`

旧的 16/17 位归一化无法处理 24 位输入，导致该批次导入 `success=0 error=20`。

**简化决策（2026-06-17）**：经与客户确认，**RFID 标签上写入的就是 24 位十进制字符串本身**（不是 hex 编码），且**项目不实现写卡功能**。所以**不需要任何十进制/十六进制转换**：导入和扫描读取两条链路都只走 `EpcNormalizer.normalize`。

`EpcCodec`、`RfidController.writeEpc`、`SdkRfidAdapter.writeEpc` 整套写卡 + hex 转换的代码全部删除。

---

## 2. 编码规则

只剩一个：`EpcNormalizer.normalize`。

```
输入: raw (任意字符串)
输出: 24 位十进制字符串, 或 null (非法)

normalize(raw):
  if raw == null: return null
  s = raw.trim()
  if s.isEmpty(): return null
  if !s.matches("[0-9]+"): return null
  switch s.length():
    case 16: return "00000000" + s   // 补 8 个 0
    case 17: return "0000000" + s    // 补 7 个 0
    case 24: return s                // 原样
    default: return null
```

---

## 3. 改动清单

### 3.1 2026-06-17 第一轮（24 位 canonical 化）

| 文件 | 改动 |
|---|---|
| `util/EpcNormalizer.java` | `normalize()` 改为三分支：16 → 补 8 个 0、17 → 补 7 个 0、24 → 原样；其他长度返回 null |
| `excel/ExcelImporter.java` | 错误文案 `"需 16 或 17 位十进制"` → `"需 16、17 或 24 位十进制"` |
| 所有相关测试 | 期望值从 16/17 位改为 24 位 |

### 3.2 2026-06-17 第二轮（删 hex 层）

| 文件 | 改动 |
|---|---|
| `util/EpcCodec.java` | **删除整个类** |
| `util/EpcCodecTest.java` | **删除整个文件** |
| `rfid/RfidController.java` | **删除 `writeEpc` 方法 + import EpcCodec/EpcNormalizer** |
| `rfid/SdkRfidAdapter.java` | **删除 `writeEpc` 方法 + 顶部注释中的 writeEpc 提及** |
| `rfid/RfidControllerTest.java` | 删除 `writeEpc_delegatesHexToAdapter` / `writeEpc_returnsFalseWhenNotReady` / `writeEpc_returnsFalseOnCodecFailure` 三个测试 + 未用的 import |
| `ui/scan/ScanFragment.java` | 删 `EpcCodec.fromHex` 调用 + import，`rfidCallback` 直接对 SDK 返回的字符串调 `EpcNormalizer.normalize` |

**DB schema 不动**（`Asset.epc` 仍是 `String`，无长度约束，无需迁移）。
**UI / ViewModel / DAO 不动**。

---

## 4. 数据流

```
[Excel 导入]
  Excel 单元格 (16/17/24 位文本)
  → POI 读取
  → EpcNormalizer.normalize (16/17 → 高位补 0, 24 → 原样)
  → Asset.epc (24 位, 存 DB)
  ✅ DB 统一存 24 位规范形态

[RFID 扫描]
  SDK readTagFromBuffer → UHFTAGInfo.getEPC() (假定卡上直接存 16/17/24 位十进制串)
  → EpcNormalizer.normalize → 24 位
  → ScanViewModel.onEpcScanned
  → AssetDao.findByEpc(taskId, decimal24)
  ✅ 匹配 DB 里的 24 位码
```

---

## 5. 已知假设

1. **客户提供数据无前导零**（16/17 位值都是有效数字；24 位值已是补 0 后的规范形态）
2. **DB 无旧 16/17 位数据**（用户已确认）—— 不需要数据迁移
3. **RFID 卡上写入的是 24 位（或 16/17 位）十进制串本身**（用户 2026-06-17 确认）
4. **项目不实现写卡功能**（用户 2026-06-17 确认）

---

## 6. 非目标

- ❌ 不实现 RFID 标签写入（用户明确不做）
- ❌ 不改 DB schema（String 字段无长度约束）
- ❌ 不做任何十六进制转换（用户明确"不需要进制转换"）

package com.huaxia.inventory.ui.result;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 复现 + 回归测试: 弹窗保存时回写 rawRowJson 不能丢 key.
 * 现象: 用户报告"更新领用部门/领用人后导出列数从 30+ 掉到 4",
 * 根因怀疑是 parseHeaders 对含 null 值的 JSON 用 TypeReference<Map<String, String>>
 * 解析时崩溃, 返回空 map, 然后 2 个 put 后只 2 个 key, 写回后导出列数 = 2 + 2 = 4.
 */
public class AssetDetailDialogJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 正向回归: TypeReference<Map<String, String>> 在遇到 null 值时不丢 key.
     * 之前怀疑这是 30→4 bug 根因, 但本测试证伪 — Jackson 把 null 当成合法 String 值保留.
     * 真实根因在别处 (见 writeBackPreservesAllKeys + ExcelImporter 实际写出的 rawRowJson 列数).
     */
    @Test
    public void typedReadPreservesAllKeys() throws Exception {
        // 模拟 ExcelImporter 写出来的 rawRowJson (含 null 值, 共 26 列)
        String rawRowJson = "{\"序号\":\"1\",\"所属机构\":\"3500\",\"合同号\":\"x\",\"合同名称\":\"y\","
                + "\"设备名称\":\"a\",\"设备大类\":\"b\",\"设备小类\":\"c\",\"设备类型\":\"d\","
                + "\"设备型号\":\"e\",\"归口部门\":\"f\",\"序列号\":\"g\",\"固资编号\":\"h\","
                + "\"电子标签\":\"\",\"资产状态\":\"在用\",\"机房\":\"\",\"机柜\":\"\",\"U位\":\"\","
                + "\"供应商\":\"s1\",\"生产商\":\"s2\",\"维护商\":\"s3\",\"详细地点\":\"loc\","
                + "\"放置地点\":\"\",\"领用部门\":\"松柏支行\",\"领用人\":\"黄薇\",\"用途\":\"use\","
                + "\"盘点日期\":null}";

        Map<String, String> headers = MAPPER.readValue(
                rawRowJson, new TypeReference<Map<String, String>>() {});

        System.out.println("[typed] headers.size = " + headers.size());
        assertNotNull("headers must not be null", headers);
        assertEquals("TypeReference parse must preserve all 26 keys (incl. null value)",
                26, headers.size());
    }

    @Test
    public void rawMapReadPreservesAllKeys() throws Exception {
        String rawRowJson = "{\"序号\":\"1\",\"所属机构\":\"3500\",\"领用部门\":\"松柏支行\","
                + "\"领用人\":\"黄薇\",\"盘点日期\":null}";

        @SuppressWarnings("unchecked")
        Map<String, Object> headers = MAPPER.readValue(rawRowJson, Map.class);

        System.out.println("[raw] headers.size = " + headers.size());
        assertEquals("Raw Map.class read must preserve all keys (incl. null)",
                5, headers.size());
    }

    @Test
    public void writeBackPreservesAllKeys() throws Exception {
        // 模拟完整流程: parse → put → serialize
        String rawRowJson = "{\"序号\":\"1\",\"所属机构\":\"3500\",\"领用部门\":\"oldDept\","
                + "\"领用人\":\"oldUser\",\"盘点日期\":null}";

        // 用 raw Map 读
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = MAPPER.readValue(rawRowJson, Map.class);
        // 模拟新值
        headers.put("领用部门", "newDept");
        headers.put("领用人", "newUser");

        String newJson = MAPPER.writeValueAsString(headers);
        System.out.println("[writeBack] newJson = " + newJson);

        // 写回后应保留所有 key
        @SuppressWarnings("unchecked")
        Map<String, Object> roundtrip = MAPPER.readValue(newJson, Map.class);
        assertEquals("Write-back must preserve all keys", 5, roundtrip.size());
        assertEquals("newDept", "newDept", roundtrip.get("领用部门"));
        assertEquals("newUser", "newUser", roundtrip.get("领用人"));
    }
}

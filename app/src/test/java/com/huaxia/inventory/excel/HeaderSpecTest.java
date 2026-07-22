package com.huaxia.inventory.excel;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HeaderSpecTest {

    @Test
    public void validatesRequiredHeaders() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "序号", "设备类型", "设备型号", "序列号",
                "固资编号", "领用部门", "领用人", "盘点日期"));
        assertTrue(spec.isValid());
    }

    @Test
    public void reportsMissingHeaders() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList("序号", "设备名称"));
        assertEquals(7, spec.missingHeaders().size());
        assertTrue(spec.missingHeaders().contains("固资编号"));
    }

    @Test
    public void epcColumnIndex_found() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "序号", "固资编号", "设备名称"));
        assertEquals(1, spec.epcColumnIndex());
    }

    @Test
    public void epcColumnIndex_throwsWhenMissing() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList("序号", "设备名称"));
        assertThrows(IllegalStateException.class, spec::epcColumnIndex);
    }

    @Test
    public void epcColumnIndex_findsAssetCode() {
        // 验证列查找走"固资编号"名称, 即使表头顺序不同也能找到
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "设备名称", "所属机构", "固资编号", "设备型号"));
        assertEquals(2, spec.epcColumnIndex());
    }
}

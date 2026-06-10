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
                "序号", "设备名称", "设备类型", "设备型号", "所属机构",
                "详细地点", "放置地点", "资产状态", "电子标签"));
        assertTrue(spec.isValid());
    }

    @Test
    public void reportsMissingHeaders() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList("序号", "设备名称"));
        assertEquals(7, spec.missingHeaders().size());
        assertTrue(spec.missingHeaders().contains("电子标签"));
    }

    @Test
    public void epcColumnIndex_found() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList(
                "序号", "电子标签", "设备名称"));
        assertEquals(1, spec.epcColumnIndex());
    }

    @Test
    public void epcColumnIndex_throwsWhenMissing() {
        HeaderSpec spec = new HeaderSpec(Arrays.asList("序号", "设备名称"));
        assertThrows(IllegalStateException.class, spec::epcColumnIndex);
    }
}

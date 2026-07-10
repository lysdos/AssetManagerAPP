package com.huaxia.inventory.excel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HeaderSpec {

    public static final List<String> REQUIRED = Arrays.asList(
            "序号", "设备类型", "设备型号", "序列号",
            "固资编号", "领用部门", "领用人", "盘点日期");

    private final List<String> actual;

    public HeaderSpec(List<String> actual) {
        this.actual = actual;
    }

    public boolean isValid() {
        return missingHeaders().isEmpty();
    }

    public List<String> missingHeaders() {
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED) {
            if (!actual.contains(required)) missing.add(required);
        }
        return missing;
    }

    public int columnIndex(String name) {
        int i = actual.indexOf(name);
        if (i < 0) throw new IllegalStateException("Column not found: " + name);
        return i;
    }

    public int epcColumnIndex() {
        return columnIndex("固资编号");
    }

    public List<String> all() { return actual; }
}

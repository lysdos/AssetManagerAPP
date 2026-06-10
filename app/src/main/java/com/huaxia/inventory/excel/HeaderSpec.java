package com.huaxia.inventory.excel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HeaderSpec {

    public static final List<String> REQUIRED = Arrays.asList(
            "序号", "设备名称", "设备类型", "设备型号", "所属机构",
            "详细地点", "放置地点", "资产状态", "电子标签");

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
        return columnIndex("电子标签");
    }

    public List<String> all() { return actual; }
}

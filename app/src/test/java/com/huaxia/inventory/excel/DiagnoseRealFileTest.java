package com.huaxia.inventory.excel;

import com.huaxia.inventory.data.entity.Asset;
import org.junit.Test;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class DiagnoseRealFileTest {

    @Test
    public void imports_all131Rows_file1() throws Exception {
        DiagnoseReport r = diagnose("test_assets.xlsx");
        assertEquals("file1 success", 131, r.success);
        assertEquals("file1 errors", 0, r.errors);
        // 留个 debug 线索, 失败时直接看就知道前 3 条 epc 长啥样
        System.out.println("DIAG file1 first3 epc=" + r.first3Epc);
    }

    @Test
    public void imports_all20Rows_file2() throws Exception {
        // file2 是已高位补 0 的 24 位码, 只有 20 行 (改前 EpcNormalizer 全拒 → error=20, 改后 success=20)
        DiagnoseReport r = diagnose("test_assets2.xlsx");
        assertEquals("file2 success", 20, r.success);
        assertEquals("file2 errors", 0, r.errors);
        System.out.println("DIAG file2 first3 epc=" + r.first3Epc);
    }

    private DiagnoseReport diagnose(String resourceName) throws Exception {
        int[] success = {0};
        int[] error = {0};
        List<String> errorSample = new ArrayList<>();
        List<Asset> first3 = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("test resource not found: " + resourceName);
            }
            ExcelImporter.stream(in, 0L, 500,
                    new ExcelImporter.BatchHandler() {
                        @Override public void onBatch(List<Asset> batch) {
                            success[0] += batch.size();
                            for (Asset a : batch) {
                                if (first3.size() < 3) first3.add(a);
                            }
                        }
                        @Override public void onError(String message) {
                            error[0]++;
                            if (errorSample.size() < 10) errorSample.add(message);
                        }
                    });
        }
        System.out.println("DIAG " + resourceName + " success=" + success[0] + " error=" + error[0]);
        if (!errorSample.isEmpty()) {
            System.out.println("DIAG " + resourceName + " errorSample=" + errorSample);
        }
        DiagnoseReport r = new DiagnoseReport();
        r.success = success[0];
        r.errors = error[0];
        r.first3Epc = first3.stream().map(a -> a.epc).toList();
        return r;
    }

    private static class DiagnoseReport {
        int success;
        int errors;
        List<String> first3Epc;
    }
}

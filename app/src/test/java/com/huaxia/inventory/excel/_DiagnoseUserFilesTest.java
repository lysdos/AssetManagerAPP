package com.huaxia.inventory.excel;

import com.huaxia.inventory.data.entity.Asset;
import org.junit.Test;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.fail;

public class _DiagnoseUserFilesTest {

    private static final String FILE_20 =
            "C:\\Users\\Asus\\Desktop\\固定资产测试\\20条补0数据测试.xlsx";
    private static final String FILE_BIG =
            "C:\\Users\\Asus\\Desktop\\固定资产测试\\固定资产测试.xlsx";

    @Test
    public void stream_file_20rows() throws Exception {
        stream(FILE_20, "FILE-20");
    }

    @Test
    public void stream_file_big() throws Exception {
        stream(FILE_BIG, "FILE-BIG");
    }

    private void stream(String path, String tag) throws Exception {
        System.out.println("\n========== " + tag + " : " + path + " ==========");
        int[] success = {0};
        int[] error = {0};
        List<String> errorSample = new ArrayList<>();
        List<String> first5Epc = new ArrayList<>();
        try (InputStream in = new FileInputStream(path)) {
            ExcelImporter.stream(in, 0L, 500,
                    new ExcelImporter.BatchHandler() {
                        @Override public void onBatch(List<Asset> batch) {
                            success[0] += batch.size();
                            for (Asset a : batch) {
                                if (first5Epc.size() < 5) {
                                    first5Epc.add("row=" + a.excelRowIndex
                                            + " epcLen=" + a.epc.length()
                                            + " epc='" + a.epc + "'");
                                }
                            }
                        }
                        @Override public void onError(String message) {
                            error[0]++;
                            if (errorSample.size() < 10) errorSample.add(message);
                        }
                    });
        } catch (Exception e) {
            System.out.println(tag + " TOP-LEVEL EXCEPTION: " + e.getClass().getName()
                    + " msg=" + e.getMessage());
            Throwable t = e;
            int depth = 0;
            while (t != null && depth < 5) {
                System.out.println(tag + "  caused[" + depth + "] " + t.getClass().getName() + ": " + t.getMessage());
                for (StackTraceElement el : t.getStackTrace()) {
                    System.out.println(tag + "    at " + el);
                    if (el.getClassName().contains("poi") || el.getClassName().contains("logging")) {
                        // 只印前 15 行 poi/log4j 栈
                    }
                }
                t = t.getCause();
                depth++;
            }
            return;
        }
        System.out.println(tag + " success=" + success[0] + " error=" + error[0]);
        System.out.println(tag + " first5: " + first5Epc);
        for (String m : errorSample) System.out.println(tag + " ERR: " + m);
    }
}

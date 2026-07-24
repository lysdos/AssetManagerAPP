package com.huaxia.inventory.log;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 防回归: 3 处生产日志里明文输出业务敏感数据 (EPC / taskId / 原始扫码码),
 * 改成 redactTail (只输出长度 + 末 4 位) 或干脆不输出. EPC / 固资编号属于
 * 业务敏感身份, logcat 暴露给运维 / 厂商诊断通道有过 PII 面.
 */
public class LogRedactionTest {

    @Test
    public void resultFragment_doesNotLogRawEpcInConstraintCatch() throws Exception {
        String text = readSrc(
                "app/src/main/java/com/huaxia/inventory/ui/result/ResultFragment.java");
        assertTrue("source not found", !text.isEmpty());
        int idx = text.indexOf("SQLiteConstraintException");
        assertTrue("SQLiteConstraintException catch must exist. Source:\n" + text,
                idx >= 0);
        String catchBody = extractCatchBody(text, idx);
        assertFalse("ResultFragment must NOT log raw pendingSave.epc — EPC is "
                + "business-sensitive PII. Catch body:\n" + catchBody,
                catchBody.contains("epc=\" + pendingSave.epc")
                        || catchBody.contains("epc = \" + pendingSave.epc")
                        || catchBody.contains("epc=pendingSave.epc"));
    }

    @Test
    public void barcodeController_doesNotLogRawCodeInOnMlKitResult() throws Exception {
        String text = readSrc(
                "app/src/main/java/com/huaxia/inventory/barcode/BarcodeCameraController.java");
        assertTrue("source not found", !text.isEmpty());
        // redactTail 调用放在 onMlKitResult 里 — emit() 仅 dispatch callback, 日志
        // (含 redactTail) 在 onMlKitResult 完成.
        String onMlKitBody = extractMethodBody(text,
                "private void onMlKitResult(");
        assertTrue(onMlKitBody.length() > 0);
        assertFalse("BarcodeCameraController.onMlKitResult must NOT log full raw barcode "
                + "string. Body:\n" + onMlKitBody,
                onMlKitBody.contains("detected: '\" + raw")
                        || onMlKitBody.contains("raw + \"'"));
        assertTrue("onMlKitResult must use ExcelExporter.redactTail to log length "
                + "+ 4-char tail. Body:\n" + onMlKitBody,
                onMlKitBody.contains("redactTail(raw)"));
    }

    @Test
    public void scanViewModel_doesNotLogRawTaskIdInBindToTask() throws Exception {
        String text = readSrc(
                "app/src/main/java/com/huaxia/inventory/ui/scan/ScanViewModel.java");
        assertTrue("source not found", !text.isEmpty());
        String body = extractMethodBody(text, "public void bindToTask(");
        assertTrue(body.length() > 0);
        assertFalse("bindToTask log line must NOT print the raw taskId — "
                + "InventoryTask.id is a business correlation id. Body:\n" + body,
                body.contains("taskId=\" + taskId")
                        || body.contains("taskId = \" + taskId")
                        || body.contains("taskId=taskId"));
    }

    @Test
    public void excelExporter_containsRedactTailHelper() throws Exception {
        // source-guard: redactTail 是 String → String 静态助手, 供 BarcodeCameraController 等
        // 跨包调用方做日志脱敏, 不重复造轮子. 这里通过源码扫描确认存在, 不用反射.
        String text = readSrc(
                "app/src/main/java/com/huaxia/inventory/excel/ExcelExporter.java");
        assertTrue("source not found", !text.isEmpty());
        assertTrue("ExcelExporter must declare public static redactTail(String) helper. "
                + "Source:\n" + text,
                text.contains("public static String redactTail("));
        // 实现尾部必须保留 4 位明文, 中间替换为 **
        String impl = extractMethodBody(text, "public static String redactTail(String s)");
        assertTrue(impl.length() > 0);
        assertTrue("redactTail must use Math.min(4, len) for tail length and substring "
                + "preserving last 4 chars. Body:\n" + impl,
                impl.contains("Math.min(4, len)")
                        || impl.contains("Math.min(4,  len)"));
        assertTrue("redactTail null/empty must return sentinel. Body:\n" + impl,
                impl.contains("if (s == null") || impl.contains("s == null"));
    }

    private static String readSrc(String relPath) throws Exception {
        File moduleDir = new File(System.getProperty("user.dir"));
        File source = new File(moduleDir, relPath);
        if (!source.exists()) {
            source = new File(moduleDir, relPath.replaceFirst("^app/", ""));
        }
        if (!source.exists()) return "";
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }

    private static String extractMethodBody(String text, String signature) {
        int sigStart = text.indexOf(signature);
        if (sigStart < 0) return "";
        int braceStart = text.indexOf('{', sigStart);
        if (braceStart < 0) return "";
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return "";
        return text.substring(braceStart + 1, i - 1);
    }

    private static String extractCatchBody(String text, int throwIdx) {
        int braceStart = text.indexOf('{', throwIdx);
        if (braceStart < 0) return "";
        int depth = 1;
        int i = braceStart + 1;
        while (i < text.length() && depth > 0) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        if (depth != 0) return "";
        return text.substring(braceStart + 1, i - 1);
    }
}

package com.huaxia.inventory.excel;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * 流式 XLSX 写入器 — 使用 SAX 方式直接生成 OOXML，不依赖 POI 的 SheetUtil
 * （避免 Android 上 java.awt.font.FontRenderContext 缺失导致的崩溃）。
 * <p>
 * 内部维护滑动窗口：每 {@link #FLUSH_THRESHOLD} 行刷写到临时文件，
 * 最终 finish() 时组装 ZIP。峰值内存 ≈ FLUSH_THRESHOLD * 单行大小。
 *
 * <p><b>tmpDir 选择</b>: 必须传入 Android 应用的 {@code context.getCacheDir()},
 * 而非 {@code System.getProperty("java.io.tmpdir")}. 原因: Android 上
 * {@code java.io.tmpdir} 通常落到 {@code /sdcard} 或 {@code /data/local/tmp},
 * 前者会被 Scoped Storage/SAF 拒绝写, 后者权限不一定有;
 * {@code context.getCacheDir()} 位于应用私有目录, 无需权限, 系统压力下也会被自动清理.</p>
 */
public final class XlsxStreamWriter implements AutoCloseable {

    private static final int FLUSH_THRESHOLD = 2000;

    private final OutputStream out;
    private final List<String> headers;

    private int totalRows;
    private boolean finished;
    private final List<List<CellData>> buffer = new ArrayList<>();
    private final List<FlushedChunk> chunks = new ArrayList<>();

    private int nextFileId;
    private final File tempDir;
    /** 可选列宽（字符单位），长度与表头列数一致；null 表示不写 &lt;cols&gt;. */
    private int[] columnWidths;
    /** 工作表名；默认「盘点结果」，资产台账导出可改为「资产台账」. */
    private String sheetName = "盘点结果";

    /**
     * @param parentTmpDir 临时文件父目录, 推荐传入 {@code context.getCacheDir()}.
     *                      不允许为 null. (强制要求, 不留默认 tmpdir 是因为
     *                      Android JVM 的 {@code java.io.tmpdir} 不可写.)
     */
    public XlsxStreamWriter(OutputStream out, List<String> headers, File parentTmpDir) {
        if (parentTmpDir == null) {
            throw new IllegalArgumentException("parentTmpDir must not be null; "
                    + "pass context.getCacheDir() on Android");
        }
        this.out = out;
        this.headers = headers;
        this.tempDir = new File(parentTmpDir, "xlsx_stream_" + System.nanoTime());
        // 父目录可能在初始化前未创建, 这里 mkdirs 兜底.
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new UncheckedIOException(new IOException(
                    "Cannot create tempDir at " + tempDir.getAbsolutePath()
                            + " (parent=" + parentTmpDir.getAbsolutePath()
                            + ", exists=" + parentTmpDir.exists() + ")"));
        }
    }

    // ---- 公共 API ----

    /**
     * 设置工作表列宽（字符单位）。必须在 {@link #finish()} 前调用。
     * 不设置时保持原有行为，不写 &lt;cols&gt; 节点。
     */
    /** 设置工作表名；默认「盘点结果」，必须在 {@link #finish()} 前调用。 */
    public void setSheetName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("sheet name must not be empty");
        }
        this.sheetName = name;
    }

    public void setColumnWidths(int... widths) {
        if (widths == null || widths.length == 0) {
            this.columnWidths = null;
            return;
        }
        for (int width : widths) {
            if (width <= 0) {
                throw new IllegalArgumentException("column width must be > 0");
            }
        }
        this.columnWidths = widths.clone();
    }

    public void writeRow(String... values) {
        List<CellData> row = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                row.add(new CellData(v, false, 0));
            }
        }
        addRow(row);
    }

    public void writeRow(Object... values) {
        List<CellData> row = new ArrayList<>();
        if (values != null) {
            for (Object v : values) {
                if (v == null) {
                    row.add(new CellData("", false, 0));
                } else if (v instanceof Number) {
                    row.add(new CellData(v.toString(), true, 0));
                } else {
                    row.add(new CellData(v.toString(), false, 0));
                }
            }
        }
        addRow(row);
    }

    /** 写一行加粗表头（使用 styles.xml 中 fontId=1 的样式）。 */
    public void writeHeaderRow(String... values) {
        List<CellData> row = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                row.add(new CellData(v, false, 1));
            }
        }
        addRow(row);
    }

    public int finish() throws IOException {
        if (finished) return totalRows;
        finished = true;
        flushBuffer();
        writeParts();
        cleanup();
        return totalRows;
    }

    @Override
    public void close() throws IOException {
        // finish 失败必须外抛 — 调用方 (ExcelExporter.exportStreamed) 才能感知导出
        // 是否成功结束. 此前 catch IOException ignored 让 cleanup 掩盖原始错误,
        // xlsx 可能损坏但调用方以为成功.
        if (!finished) {
            finish();
        }
        cleanup();
    }

    // ---- 内部 ----

    private void addRow(List<CellData> row) {
        buffer.add(row);
        totalRows++;
        if (buffer.size() >= FLUSH_THRESHOLD) {
            flushBuffer();
        }
    }

    private void flushBuffer() {
        if (buffer.isEmpty()) return;
        File f = new File(tempDir, "rows_" + (nextFileId++) + ".xml");
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            for (List<CellData> row : buffer) {
                w.write("<row>");
                int col = 0;
                for (CellData c : row) {
                    String ref = colRef(col++);
                    w.write("<c r=\"");
                    w.write(ref);
                    w.write('"');
                    if (c.style != 0) {
                        w.write(" s=\"");
                        w.write(String.valueOf(c.style));
                        w.write('"');
                    }
                    if (c.isNumber) {
                        w.write(" t=\"n\"><v>");
                        w.write(esc(c.value));
                        w.write("</v></c>");
                    } else {
                        w.write(" t=\"inlineStr\"><is><t>");
                        w.write(esc(c.value));
                        w.write("</t></is></c>");
                    }
                }
                w.write("</row>\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        chunks.add(new FlushedChunk(f, buffer.size()));
        buffer.clear();
    }

    private void writeParts() throws IOException {
        ZipOutputStream zos = new ZipOutputStream(out);

        zos.putNextEntry(new ZipEntry("[Content_Types].xml"));
        writeContentTypes(zos);
        zos.closeEntry();

        zos.putNextEntry(new ZipEntry("_rels/.rels"));
        writeRels(zos);
        zos.closeEntry();

        zos.putNextEntry(new ZipEntry("xl/workbook.xml"));
        writeWorkbook(zos);
        zos.closeEntry();

        zos.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));
        writeWorkbookRels(zos);
        zos.closeEntry();

        zos.putNextEntry(new ZipEntry("xl/styles.xml"));
        writeStyles(zos);
        zos.closeEntry();

        // sheet1.xml — 先写头部行，再依次流式输出各 chunk
        zos.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
        OutputStreamWriter sw = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
        sw.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        if (columnWidths != null) {
            sw.write("<cols>");
            for (int i = 0; i < columnWidths.length; i++) {
                sw.write("<col min=\"");
                sw.write(String.valueOf(i + 1));
                sw.write("\" max=\"");
                sw.write(String.valueOf(i + 1));
                sw.write("\" width=\"");
                sw.write(String.valueOf(columnWidths[i]));
                sw.write("\"/>");
            }
            sw.write("</cols>");
        }
        sw.write("<sheetData>");

        for (FlushedChunk chunk : chunks) {
            File f = chunk.file;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sw.write(line);
                    sw.write('\n');
                }
            }
        }

        sw.write("</sheetData></worksheet>");
        sw.flush();
        zos.closeEntry();

        zos.finish();
        zos.close();
    }

    // ---- OOXML XML 片段 ----

    private void writeContentTypes(OutputStream os) throws IOException {
        os.write(("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>").getBytes(StandardCharsets.UTF_8));
    }

    private void writeRels(OutputStream os) throws IOException {
        os.write(("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>").getBytes(StandardCharsets.UTF_8));
    }

    private void writeWorkbook(OutputStream os) throws IOException {
        os.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + esc(sheetName)
                + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>").getBytes(StandardCharsets.UTF_8));
    }

    private void writeWorkbookRels(OutputStream os) throws IOException {
        os.write(("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>").getBytes(StandardCharsets.UTF_8));
    }

    private void writeStyles(OutputStream os) throws IOException {
        os.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<fonts><font><sz val=\"11\"/></font><font><b/><sz val=\"11\"/></font></fonts>"
                + "<fills><fill><patternFill patternType=\"none\"/></fill></fills>"
                + "<borders><border/></borders>"
                + "<cellXfs>"
                + "<xf fontId=\"0\"/>"
                + "<xf fontId=\"1\" applyFont=\"1\"/>"
                + "</cellXfs>"
                + "</styleSheet>").getBytes(StandardCharsets.UTF_8));
    }

    // ---- 工具方法 ----

    private static String colRef(int col) {
        StringBuilder sb = new StringBuilder();
        int c = col;
        do {
            sb.insert(0, (char) ('A' + c % 26));
            c = c / 26 - 1;
        } while (c >= 0);
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // XML 1.0 非法控制字符 (0x00-0x08, 0x0B, 0x0C, 0x0E-0x1F) 即使转义
                    // 也不合法, 会让整个 sheet XML 无法解析 → 导出的 xlsx 打不开.
                    // 源单元格里混进这类脏字符(常见于从其它系统导出的 Excel)时丢弃,
                    // 不牵连整个导出文件; \t \n \r 合法, 保留.
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private void cleanup() {
        for (FlushedChunk c : chunks) c.file.delete();
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        tempDir.delete();
    }

    // ---- 内部数据结构 ----

    private static final class CellData {
        final String value;
        final boolean isNumber;
        final int style;
        CellData(String value, boolean isNumber, int style) {
            this.value = value != null ? value : "";
            this.isNumber = isNumber;
            this.style = style;
        }
    }

    private static final class FlushedChunk {
        final File file;
        final int rowCount;
        FlushedChunk(File file, int rowCount) {
            this.file = file;
            this.rowCount = rowCount;
        }
    }
}

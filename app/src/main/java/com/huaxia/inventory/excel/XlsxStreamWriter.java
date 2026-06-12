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
    private File tempDir;

    public XlsxStreamWriter(OutputStream out, List<String> headers) {
        this.out = out;
        this.headers = headers;
        this.tempDir = new File(System.getProperty("java.io.tmpdir"),
                "xlsx_stream_" + System.nanoTime());
        tempDir.mkdirs();
    }

    // ---- 公共 API ----

    public void writeRow(String... values) {
        List<CellData> row = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                row.add(new CellData(v, false));
            }
        }
        addRow(row);
    }

    public void writeRow(Object... values) {
        List<CellData> row = new ArrayList<>();
        if (values != null) {
            for (Object v : values) {
                if (v == null) {
                    row.add(new CellData("", false));
                } else if (v instanceof Number) {
                    row.add(new CellData(v.toString(), true));
                } else {
                    row.add(new CellData(v.toString(), false));
                }
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
    public void close() {
        if (!finished) {
            try {
                finish();
            } catch (IOException ignored) {
            }
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
                + "<sheets><sheet name=\"盘点结果\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
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
                default:   sb.append(c);
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
        CellData(String value, boolean isNumber) {
            this.value = value != null ? value : "";
            this.isNumber = isNumber;
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

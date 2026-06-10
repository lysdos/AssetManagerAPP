package com.huaxia.inventory.excel;

import com.huaxia.inventory.data.entity.Asset;
import org.junit.Test;
import java.io.InputStream;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ExcelImporterTest {

    @Test
    public void parsesSampleFile_evenWhenAllEpcsEmpty() throws Exception {
        // The sample template has no EPCs filled in. Parser should skip them,
        // record errors, but not crash.
        try (InputStream in = getClass().getResourceAsStream("/sample-assets.xlsx")) {
            ExcelImporter.Result r = ExcelImporter.parse(in, /* taskId */ 1L);
            assertEquals(0, r.success);
            assertTrue(r.errors.size() > 100,
                    "Expected many empty-EPC errors; got " + r.errors.size());
        }
    }

    @Test
    public void rejectsNonExcel() {
        InputStream bad = new java.io.ByteArrayInputStream("not a zip".getBytes());
        assertThrows(Exception.class, () -> ExcelImporter.parse(bad, 1L));
    }
}

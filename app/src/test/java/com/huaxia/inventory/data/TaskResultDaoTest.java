package com.huaxia.inventory.data;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.entity.TaskResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TaskResultDaoTest {

    private AppDatabase db;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries().build();
    }

    @After
    public void tearDown() { db.close(); }

    private static TaskResult row(long taskId, int rowIndex, String status) {
        TaskResult r = new TaskResult();
        r.taskId = taskId;
        r.rowIndex = rowIndex;
        r.epc = "E" + rowIndex;
        r.status = status;
        return r;
    }

    @Test
    public void countsAndPages_byStatus() {
        List<TaskResult> rows = new ArrayList<>();
        rows.add(row(7L, 1, "FOUND"));
        rows.add(row(7L, 2, "MISSING"));
        rows.add(row(7L, 3, "MISSING"));
        rows.add(row(7L, 0, "EXTRA"));
        rows.add(row(8L, 1, "FOUND"));   // 别的任务，不该被算进来
        db.taskResultDao().insertAll(rows);

        assertThat(db.taskResultDao().countExpected(7L)).isEqualTo(3);
        assertThat(db.taskResultDao().countByStatus(7L, "FOUND")).isEqualTo(1);
        assertThat(db.taskResultDao().countByStatus(7L, "MISSING")).isEqualTo(2);
        assertThat(db.taskResultDao().countByStatus(7L, "EXTRA")).isEqualTo(1);

        // ALL 只含 FOUND/MISSING，按 rowIndex 升序，盘盈不进 ALL
        List<TaskResult> all = db.taskResultDao().pageAll(7L, 10, 0);
        assertThat(all).extracting(r -> r.rowIndex).containsExactly(1, 2, 3);
        assertThat(db.taskResultDao().pageExtra(7L, 10, 0)).hasSize(1);
    }

    @Test
    public void deleteByTask_removesOnlyThatTask() {
        List<TaskResult> rows = new ArrayList<>();
        rows.add(row(7L, 1, "FOUND"));
        rows.add(row(8L, 1, "FOUND"));
        db.taskResultDao().insertAll(rows);

        assertThat(db.taskResultDao().deleteByTask(7L)).isEqualTo(1);
        assertThat(db.taskResultDao().findAll(8L)).hasSize(1);
    }
}

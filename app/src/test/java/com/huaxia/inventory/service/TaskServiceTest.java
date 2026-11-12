package com.huaxia.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.huaxia.inventory.data.AppDatabase;
import com.huaxia.inventory.data.entity.Asset;
import com.huaxia.inventory.data.entity.InventoryTask;
import com.huaxia.inventory.session.InventorySession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class TaskServiceTest {

    private AppDatabase db;
    private InventorySession session;
    private TaskService service;

    @Before
    public void setUp() {
        db = Room.inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext(), AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        session = new InventorySession();
        service = new TaskService(db, session);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private void seedPool(String epc, String dept) {
        Asset a = new Asset();
        a.taskId = 0L;
        a.excelRowIndex = 1;
        a.epc = epc;
        a.dept = dept;
        a.assetType = "笔记本";
        db.assetDao().upsertOne(a);
    }

    @Test
    public void createTask_assignsWholePoolWhenDeptNull() {
        seedPool("1001", "行政部");
        seedPool("1002", "财务部");

        long id = service.createTask("Q4 全量", null, "备注");

        InventoryTask t = db.inventoryTaskDao().findById(id);
        assertThat(t).isNotNull();
        assertThat(t.status).isEqualTo("IN_PROGRESS");
        assertThat(t.remark).isEqualTo("备注");
        assertThat(t.expectedCount).isEqualTo(2);
        assertThat(db.assetDao().countPool()).isZero();
    }

    @Test
    public void createTask_assignsOnlyMatchingDept() {
        seedPool("1001", "行政部");
        seedPool("1002", "财务部");

        long id = service.createTask("行政部盘点", "行政部", null);

        InventoryTask t = db.inventoryTaskDao().findById(id);
        assertThat(t.dept).isEqualTo("行政部");
        assertThat(t.expectedCount).isEqualTo(1);
        assertThat(db.assetDao().countPool()).isEqualTo(1);
    }

    @Test
    public void deleteTask_releasesAssetsAndClearsTask() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);

        int released = service.deleteTask(id);

        assertThat(released).isEqualTo(1);
        assertThat(db.inventoryTaskDao().findById(id)).isNull();
        assertThat(db.assetDao().countPool()).isEqualTo(1);
    }

    @Test
    public void deleteTask_rollsBackEditedFieldsWhenNotCompleted() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);

        Asset edited = db.assetDao().findByEpc(id, "1001");
        edited.custodian = "张三";
        db.assetDao().update(edited);

        service.deleteTask(id);

        Asset back = db.assetDao().findByEpcGlobal("1001");
        assertThat(back.custodian).isNull();
    }

    @Test
    public void completeTask_keepsEditedFieldsAndClearsSnapshot() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);

        Asset edited = db.assetDao().findByEpc(id, "1001");
        edited.custodian = "李四";
        db.assetDao().update(edited);

        service.completeTask(id);

        Asset kept = db.assetDao().findByEpc(id, "1001");
        assertThat(kept.custodian).isEqualTo("李四");
        assertThat(kept.origCustodian).isNull();
        assertThat(db.inventoryTaskDao().findById(id).status).isEqualTo("COMPLETED");
    }

    @Test
    public void resumeTask_setsSessionCurrentTask() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);
        service.pauseTask(id);
        assertThat(session.getCurrentTaskId()).isNull();

        service.resumeTask(id);

        assertThat(session.getCurrentTaskId()).isEqualTo(id);
    }

    @Test
    public void pauseTask_clearsSessionWhenPausingCurrent() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);
        session.setCurrentTaskId(id);

        service.pauseTask(id);

        assertThat(session.getCurrentTaskId()).isNull();
        assertThat(db.inventoryTaskDao().findById(id).status).isEqualTo("PAUSED");
    }

    @Test
    public void clearCompleted_releasesAssetsBackToPool() {
        seedPool("1001", "行政部");
        long id = service.createTask("T", null, null);
        service.completeTask(id);

        service.clearCompleted();

        assertThat(db.inventoryTaskDao().findAllCompleted()).isEmpty();
        assertThat(db.assetDao().countPool()).isEqualTo(1);
    }
}

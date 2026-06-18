package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.huaxia.inventory.data.entity.InventoryTask;
import java.util.List;

@Dao
public interface InventoryTaskDao {

    @Insert
    long insert(InventoryTask task);

    @Update
    void update(InventoryTask task);

    @Query("SELECT * FROM InventoryTask WHERE id = :id")
    InventoryTask findById(long id);

    @Query("SELECT * FROM InventoryTask ORDER BY importedAt DESC")
    List<InventoryTask> findAll();

    @Query("SELECT * FROM InventoryTask WHERE status = 'IN_PROGRESS' ORDER BY importedAt DESC LIMIT 1")
    InventoryTask findInProgress();

    @Query("UPDATE InventoryTask SET scannedUniqueCount = (SELECT COUNT(*) FROM ScanRecord WHERE taskId = :taskId) WHERE id = :taskId")
    void recomputeScannedCount(long taskId);

    @Query("DELETE FROM InventoryTask WHERE id = :taskId")
    int deleteById(long taskId);
}

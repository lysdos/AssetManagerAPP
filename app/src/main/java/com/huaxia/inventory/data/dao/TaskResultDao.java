package com.huaxia.inventory.data.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.huaxia.inventory.data.entity.TaskResult;

import java.util.List;

/** {@link TaskResult} 读写。只服务已完成任务的结果查询与导出。 */
@Dao
public interface TaskResultDao {

    @Insert
    void insertAll(List<TaskResult> rows);

    /** 已盘 + 盘亏 = 应盘数（不含盘盈）。 */
    @Query("SELECT COUNT(*) FROM TaskResult WHERE taskId = :taskId "
            + "AND status IN ('FOUND', 'MISSING')")
    int countExpected(long taskId);

    @Query("SELECT COUNT(*) FROM TaskResult WHERE taskId = :taskId AND status = :status")
    int countByStatus(long taskId, String status);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId "
            + "AND status IN ('FOUND', 'MISSING') ORDER BY rowIndex ASC "
            + "LIMIT :limit OFFSET :offset")
    List<TaskResult> pageAll(long taskId, int limit, int offset);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId AND status = 'FOUND' "
            + "ORDER BY rowIndex ASC LIMIT :limit OFFSET :offset")
    List<TaskResult> pageFound(long taskId, int limit, int offset);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId AND status = 'MISSING' "
            + "ORDER BY rowIndex ASC LIMIT :limit OFFSET :offset")
    List<TaskResult> pageMissing(long taskId, int limit, int offset);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId AND status = 'EXTRA' "
            + "ORDER BY scannedAt ASC LIMIT :limit OFFSET :offset")
    List<TaskResult> pageExtra(long taskId, int limit, int offset);

    @Query("SELECT * FROM TaskResult WHERE taskId = :taskId "
            + "AND status IN ('FOUND', 'MISSING') ORDER BY rowIndex ASC")
    List<TaskResult> findAll(long taskId);

    @Query("DELETE FROM TaskResult WHERE taskId = :taskId")
    int deleteByTask(long taskId);
}

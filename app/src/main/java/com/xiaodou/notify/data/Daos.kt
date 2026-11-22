package com.xiaodou.notify.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: Long): NotificationEntity?

    /**
     * 按 id 集合取回待办关联的通知，避免统计页为数个待办 id 读取整张表。
     * Room 会对集合参数展开为 IN (...)，Android SQLite 变量上限 999，
     * 待办规模通常远小于此；调用方按 400 一批查询以稳妥兜底。
     */
    @Query("SELECT id, category FROM notifications WHERE id IN (:ids)")
    suspend fun categoryByIds(ids: List<Long>): List<IdCategory>

    @Query("SELECT * FROM notifications WHERE packageName = :pkg ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByPackage(pkg: String, limit: Int): List<NotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: NotificationEntity): Long

    @Update
    suspend fun update(item: NotificationEntity)

    @Delete
    suspend fun delete(item: NotificationEntity)

    @Query("UPDATE notifications SET marked = :label WHERE id = :id")
    suspend fun setMarked(id: Long, label: Int)

    @Query("UPDATE notifications SET removedReason = :reason WHERE id = :id")
    suspend fun setRemovedReason(id: Long, reason: String)

    @Query("SELECT COUNT(*) FROM notifications")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE result = :result")
    suspend fun countByResult(result: String): Int

    @Query("SELECT packageName, COUNT(*) AS cnt FROM notifications WHERE timestamp >= :since GROUP BY packageName ORDER BY cnt DESC")
    suspend fun packageRank(since: Long): List<PackageCount>

    @Query("SELECT packageName, COUNT(*) AS cnt FROM notifications WHERE timestamp >= :since AND result = :result GROUP BY packageName ORDER BY cnt DESC")
    suspend fun packageRankByResult(since: Long, result: String): List<PackageCount>

    @Query("SELECT timestamp, result FROM notifications WHERE timestamp >= :since")
    suspend fun dailyRaw(since: Long): List<DailyRaw>

    // —— v1.7.3 系统操作提示：显示开关关闭时，从列表/统计中排除，但记录仍完整落库 ——
    // Room 把 Boolean 绑定为 0/1；:showFeedback=true 时不加限制，false 时排除 feedback 类。
    // 使用 COALESCE 让老数据（category 为 null）不受影响。

    @Query("SELECT COUNT(*) FROM notifications WHERE :showFeedback = 1 OR COALESCE(category, '') != 'feedback'")
    suspend fun countAllVisible(showFeedback: Boolean): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE result = :result AND (:showFeedback = 1 OR COALESCE(category, '') != 'feedback')")
    suspend fun countByResultVisible(result: String, showFeedback: Boolean): Int

    @Query("SELECT packageName, COUNT(*) AS cnt FROM notifications WHERE timestamp >= :since AND (:showFeedback = 1 OR COALESCE(category, '') != 'feedback') GROUP BY packageName ORDER BY cnt DESC")
    suspend fun packageRankVisible(since: Long, showFeedback: Boolean): List<PackageCount>

    @Query("SELECT packageName, COUNT(*) AS cnt FROM notifications WHERE timestamp >= :since AND result = :result AND (:showFeedback = 1 OR COALESCE(category, '') != 'feedback') GROUP BY packageName ORDER BY cnt DESC")
    suspend fun packageRankByResultVisible(since: Long, result: String, showFeedback: Boolean): List<PackageCount>

    @Query("SELECT timestamp, result FROM notifications WHERE timestamp >= :since AND (:showFeedback = 1 OR COALESCE(category, '') != 'feedback')")
    suspend fun dailyRawVisible(since: Long, showFeedback: Boolean): List<DailyRaw>

    @Query("SELECT COUNT(*) FROM notifications WHERE marked IS NOT NULL AND (:showFeedback = 1 OR COALESCE(category, '') != 'feedback')")
    suspend fun countLearnedVisible(showFeedback: Boolean): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE timestamp >= :since AND (:showFeedback = 1 OR COALESCE(category, '') != 'feedback')")
    suspend fun countSinceVisible(since: Long, showFeedback: Boolean): Int

    @Query("SELECT COUNT(*) FROM notifications WHERE timestamp >= :since AND result != 'kept' AND (:showFeedback = 1 OR COALESCE(category, '') != 'feedback')")
    suspend fun countFilteredSinceVisible(since: Long, showFeedback: Boolean): Int

    @Query("DELETE FROM notifications")
    suspend fun clearAll()

    @Query("DELETE FROM notifications WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long)

    /** 滚动清理：保留最新 keep 条，删除其余最旧记录（FR-H1 最旧优先） */
    @Query("DELETE FROM notifications WHERE id IN (SELECT id FROM notifications ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("SELECT * FROM notifications WHERE result = :r AND marked IS NULL ORDER BY timestamp DESC LIMIT :limit")
    suspend fun unscored(r: String, limit: Int): List<NotificationEntity>

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NotificationEntity>

    /** v1.6.0 验证码保险箱：取所有验证码/取件码类通知，倒序 */
    @Query("SELECT * FROM notifications WHERE category = 'code' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun codeVault(limit: Int): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE category = 'code'")
    suspend fun codeVaultCount(): Int

    /** 按时间范围+App 筛选导出（FR-H6） */
    @Query("SELECT * FROM notifications WHERE timestamp >= :from AND timestamp <= :to AND (:pkg = '' OR packageName = :pkg) ORDER BY timestamp DESC")
    suspend fun queryForExport(from: Long, to: Long, pkg: String): List<NotificationEntity>

    /** 按日聚合统计（FR-S2）：返回每天总数与过滤数 */
    @Query("SELECT timestamp, result FROM notifications WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun statsForDaily(since: Long): List<DailyRaw>

    /** 指定 App 在近 N 条的过滤/保留分布（FR-S1 分口径） */
    @Query("SELECT result, COUNT(*) AS cnt FROM notifications WHERE timestamp >= :since AND (:pkg = '' OR packageName = :pkg) GROUP BY result")
    suspend fun resultBreakdown(since: Long, pkg: String): List<ResultCount>

    @Query("SELECT COUNT(*) FROM notifications WHERE marked IS NOT NULL")
    suspend fun countLearned(): Int

    /** FR-E4 桌面小部件：指定起点之后的总数 */
    @Query("SELECT COUNT(*) FROM notifications WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int

    /** FR-E4 桌面小部件：指定起点之后被过滤（非 kept）的数量 */
    @Query("SELECT COUNT(*) FROM notifications WHERE timestamp >= :since AND result != 'kept'")
    suspend fun countFilteredSince(since: Long): Int

    /** 重置模型：清除所有打分与标注，保留历史内容 */
    @Query("UPDATE notifications SET score = 0, marked = NULL")
    suspend fun resetAllScores()
}

data class IdCategory(val id: Long, val category: String?)

data class ResultCount(val result: String, val cnt: Int)

data class PackageCount(val packageName: String, val cnt: Int)
data class DailyRaw(val timestamp: Long, val result: String)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<FilterRule>>

    @Query("SELECT * FROM rules WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun enabledRules(): List<FilterRule>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getById(id: Long): FilterRule?

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: FilterRule): Long

    @Update
    suspend fun update(rule: FilterRule)

    @Delete
    suspend fun delete(rule: FilterRule)

    @Query("DELETE FROM rules")
    suspend fun clearAll()
}

@Dao
interface AiFeedbackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(feedback: AiFeedback)

    @Query("SELECT COUNT(*) FROM ai_feedback WHERE label = 1")
    suspend fun spamCount(): Int

    @Query("SELECT COUNT(*) FROM ai_feedback WHERE label = 0")
    suspend fun normalCount(): Int

    @Query("SELECT * FROM ai_feedback ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AiFeedback>

    @Query("DELETE FROM ai_feedback")
    suspend fun clearAll()
}

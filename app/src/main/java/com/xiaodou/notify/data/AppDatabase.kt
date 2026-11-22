package com.xiaodou.notify.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationEntity::class, FilterRule::class, AiFeedback::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao
    abstract fun ruleDao(): RuleDao
    abstract fun aiFeedbackDao(): AiFeedbackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1→v2：通知新增 sysKey（精确消除）、category（类型筛选） */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifications ADD COLUMN sysKey TEXT")
                db.execSQL("ALTER TABLE notifications ADD COLUMN category TEXT")
            }
        }


        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xiaodou.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // P1 性能：补高频查询索引。用 onOpen + IF NOT EXISTS，不改 @Entity 注解，
                    // 因此不触发 Room schema 版本校验，已安装用户下次打开即自动建索引。
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_result ON notifications(result)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_category ON notifications(category)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_marked ON notifications(marked)")
                            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notif_ts_result ON notifications(timestamp, result)")
                        }
                    })
                    .build().also { INSTANCE = it }
            }
        }
    }
}

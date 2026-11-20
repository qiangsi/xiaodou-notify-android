package com.xiaodou.notify.listener

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.xiaodou.notify.util.isListenerEnabled

/**
 * 保活看门狗：每 15 分钟检查一次，进程被回收/任务被划掉后重新拉起前台服务。
 * JobScheduler 的任务会跨进程存活，且重启后仍保留（setPersisted）。
 */
class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (isListenerEnabled(this)) {
            try {
                KeepAliveService.start(applicationContext)
            } catch (e: Exception) {
                // 系统限制后台启动前台服务时忽略，等下一个周期重试
            }
            // 假连接自愈：权限已给但 listener 没真正连上，周期性 toggle 组件强制重绑
            if (!NotificationMonitorService.isConnected) {
                NotificationMonitorService.forceRebind(applicationContext)
            }
        }
        // 周期性任务执行一次后需要重新排期
        schedule(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 1002
        private const val INTERVAL_MS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .build()
                scheduler.schedule(job)
            } catch (e: Exception) {
                // 部分定制系统限制 JobScheduler，忽略即可
            }
        }
    }
}

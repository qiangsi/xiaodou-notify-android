package com.xiaodou.notify.ui

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.xiaodou.notify.R
import com.xiaodou.notify.data.NotificationEntity
import com.xiaodou.notify.data.Result
import com.xiaodou.notify.databinding.ItemNotificationBinding
import com.xiaodou.notify.util.DecryptCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间轴列表项：日期分隔头 或 通知 */
sealed class TimelineItem {
    data class Header(val label: String, val key: String) : TimelineItem()
    data class Entry(val entity: NotificationEntity) : TimelineItem()
}

class NotificationAdapter(
    private val onClick: (NotificationEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<TimelineItem> = emptyList()
    private val iconCache = HashMap<String, Drawable?>()
    /** 时间显示格式化器复用，避免滚动时逐条 new */
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1

        /**
         * 分组为时间轴条目。纯函数、无 UI 依赖，供 HistoryFragment 在后台线程预计算，
         * 避免数据量大时在主线程分组掉帧。list 需按时间倒序。
         */
        fun group(list: List<NotificationEntity>, groupByDay: Boolean): List<TimelineItem> {
            val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dayLabel = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
            val result = mutableListOf<TimelineItem>()
            if (!groupByDay) {
                list.forEach { result += TimelineItem.Entry(it) }
            } else {
                val byDay = LinkedHashMap<String, MutableList<NotificationEntity>>()
                list.forEach { e ->
                    val k = dayKey.format(Date(e.timestamp))
                    byDay.getOrPut(k) { mutableListOf() }.add(e)
                }
                byDay.forEach { (_, entries) ->
                    val first = entries.first().timestamp
                    result += TimelineItem.Header(dayLabel.format(Date(first)), dayKey.format(Date(first)))
                    // v1.6.0：组内也按时间倒序，最新的通知排在最上面（整页彻底倒叙）
                    entries.sortedByDescending { it.timestamp }.forEach { result += TimelineItem.Entry(it) }
                }
            }
            return result
        }
    }

    class HeaderVH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tv_date)
    }
    class EntryVH(val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int) = when (items[position]) {
        is TimelineItem.Header -> TYPE_HEADER
        is TimelineItem.Entry -> TYPE_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            HeaderVH(LayoutInflater.from(parent.context).inflate(R.layout.item_date_header, parent, false))
        } else {
            EntryVH(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TimelineItem.Header -> (holder as HeaderVH).tvDate.text = item.label
            is TimelineItem.Entry -> bindEntry((holder as EntryVH), item.entity)
        }
    }

    override fun getItemCount() = items.size

    private fun bindEntry(holder: EntryVH, item: NotificationEntity) {
        val b = holder.binding
        b.tvApp.text = item.appName
        b.tvTime.text = timeFmt.format(Date(item.timestamp))
        val plain = DecryptCache.plain(item)
        b.tvTitle.text = plain.first
        b.tvText.text = plain.second

        val filtered = item.result != Result.KEPT
        b.tvBadge.setText(if (filtered) R.string.badge_filtered else R.string.badge_kept)
        b.tvBadge.setBackgroundResource(if (filtered) R.drawable.bg_badge_filtered else R.drawable.bg_badge_kept)
        b.tvBadge.setTextColor(b.root.context.getColor(if (filtered) R.color.badge_filtered_text else R.color.badge_kept_text))
        b.tvReason.text = item.filterReason ?: ""

        if (item.score > 0 && !filtered) {
            b.tvScore.text = "骚扰 ${item.score}%"
            b.tvScore.setTextColor(b.root.context.getColor(if (item.score >= 70) R.color.score_high else if (item.score >= 40) R.color.score_mid else R.color.score_low))
        } else {
            b.tvScore.text = ""
        }
        b.root.setOnClickListener { onClick(item) }
        bindAppIcon(b, item.packageName)
    }

    /** 应用图标：缓存避免滚动时重复查询 PackageManager；取不到时回退到通用铃铛。 */
    private fun bindAppIcon(b: ItemNotificationBinding, pkg: String) {
        val ctx = b.root.context
        val icon: Drawable? = iconCache.getOrPut(pkg) {
            try {
                ctx.packageManager.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
        }
        if (icon != null) {
            b.ivAppIcon.setPadding(0, 0, 0, 0)
            b.ivAppIcon.imageTintList = null
            b.ivAppIcon.setImageDrawable(icon)
        } else {
            val p = (8 * ctx.resources.displayMetrics.density).toInt()
            b.ivAppIcon.setPadding(p, p, p, p)
            b.ivAppIcon.setImageResource(R.drawable.ic_bell)
            b.ivAppIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.text_tertiary)
            )
        }
    }

    /**
     * 计算与当前列表的差异。纯计算、可在后台线程调用，配合 [applyDiff] 在主线程提交。
     * 日期头按 key（yyyy-MM-dd）识别、通知按 entity.id 识别；内容变化按 data class equals 判定。
     */
    fun computeDiff(grouped: List<TimelineItem>): DiffUtil.DiffResult {
        val old = items
        return DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = grouped.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val a = old[oldPos]
                val b = grouped[newPos]
                return when {
                    a is TimelineItem.Header && b is TimelineItem.Header -> a.key == b.key
                    a is TimelineItem.Entry && b is TimelineItem.Entry -> a.entity.id == b.entity.id
                    else -> false
                }
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = old[oldPos] == grouped[newPos]
        })
    }

    /** 主线程提交后台算好的差异结果 */
    fun applyDiff(grouped: List<TimelineItem>, diff: DiffUtil.DiffResult) {
        items = grouped
        diff.dispatchUpdatesTo(this)
    }
}

package com.xiaodou.notify.util

import com.xiaodou.notify.data.CryptoHelper
import com.xiaodou.notify.data.NotificationEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知正文解密缓存。
 *
 * 背景：标题/正文用 Android Keystore 的 AES-GCM 加密，每次 decrypt 都要走 Keystore
 * 与 Cipher 初始化，是列表滚动与历史搜索里最贵的一步。密文一旦落库不再变化，
 * 因此按通知 id 缓存明文是安全的（更新只动 marked/score/filterReason，不动密文）。
 *
 * 只缓存明文字符串，不缓存 Entity 本身，避免 Entity 被更新后 DiffUtil 误判。
 * 上限到达后整体清空，防止历史量大时无限增长；清空只损失性能，不影响正确性。
 */
object DecryptCache {

    private const val MAX_ENTRIES = 4000
    private val cache = ConcurrentHashMap<Long, Pair<String, String>>()

    fun plain(entity: NotificationEntity): Pair<String, String> {
        cache[entity.id]?.let { return it }
        val value = CryptoHelper.decrypt(entity.titleEnc) to CryptoHelper.decrypt(entity.textEnc)
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[entity.id] = value
        return value
    }

    /** 通知被删除或清空数据时调用，避免 id 复用导致读到旧明文（id 自增，实际不会复用） */
    fun remove(id: Long) {
        cache.remove(id)
    }

    fun clear() {
        cache.clear()
    }
}

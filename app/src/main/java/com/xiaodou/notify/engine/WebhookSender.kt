package com.xiaodou.notify.engine

import android.util.Log
import com.xiaodou.notify.data.NotificationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** Webhook 转发：这是产品唯一的数据外发通道，须由用户在规则中显式配置 URL。 */
object WebhookSender {

    /** 单条 JSON 记录（字段表见 PRD FR-R3-10） */
    private fun buildJson(title: String, text: String, e: NotificationEntity) = JSONObject().apply {
        put("app", e.packageName)
        put("appName", e.appName)
        put("title", title)
        put("text", text)
        put("timestamp", e.timestamp)
        put("result", e.result)
        put("channel", e.channel ?: JSONObject.NULL)
        put("category", e.category ?: JSONObject.NULL)
    }

    /**
     * 发送并重试：仅对 5xx / 网络异常重试，最多 3 次，指数退避。
     * 4xx（如 URL 错误/鉴权失败）不重试，避免无意义请求。
     */
    fun send(url: String, title: String, text: String, entity: NotificationEntity): Boolean {
        if (url.isBlank()) return false
        val body = buildJson(title, text, entity).toString()
        var attempt = 0
        while (attempt < 3) {
            val code = tryPost(url, body)
            if (code in 200..299) return true
            if (code in 400..499) return false // 客户端错误不重试
            attempt++
            if (attempt < 3) Thread.sleep(500L * (1 shl (attempt - 1))) // 0.5s, 1s
        }
        Log.w("XiaodouWebhook", "转发失败：重试 3 次后仍失败")
        return false
    }

    private fun tryPost(url: String, body: String): Int {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            conn.responseCode
        } catch (e: Exception) {
            Log.w("XiaodouWebhook", "请求异常: ${e.message}")
            -1
        } finally {
            conn?.disconnect()
        }
    }
}

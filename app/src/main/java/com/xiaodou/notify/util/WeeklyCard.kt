package com.xiaodou.notify.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.7.5 清净周报图片卡片：把过滤战绩绘成一张可保存/分享的竖版图片。
 * 全部在本地 Canvas 绘制，不上传、不联网、不含任何通知正文——只有聚合数字。
 */
object WeeklyCard {

    private const val W = 1080
    private const val H = 1440

    data class Data(
        val filtered: Int,
        val total: Int,
        val score: Int,
        val topApp: String,
        val title: String,
        val subtitle: String,
        val footer: String
    )

    fun render(data: Data): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(0f, 0f, 0f, H.toFloat(),
            intArrayOf(0xFF1B3A6B.toInt(), 0xFF0E203F.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        val card = Paint(Paint.ANTI_ALIAS_FLAG)
        card.color = Color.parseColor("#FFFFFF")
        c.drawRoundRect(RectF(72f, 180f, W - 72f, H - 220f), 48f, 48f, card)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG)
        accent.color = Color.parseColor("#7EA6E8")
        val dark = Paint(Paint.ANTI_ALIAS_FLAG)
        dark.color = Color.parseColor("#1A2A44")
        val muted = Paint(Paint.ANTI_ALIAS_FLAG)
        muted.color = Color.parseColor("#71809A")

        val titleP = Paint(dark).apply { textSize = 46f; typeface = Typeface.DEFAULT_BOLD }
        val subP = Paint(muted).apply { textSize = 26f }
        val bigP = Paint(accent).apply { textSize = 190f; typeface = Typeface.DEFAULT_BOLD }
        val bigLabel = Paint(dark).apply { textSize = 30f }
        val midP = Paint(dark).apply { textSize = 34f; typeface = Typeface.DEFAULT_BOLD }
        val smallP = Paint(muted).apply { textSize = 26f }
        val footP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B9C6DC"); textSize = 24f; textAlign = Paint.Align.CENTER
        }

        var y = 280f
        c.drawText(data.title, 130f, y, titleP)
        y += 48f
        c.drawText(data.subtitle, 130f, y, subP)
        y += 150f

        c.drawText(data.score.toString(), 130f, y + 120f, bigP)
        val scoreEnd = 130f + bigP.measureText(data.score.toString())
        val unit = Paint(accent).apply { textSize = 40f }
        c.drawText("分", scoreEnd + 16f, y + 120f, unit)
        c.drawText("清净指数", 130f, y + 172f, bigLabel)
        y += 230f

        c.drawLine(130f, y, W - 130f, y, Paint(muted).apply { color = Color.parseColor("#E4E9F2") })
        y += 80f

        c.drawText("${data.filtered}", 130f, y, midP)
        c.drawText("条骚扰被挡下", 130f + midP.measureText("${data.filtered}") + 16f, y, smallP)
        y += 76f

        val ratio = if (data.total > 0) data.filtered.toFloat() / data.total else 0f
        c.drawText("占全部通知的 ${(ratio * 100).toInt()}%", 130f, y, smallP)
        y += 76f

        val top = if (data.topApp.length > 12) data.topApp.take(12) + "…" else data.topApp
        c.drawText("最吵的应用：${top}", 130f, y, smallP)

        c.drawText(data.footer, (W / 2).toFloat(), H - 120f, footP)
        return bmp
    }

    /** 保存到应用私有目录，返回可供 FileProvider 分享的 Uri */
    fun saveAndShareUri(context: Context, bmp: Bitmap): Uri {
        val dir = File(context.cacheDir, "weekly").apply { mkdirs() }
        val file = File(dir, "qingjing_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())}.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareIntent(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

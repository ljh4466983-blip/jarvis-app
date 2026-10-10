package com.imagestory.jarvis.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.imagestory.jarvis.data.CallRecord
import com.imagestory.jarvis.data.RecordStore
import com.imagestory.jarvis.data.Times
import com.imagestory.jarvis.ui.MainActivity
import com.imagestory.jarvis.ui.ReviewActivity

object Notifier {
    const val CH_SERVICE = "service"
    const val CH_REVIEW = "review"
    const val CH_BRIEF = "briefing"
    const val ID_ONGOING = 1
    const val ID_BRIEF = 2
    private val ICON = android.R.drawable.ic_menu_my_calendar

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "상시 감시", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_REVIEW, "일정 확인 요청", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_BRIEF, "오늘 일정 브리핑", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun idleText(ctx: Context): String {
        val n = RecordStore.pendingCount(ctx)
        return if (n > 0) "통화 녹음 감시 중 · 확인 대기 ${n}건" else "통화 녹음 감시 중"
    }

    private fun mainIntent(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun reviewIntent(ctx: Context, id: String) = PendingIntent.getActivity(
        ctx, id.hashCode(), ReviewActivity.intent(ctx, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun ongoing(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(ICON)
            .setContentTitle("자비스")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(mainIntent(ctx))
            .build()

    fun updateOngoing(ctx: Context, text: String) = post(ctx, ID_ONGOING, ongoing(ctx, text))

    fun review(ctx: Context, r: CallRecord) {
        val lines = r.events.joinToString("\n") { "• ${Times.range(it)} ${it.title}" }
        val n = NotificationCompat.Builder(ctx, CH_REVIEW)
            .setSmallIcon(ICON)
            .setContentTitle("일정 ${r.events.size}건 확인해 주세요 · ${r.contact.ifBlank { "통화" }}")
            .setContentText(r.events.firstOrNull()?.let { "${Times.range(it)} ${it.title}" } ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$lines\n\n눌러서 확인·수정 후 승인하면 캘린더에 등록됩니다."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(reviewIntent(ctx, r.id))
            .build()
        post(ctx, r.id.hashCode(), n)
    }

    fun info(ctx: Context, id: Int, title: String, text: String, recordId: String? = null) {
        val n = NotificationCompat.Builder(ctx, CH_BRIEF)
            .setSmallIcon(ICON)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(recordId?.let { reviewIntent(ctx, it) } ?: mainIntent(ctx))
            .build()
        post(ctx, id, n)
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        runCatching { nm.notify(id, n) }
    }
}

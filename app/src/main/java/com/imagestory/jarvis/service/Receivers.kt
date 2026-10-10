package com.imagestory.jarvis.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.imagestory.jarvis.data.CalendarRepo
import com.imagestory.jarvis.data.Prefs
import com.imagestory.jarvis.data.RecordStore
import com.imagestory.jarvis.data.Times
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 재부팅·앱 업데이트 후 감시 서비스 자동 시작 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs(ctx).serviceEnabled) runCatching { WatcherService.start(ctx) }
        Briefing.schedule(ctx)
    }
}

/** 매일 아침 오늘 일정·겹치는 일정·확인 대기 건수 알림 */
class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { Briefing.post(ctx) } finally { pending.finish() }
        }.start()
    }
}

object Briefing {
    fun schedule(ctx: Context) {
        val hour = Prefs(ctx).briefingHour
        var next = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour, 0))
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1)
        val pi = PendingIntent.getBroadcast(
            ctx, 100, Intent(ctx, BriefingReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        ctx.getSystemService(AlarmManager::class.java)
            .setInexactRepeating(AlarmManager.RTC_WAKEUP, Times.ms(next), AlarmManager.INTERVAL_DAY, pi)
    }

    fun post(ctx: Context) {
        val today = LocalDate.now()
        val events = CalendarRepo.events(ctx, Times.ms(today.atStartOfDay()), Times.ms(today.plusDays(1).atStartOfDay()))
        val overlaps = CalendarRepo.overlaps(events)
        val waiting = RecordStore.pendingCount(ctx)
        val text = buildString {
            if (events.isEmpty()) appendLine("오늘 등록된 일정이 없습니다.")
            events.forEach { appendLine(if (it.allDay) "• 종일 ${it.title}" else "• ${Times.time(it.begin)} ${it.title}") }
            if (overlaps.isNotEmpty()) {
                appendLine()
                overlaps.forEach { (a, b) -> appendLine("⚠ 시간 겹침: ${a.title} ↔ ${b.title}") }
            }
            if (waiting > 0) { appendLine(); append("통화에서 찾은 일정 ${waiting}건이 확인을 기다립니다.") }
        }.trim()
        Notifier.info(ctx, Notifier.ID_BRIEF, "오늘 일정 ${events.size}건" + if (overlaps.isNotEmpty()) " · 겹침 ${overlaps.size}" else "", text)
    }
}

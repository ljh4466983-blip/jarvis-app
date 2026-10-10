package com.imagestory.jarvis.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 날짜·시간 표시와 초안 편집 계산 */
object Times {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dateFmt = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm", Locale.KOREAN)

    fun ldt(ms: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
    fun ms(t: LocalDateTime): Long = t.atZone(zone).toInstant().toEpochMilli()

    fun date(ms: Long): String = ldt(ms).format(dateFmt)
    fun time(ms: Long): String = ldt(ms).format(timeFmt)
    fun full(ms: Long): String = ldt(ms).format(fullFmt)

    /** "10월 14일 (화) 15:00–16:00" / "10월 14일 (화) 종일" */
    fun range(d: EventDraft): String =
        if (d.allDay) {
            val lastDay = ldt(d.endMillis - 1)
            if (lastDay.toLocalDate() == ldt(d.startMillis).toLocalDate()) "${date(d.startMillis)} 종일"
            else "${date(d.startMillis)} ~ ${date(d.endMillis - 1)} 종일"
        } else "${date(d.startMillis)} ${time(d.startMillis)}–${time(d.endMillis)}"

    fun eventLine(e: CalEvent): String =
        if (e.allDay) "${date(e.begin)} 종일 · ${e.title}" else "${date(e.begin)} ${time(e.begin)}–${time(e.end)} · ${e.title}"

    fun withDate(d: EventDraft, date: LocalDate): EventDraft {
        val dur = d.endMillis - d.startMillis
        val s = ms(LocalDateTime.of(date, ldt(d.startMillis).toLocalTime()))
        return d.copy(startMillis = s, endMillis = s + dur)
    }

    fun withStart(d: EventDraft, t: LocalTime): EventDraft {
        val dur = d.endMillis - d.startMillis
        val s = ms(LocalDateTime.of(ldt(d.startMillis).toLocalDate(), t))
        return d.copy(startMillis = s, endMillis = s + dur)
    }

    fun withEnd(d: EventDraft, t: LocalTime): EventDraft {
        var e = ms(LocalDateTime.of(ldt(d.startMillis).toLocalDate(), t))
        if (e <= d.startMillis) e += 86_400_000L // 자정 넘김
        return d.copy(endMillis = e)
    }

    fun withAllDay(d: EventDraft, allDay: Boolean): EventDraft {
        val day = ldt(d.startMillis).toLocalDate()
        return if (allDay) {
            val s = ms(day.atStartOfDay())
            d.copy(allDay = true, startMillis = s, endMillis = ms(day.plusDays(1).atStartOfDay()))
        } else {
            val s = ms(LocalDateTime.of(day, LocalTime.of(10, 0)))
            d.copy(allDay = false, startMillis = s, endMillis = s + 3_600_000L)
        }
    }
}

package com.imagestory.jarvis.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

data class CalInfo(val id: Long, val name: String, val account: String)
data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean)

/**
 * 휴대폰에 로그인된 구글 계정 캘린더를 Android 캘린더 저장소로 읽고 쓴다.
 * 여기에 넣은 일정은 휴대폰의 구글 계정 동기화로 Google Calendar에 올라간다(별도 OAuth 불필요).
 */
object CalendarRepo {

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 쓰기 가능한 캘린더 목록. 구글 계정 캘린더를 앞에 둔다. */
    fun calendars(ctx: Context): List<CalInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<Pair<CalInfo, Boolean>>()
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
            ),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalInfo(c.getLong(0), c.getString(1) ?: "(이름 없음)", c.getString(2) ?: "") to
                    (c.getString(3) == "com.google")
            }
        }
        return out.sortedByDescending { it.second }.map { it.first }
    }

    /** 기간 내 모든 보이는 캘린더의 일정(반복 일정 펼침 포함) */
    fun events(ctx: Context, begin: Long, end: Long): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
        }.build()
        val out = mutableListOf<CalEvent>()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                var b = c.getLong(1); var e = c.getLong(2)
                if (allDay) { b = utcMidnightToLocal(b); e = utcMidnightToLocal(e) } // 종일 일정은 UTC 자정 기준
                out += CalEvent(c.getString(0) ?: "(제목 없음)", b, e, allDay)
            }
        }
        return out
    }

    /** 초안과 시간이 겹치는 기존 일정 */
    fun conflicts(ctx: Context, d: EventDraft): List<CalEvent> =
        events(ctx, d.startMillis - 86_400_000L, d.endMillis + 86_400_000L).filter {
            it.begin < d.endMillis && it.end > d.startMillis && (d.allDay || !it.allDay)
        }

    /** 기간 내 서로 겹치는 일정 쌍 (브리핑용) */
    fun overlaps(list: List<CalEvent>): List<Pair<CalEvent, CalEvent>> {
        val timed = list.filter { !it.allDay }
        val out = mutableListOf<Pair<CalEvent, CalEvent>>()
        for (i in timed.indices) for (j in i + 1 until timed.size) {
            val a = timed[i]; val b = timed[j]
            if (a.begin < b.end && b.begin < a.end) out += a to b
        }
        return out
    }

    /** 일정 등록. 성공하면 이벤트 ID */
    fun insert(ctx: Context, calendarId: Long, d: EventDraft, reminderMin: Int): Long? {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, d.title)
            put(CalendarContract.Events.DESCRIPTION, d.description)
            put(CalendarContract.Events.EVENT_LOCATION, d.location)
            if (d.allDay) {
                val s = localMidnightToUtc(d.startMillis)
                val e = maxOf(localMidnightToUtc(d.endMillis), s + 86_400_000L)
                put(CalendarContract.Events.DTSTART, s)
                put(CalendarContract.Events.DTEND, e)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, d.startMillis)
                put(CalendarContract.Events.DTEND, d.endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return null
        val id = ContentUris.parseId(uri)
        if (reminderMin >= 0) {
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, if (d.allDay) 9 * 60 else reminderMin) // 종일: 전날 15시
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    private fun localMidnightToUtc(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun utcMidnightToLocal(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

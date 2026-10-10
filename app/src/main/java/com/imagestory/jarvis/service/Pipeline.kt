package com.imagestory.jarvis.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.imagestory.jarvis.ai.ScheduleExtractor
import com.imagestory.jarvis.ai.SpeechToText
import com.imagestory.jarvis.data.CalendarRepo
import com.imagestory.jarvis.data.CallRecord
import com.imagestory.jarvis.data.Prefs
import com.imagestory.jarvis.data.RecordStore
import com.imagestory.jarvis.data.Status
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 휴대폰에 새로 생긴 통화녹음 파일 찾기 (삼성 기본 통화녹음: 내장저장소/Recordings/Call) */
object Scanner {
    data class Candidate(val uri: Uri, val name: String, val addedSec: Long, val durationMs: Long)

    fun findNew(ctx: Context, prefs: Prefs): List<Candidate> {
        val keys = prefs.keywordList()
        val out = mutableListOf<Candidate>()
        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
        )
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Audio.Media.DATE_ADDED} > ?", arrayOf(prefs.baselineSec.toString()),
            "${MediaStore.Audio.Media.DATE_ADDED} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val path = c.getString(2) ?: ""
                val haystack = "$path/$name"
                if (keys.none { haystack.contains(it, ignoreCase = true) }) continue
                val dur = c.getLong(4)
                if (dur in 1 until 10_000) continue // 10초 미만 통화는 건너뜀
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                if (prefs.isProcessed(uri.toString())) continue
                out += Candidate(uri, name, c.getLong(3), dur)
            }
        }
        return out
    }
}

/** 파일명에서 통화 상대·시각 알아내기. 예) "통화 녹음 홍길동_241011_143022.m4a" */
object FileNameParser {
    private val tail = Regex("""[_\s-](\d{6})[_\s-](\d{6})$""")
    private val prefixes = listOf("통화 녹음 ", "통화녹음 ", "통화녹음_", "Call recording ", "Call_", "call_")

    fun parse(fileName: String): Pair<String, Long?> {
        var base = fileName.substringBeforeLast('.')
        var time: Long? = null
        tail.find(base)?.let { m ->
            time = runCatching {
                com.imagestory.jarvis.data.Times.ms(
                    LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                )
            }.getOrNull()
            base = base.substring(0, m.range.first)
        }
        prefixes.firstOrNull { base.startsWith(it) }?.let { base = base.removePrefix(it) }
        return base.trim() to time
    }
}

/** 녹음 1건 처리: 복사 → 음성인식 → 일정 추출 → 저장 → 확인 알림 */
object Pipeline {
    private val lock = Mutex()

    suspend fun process(
        ctx: Context,
        uri: Uri,
        fileName: String,
        fallbackTime: Long,
        existingId: String? = null,
    ) = lock.withLock {
        val prefs = Prefs(ctx)
        val (contact, nameTime) = FileNameParser.parse(fileName)
        var rec = CallRecord(
            id = existingId ?: UUID.randomUUID().toString(),
            sourceUri = uri.toString(),
            fileName = fileName,
            contact = contact,
            callTime = nameTime ?: fallbackTime,
            status = Status.PROCESSING,
        )
        RecordStore.save(ctx, rec)
        Notifier.updateOngoing(ctx, "분석 중: ${contact.ifBlank { fileName }}")

        val ext = fileName.substringAfterLast('.', "m4a")
        val tmp = File(ctx.cacheDir, "rec_" + rec.id + "." + ext)
        try {
            ctx.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "녹음 파일을 열 수 없습니다" }
                tmp.outputStream().use { input.copyTo(it) }
            }
            val transcript = SpeechToText.transcribe(prefs, tmp)
            rec = rec.copy(transcript = transcript)
            RecordStore.save(ctx, rec)

            val existing = CalendarRepo.events(ctx, rec.callTime - 86_400_000L, rec.callTime + 14 * 86_400_000L)
            val r = ScheduleExtractor.extract(prefs, transcript, contact, rec.callTime, existing)
            rec = rec.copy(
                summary = r.summary, todos = r.todos, events = r.events,
                status = if (r.events.isEmpty()) Status.NO_EVENT else Status.REVIEW,
            )
            RecordStore.save(ctx, rec)
            if (rec.status == Status.REVIEW) Notifier.review(ctx, rec)
            else Notifier.info(ctx, rec.id.hashCode(), "${rec.contact.ifBlank { "통화" }} · 일정 없음", rec.summary.ifBlank { "통화 내용을 정리해 두었습니다." }, rec.id)
        } catch (t: Throwable) {
            rec = rec.copy(status = Status.ERROR, error = t.message ?: t.javaClass.simpleName)
            RecordStore.save(ctx, rec)
            Notifier.info(ctx, rec.id.hashCode(), "통화 분석 실패", rec.error, rec.id)
        } finally {
            tmp.delete()
            Notifier.updateOngoing(ctx, Notifier.idleText(ctx))
        }
    }
}

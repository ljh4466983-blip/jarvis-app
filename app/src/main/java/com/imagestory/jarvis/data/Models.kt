package com.imagestory.jarvis.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Status(val label: String) {
    PROCESSING("처리 중"),
    REVIEW("확인 대기"),
    NO_EVENT("일정 없음"),
    APPROVED("등록 완료"),
    REJECTED("거절함"),
    ERROR("오류"),
}

/** 캘린더에 넣기 전, 사용자가 확인·수정하는 일정 초안 */
data class EventDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String = "",
    val description: String = "",
    val confidence: Double = 1.0,
    val evidence: String = "",
    val ambiguity: String = "",
    val include: Boolean = true,
    val calendarEventId: Long? = null,
) {
    fun toJson() = JSONObject().apply {
        put("title", title); put("start", startMillis); put("end", endMillis); put("allDay", allDay)
        put("location", location); put("description", description); put("confidence", confidence)
        put("evidence", evidence); put("ambiguity", ambiguity); put("include", include)
        calendarEventId?.let { put("calEventId", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = EventDraft(
            title = o.optString("title"),
            startMillis = o.optLong("start"),
            endMillis = o.optLong("end"),
            allDay = o.optBoolean("allDay"),
            location = o.optString("location"),
            description = o.optString("description"),
            confidence = o.optDouble("confidence", 1.0),
            evidence = o.optString("evidence"),
            ambiguity = o.optString("ambiguity"),
            include = o.optBoolean("include", true),
            calendarEventId = if (o.has("calEventId")) o.getLong("calEventId") else null,
        )
    }
}

data class CallRecord(
    val id: String,
    val sourceUri: String,
    val fileName: String,
    val contact: String,
    val callTime: Long,
    val status: Status,
    val transcript: String = "",
    val summary: String = "",
    val todos: List<String> = emptyList(),
    val events: List<EventDraft> = emptyList(),
    val error: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("sourceUri", sourceUri); put("fileName", fileName); put("contact", contact)
        put("callTime", callTime); put("status", status.name); put("transcript", transcript)
        put("summary", summary); put("todos", JSONArray(todos)); put("error", error); put("updatedAt", updatedAt)
        put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): CallRecord {
            val todos = o.optJSONArray("todos")
            val evs = o.optJSONArray("events")
            return CallRecord(
                id = o.getString("id"),
                sourceUri = o.optString("sourceUri"),
                fileName = o.optString("fileName"),
                contact = o.optString("contact"),
                callTime = o.optLong("callTime"),
                status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.ERROR),
                transcript = o.optString("transcript"),
                summary = o.optString("summary"),
                todos = if (todos == null) emptyList() else List(todos.length()) { todos.getString(it) },
                events = if (evs == null) emptyList() else List(evs.length()) { EventDraft.fromJson(evs.getJSONObject(it)) },
                error = o.optString("error"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }
}

/** 통화 기록 저장소: 앱 내부 폴더 files/calls/{id}.json */
object RecordStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "calls").apply { mkdirs() }

    @Synchronized
    fun save(ctx: Context, r: CallRecord) {
        val rec = r.copy(updatedAt = System.currentTimeMillis())
        val tmp = File(dir(ctx), "${rec.id}.tmp")
        tmp.writeText(rec.toJson().toString())
        tmp.renameTo(File(dir(ctx), "${rec.id}.json"))
    }

    fun load(ctx: Context, id: String): CallRecord? = runCatching {
        CallRecord.fromJson(JSONObject(File(dir(ctx), "$id.json").readText()))
    }.getOrNull()

    fun all(ctx: Context): List<CallRecord> =
        dir(ctx).listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { CallRecord.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.callTime }

    fun pendingCount(ctx: Context) = all(ctx).count { it.status == Status.REVIEW }

    /**
     * 통화 기록(녹취록·요약·일정 초안) 삭제.
     * 휴대폰의 원본 통화녹음 파일과 이미 캘린더에 등록한 일정은 지우지 않는다.
     * 처리 완료 표시는 남겨 두어 같은 녹음이 다시 자동 분석되지 않게 한다.
     */
    @Synchronized
    fun delete(ctx: Context, id: String): Boolean {
        File(dir(ctx), "$id.tmp").delete()
        return File(dir(ctx), "$id.json").delete()
    }

    /** 분석 중인 건을 뺀 모든 통화 기록 삭제. 삭제한 건수 반환 */
    fun deleteAll(ctx: Context): Int =
        all(ctx).filter { it.status != Status.PROCESSING }.count { delete(ctx, it.id) }
}

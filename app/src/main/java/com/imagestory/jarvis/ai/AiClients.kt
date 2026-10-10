package com.imagestory.jarvis.ai

import com.imagestory.jarvis.data.CalEvent
import com.imagestory.jarvis.data.EventDraft
import com.imagestory.jarvis.data.Prefs
import com.imagestory.jarvis.data.Times
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class AiException(msg: String) : Exception(msg)

private val http = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(5, TimeUnit.MINUTES)
    .build()

/** 1단계: 통화 녹음 → 텍스트 (OpenAI 음성인식) */
object SpeechToText {
    private const val MAX_BYTES = 25L * 1024 * 1024

    fun transcribe(prefs: Prefs, file: File): String {
        if (prefs.openAiKey.isBlank()) throw AiException("OpenAI API 키가 설정되지 않았습니다 (설정 > OpenAI 키)")
        if (file.length() > MAX_BYTES) throw AiException("녹음 파일이 25MB를 넘어 음성인식 서버로 보낼 수 없습니다")
        val mime = when (file.extension.lowercase()) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", prefs.sttModel)
            .addFormDataPart("language", "ko")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("prompt", "업무 통화 녹음입니다. 날짜, 요일, 시간, 장소, 미팅, 방문, 마감, 납품 이야기가 나옵니다.")
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .build()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${prefs.openAiKey}")
            .post(body).build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("음성인식 실패 (${res.code}): ${text.take(300)}")
            return JSONObject(text).optString("text").trim()
        }
    }
}

/** 2단계: 녹취록 → 요약·할 일·일정 초안 (Claude) */
object ScheduleExtractor {

    data class Result(val summary: String, val todos: List<String>, val events: List<EventDraft>)

    private val SYSTEM = """
너는 사용자의 통화 비서 '자비스'다. 통화 녹취록을 읽고 사용자의 캘린더에 넣을 일정을 뽑는다.

규칙
1. 통화에서 실제로 합의됐거나 사용자가 하기로 정해진 일정만 뽑는다 (미팅, 방문, 다시 전화하기로 한 약속, 마감, 납품, 결제일, 행사 등). 제안만 하고 확정되지 않은 것은 confidence를 0.5 미만으로 두고 ambiguity에 이유를 쓴다.
2. "내일", "모레", "다음 주 화요일", "이번 달 말" 같은 표현은 '통화 시각'을 기준으로 실제 날짜로 바꾼다. "다음 주"는 통화일이 속한 주(월~일)의 다음 주다.
3. 시각이 없으면 all_day=true, start_time=null로 두고 ambiguity에 "시간 미정"이라고 쓴다. "3시쯤"처럼 대략적이면 그 시각을 쓰고 ambiguity에 적는다. 오전/오후가 불분명하면 업무 시간(09~19시)에 맞는 쪽으로 정하고 ambiguity에 적는다. "오후"만 있으면 14:00으로 두고 적는다.
4. 끝 시각이 언급되지 않으면 end_time은 null.
5. 녹취록은 음성인식 결과라 잘못 들린 말이 있다. 맥락으로 바로잡되 확신이 없으면 ambiguity에 적는다. 녹취록에 없는 내용은 지어내지 않는다.
6. 같은 일정이 여러 번 바뀌어 언급되면 마지막으로 합의된 내용 하나만 남긴다. 취소된 일정은 넣지 않는다.
7. '기존 일정' 목록에 있는 일정을 옮기거나 바꾸는 이야기라면 새 일정으로 뽑고 ambiguity에 "기존 일정 '제목' 변경으로 보임"이라고 쓴다.
8. title은 "[상대 이름 또는 회사] 핵심 내용" 형식으로 20자 안팎. description에는 준비물, 금액, 수량, 연락처 등 통화에서 나온 세부 사항을 적는다. location은 언급된 장소만.
9. evidence에는 근거가 된 녹취록 문장을 짧게 그대로 옮긴다.
10. 일정은 아니지만 사용자가 해야 할 일(자료 보내기, 견적 확인 등, 날짜 없음)은 todos에 넣는다.

반드시 아래 형식의 JSON 하나만 출력한다. 설명이나 코드블록 표시는 쓰지 않는다.
{"summary":"통화 요약 2~4문장","todos":["..."],"events":[{"title":"","date":"YYYY-MM-DD","start_time":"HH:mm 또는 null","end_time":"HH:mm 또는 null","all_day":false,"location":"","description":"","confidence":0.0,"evidence":"","ambiguity":""}]}
""".trimIndent()

    fun extract(
        prefs: Prefs,
        transcript: String,
        contact: String,
        callTime: Long,
        existing: List<CalEvent>,
    ): Result {
        if (prefs.anthropicKey.isBlank()) throw AiException("Anthropic(Claude) API 키가 설정되지 않았습니다 (설정 > Claude 키)")
        if (transcript.isBlank()) return Result("녹취 내용이 비어 있습니다.", emptyList(), emptyList())

        val user = buildString {
            appendLine("통화 상대: ${contact.ifBlank { "알 수 없음" }}")
            appendLine("통화 시각: ${Times.full(callTime)} (${java.util.TimeZone.getDefault().id})")
            appendLine()
            appendLine("기존 일정 (참고용, 앞으로 2주):")
            if (existing.isEmpty()) appendLine("- 없음")
            existing.take(40).forEach { appendLine("- ${Times.eventLine(it)}") }
            appendLine()
            appendLine("[녹취록]")
            append(transcript)
        }
        val body = JSONObject().apply {
            put("model", prefs.claudeModel)
            put("max_tokens", 4000)
            put("system", SYSTEM)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        }
        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", prefs.anthropicKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw AiException("일정 분석 실패 (${res.code}): ${raw.take(300)}")
            val content = JSONObject(raw).optJSONArray("content") ?: JSONArray()
            (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }
                .joinToString("") { it.optString("text") }
        }
        return parse(text)
    }

    /** 모델 응답(JSON) → 초안. 날짜·시간이 이상한 항목은 버리지 않고 종일 일정 + 경고로 살린다. */
    fun parse(text: String): Result {
        val s = text.indexOf('{'); val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) throw AiException("일정 분석 결과를 읽을 수 없습니다: ${text.take(200)}")
        val o = JSONObject(text.substring(s, e + 1))
        val todosArr = o.optJSONArray("todos") ?: JSONArray()
        val evArr = o.optJSONArray("events") ?: JSONArray()
        val events = (0 until evArr.length()).mapNotNull { i ->
            val ev = evArr.optJSONObject(i) ?: return@mapNotNull null
            val notes = mutableListOf<String>()
            ev.optString("ambiguity").takeIf { it.isNotBlank() && it != "null" }?.let { notes += it }
            val date = runCatching { LocalDate.parse(ev.optString("date")) }.getOrNull()
                ?: run { notes += "날짜를 알아내지 못함 — 직접 지정하세요"; LocalDate.now().plusDays(1) }
            val st = timeOrNull(ev, "start_time")
            val et = timeOrNull(ev, "end_time")
            val allDay = ev.optBoolean("all_day", false) || st == null
            val (startMs, endMs) = if (allDay) {
                Times.ms(date.atStartOfDay()) to Times.ms(date.plusDays(1).atStartOfDay())
            } else {
                val sMs = Times.ms(LocalDateTime.of(date, st))
                var eMs = if (et != null) Times.ms(LocalDateTime.of(date, et)) else sMs + 3_600_000L
                if (eMs <= sMs) eMs = sMs + 3_600_000L
                sMs to eMs
            }
            EventDraft(
                title = ev.optString("title").ifBlank { "통화 약속" },
                startMillis = startMs,
                endMillis = endMs,
                allDay = allDay,
                location = clean(ev.optString("location")),
                description = clean(ev.optString("description")),
                confidence = ev.optDouble("confidence", 0.7).coerceIn(0.0, 1.0),
                evidence = clean(ev.optString("evidence")),
                ambiguity = notes.joinToString(" / "),
                include = ev.optDouble("confidence", 0.7) >= 0.5,
            )
        }
        return Result(
            summary = clean(o.optString("summary")),
            todos = (0 until todosArr.length()).map { todosArr.optString(it) }.filter { it.isNotBlank() },
            events = events,
        )
    }

    private fun clean(s: String) = if (s == "null") "" else s.trim()

    private fun timeOrNull(o: JSONObject, key: String): LocalTime? {
        if (o.isNull(key)) return null
        val v = o.optString(key).trim()
        if (v.isBlank() || v == "null") return null
        return runCatching { LocalTime.parse(if (v.length == 4) "0$v" else v) }.getOrNull()
    }
}

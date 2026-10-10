package com.imagestory.jarvis.data

import android.content.Context

/** 앱 설정. 휴대폰 내부 전용 저장소(MODE_PRIVATE)에만 저장된다. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = sp.getString(key, def) ?: def
    private fun putStr(key: String, v: String) = sp.edit().putString(key, v.trim()).apply()

    var openAiKey: String
        get() = str("openai_key"); set(v) = putStr("openai_key", v)
    var anthropicKey: String
        get() = str("anthropic_key"); set(v) = putStr("anthropic_key", v)
    var claudeModel: String
        get() = str("claude_model", "claude-sonnet-5-5"); set(v) = putStr("claude_model", v)
    var sttModel: String
        get() = str("stt_model", "whisper-1"); set(v) = putStr("stt_model", v)
    /** 통화녹음 파일을 알아보는 단어(폴더명/파일명). 쉼표로 구분 */
    var keywords: String
        get() = str("keywords", "Call,통화"); set(v) = putStr("keywords", v)
    var calendarId: Long
        get() = sp.getLong("calendar_id", -1); set(v) = sp.edit().putLong("calendar_id", v).apply()
    var reminderMinutes: Int
        get() = sp.getInt("reminder_min", 30); set(v) = sp.edit().putInt("reminder_min", v).apply()
    var briefingHour: Int
        get() = sp.getInt("briefing_hour", 8); set(v) = sp.edit().putInt("briefing_hour", v).apply()
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", true); set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    /** 이 시각(초) 이전에 생긴 녹음은 처리하지 않는다 → 설치 전 과거 녹음 일괄 처리 방지 */
    val baselineSec: Long
        get() {
            var b = sp.getLong("baseline_sec", 0)
            if (b == 0L) {
                b = System.currentTimeMillis() / 1000
                sp.edit().putLong("baseline_sec", b).apply()
            }
            return b
        }

    fun isProcessed(key: String) = sp.getStringSet("processed", emptySet())!!.contains(key)

    @Synchronized
    fun markProcessed(key: String) {
        val set = HashSet(sp.getStringSet("processed", emptySet())!!)
        set.add(key)
        sp.edit().putStringSet("processed", set).apply()
    }

    fun keywordList() = keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

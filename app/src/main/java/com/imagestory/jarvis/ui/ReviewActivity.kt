package com.imagestory.jarvis.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.imagestory.jarvis.data.CalendarRepo
import com.imagestory.jarvis.data.CallRecord
import com.imagestory.jarvis.data.EventDraft
import com.imagestory.jarvis.data.Prefs
import com.imagestory.jarvis.data.RecordStore
import com.imagestory.jarvis.data.Status
import com.imagestory.jarvis.data.Times
import com.imagestory.jarvis.service.Notifier
import com.imagestory.jarvis.service.WatcherService
import java.time.LocalDate
import java.time.LocalTime

class ReviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        setContent { JarvisTheme { ReviewScreen(id) { finish() } } }
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(ctx: Context, id: String) = Intent(ctx, ReviewActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(id: String, close: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val rec = remember { RecordStore.load(ctx, id) }
    if (rec == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val drafts = remember { mutableStateListOf(*rec.events.toTypedArray()) }
    val calendars = remember { CalendarRepo.calendars(ctx) }
    val calName = calendars.firstOrNull { it.id == prefs.calendarId }?.let { "${it.name} (${it.account})" }
    val editable = rec.status == Status.REVIEW || rec.status == Status.NO_EVENT
    var showTranscript by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(rec.contact.ifBlank { "통화" } + " · 일정 확인") }) },
        bottomBar = {
            if (editable) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            RecordStore.save(ctx, rec.copy(status = Status.REJECTED, events = drafts.toList()))
                            Notifier.updateOngoing(ctx, Notifier.idleText(ctx))
                            close()
                        }, modifier = Modifier.weight(1f)) { Text("거절") }
                        Button(
                            enabled = drafts.any { it.include },
                            onClick = { approve(ctx, prefs, rec, drafts.toList(), close) },
                            modifier = Modifier.weight(2f),
                        ) { Text("승인 · 캘린더 등록 (${drafts.count { it.include }}건)") }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Times.full(rec.callTime), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        StatusChip(rec.status)
                    }
                    if (rec.summary.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("통화 요약", fontWeight = FontWeight.Bold)
                        Text(rec.summary)
                    }
                    if (rec.todos.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("할 일 (일정 아님)", fontWeight = FontWeight.Bold)
                        rec.todos.forEach { Text("• $it") }
                    }
                }
            }

            if (rec.status == Status.ERROR) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("분석 실패", fontWeight = FontWeight.Bold)
                        Text(rec.error)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            WatcherService.process(ctx, Uri.parse(rec.sourceUri), rec.fileName, rec.callTime, rec.id)
                            Toast.makeText(ctx, "다시 분석합니다", Toast.LENGTH_SHORT).show()
                            close()
                        }) { Text("다시 분석") }
                    }
                }
            }

            // ── 저장 미리보기: "이렇게 저장합니다"
            if (drafts.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (editable) "이렇게 저장합니다" else "저장된 내용", fontWeight = FontWeight.Bold)
                        Text("캘린더: ${calName ?: "선택 안 됨 — 메인 화면 설정에서 고르세요"}", style = MaterialTheme.typography.bodySmall)
                        drafts.filter { it.include }.forEach { d ->
                            Text("• ${Times.range(d)}  ${d.title}" + if (d.location.isNotBlank()) "  @${d.location}" else "")
                        }
                        if (drafts.none { it.include }) Text("선택된 일정이 없습니다.")
                        if (editable) Text("틀린 부분은 아래에서 고친 뒤 승인하세요.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            itemsIndexed(drafts) { i, d ->
                DraftEditor(i + 1, d, editable) { drafts[i] = it }
            }

            if (editable) item {
                OutlinedButton(onClick = {
                    val day = Times.ldt(rec.callTime).toLocalDate().plusDays(1)
                    val s = Times.ms(day.atTime(10, 0))
                    drafts.add(EventDraft("[${rec.contact}] ", s, s + 3_600_000L, false, confidence = 1.0))
                }, modifier = Modifier.fillMaxWidth()) { Text("+ 일정 직접 추가") }
            }

            if (rec.transcript.isNotBlank()) item {
                Column {
                    TextButton(onClick = { showTranscript = !showTranscript }) {
                        Text(if (showTranscript) "녹취록 접기" else "녹취록 보기")
                    }
                    if (showTranscript) Text(rec.transcript, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DraftEditor(no: Int, d: EventDraft, editable: Boolean, onChange: (EventDraft) -> Unit) {
    val ctx = LocalContext.current
    val conflicts = remember(d.startMillis, d.endMillis, d.allDay) { CalendarRepo.conflicts(ctx, d) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(d.include, onCheckedChange = { onChange(d.copy(include = it)) }, enabled = editable)
                Text("일정 $no", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (d.calendarEventId != null) Text("등록됨 ✅", style = MaterialTheme.typography.labelMedium)
                else Text("확신 ${(d.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelMedium,
                    color = if (d.confidence < 0.5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
            }
            OutlinedTextField(d.title, { onChange(d.copy(title = it)) }, label = { Text("제목") },
                enabled = editable, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PickChip(Times.date(d.startMillis), editable) {
                    val t = Times.ldt(d.startMillis)
                    DatePickerDialog(ctx, { _, y, m, day -> onChange(Times.withDate(d, LocalDate.of(y, m + 1, day))) },
                        t.year, t.monthValue - 1, t.dayOfMonth).show()
                }
                Spacer(Modifier.weight(1f))
                Text("종일")
                Switch(d.allDay, onCheckedChange = { onChange(Times.withAllDay(d, it)) }, enabled = editable)
            }
            if (!d.allDay) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PickChip("시작 ${Times.time(d.startMillis)}", editable) {
                    val t = Times.ldt(d.startMillis)
                    TimePickerDialog(ctx, { _, h, mi -> onChange(Times.withStart(d, LocalTime.of(h, mi))) }, t.hour, t.minute, true).show()
                }
                PickChip("끝 ${Times.time(d.endMillis)}", editable) {
                    val t = Times.ldt(d.endMillis)
                    TimePickerDialog(ctx, { _, h, mi -> onChange(Times.withEnd(d, LocalTime.of(h, mi))) }, t.hour, t.minute, true).show()
                }
            }
            OutlinedTextField(d.location, { onChange(d.copy(location = it)) }, label = { Text("장소") },
                enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(d.description, { onChange(d.copy(description = it)) }, label = { Text("메모") },
                enabled = editable, modifier = Modifier.fillMaxWidth())

            if (d.evidence.isNotBlank())
                Text("근거: “${d.evidence}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            if (d.ambiguity.isNotBlank())
                Text("⚠ 확인 필요: ${d.ambiguity}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (d.calendarEventId == null && conflicts.isNotEmpty()) {
                Text("⚠ 겹치는 일정", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                conflicts.take(5).forEach {
                    Text("  · ${Times.eventLine(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PickChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, enabled = enabled)
}

private fun approve(ctx: Context, prefs: Prefs, rec: CallRecord, drafts: List<EventDraft>, close: () -> Unit) {
    if (!CalendarRepo.hasPermission(ctx)) {
        Toast.makeText(ctx, "캘린더 권한이 필요합니다. 메인 화면에서 허용하세요.", Toast.LENGTH_LONG).show(); return
    }
    val calId = prefs.calendarId
    if (calId < 0 || CalendarRepo.calendars(ctx).none { it.id == calId }) {
        Toast.makeText(ctx, "등록할 캘린더를 메인 화면 설정에서 골라 주세요.", Toast.LENGTH_LONG).show(); return
    }
    if (drafts.any { it.include && it.title.isBlank() }) {
        Toast.makeText(ctx, "제목이 비어 있는 일정이 있습니다.", Toast.LENGTH_SHORT).show(); return
    }
    var ok = 0; var fail = 0
    val result = drafts.map { d ->
        if (!d.include || d.calendarEventId != null) d
        else {
            val eventId = runCatching { CalendarRepo.insert(ctx, calId, d.copy(
                description = listOf(d.description, "— 자비스: ${rec.contact} 통화(${Times.full(rec.callTime)})에서 등록")
                    .filter { it.isNotBlank() }.joinToString("\n")
            ), prefs.reminderMinutes) }.getOrNull()
            if (eventId != null) ok++ else fail++
            d.copy(calendarEventId = eventId)
        }
    }
    RecordStore.save(ctx, rec.copy(events = result, status = if (fail == 0) Status.APPROVED else Status.REVIEW))
    Notifier.updateOngoing(ctx, Notifier.idleText(ctx))
    Toast.makeText(ctx, if (fail == 0) "${ok}건을 캘린더에 등록했습니다" else "${ok}건 등록, ${fail}건 실패", Toast.LENGTH_LONG).show()
    if (fail == 0) close()
}

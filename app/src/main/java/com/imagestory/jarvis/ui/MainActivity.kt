package com.imagestory.jarvis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.imagestory.jarvis.data.CalendarRepo
import com.imagestory.jarvis.data.CallRecord
import com.imagestory.jarvis.data.Prefs
import com.imagestory.jarvis.data.RecordStore
import com.imagestory.jarvis.data.Status
import com.imagestory.jarvis.data.Times
import com.imagestory.jarvis.service.Notifier
import com.imagestory.jarvis.service.WatcherService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs(this).serviceEnabled) runCatching { WatcherService.start(this) }
        setContent { JarvisTheme { MainScreen() } }
    }
}

private fun neededPermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
}.toTypedArray()

private fun allGranted(ctx: Context) = neededPermissions().all {
    ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun batteryFree(ctx: Context) =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
private fun askBattery(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    }
}

private fun displayName(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "녹음 파일"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }

    // 화면으로 돌아올 때마다 새로고침
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (prefs.serviceEnabled) runCatching { WatcherService.start(ctx) }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            WatcherService.process(ctx, uri, displayName(ctx, uri), System.currentTimeMillis())
            Toast.makeText(ctx, "분석을 시작했습니다. 끝나면 알림이 옵니다.", Toast.LENGTH_SHORT).show()
            tick++
        }
    }

    val records = remember(tick) { RecordStore.all(ctx) }
    val calendars = remember(tick) { CalendarRepo.calendars(ctx) }
    val upcoming = remember(tick) {
        val now = System.currentTimeMillis()
        CalendarRepo.events(ctx, now, now + 7 * 86_400_000L)
    }
    val overlapTitles = remember(upcoming) {
        CalendarRepo.overlaps(upcoming).flatMap { listOf(it.first, it.second) }.toSet()
    }
    // 처리 중인 건이 있으면 3초마다 갱신
    LaunchedEffect(records) {
        if (records.any { it.status == Status.PROCESSING }) { delay(3000); tick++ }
    }

    var serviceOn by remember { mutableStateOf(prefs.serviceEnabled) }
    var openAi by remember { mutableStateOf(prefs.openAiKey) }
    var claude by remember { mutableStateOf(prefs.anthropicKey) }
    var model by remember { mutableStateOf(prefs.claudeModel) }
    var stt by remember { mutableStateOf(prefs.sttModel) }
    var keywords by remember { mutableStateOf(prefs.keywords) }
    var reminder by remember { mutableStateOf(prefs.reminderMinutes.toString()) }
    var calId by remember { mutableLongStateOf(prefs.calendarId) }
    var showSettings by remember { mutableStateOf(prefs.openAiKey.isBlank() || prefs.anthropicKey.isBlank()) }

    var deleteTarget by remember { mutableStateOf<CallRecord?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    deleteTarget?.let { r ->
        DeleteDialog(
            title = "이 통화 기록을 삭제할까요?",
            target = "${r.contact.ifBlank { r.fileName }} · ${Times.full(r.callTime)}",
            onConfirm = {
                RecordStore.delete(ctx, r.id)
                Notifier.cancelRecord(ctx, r.id)
                Notifier.updateOngoing(ctx, Notifier.idleText(ctx))
                deleteTarget = null; tick++
            },
            onDismiss = { deleteTarget = null },
        )
    }
    if (confirmDeleteAll) {
        DeleteDialog(
            title = "통화 기록을 모두 삭제할까요?",
            target = "분석 중인 건을 뺀 ${records.count { it.status != Status.PROCESSING }}건",
            onConfirm = {
                records.filter { it.status != Status.PROCESSING }.forEach { Notifier.cancelRecord(ctx, it.id) }
                val n = RecordStore.deleteAll(ctx)
                Notifier.updateOngoing(ctx, Notifier.idleText(ctx))
                Toast.makeText(ctx, "${n}건을 삭제했습니다", Toast.LENGTH_SHORT).show()
                confirmDeleteAll = false; tick++
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }

    // 캘린더를 아직 안 골랐으면 첫 구글 캘린더 자동 선택
    LaunchedEffect(calendars) {
        if (calId < 0 && calendars.isNotEmpty()) { calId = calendars.first().id; prefs.calendarId = calId }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("자비스 · 통화 일정 비서") }) }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 상태
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("상시 감시", fontWeight = FontWeight.Bold)
                                Text(
                                    if (serviceOn) "통화가 끝나면 녹음을 자동 분석합니다" else "꺼져 있음",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(serviceOn, onCheckedChange = {
                                serviceOn = it; prefs.serviceEnabled = it
                                if (it) WatcherService.start(ctx) else WatcherService.stop(ctx)
                            })
                        }
                        CheckRow("권한 (캘린더·오디오·알림)", allGranted(ctx), "허용하기") { permLauncher.launch(neededPermissions()) }
                        CheckRow("배터리 최적화 제외 (꺼지지 않게)", batteryFree(ctx), "설정") { askBattery(ctx) }
                        CheckRow("API 키 입력", prefs.openAiKey.isNotBlank() && prefs.anthropicKey.isNotBlank(), "입력") { showSettings = true }
                        CheckRow("등록할 캘린더 선택", calId >= 0 && calendars.any { it.id == calId }, "선택") { showSettings = true }
                    }
                }
            }

            // ── 설정
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { showSettings = !showSettings }) {
                            Text("설정", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (showSettings) "접기" else "펼치기")
                        }
                        if (showSettings) {
                            OutlinedTextField(openAi, { openAi = it }, label = { Text("OpenAI API 키 (음성인식)") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(claude, { claude = it }, label = { Text("Anthropic API 키 (일정 분석)") },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(model, { model = it }, label = { Text("Claude 모델") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(stt, { stt = it }, label = { Text("음성인식 모델 (whisper-1 / gpt-4o-transcribe)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(keywords, { keywords = it }, label = { Text("통화녹음 폴더·파일 키워드 (쉼표 구분)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(reminder, { reminder = it.filter(Char::isDigit) }, label = { Text("일정 알림 (분 전)") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                            Text("등록할 캘린더", style = MaterialTheme.typography.labelLarge)
                            if (calendars.isEmpty()) Text("캘린더 권한을 허용하면 목록이 보입니다.", style = MaterialTheme.typography.bodySmall)
                            calendars.forEach { c ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { calId = c.id }) {
                                    RadioButton(calId == c.id, onClick = { calId = c.id })
                                    Column {
                                        Text(c.name)
                                        Text(c.account, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Button(onClick = {
                                prefs.openAiKey = openAi; prefs.anthropicKey = claude; prefs.claudeModel = model
                                prefs.sttModel = stt; prefs.keywords = keywords; prefs.calendarId = calId
                                prefs.reminderMinutes = reminder.toIntOrNull() ?: 30
                                Toast.makeText(ctx, "저장했습니다", Toast.LENGTH_SHORT).show()
                                showSettings = false; tick++
                            }, modifier = Modifier.fillMaxWidth()) { Text("설정 저장") }
                        }
                    }
                }
            }

            item {
                OutlinedButton(onClick = { pickLauncher.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("녹음 파일 직접 골라서 분석")
                }
            }

            // ── 통화 기록
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("통화 기록 ${records.size}건", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (records.any { it.status != Status.PROCESSING }) {
                        TextButton(onClick = { confirmDeleteAll = true }) {
                            Text("전체 삭제", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (records.isEmpty()) item { Text("아직 분석한 통화가 없습니다. 설치 이후의 통화부터 자동으로 분석합니다.", style = MaterialTheme.typography.bodySmall) }
            items(records, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { ctx.startActivity(ReviewActivity.intent(ctx, r.id)) }) {
                    Column(Modifier.padding(start = 14.dp, top = 6.dp, end = 4.dp, bottom = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.contact.ifBlank { r.fileName }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            StatusChip(r.status)
                            if (r.status != Status.PROCESSING) {
                                TextButton(onClick = { deleteTarget = r }) {
                                    Text("삭제", color = MaterialTheme.colorScheme.error)
                                }
                            } else Spacer(Modifier.width(12.dp))
                        }
                        Text(Times.full(r.callTime), style = MaterialTheme.typography.bodySmall)
                        val line = when {
                            r.status == Status.ERROR -> r.error
                            r.events.isNotEmpty() -> r.events.joinToString(" · ") { it.title }
                            else -> r.summary
                        }
                        if (line.isNotBlank()) Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ── 다가오는 일정
            item { Text("앞으로 7일 일정", style = MaterialTheme.typography.titleMedium) }
            if (upcoming.isEmpty()) item { Text("일정이 없거나 캘린더 권한이 없습니다.", style = MaterialTheme.typography.bodySmall) }
            items(upcoming) { e ->
                val clash = e in overlapTitles
                Text(
                    (if (clash) "⚠ " else "") + Times.eventLine(e),
                    color = if (clash) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 삭제 확인 창. 무엇이 지워지고 무엇이 남는지 함께 보여 준다. */
@Composable
fun DeleteDialog(title: String, target: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(target, fontWeight = FontWeight.Bold)
                Text("지워지는 것: 녹취록, 통화 요약, 할 일, 일정 초안")
                Text("남는 것: 휴대폰의 원본 통화녹음 파일, 이미 캘린더에 등록한 일정",
                    style = MaterialTheme.typography.bodySmall)
                Text("삭제하면 되돌릴 수 없습니다.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("삭제", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun CheckRow(label: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✅" else "⬜", modifier = Modifier.padding(end = 8.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
fun StatusChip(s: Status) {
    val c = when (s) {
        Status.REVIEW -> MaterialTheme.colorScheme.primary
        Status.ERROR -> MaterialTheme.colorScheme.error
        Status.APPROVED -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(color = c.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(s.label, color = c, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

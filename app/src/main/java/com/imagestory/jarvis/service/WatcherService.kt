package com.imagestory.jarvis.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.imagestory.jarvis.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 휴대폰이 켜져 있는 동안 상시 실행되는 감시 서비스.
 * - 새 오디오 파일이 생기면(통화 종료 후 녹음 저장) 즉시 감지
 * - 감지 누락 대비 10분마다 재검사
 * - 재부팅·앱 업데이트 후 자동 재시작(BootReceiver), 강제 종료 시 시스템이 재시작(START_STICKY)
 */
class WatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private lateinit var observer: ContentObserver

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = Notifier.ongoing(this, Notifier.idleText(this))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_ONGOING, n)
        }
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = requestScan(20_000)
        }
        contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        Briefing.schedule(this)
        scope.launch {
            while (isActive) { delay(10 * 60_000L); requestScan(0) }
        }
        requestScan(3_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROCESS) {
            val uri = intent.data
            val name = intent.getStringExtra(EXTRA_NAME) ?: "녹음 파일"
            val id = intent.getStringExtra(EXTRA_RECORD_ID)
            val time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis())
            if (uri != null) scope.launch { Pipeline.process(this@WatcherService, uri, name, time, id) }
        }
        return START_STICKY
    }

    private fun requestScan(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            scanLock.withLock { scanOnce() }
        }
    }

    private suspend fun scanOnce() {
        if (!hasAudioPermission(this)) return
        val prefs = Prefs(this)
        val nowSec = System.currentTimeMillis() / 1000
        var retryLater = false
        for (c in Scanner.findNew(this, prefs)) {
            if (nowSec - c.addedSec < 15) { retryLater = true; continue } // 아직 저장 중일 수 있음
            prefs.markProcessed(c.uri.toString())
            Pipeline.process(this, c.uri, c.name, c.addedSec * 1000)
        }
        if (retryLater) requestScan(20_000)
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(observer) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PROCESS = "com.imagestory.jarvis.PROCESS"
        const val EXTRA_NAME = "name"
        const val EXTRA_RECORD_ID = "recordId"
        const val EXTRA_TIME = "time"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WatcherService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatcherService::class.java))
        }

        /** 파일을 직접 골라 분석하거나, 실패한 건을 다시 처리 */
        fun process(ctx: Context, uri: Uri, name: String, time: Long, recordId: String? = null) {
            val i = Intent(ctx, WatcherService::class.java).apply {
                action = ACTION_PROCESS
                data = uri
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_TIME, time)
                recordId?.let { putExtra(EXTRA_RECORD_ID, it) }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun hasAudioPermission(ctx: Context): Boolean {
            val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
            return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }
    }
}

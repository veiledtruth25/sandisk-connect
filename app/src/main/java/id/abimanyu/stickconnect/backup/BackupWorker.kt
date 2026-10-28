package id.abimanyu.stickconnect.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import id.abimanyu.stickconnect.stick.AuthRequiredException
import id.abimanyu.stickconnect.stick.Paths
import id.abimanyu.stickconnect.stick.Stick
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val started = SystemClock.elapsedRealtime()
        ensureChannel(ctx)
        if (manual) runCatching { setForeground(foreground("Mencari stick…", 0, 0)) }

        val conn = try {
            Stick.connect(ctx)
        } catch (e: AuthRequiredException) {
            return finish("Gagal: password stick belum diisi di aplikasi", manual)
        } catch (e: Exception) {
            return finish("Stick tidak ditemukan (${now()})", manual)
        }
        val client = conn.client
        val db = BackupDb(ctx)
        val done = db.doneKeys()
        val pending = Media.query(ctx, BackupSettings.buckets(ctx)).filter { it.key !in done }
        if (pending.isEmpty()) return finish("Semua file sudah ter-backup (${now()})", manual)

        val root = Paths.normalize(BackupSettings.target(ctx))
        val readyDirs = mutableSetOf<String>()
        var free = conn.info.cardFree
        var uploaded = 0
        var skipped = 0
        var stopReason: String? = null
        val canReadOriginal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

        for ((index, item) in pending.withIndex()) {
            if (isStopped) {
                stopReason = "dihentikan"
                break
            }
            if (!manual && SystemClock.elapsedRealtime() - started > 8 * 60_000L) {
                stopReason = "lanjut di jadwal berikutnya"
                break
            }
            val dir = Paths.join(root, safe(item.bucket))
            var remote = Paths.join(dir, safe(item.name))
            try {
                if (dir !in readyDirs) {
                    client.mkdirs(dir)
                    readyDirs += dir
                }
                val existing = client.stat(remote)
                if (existing == item.size) {
                    db.markDone(item.key, remote)
                    skipped++
                    continue
                }
                if (existing != null) remote = Paths.join(dir, withSuffix(safe(item.name), "_${item.id}"))
                if (item.size > free - 16L * 1024 * 1024) {
                    stopReason = "stick penuh"
                    break
                }
                val label = "${index + 1}/${pending.size} ${item.name}"
                setProgress(workDataOf("done" to index, "total" to pending.size, "name" to item.name))
                var lastNotify = 0L
                val uri = if (canReadOriginal) MediaStore.setRequireOriginal(item.uri) else item.uri
                client.upload(remote, item.size, {
                    ctx.contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka ${item.name}")
                }) { sent, total ->
                    val t = SystemClock.elapsedRealtime()
                    if (t - lastNotify > 1_000) {
                        lastNotify = t
                        showProgress(ctx, label, sent, total)
                    }
                }
                db.markDone(item.key, remote)
                free -= item.size
                uploaded++
            } catch (e: AuthRequiredException) {
                stopReason = "password stick salah"
                break
            } catch (e: IOException) {
                stopReason = "koneksi terputus (${e.message})"
                break
            }
        }

        val msg = buildString {
            append("$uploaded file ter-upload")
            if (skipped > 0) append(", $skipped sudah ada")
            val left = pending.size - uploaded - skipped
            if (left > 0) append(", $left tersisa")
            stopReason?.let { append(" — $it") }
            append(" (${now()})")
        }
        return finish(msg, manual)
    }

    private fun finish(msg: String, manual: Boolean): Result {
        BackupSettings.setLastResult(applicationContext, msg)
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIF_PROGRESS)
        if (manual) {
            nm?.notify(
                NOTIF_DONE,
                NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle("Backup ke stick")
                    .setContentText(msg)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                    .setAutoCancel(true)
                    .build(),
            )
        }
        return Result.success(workDataOf("result" to msg))
    }

    private fun foreground(text: String, sent: Long, total: Long): ForegroundInfo {
        val n = buildNotification(applicationContext, text, sent, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_PROGRESS, n)
        }
    }

    companion object {
        const val CHANNEL = "backup"
        const val NOTIF_PROGRESS = 1001
        const val NOTIF_DONE = 1002
        const val KEY_MANUAL = "manual"
        const val WORK_PERIODIC = "auto-backup"
        const val WORK_NOW = "backup-now"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_PERIODIC)
        }

        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, req)
        }

        fun stopNow(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NOW)
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL, "Backup ke stick", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }

        private fun buildNotification(ctx: Context, text: String, sent: Long, total: Long) =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Backup ke stick")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .apply {
                    if (total > 0) setProgress(1000, (sent * 1000 / total).toInt(), false)
                    else setProgress(0, 0, true)
                }
                .build()

        private fun showProgress(ctx: Context, text: String, sent: Long, total: Long) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            ctx.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_PROGRESS, buildNotification(ctx, text, sent, total))
        }

        private fun now(): String = SimpleDateFormat("dd MMM HH:mm", Locale("id")).format(Date())

        private fun safe(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "_" }

        private fun withSuffix(name: String, suffix: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) + suffix + name.substring(dot) else name + suffix
        }
    }
}

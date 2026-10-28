package id.abimanyu.stickconnect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import id.abimanyu.stickconnect.backup.BackupDb
import id.abimanyu.stickconnect.backup.BackupSettings
import id.abimanyu.stickconnect.backup.BackupWorker
import id.abimanyu.stickconnect.backup.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BackupScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(BackupSettings.enabled(ctx)) }
    var selected by remember { mutableStateOf(BackupSettings.buckets(ctx)) }
    var target by remember { mutableStateOf(BackupSettings.target(ctx)) }
    var buckets by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var doneCount by remember { mutableIntStateOf(0) }
    var editTarget by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val wm = remember { WorkManager.getInstance(ctx) }
    val nowInfos by wm.getWorkInfosForUniqueWorkLiveData(BackupWorker.WORK_NOW).observeAsState(emptyList())
    val autoInfos by wm.getWorkInfosForUniqueWorkLiveData(BackupWorker.WORK_PERIODIC).observeAsState(emptyList())
    val running = (nowInfos + autoInfos).firstOrNull { it.state == WorkInfo.State.RUNNING }
    val lastResult = remember(nowInfos, autoInfos) { BackupSettings.lastResult(ctx) }

    LaunchedEffect(nowInfos, autoInfos) {
        val (b, c) = withContext(Dispatchers.IO) { Media.buckets(ctx) to BackupDb(ctx).count() }
        buckets = b
        doneCount = c
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Backup otomatis", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Foto & video baru di-upload ke stick setiap ±30 menit saat HP tersambung WiFi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        BackupSettings.setEnabled(ctx, it)
                        if (it) BackupWorker.schedule(ctx) else BackupWorker.cancel(ctx)
                    })
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                if (running != null) {
                    val done = running.progress.getInt("done", 0)
                    val total = running.progress.getInt("total", 0)
                    val name = running.progress.getString("name").orEmpty()
                    Text(if (total > 0) "Meng-upload ${done + 1}/$total: $name" else "Menyiapkan…")
                    if (total > 0) {
                        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                } else {
                    Text(lastResult ?: "Belum pernah backup", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "$doneCount file tercatat sudah ter-backup",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running != null && nowInfos.any { it.state == WorkInfo.State.RUNNING }) {
                        OutlinedButton(onClick = { BackupWorker.stopNow(ctx) }) { Text("Hentikan") }
                    } else {
                        Button(onClick = { BackupWorker.runNow(ctx) }, enabled = running == null) { Text("Backup sekarang") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Folder tujuan di stick", style = MaterialTheme.typography.titleMedium)
                Text(target, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Setiap album jadi subfolder sendiri.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { editTarget = true }) { Text("Ubah") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Album yang di-backup",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                BucketRow("Semua album", buckets.sumOf { it.second }, selected.isEmpty()) {
                    selected = emptySet()
                    BackupSettings.setBuckets(ctx, selected)
                }
                if (buckets.isEmpty()) {
                    Text(
                        "Tidak ada foto/video terbaca. Pastikan izin akses foto & video sudah diberikan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                buckets.forEach { (name, count) ->
                    BucketRow(name, count, name in selected) {
                        selected = if (name in selected) selected - name else selected + name
                        BackupSettings.setBuckets(ctx, selected)
                    }
                }
            }
        }

        TextButton(onClick = { confirmReset = true }) { Text("Reset catatan backup") }
    }

    if (editTarget) {
        TextInputDialog(
            title = "Folder tujuan",
            label = "Path di stick",
            initial = target,
            onDismiss = { editTarget = false },
            onConfirm = {
                target = it.trim()
                BackupSettings.setTarget(ctx, target)
            },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset catatan?",
            message = "Aplikasi akan memeriksa ulang semua file. File yang sudah ada di stick dengan ukuran sama tidak di-upload ulang.",
            confirmText = "Reset",
            onDismiss = { confirmReset = false },
            onConfirm = {
                BackupDb(ctx).reset()
                doneCount = 0
            },
        )
    }
}

@Composable
private fun BucketRow(name: String, count: Int, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(name, modifier = Modifier.weight(1f))
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
    }
}

package id.abimanyu.stickconnect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class StickDialog { AppPassword, StickPassword, Rename, Timeout }

@Composable
fun StickScreen(vm: AppViewModel) {
    val info = vm.conn?.info ?: return
    var dialog by remember { mutableStateOf<StickDialog?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Penyimpanan", style = MaterialTheme.typography.titleMedium)
                val used = (info.cardTotal - info.cardFree).coerceAtLeast(0)
                val frac = if (info.cardTotal > 0) used.toFloat() / info.cardTotal else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Text("${formatSize(used)} terpakai dari ${formatSize(info.cardTotal)} • ${formatSize(info.cardFree)} bebas")
                Text(
                    "Format ${info.cardFormat.uppercase()} • ${info.cardStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Info stick", style = MaterialTheme.typography.titleMedium)
                InfoRow("Nama WiFi", info.ssid)
                InfoRow("Baterai", batteryText(info.batteryStatus, info.batteryVoltage))
                InfoRow("Firmware", "${info.version} (build ${info.build})")
                InfoRow("Model", info.model)
                InfoRow("Serial / MAC", info.serial)
                InfoRow("Alamat", vm.conn?.client?.host.orEmpty())
                InfoRow("Auto-mati", if (info.timeoutMinutes > 0) "${info.timeoutMinutes} menit" else "-")
                TextButton(onClick = { vm.refreshInfo() }) { Text("Muat ulang") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    "Pengaturan",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingButton("Password stick di aplikasi", "Dipakai untuk akses dari WiFi rumah") { dialog = StickDialog.AppPassword }
                SettingButton("Ganti password WiFi stick", "Stick akan restart") { dialog = StickDialog.StickPassword }
                SettingButton("Ganti nama WiFi stick", "Stick akan restart") { dialog = StickDialog.Rename }
                SettingButton("Auto-mati saat tidak dipakai", "Hemat baterai") { dialog = StickDialog.Timeout }
            }
        }
    }

    when (dialog) {
        StickDialog.AppPassword -> TextInputDialog(
            title = "Password stick",
            message = "Password WiFi stick. Hanya disimpan di HP ini.",
            label = "Password",
            password = true,
            onDismiss = { dialog = null },
            onConfirm = { vm.submitPassword(it) },
        )
        StickDialog.StickPassword -> TextInputDialog(
            title = "Password WiFi stick baru",
            message = "8–63 karakter. Setelah stick restart, sambungkan ulang perangkat dengan password baru.",
            label = "Password baru",
            password = true,
            confirmText = "Ganti",
            onDismiss = { dialog = null },
            onConfirm = { vm.changeStickPassword(it) },
        )
        StickDialog.Rename -> TextInputDialog(
            title = "Nama WiFi stick",
            label = "Nama baru",
            initial = info.ssid,
            confirmText = "Ganti",
            onDismiss = { dialog = null },
            onConfirm = { vm.renameStick(it.trim()) },
        )
        StickDialog.Timeout -> TextInputDialog(
            title = "Auto-mati (menit)",
            message = "Stick mati otomatis kalau tidak dipakai selama sekian menit.",
            label = "Menit",
            initial = info.timeoutMinutes.toString(),
            number = true,
            onDismiss = { dialog = null },
            onConfirm = { s -> s.trim().toIntOrNull()?.let { vm.setTimeout(it) } },
        )
        null -> Unit
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value.ifEmpty { "-" }, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun SettingButton(title: String, subtitle: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun batteryText(status: String, mv: Int): String {
    val s = when (status.lowercase()) {
        "high" -> "Penuh"
        "medium" -> "Sedang"
        "low" -> "Lemah"
        "critical" -> "Kritis"
        "charging" -> "Mengisi"
        else -> status
    }
    return if (mv > 0) "$s (${"%.2f".format(mv / 1000.0)} V)" else s
}

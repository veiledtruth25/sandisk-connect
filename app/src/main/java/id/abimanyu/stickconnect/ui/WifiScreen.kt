package id.abimanyu.stickconnect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.abimanyu.stickconnect.stick.WifiNet

@Composable
fun WifiScreen(vm: AppViewModel) {
    var joining by remember { mutableStateOf<WifiNet?>(null) }
    var forgetting by remember { mutableStateOf<WifiNet?>(null) }
    val info = vm.conn?.info
    val client = info?.client

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Koneksi stick ke WiFi rumah", style = MaterialTheme.typography.titleMedium)
                if (client != null && client.isConnected) {
                    Text("Tersambung ke ${client.ssid}")
                    Text(
                        "IP stick di jaringan rumah: ${client.ip}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(if (client?.status.isNullOrEmpty()) "Belum tersambung" else "Status: ${client?.status}")
                }
                Text(
                    "Saat stick tersambung ke WiFi rumah, HP cukup tetap di WiFi rumah. Aplikasi akan menemukan stick otomatis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                vm.wifiBusy?.let {
                    Spacer(Modifier.padding(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pass-through internet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Meneruskan internet ke perangkat yang tersambung ke WiFi stick. Di firmware tertentu fitur ini tidak meneruskan internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = info?.passthrough == true,
                    onCheckedChange = { vm.setPassthrough(it) },
                    enabled = info != null,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("WiFi di sekitar stick", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (vm.scanning) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Button(onClick = { vm.scan() }, enabled = vm.wifiBusy == null) { Text("Scan") }
                    }
                }
                if (vm.scanResults.isEmpty()) {
                    Text(
                        if (vm.scanning) "Memindai… (±10–30 detik)" else "Tekan Scan untuk mencari WiFi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                vm.scanResults.forEach { net ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = vm.wifiBusy == null) {
                            if (net.needsPassword) joining = net else vm.join(net, null)
                        },
                        leadingContent = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                        headlineContent = { Text(net.ssid) },
                        supportingContent = { Text("Sinyal ${net.rssi} • ${net.security.uppercase()}" + if (net.connected) " • tersambung" else "") },
                        trailingContent = { if (net.needsPassword) Icon(Icons.Filled.Lock, contentDescription = "Terkunci") },
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("WiFi tersimpan di stick", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.loadSaved() }) { Text("Muat ulang") }
                }
                if (vm.saved.isEmpty()) {
                    Text(
                        "Belum ada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                vm.saved.forEach { net ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = vm.wifiBusy == null) { vm.connectSaved(net) },
                        headlineContent = { Text(net.ssid) },
                        supportingContent = { Text(if (net.connected) "Tersambung" else "Ketuk untuk menyambungkan") },
                        trailingContent = {
                            IconButton(onClick = { forgetting = net }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Hapus")
                            }
                        },
                    )
                }
            }
        }
    }

    joining?.let { net ->
        TextInputDialog(
            title = net.ssid,
            message = "Masukkan password WiFi ini. Stick akan menyambung ke jaringan tersebut.",
            label = "Password WiFi",
            password = true,
            confirmText = "Sambungkan",
            onDismiss = { joining = null },
            onConfirm = { vm.join(net, it) },
        )
    }
    forgetting?.let { net ->
        ConfirmDialog(
            title = "Hapus ${net.ssid}?",
            message = "Stick tidak akan menyambung otomatis ke WiFi ini lagi.",
            confirmText = "Hapus",
            onDismiss = { forgetting = null },
            onConfirm = { vm.forget(net) },
        )
    }
}

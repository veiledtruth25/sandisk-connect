package id.abimanyu.stickconnect.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.abimanyu.stickconnect.stick.RemoteFile

@Composable
fun FilesScreen(vm: AppViewModel) {
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    var renaming by remember { mutableStateOf<RemoteFile?>(null) }
    var deleting by remember { mutableStateOf<RemoteFile?>(null) }
    var newFolder by remember { mutableStateOf(false) }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris)
    }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingDownload
        if (uri != null && f != null) vm.download(f, uri)
        pendingDownload = null
    }

    BackHandler(enabled = vm.path != "/") { vm.up() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.up() }, enabled = vm.path != "/") {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Naik satu folder")
                }
                Text(
                    vm.path,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                vm.conn?.info?.let {
                    Text(
                        "${formatSize(it.cardFree)} bebas",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
            if (vm.filesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.transfer?.let { t ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(t, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { vm.transferProgress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            HorizontalDivider()
            if (vm.files.isEmpty() && !vm.filesLoading) {
                Text(
                    "Folder kosong",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
                items(vm.files, key = { it.path }) { f ->
                    FileRow(
                        f,
                        onOpen = {
                            if (f.isDir) vm.loadDir(f.path) else {
                                pendingDownload = f
                                pickDest.launch(f.name)
                            }
                        },
                        onDownload = { pendingDownload = f; pickDest.launch(f.name) },
                        onRename = { renaming = f },
                        onDelete = { deleting = f },
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(onClick = { newFolder = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Folder baru")
            }
            ExtendedFloatingActionButton(
                onClick = { pickUpload.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                text = { Text("Upload") },
            )
        }
    }

    if (newFolder) {
        TextInputDialog(
            title = "Folder baru",
            label = "Nama folder",
            onDismiss = { newFolder = false },
            onConfirm = { vm.mkdir(it.trim()) },
        )
    }
    renaming?.let { f ->
        TextInputDialog(
            title = "Ganti nama",
            label = "Nama baru",
            initial = f.name,
            onDismiss = { renaming = null },
            onConfirm = { vm.rename(f, it.trim()) },
        )
    }
    deleting?.let { f ->
        ConfirmDialog(
            title = "Hapus ${if (f.isDir) "folder" else "file"}?",
            message = if (f.isDir) "\"${f.name}\" beserta seluruh isinya akan dihapus dari stick." else "\"${f.name}\" akan dihapus dari stick.",
            confirmText = "Hapus",
            onDismiss = { deleting = null },
            onConfirm = { vm.delete(f) },
        )
    }
}

@Composable
private fun FileRow(
    f: RemoteFile,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            Icon(
                if (f.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (f.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val info = listOfNotNull(
                if (f.isDir) null else formatSize(f.size),
                f.modified.ifEmpty { null }?.let { shortDate(it) },
            ).joinToString(" • ")
            if (info.isNotEmpty()) Text(info, maxLines = 1)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!f.isDir) {
                        DropdownMenuItem(text = { Text("Download ke HP") }, onClick = { menu = false; onDownload() })
                    }
                    DropdownMenuItem(text = { Text("Ganti nama") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Hapus") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

/** "Wed, 28 Oct 2026 02:23:18 GMT" -> "28 Oct 2026" */
private fun shortDate(http: String): String {
    val parts = http.split(' ')
    return if (parts.size >= 4) "${parts[1]} ${parts[2]} ${parts[3]}" else http
}

package id.abimanyu.stickconnect.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewList
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import id.abimanyu.stickconnect.stick.RemoteFile
import id.abimanyu.stickconnect.stick.Stick

private val IMAGE_EXT = setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif")
private val VIDEO_EXT = setOf("mp4","mkv","avi","mov","3gp","webm","m4v","ts","m2ts")
private fun RemoteFile.isImage() = name.substringAfterLast('.').lowercase() in IMAGE_EXT
private fun RemoteFile.isVideo() = name.substringAfterLast('.').lowercase() in VIDEO_EXT
private fun RemoteFile.isMedia() = isImage() || isVideo()

@Composable
fun FilesScreen(vm: AppViewModel) {
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    var renaming by remember { mutableStateOf<RemoteFile?>(null) }
    var deleting by remember { mutableStateOf<RemoteFile?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var gridMode by rememberSaveable { mutableStateOf(false) }

    val pickUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.upload(uris)
    }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val f = pendingDownload
        if (uri != null && f != null) vm.download(f, uri)
        pendingDownload = null
    }

    BackHandler(enabled = vm.path != "/") { vm.up() }

    // ImageLoader dengan Digest auth — hanya dibuat sekali per koneksi
    val ctx = LocalContext.current
    val imageLoader = remember(vm.conn) {
        val okHttp = runCatching { Stick.mediaOkHttp(ctx) }.getOrNull()
        if (okHttp != null) ImageLoader.Builder(ctx).okHttpClient(okHttp).build()
        else ImageLoader.Builder(ctx).build()
    }

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
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                // Tombol Tempel (muncul bila ada clipboard)
                if (vm.clipboard != null) {
                    IconButton(onClick = { vm.paste() }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = "Tempel ${if (vm.clipboard?.isCut == true) "(pindahkan)" else "(salin)"}",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // Toggle list/grid
                IconButton(onClick = { gridMode = !gridMode }) {
                    Icon(
                        if (gridMode) Icons.Filled.ViewList else Icons.Filled.GridView,
                        contentDescription = if (gridMode) "Mode daftar" else "Mode grid",
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

            if (gridMode) {
                // ── Mode Grid / Thumbnail ──────────────────────────────────────
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(vm.files, key = { it.path }) { f ->
                        GridCell(
                            f = f,
                            imageLoader = imageLoader,
                            baseUrl = vm.conn?.let { "http://${it.client.host}/files" } ?: "",
                            onOpen = {
                                when {
                                    f.isDir -> vm.loadDir(f.path)
                                    f.isMedia() -> vm.openMedia(f, vm.files)
                                    else -> { pendingDownload = f; pickDest.launch(f.name) }
                                }
                            },
                            onDownload = { pendingDownload = f; pickDest.launch(f.name) },
                            onCut = { vm.cut(f) },
                            onCopy = { vm.copy(f) },
                            onRename = { renaming = f },
                            onDelete = { deleting = f },
                        )
                    }
                }
            } else {
                // ── Mode Daftar (List) ─────────────────────────────────────────
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
                    items(vm.files, key = { it.path }) { f ->
                        FileRow(
                            f,
                            onOpen = {
                                when {
                                    f.isDir -> vm.loadDir(f.path)
                                    f.isMedia() -> vm.openMedia(f, vm.files)
                                    else -> { pendingDownload = f; pickDest.launch(f.name) }
                                }
                            },
                            onDownload = { pendingDownload = f; pickDest.launch(f.name) },
                            onCut = { vm.cut(f) },
                            onCopy = { vm.copy(f) },
                            onRename = { renaming = f },
                            onDelete = { deleting = f },
                        )
                    }
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

// ── Grid Cell ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(
    f: RemoteFile,
    imageLoader: ImageLoader,
    baseUrl: String,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }

    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menu = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            f.isDir -> {
                // Folder — tampilkan ikon besar
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
            }
            f.isImage() -> {
                // Gambar — thumbnail langsung dari stick
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data("$baseUrl${f.path}")
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = f.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    },
                    error = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    },
                )
            }
            f.isVideo() -> {
                // Video — thumbnail dari stick (first frame) + ikon play overlay
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data("$baseUrl${f.path}")
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = f.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    },
                    error = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    },
                )
                // Overlay ikon play di atas thumbnail video
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0x44000000), MaterialTheme.shapes.extraLarge),
                )
            }
            else -> {
                // File biasa
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        // Nama file di bagian bawah (semi-transparan)
        Text(
            f.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )

        // Dropdown menu (long-press)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!f.isDir) {
                DropdownMenuItem(
                    text = { Text("Download ke HP") },
                    leadingIcon = { Icon(Icons.Filled.Description, null) },
                    onClick = { menu = false; onDownload() },
                )
            }
            DropdownMenuItem(
                text = { Text("Cut (Pindahkan)") },
                leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
                onClick = { menu = false; onCut() },
            )
            DropdownMenuItem(
                text = { Text("Copy (Salin)") },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { menu = false; onCopy() },
            )
            DropdownMenuItem(
                text = { Text("Ganti nama") },
                leadingIcon = { Icon(Icons.Filled.MoreVert, null) },
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Hapus") },
                leadingIcon = { Icon(Icons.Filled.Description, null) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

// ── List Row ───────────────────────────────────────────────────────────────────

@Composable
private fun FileRow(
    f: RemoteFile,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
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
                        DropdownMenuItem(
                            text = { Text("Download ke HP") },
                            leadingIcon = { Icon(Icons.Filled.Description, null) },
                            onClick = { menu = false; onDownload() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Cut (Pindahkan)") },
                        leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
                        onClick = { menu = false; onCut() },
                    )
                    DropdownMenuItem(
                        text = { Text("Copy (Salin)") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                        onClick = { menu = false; onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text("Ganti nama") },
                        leadingIcon = { Icon(Icons.Filled.MoreVert, null) },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Hapus") },
                        leadingIcon = { Icon(Icons.Filled.Description, null) },
                        onClick = { menu = false; onDelete() },
                    )
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

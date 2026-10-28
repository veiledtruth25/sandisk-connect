package id.abimanyu.stickconnect.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import okhttp3.OkHttpClient

/**
 * Fullscreen viewer: gambar bisa di-zoom/pan, video langsung diputar.
 * Swipe kiri/kanan atau tekan tombol panah untuk navigasi antar file.
 */
@Composable
fun MediaViewer(
    url: String,
    isVideo: Boolean,
    okHttp: OkHttpClient,
    fileName: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    // Tangkap tombol back sistem supaya tidak langsung close app
    BackHandler { onBack() }

    // Akumulasi drag horizontal untuk deteksi swipe
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val swipeThreshold = 120f

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(hasPrev, hasNext) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { _, delta -> dragTotal += delta },
                    onDragEnd = {
                        when {
                            dragTotal < -swipeThreshold && hasNext -> onNext()
                            dragTotal > swipeThreshold && hasPrev -> onPrev()
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f },
                )
            },
    ) {
        if (isVideo) {
            VideoPlayer(url, okHttp)
        } else {
            ZoomableImage(url, okHttp)
        }

        // ── Baris atas: tombol kembali + nama file ──
        Box(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000)),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali", tint = Color.White)
            }

            if (fileName.isNotEmpty()) {
                Text(
                    text = fileName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 56.dp, end = 56.dp)
                        .background(Color(0x66000000), MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // ── Tombol prev (kiri tengah) ──
        if (hasPrev) {
            IconButton(
                onClick = onPrev,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000)),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = "File sebelumnya", tint = Color.White)
            }
        }

        // ── Tombol next (kanan tengah) ──
        if (hasNext) {
            IconButton(
                onClick = onNext,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0x66000000)),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = "File berikutnya", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String, okHttp: OkHttpClient) {
    val ctx = LocalContext.current

    // ImageLoader dengan OkHttpClient yang sudah punya Digest auth
    val imageLoader = remember(okHttp) {
        ImageLoader.Builder(ctx).okHttpClient(okHttp).build()
    }

    // Reset zoom/pan saat URL berubah (ganti file)
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(0.5f, 8f)
        offset += pan
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(ctx).data(url).crossfade(true).build(),
            imageLoader = imageLoader,
            contentDescription = null,
            loading = {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .transformable(transformState),
        )
    }
}

@Composable
private fun VideoPlayer(url: String, okHttp: OkHttpClient) {
    val ctx = LocalContext.current

    val player = remember(url) {
        val dsFactory = OkHttpDataSource.Factory(okHttp)
        val src = ProgressiveMediaSource.Factory(dsFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
        ExoPlayer.Builder(ctx).build().apply {
            setMediaSource(src)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(url) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

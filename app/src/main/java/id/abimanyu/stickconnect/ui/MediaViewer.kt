package id.abimanyu.stickconnect.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
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
 * Ditampilkan sebagai overlay di atas seluruh layar.
 */
@Composable
fun MediaViewer(
    url: String,
    isVideo: Boolean,
    okHttp: OkHttpClient,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (isVideo) {
            VideoPlayer(url, okHttp)
        } else {
            ZoomableImage(url, okHttp)
        }

        // Tombol kembali di pojok kiri atas
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Kembali",
                tint = Color.White,
            )
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

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
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

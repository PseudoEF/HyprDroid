package dev.hyprdroid.launcher.ui

import android.content.*
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hyprdroid.launcher.data.AnimatedWallpaperFiles
import dev.hyprdroid.launcher.model.WallpaperScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

internal fun decodeWallpaper(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val ratio = (maxDimension.toFloat() / max(info.size.width, info.size.height)).coerceAtMost(1f)
        decoder.setTargetSize(max(1, (info.size.width * ratio).roundToInt()), max(1, (info.size.height * ratio).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

private data class WallpaperContent(val bitmap: Bitmap? = null, val animated: Drawable? = null)

@Composable
fun WallpaperLayer(uri: String?, scale: WallpaperScale = WallpaperScale.CROP, motion: Boolean = true,
    desktopVisible: Boolean = true, onFailure: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val failure by rememberUpdatedState(onFailure)
    val content by produceState<WallpaperContent?>(null, uri) {
        value = null
        if (uri != null) try {
            value = withContext(Dispatchers.IO) {
                val source = Uri.parse(uri)
                if (AnimatedWallpaperFiles.isGif(context, source)) WallpaperContent(animated = AnimatedWallpaperFiles.decode(context, source))
                else WallpaperContent(bitmap = decodeWallpaper(context, source))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { failure() }
          catch (_: OutOfMemoryError) { failure() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val drawable = content?.animated
    val currentAllowed by rememberUpdatedState(motion && desktopVisible)
    var interactive by remember { mutableStateOf(power.isInteractive) }
    var powerSaving by remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(lifecycle, context, drawable) {
        fun refresh(screenOff: Boolean = false) {
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); interactive = !screenOff && power.isInteractive; powerSaving = power.isPowerSaveMode
            // STOP can pause the Compose recomposer: control the native animation synchronously too.
            val animated = drawable as? AnimatedImageDrawable
            if (currentAllowed && resumed && interactive && !powerSaving) { animated?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; animated?.start() }
            else animated?.stop()
        }
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { refresh(intent?.action == Intent.ACTION_SCREEN_OFF) } }
        lifecycle.addObserver(observer)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
        refresh()
        onDispose { lifecycle.removeObserver(observer); context.unregisterReceiver(receiver); (drawable as? AnimatedImageDrawable)?.stop() }
    }
    val playing = motion && desktopVisible && resumed && interactive && !powerSaving
    DisposableEffect(drawable, playing) {
        val animated = drawable as? AnimatedImageDrawable
        if (playing) { animated?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; animated?.start() } else animated?.stop()
        onDispose { animated?.stop() }
    }
    content?.bitmap?.let { Image(it.asImageBitmap(), null, contentScale = when (scale) {
        WallpaperScale.CROP -> ContentScale.Crop; WallpaperScale.FIT -> ContentScale.Fit; WallpaperScale.STRETCH -> ContentScale.FillBounds
    }, modifier = Modifier.fillMaxSize().testTag("static-wallpaper")) }
    if (drawable != null) key(drawable) {
        AndroidView(factory = { visual -> ImageView(visual).apply { tag = "animated-wallpaper-view"; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO; setImageDrawable(drawable) } },
            modifier = Modifier.fillMaxSize().testTag("animated-wallpaper"), onReset = null,
            onRelease = { (drawable as? AnimatedImageDrawable)?.stop(); it.setImageDrawable(null); drawable.callback = null },
            update = { view -> view.scaleType = when (scale) {
                WallpaperScale.CROP -> ImageView.ScaleType.CENTER_CROP; WallpaperScale.FIT -> ImageView.ScaleType.FIT_CENTER; WallpaperScale.STRETCH -> ImageView.ScaleType.FIT_XY
            } })
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        Mocha.Base.copy(alpha = .22f), Mocha.Base.copy(alpha = .08f), Mocha.Base.copy(alpha = .45f),
    ))))
}

package dev.hyprdroid.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.IOException
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

@Composable
fun WallpaperLayer(uri: String?, onFailure: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = null
        if (uri != null) {
            try {
                value = withContext(Dispatchers.IO) { decodeWallpaper(context, Uri.parse(uri)) }
            } catch (_: IOException) {
                onFailure()
            } catch (_: SecurityException) {
                onFailure()
            } catch (_: IllegalArgumentException) {
                onFailure()
            }
        }
    }
    // With no image this is transparent to Android's wallpaper window, including live wallpapers.
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        Mocha.Base.copy(alpha = .22f), Mocha.Base.copy(alpha = .08f), Mocha.Base.copy(alpha = .45f),
    ))))
}

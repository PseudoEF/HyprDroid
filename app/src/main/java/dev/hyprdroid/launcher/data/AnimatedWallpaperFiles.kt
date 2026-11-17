package dev.hyprdroid.launcher.data

import android.content.Context
import android.app.ActivityManager
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.sync.Mutex
import kotlin.math.max
import kotlin.math.roundToInt

/** Private, bounded compressed GIF files; native ImageDecoder owns incremental frame decoding. */
internal object AnimatedWallpaperFiles {
    val operations = Mutex()
    const val MAX_BYTES = 24L * 1024 * 1024
    const val MAX_DECODE_DIMENSION = 1024
    const val MAX_SOURCE_PIXELS = 8L * 1024 * 1024
    fun isGif(context: Context, uri: Uri): Boolean = context.contentResolver.openInputStream(uri)?.use {
        val bytes = ByteArray(6)
        var count = 0
        while (count < bytes.size) { val next = it.read(bytes, count, bytes.size - count); if (next < 0) break; count += next }
        count == 6 && String(bytes, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
    } ?: throw IOException("File unavailable")
    fun decode(context: Context, uri: Uri, bound: Int = MAX_DECODE_DIMENSION): Drawable {
        if (isOwned(context, uri)) {
            if (File(requireNotNull(uri.path)).length() > MAX_BYTES) throw IOException("GIF exceeds compressed limit")
        } else {
            // Legacy SAF GIFs may predate the private import. Validate even unknown provider lengths.
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyBounded(input, object : OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
                }, MAX_BYTES)
            } ?: throw IOException("File unavailable")
        }
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            if (info.mimeType != "image/gif" || info.size.width !in 1..8192 || info.size.height !in 1..8192 ||
                info.size.width.toLong() * info.size.height > MAX_SOURCE_PIXELS) throw IOException("Unsupported GIF dimensions")
            val memory = context.getSystemService(ActivityManager::class.java)
            val targetBound = minOf(bound, if (memory.isLowRamDevice || memory.memoryClass < 128) 512 else MAX_DECODE_DIMENSION)
            val ratio = (targetBound.toFloat() / max(info.size.width, info.size.height)).coerceAtMost(1f)
            decoder.setTargetSize(max(1, (info.size.width * ratio).roundToInt()), max(1, (info.size.height * ratio).roundToInt()))
            decoder.setOnPartialImageListener { false }
        }
    }
    fun importGif(context: Context, source: Uri): Uri {
        val directory = File(context.filesDir, "animated-wallpapers").apply { if (!exists() && !mkdirs()) throw IOException("Cannot create wallpaper directory") }
        val target = File.createTempFile("wallpaper-", ".gif", directory)
        try {
            context.contentResolver.openInputStream(source)?.use { input -> target.outputStream().use { output ->
                copyBounded(input, output, MAX_BYTES)
                output.flush()
            } } ?: throw IOException("File unavailable")
            val uri = Uri.fromFile(target)
            val probe = decode(context, uri, 64)
            (probe as? AnimatedImageDrawable)?.stop()
            probe.callback = null
            return uri
        } catch (error: Throwable) { target.delete(); throw error }
    }
    fun isOwned(context: Context, uri: Uri): Boolean = uri.scheme == "file" &&
        uri.path?.let { File(it).canonicalFile.parentFile == File(context.filesDir, "animated-wallpapers").canonicalFile } == true
    fun remove(context: Context, uri: Uri) { runCatching { if (isOwned(context, uri)) File(requireNotNull(uri.path)).delete() } }
    fun cleanup(context: Context, keep: String?) {
        val kept = keep?.let(Uri::parse)?.takeIf { it.scheme == "file" }?.path?.let { File(it).canonicalPath }
        File(context.filesDir, "animated-wallpapers").listFiles()?.filter { it.name.startsWith("wallpaper-") && it.extension == "gif" }
            ?.forEach { if (it.canonicalPath != kept) it.delete() }
    }
}

internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val count = input.read(buffer); if (count < 0) return
        total += count
        if (total > limit) throw IOException("GIF exceeds allowed compressed size")
        output.write(buffer, 0, count)
    }
}

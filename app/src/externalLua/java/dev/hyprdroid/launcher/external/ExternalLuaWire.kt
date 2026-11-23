package dev.hyprdroid.launcher.external

import android.os.SharedMemory
import android.system.OsConstants
import dev.hyprdroid.launcher.plugins.PluginResult
import dev.hyprdroid.launcher.plugins.TileResult
import dev.hyprdroid.launcher.plugins.TileUiCodec
import dev.hyprdroid.launcher.plugins.isPluginText
import java.nio.ByteBuffer

internal fun sealedBytes(bytes: ByteArray): SharedMemory =
    SharedMemory.create(null, bytes.size + 4).also { memory ->
        try {
            val mapping = memory.mapReadWrite()
            try {
                mapping.putInt(bytes.size)
                mapping.put(bytes)
            } finally {
                SharedMemory.unmap(mapping)
            }
            check(memory.setProtect(OsConstants.PROT_READ))
        } catch (failure: Exception) {
            memory.close()
            throw failure
        }
    }

internal fun checkedBytes(memory: SharedMemory, limit: Int): ByteArray = memory.use {
    require(it.size in 4..(4 + limit))
    val mapped = it.mapReadOnly()
    try {
        val length = mapped.int
        require(length == mapped.remaining() && length in 0..limit)
        ByteArray(length).also(mapped::get)
    } finally {
        SharedMemory.unmap(mapped)
    }
}

internal fun decodeFrame(bytes: ByteArray): PluginResult.Success {
    require(bytes.size in 3..16385 && bytes[0] == 0.toByte())
    val buffer = ByteBuffer.wrap(bytes, 1, bytes.size - 1)
    val fields = mutableListOf<String>()
    while (buffer.hasRemaining()) {
        require(fields.size < 145 && buffer.remaining() >= 2)
        val size = buffer.short.toInt() and 0xffff
        require(size <= 1024 && size <= buffer.remaining())
        val utf8 = ByteArray(size).also(buffer::get)
        fields += Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(utf8)).toString()
    }
    val state = fields.removeAt(fields.lastIndex)
    require(state.isPluginText(512))
    return PluginResult.Success(emptyList(), null, TileResult(fields.takeIf { it.isNotEmpty() }?.let(TileUiCodec::decode), state))
}

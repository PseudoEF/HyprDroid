package dev.hyprdroid.launcher.external

import dev.hyprdroid.launcher.plugins.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

internal const val TILE_WIRE_V2 = 1
internal const val TILE_CONTEXT_V2_LIMIT = 2_048
internal const val TILE_SOURCE_LIMIT = 16_384
internal const val TILE_FRAME_LIMIT = 16_385

internal data class WireRequestV2(val context: ByteArray, val event: Int, val capabilities: Int)

internal fun encodeRequestV2(input: TileExecutionV2): WireRequestV2 {
    require("ui.tile" in input.permissions)
    val stateAllowed = "state.local" in input.permissions
    val clockAllowed = "system.time" in input.permissions
    val batteryAllowed = "system.battery" in input.permissions
    val capabilities = (if (stateAllowed) 1 else 0) or (if (clockAllowed) 2 else 0) or (if (batteryAllowed) 4 else 0)
    require(stateAllowed || input.state.isEmpty())
    TileStateV2.encode(input.state)
    val buffer = ByteArrayOutputStream()
    val out = DataOutputStream(buffer)
    fun token(value: String) {
        val bytes = value.encodeToByteArray()
        require(bytes.size <= 512)
        out.writeShort(bytes.size)
        out.write(bytes)
    }
    fun scalar(value: TileStateValue) = when (value) {
        is TileStateValue.Text -> { token("s"); token(value.value) }
        is TileStateValue.Number -> { token("n"); token(value.value.toString()) }
        is TileStateValue.Flag -> { token("b"); token(if (value.value) "1" else "0") }
    }

    token(input.state.size.toString())
    for ((key, value) in input.state) {
        token(key)
        scalar(value)
    }
    val clock = input.system.clock.takeIf { clockAllowed }
    token(clock?.date.orEmpty())
    token(clock?.time24.orEmpty())
    token(clock?.time12.orEmpty())
    token(input.system.batteryLevel?.takeIf { batteryAllowed && it in 0..100 }?.toString().orEmpty())
    token(input.system.charging?.takeIf { batteryAllowed }?.let { if (it) "1" else "0" }.orEmpty())
    require(input.interaction == null || input.signal == null)
    val interaction = input.interaction
    val id = interaction?.id ?: (input.signal as? TileSignal.Timer)?.id.orEmpty()
    require(id.isEmpty() && interaction == null && input.signal !is TileSignal.Timer || tileEventId.matches(id))
    token(id)
    val event = when (interaction) {
        is TileInteraction.Click -> 1
        is TileInteraction.Toggle -> { scalar(TileStateValue.Flag(interaction.value)); 2 }
        is TileInteraction.Change -> {
            require(validTileInput(interaction.value)); scalar(TileStateValue.Text(interaction.value)); 2
        }
        is TileInteraction.Submit -> {
            require(validTileInput(interaction.value)); scalar(TileStateValue.Text(interaction.value)); 3
        }
        null -> when (input.signal) {
            TileSignal.Resume -> 5
            is TileSignal.Timer -> 4
            null -> 0
        }
    }
    return WireRequestV2(buffer.toByteArray().also { require(it.size <= TILE_CONTEXT_V2_LIMIT) }, event, capabilities)
}

internal fun decodeFrameV2(bytes: ByteArray, capabilities: Int): TileFrameV2 {
    require(bytes.size in 5..TILE_FRAME_LIMIT && bytes[0] == 0.toByte())
    val buffer = ByteBuffer.wrap(bytes, 1, bytes.size - 1)
    fun token(): String {
        require(buffer.remaining() >= 2)
        val size = buffer.short.toInt() and 0xffff
        require(size <= 1024 && size <= buffer.remaining())
        val raw = ByteArray(size).also(buffer::get)
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString()
    }
    val treeSize = buffer.short.toInt() and 0xffff
    require(treeSize in 1..(buffer.remaining() - 2))
    val treeEnd = buffer.position() + treeSize
    val fields = mutableListOf<String>()
    val frameEnd = buffer.limit()
    buffer.limit(treeEnd)
    while (buffer.hasRemaining()) {
        require(fields.size < 144)
        fields += token()
    }
    buffer.limit(frameEnd)
    val tree = TileUiCodec.decode(fields, 2)
    val state = linkedMapOf<String, TileStateValue>()
    while (true) {
        val key = token()
        if (key.isEmpty()) break
        require(capabilities and 1 != 0 && state.size < 16 && key !in state)
        val type = token()
        val value = token()
        state[key] = when (type) {
            "s" -> TileStateValue.Text(value)
            "n" -> TileStateValue.Number(requireNotNull(value.toDoubleOrNull()).also { require(it.isFinite()) })
            "b" -> TileStateValue.Flag(when (value) {
                "1" -> true
                "0" -> false
                else -> error("Invalid boolean")
            })
            else -> error("Invalid scalar")
        }
    }
    TileStateV2.encode(state)
    val timers = mutableListOf<TileTimer>()
    while (buffer.hasRemaining()) {
        require(timers.size < 2)
        timers += TileTimer(token(), requireNotNull(token().toLongOrNull()))
    }
    TileScheduleV2().configure(timers, 0)
    return TileFrameV2(tree, state, timers)
}

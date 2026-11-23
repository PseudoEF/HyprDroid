package dev.hyprdroid.launcher.plugins

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue

internal enum class TileElement(val wire: String) {
    TEXT("text"), BUTTON("button"), COLUMN("column"), ROW("row"), PROGRESS("progress"),
    SPACER("spacer"), CARD("card"), SCROLLABLE_COLUMN("scrollableColumn"),
}

internal sealed interface TileNode {
    data class Text(val text: String) : TileNode
    data class Button(val id: String, val text: String) : TileNode
    data class Progress(val fraction: Float) : TileNode
    data class Spacer(val size: Int) : TileNode
    data class Group(val element: TileElement, val children: List<TileNode>) : TileNode
}

internal data class TileRequest(val event: String? = null, val state: String = "")
internal data class TileResult(val root: TileNode?, val state: String)
internal val tileEventId = Regex("[a-zA-Z][a-zA-Z0-9_-]{0,31}")
internal const val TILE_STATE_LIMIT = 512

/** A bounded preorder stream of scalar strings. The receiver independently decodes it. */
internal object TileUiCodec {
    private const val MAX_NODES = 48
    private const val MAX_DEPTH = 6
    private const val MAX_TEXT = 256
    private const val MAX_TOTAL_TEXT = 4096

    fun fromLua(value: LuaValue): TileNode {
        val fields = mutableListOf<String>()
        fun walk(node: LuaValue, depth: Int) {
            require(depth <= MAX_DEPTH && fields.size < MAX_NODES * 3)
            val table = node.checktable()
            val type = element(luaString(table.rawget("type"), 24))
            val allowed = when (type) {
                TileElement.TEXT, TileElement.PROGRESS -> setOf("type", "value")
                TileElement.BUTTON -> setOf("type", "id", "text")
                TileElement.SPACER -> setOf("type", "size")
                else -> setOf("type", "children")
            }
            require(table.keys().size <= allowed.size && table.keys().all {
                it.type() == LuaValue.TSTRING && it.tojstring() in allowed
            })
            fields += type.wire
            when (type) {
                TileElement.TEXT -> fields += luaString(table.rawget("value"), MAX_TEXT)
                TileElement.BUTTON -> {
                    fields += luaString(table.rawget("id"), 32)
                    fields += luaString(table.rawget("text"), MAX_TEXT)
                }
                TileElement.PROGRESS, TileElement.SPACER -> {
                    val value = table.rawget(if (type == TileElement.SPACER) "size" else "value")
                    require(value.type() == LuaValue.TNUMBER)
                    fields += value.tojstring()
                }
                else -> {
                    val children = table.rawget("children").checktable()
                    val count = children.rawlen()
                    require(count in 0..MAX_NODES && children.keys().size == count)
                    fields += count.toString()
                    for (index in 1..count) walk(children.rawget(index), depth + 1)
                }
            }
        }
        return try {
            walk(value, 1)
            decode(fields)
        } catch (_: IllegalArgumentException) {
            throw LuaError("Некорректное описание интерфейса тайла")
        }
    }

    fun encode(root: TileNode): ArrayList<String> = arrayListOf<String>().also { fields ->
        fun walk(node: TileNode) {
            when (node) {
                is TileNode.Text -> fields.addAll(listOf("text", node.text))
                is TileNode.Button -> fields.addAll(listOf("button", node.id, node.text))
                is TileNode.Progress -> fields.addAll(listOf("progress", node.fraction.toString()))
                is TileNode.Spacer -> fields.addAll(listOf("spacer", node.size.toString()))
                is TileNode.Group -> {
                    fields.addAll(listOf(node.element.wire, node.children.size.toString()))
                    node.children.forEach(::walk)
                }
            }
        }
        walk(root)
    }

    fun decode(fields: List<String>): TileNode {
        require(fields.size in 1..MAX_NODES * 3)
        var index = 0
        var nodes = 0
        var totalText = 0
        val buttons = hashSetOf<String>()
        fun take() = requireNotNull(fields.getOrNull(index++))
        fun text(): String = take().also {
            require(it.isPluginText(MAX_TEXT))
            totalText += it.length
            require(totalText <= MAX_TOTAL_TEXT)
        }
        fun read(depth: Int): TileNode {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES)
            return when (val type = element(take())) {
                TileElement.TEXT -> TileNode.Text(text())
                TileElement.BUTTON -> {
                    val id = take()
                    require(tileEventId.matches(id) && buttons.add(id))
                    TileNode.Button(id, text().also { require(it.isNotBlank()) })
                }
                TileElement.PROGRESS -> TileNode.Progress(take().boundedNumber().also { require(it in 0f..1f) })
                TileElement.SPACER -> TileNode.Spacer(
                    take().boundedNumber().also { require(it in 0f..32f && it == it.toInt().toFloat()) }.toInt(),
                )
                else -> {
                    val count = take()
                    require(count.length <= 2)
                    val size = requireNotNull(count.toIntOrNull())
                    require(size in 0..MAX_NODES - nodes)
                    // Compose scroll containers cannot be nested in this protocol.
                    require(type != TileElement.SCROLLABLE_COLUMN || depth == 1)
                    TileNode.Group(type, List(size) { read(depth + 1) })
                }
            }
        }
        return read(1).also { require(index == fields.size) }
    }

    private fun String.boundedNumber(): Float {
        require(length <= 24)
        return requireNotNull(toFloatOrNull()).also { require(it.isFinite()) }
    }

    private fun element(name: String) = requireNotNull(TileElement.entries.firstOrNull { it.wire == name })

    fun luaString(value: LuaValue, limit: Int): String {
        require(value.type() == LuaValue.TSTRING && value.checkstring().m_length <= limit * 3)
        return value.tojstring().also { require(it.isPluginText(limit)) }
    }
}

internal fun TileNode.containsButton(id: String): Boolean = when (this) {
    is TileNode.Button -> this.id == id
    is TileNode.Group -> children.any { it.containsButton(id) }
    else -> false
}

package dev.hyprdroid.launcher.plugins

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePluginTest {
    private val plugin = BuiltInLuaPlugin.DROIDFETCH_TILE
    private val runtime = TrustedLuaRuntime { 0L }
    private val button = TileNode.Button("refresh", "Refresh")

    @Test
    fun bundledTileRendersDeviceAndFreshBatteryOnRefresh() {
        val source = sequenceOf(File("src/main/assets", plugin.asset), File("app/src/main/assets", plugin.asset))
            .first { it.isFile }.readBytes()
        fun frame(percent: Int?, event: String?): List<TileNode> {
            val snapshot = PluginInputs(PluginDevice("Test", "Device", "15", 35), PluginBattery(percent, false))
            val result = runtime.run(source, plugin.permissions, snapshot, TileRequest(event))
            val root = (result as PluginResult.Success).tile?.root as TileNode.Group
            assertEquals(root, TileUiCodec.decode(TileUiCodec.encode(root)))
            assertTrue(root.containsButton("refresh"))
            return root.children
        }
        assertTrue(TileNode.Text("Test Device") in frame(54, null))
        assertTrue(TileNode.Progress(.82f) in frame(82, "refresh"))
        assertFalse(frame(null, "refresh").any { it is TileNode.Progress })
    }

    @Test
    fun supportsContainerAndSpacerNodes() {
        val result = run("""
            hypr.ui.render({type="column",children={
                {type="card",children={
                    {type="row",children={{type="text",value="label"},{type="spacer",size=12}}}
                }}
            }})
        """.trimIndent()) as PluginResult.Success
        val root = checkNotNull(result.tile?.root)
        assertEquals(
            listOf("column", "1", "card", "1", "row", "2", "text", "label", "spacer", "12"),
            TileUiCodec.encode(root),
        )
    }

    @Test
    fun rejectsInvalidTypesUnknownPropertiesDuplicateIdsAndUnboundedTrees() {
        val invalid = listOf(
            """{type="view",value="bad"}""",
            """{type="text",value=false}""",
            """{type="progress",value=2}""",
            """{type="text",value="text",extra="bad"}""",
            """{type="row",children={{type="button",id="duplicate",text="A"},{type="button",id="duplicate",text="B"}}}""",
            """{type="column",children={{type="scrollableColumn",children={}}}}""",
            """{type="text",value="${"a".repeat(257)}"}""",
            """${"{type='column',children={".repeat(7)}{type="spacer",size=4}${"}}".repeat(7)}""",
            """{type="row",children={${List(49) { "{type='spacer',size=4}" }.joinToString(",")}}}""",
        )
        invalid.forEach { node ->
            assertTrue(node, run("hypr.ui.render($node)") is PluginResult.Failure)
        }
        val cycle = "local root={type='row',children={}}; root.children[1]=root; hypr.ui.render(root)"
        assertTrue(run(cycle) is PluginResult.Failure)
        assertThrows(IllegalArgumentException::class.java) {
            TileUiCodec.decode(listOf("progress", "NaN"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TileUiCodec.decode(listOf("row", "2", "spacer", "1"))
        }
    }

    @Test
    fun tileModeAndSystemPermissionsAreIndependentAndCallbackLoopIsBounded() {
        val render = "hypr.ui.render({type='text',value='hello'})"
        assertTrue(runtime.run(render.encodeToByteArray(), emptySet(), PluginInputs(), TileRequest()) is PluginResult.Failure)
        val denied = run("hypr.system.getDeviceInfo()") as PluginResult.Failure
        assertTrue(denied.message.contains("system.device"))
        val loop = run("hypr.ui.on('refresh', function() while true do end end)", "refresh") as PluginResult.Failure
        assertTrue(loop.message.contains("лимит инструкций"))
    }

    @Test
    fun dispatchesButtonAndPreservesOnlyExplicitState() {
        val script = """
            hypr.ui.on("refresh", function()
                hypr.ui.setState(hypr.ui.getState() .. "!")
                hypr.ui.render({type="text",value=hypr.ui.getState()})
            end)
        """.trimIndent()
        val result = runtime.run(
            script.encodeToByteArray(), setOf(PluginPermission.UI_TILE), PluginInputs(),
            TileRequest("refresh", "one"),
        ) as PluginResult.Success
        assertEquals(TileResult(TileNode.Text("one!"), "one!"), result.tile)
        assertTrue(run(script, "other") is PluginResult.Failure)
    }

    @Test
    fun tileSessionsRouteVisibleEventsSerializeRefreshAndIsolateState() = runBlocking {
        var time = 0L
        val first = TilePluginSession { time }
        val second = TilePluginSession { time }
        val seen = mutableListOf<TileRequest>()
        first.update { request ->
            seen += request
            ready(button, "first")
        }
        second.update { request ->
            seen += request
            ready(button, "second")
        }
        first.update("refresh") { error("Rate-limited") }
        time += 400_000_000L
        first.update("foreign") { error("Unknown button") }
        first.update("refresh") { request ->
            seen += request
            assertTrue(first.busy)
            first.update("refresh") { error("Busy session") }
            ready(button, "updated")
        }
        second.update("refresh") { request ->
            seen += request
            ready(button)
        }
        assertEquals(listOf(TileRequest(), TileRequest(), TileRequest("refresh", "first"), TileRequest("refresh", "second")), seen)
    }

    @Test
    fun sessionReportsWorkerFailureAndIgnoresRepliesAfterStop() = runBlocking {
        var time = 0L
        val session = TilePluginSession { time }
        session.update { PluginResult.Failure("Изолированный процесс Lua недоступен") }
        assertTrue(session.screen is TileScreen.Error)
        time += 400_000_000L
        session.update {
            session.stop()
            ready(button)
        }
        assertEquals(TileScreen.Empty, session.screen)
        session.update { error("Stopped tile cannot run") }
        assertFalse(session.busy)
    }

    private fun ready(root: TileNode, state: String = "") =
        PluginResult.Success(emptyList(), null, TileResult(root, state))

    private fun run(script: String, event: String? = null) = runtime.run(
        script.encodeToByteArray(), setOf(PluginPermission.UI_TILE), PluginInputs(), TileRequest(event),
    )
}

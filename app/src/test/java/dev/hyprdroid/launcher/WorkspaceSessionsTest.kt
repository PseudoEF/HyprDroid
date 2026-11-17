package dev.hyprdroid.launcher

import dev.hyprdroid.launcher.data.SessionsCodec
import dev.hyprdroid.launcher.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WorkspaceSessionsTest {
    @Test fun independentAssignmentsSurviveDisableAndRoundTrip() {
        val value = WorkspaceSessions().assign(0, "app.one/.Main", true).assign(0, "app.two/.Main", true)
            .assign(1, "app.one/.Main", true).copy(enabled = true)
        assertEquals(value, SessionsCodec.read(SessionsCodec.write(value)))
        val disabled = value.copy(enabled = false)
        assertEquals(value.apps, SessionsCodec.read(SessionsCodec.write(disabled)).apps)
        assertEquals(listOf("app.one/.Main"), value.assign(0, "app.two/.Main", false).apps[1])
        assertEquals(emptyList<String>(), value.apps[2])
        assertFalse(SessionsCodec.read(null).enabled)
    }
    @Test fun thirdAppIsRejectedWithoutChangingAssignments() {
        val value = WorkspaceSessions().assign(0, "app.one/.Main", true).assign(0, "app.two/.Main", true)
        try { value.assign(0, "app.three/.Main", true); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(2, value.apps[0].size)
        assertEquals(value, value.assign(0, "app.one/.Main", true))
    }
    @Test fun unknownOrBrokenConfigurationIsNeverSilentlyReset() {
        listOf("{}", "{\"version\":99}", "broken", "{\"version\":1,\"enabled\":false,\"apps\":[[],[],[\"invalid\"]]}").forEach {
            try { SessionsCodec.read(it); fail(it) } catch (_: IOException) { }
        }
    }
    @Test fun localJournalIsBoundedAndPreservesUnverifiedResult() {
        val events = (0..70).map { SessionEvent(it.toLong(), 0, "pair_request", "unverified") }
        val decoded = SessionsCodec.events(SessionsCodec.writeEvents(events))
        assertEquals(50, decoded.size)
        assertEquals(21L, decoded.first().time)
        assertEquals("unverified", decoded.last().result)
    }
}

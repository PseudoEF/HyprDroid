package dev.hyprdroid.launcher.plugins

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** At most one operation can use an isolated worker. Guarded by the service's Binder lock. */
internal class ExternalLuaSessionGate {
    private var session: Long? = null
    private var apiVersion = 0
    private var started = false
    private var closed = false

    fun create(id: Long, api: Int, expectedApi: Int = 1): Boolean {
        if (closed || session != null || id == 0L || api != expectedApi) return false
        apiVersion = api
        session = id
        return true
    }

    fun dispatch(id: Long, request: Int, event: Int): Boolean {
        if (closed || started || session != id || request != 1 || (if (apiVersion == 2) event != 0 else event !in 0..2)) return false
        started = true
        return true
    }

    fun dispose(id: Long): Boolean {
        if (closed || session != id) return false
        closed = true
        return true
    }
}

/** Revocation races the operation itself, independently of Compose disposal. */
internal suspend fun <T : Any> whileAuthorized(
    generations: StateFlow<Long>,
    expected: Long,
    execute: suspend () -> T,
): T? = coroutineScope {
    if (generations.value != expected) return@coroutineScope null
    val revoke = async { generations.first { it != expected } }
    val operation = async { execute() }
    try {
        select {
            revoke.onAwait { null }
            operation.onAwait { result -> result.takeIf { generations.value == expected } }
        }
    } finally {
        revoke.cancel()
        operation.cancel()
    }
}

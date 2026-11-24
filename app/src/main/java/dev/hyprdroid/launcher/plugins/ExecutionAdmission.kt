package dev.hyprdroid.launcher.plugins

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A completed invalidation cannot be followed by a commit from its old generation. */
internal class ExecutionAdmission {
    private val generation = MutableStateFlow(0L)
    val generations = generation.asStateFlow()
    private val publication = Mutex()
    private val active = mutableSetOf<Job>()

    suspend fun invalidate() = publication.withLock {
        val previous = synchronized(active) {
            generation.value++
            active.toList().also { active.clear() }
        }
        previous.forEach { it.cancel() }
    }

    /** Serialize the final one-way Binder enqueue with generation invalidation. */
    fun dispatch(expected: Long, send: () -> Unit): Boolean = synchronized(active) {
        if (generation.value != expected) return@synchronized false
        send()
        true
    }

    suspend fun <T : Any> run(expected: Long, operation: suspend () -> T): T? = coroutineScope {
        val request = async(start = CoroutineStart.LAZY) { operation() }
        try {
            val accepted = synchronized(active) {
                generation.value == expected && active.add(request)
            }
            if (!accepted) return@coroutineScope null
            request.await().takeIf { generation.value == expected }
        } catch (cancelled: CancellationException) {
            ensureActive()
            if (generation.value == expected) throw cancelled
            null
        } finally {
            synchronized(active) { active.remove(request) }
            request.cancel()
        }
    }

    suspend fun commit(expected: Long, apply: suspend () -> Unit): Boolean = publication.withLock {
        if (generation.value != expected) return@withLock false
        // Keep the fence until DataStore acknowledges the transaction even if
        // the tile's composition is cancelled while the disk write is in flight.
        withContext(NonCancellable) { apply() }
        true
    }
}

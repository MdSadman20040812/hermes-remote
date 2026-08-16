package com.hermes.mobile.core.transport

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Exponential backoff with jitter for the RPC socket, per spec §C.2:
 * 0.5s → 1s → 2s → 4s → 8s → 15s cap, ±20% jitter, reset on gateway.ready.
 *
 * Pure logic — no Android, no I/O — so it is fully unit-testable.
 */
class ReconnectPolicy(
    private val random: Random = Random.Default,
    private val baseMs: Long = 500,
    private val maxMs: Long = 15_000,
    private val jitterFraction: Double = 0.20,
) {
    /** Consecutive failed attempts since the last [reset]. */
    var attempt: Int = 0
        private set

    /** Delay to wait before the next connect attempt, then increments [attempt]. */
    fun nextDelay(): Duration {
        val unclamped = baseMs shl attempt.coerceAtMost(20)
        val clamped = minOf(unclamped, maxMs)
        val jitter = (clamped * jitterFraction).toLong()
        val delta = if (jitter > 0) random.nextLong(-jitter, jitter + 1) else 0L
        attempt++
        return (clamped + delta).coerceAtLeast(0).milliseconds
    }

    /** Call on gateway.ready — the socket is healthy again. */
    fun reset() {
        attempt = 0
    }
}

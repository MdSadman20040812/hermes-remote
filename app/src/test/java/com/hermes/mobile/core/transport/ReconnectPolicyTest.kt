package com.hermes.mobile.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {

    @Test
    fun `backoff doubles from 500ms and caps at 15s`() {
        val policy = ReconnectPolicy(random = Random(42), jitterFraction = 0.0)
        val expected = listOf(500L, 1000L, 2000L, 4000L, 8000L, 15000L, 15000L, 15000L)
        val actual = (1..8).map { policy.nextDelay().inWholeMilliseconds }
        assertEquals(expected, actual)
    }

    @Test
    fun `jitter stays within plus minus 20 percent`() {
        val policy = ReconnectPolicy(random = Random(7))
        repeat(200) {
            val delay = policy.nextDelay().inWholeMilliseconds
            val base = minOf(500L shl (policy.attempt - 1).coerceAtMost(20), 15000L)
            val tolerance = (base * 0.20).toLong() + 1
            assertTrue(
                "delay $delay outside [$base±$tolerance]",
                delay in (base - tolerance)..(base + tolerance),
            )
        }
    }

    @Test
    fun `reset returns backoff to the start`() {
        val policy = ReconnectPolicy(random = Random(1), jitterFraction = 0.0)
        policy.nextDelay(); policy.nextDelay(); policy.nextDelay()
        assertEquals(3, policy.attempt)
        policy.reset()
        assertEquals(0, policy.attempt)
        assertEquals(500L, policy.nextDelay().inWholeMilliseconds)
    }
}

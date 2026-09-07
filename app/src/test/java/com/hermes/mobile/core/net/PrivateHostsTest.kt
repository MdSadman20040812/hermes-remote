package com.hermes.mobile.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cleartext guard is the only thing standing between a bad profile and the
 * dashboard password crossing the internet in the clear, so its boundaries are
 * pinned here rather than assumed.
 */
class PrivateHostsTest {

    @Test
    fun `rfc1918 ranges are private`() {
        listOf(
            "192.168.1.111", "192.168.0.1", "10.0.0.5", "10.255.255.254",
            "172.16.0.1", "172.31.255.254", "127.0.0.1", "169.254.1.2",
        ).forEach { assertTrue(it, PrivateHosts.isPrivate(it)) }
    }

    @Test
    fun `172 range boundaries are exact`() {
        assertFalse(PrivateHosts.isPrivate("172.15.0.1"))
        assertTrue(PrivateHosts.isPrivate("172.16.0.1"))
        assertTrue(PrivateHosts.isPrivate("172.31.0.1"))
        assertFalse(PrivateHosts.isPrivate("172.32.0.1"))
    }

    @Test
    fun `cgnat range covers tailnet addresses`() {
        assertTrue(PrivateHosts.isPrivate("100.88.18.123"))
        assertTrue(PrivateHosts.isPrivate("100.64.0.1"))
        assertTrue(PrivateHosts.isPrivate("100.127.255.254"))
        // 100.63.x and 100.128.x are ordinary public space, not CGNAT.
        assertFalse(PrivateHosts.isPrivate("100.63.0.1"))
        assertFalse(PrivateHosts.isPrivate("100.128.0.1"))
    }

    @Test
    fun `public addresses and names are not private`() {
        listOf("8.8.8.8", "1.1.1.1", "203.0.113.9", "example.com", "").forEach {
            assertFalse(it, PrivateHosts.isPrivate(it))
        }
    }

    @Test
    fun `local hostnames are accepted`() {
        assertTrue(PrivateHosts.isPrivate("localhost"))
        assertTrue(PrivateHosts.isPrivate("warnerbros-pc.local"))
    }

    @Test
    fun `malformed input never throws and is never private`() {
        listOf("999.1.1.1", "1.2.3", "1.2.3.4.5", "a.b.c.d", "192.168.1.-1").forEach {
            assertFalse(it, PrivateHosts.isPrivate(it))
        }
    }

    @Test
    fun `plain http to a public host is rejected`() {
        assertNotNull(PrivateHosts.rejectionReason("203.0.113.9", secure = false))
    }

    @Test
    fun `https to a public host is allowed`() {
        assertNull(PrivateHosts.rejectionReason("203.0.113.9", secure = true))
    }

    @Test
    fun `plain http to the LAN is allowed`() {
        assertNull(PrivateHosts.rejectionReason("192.168.1.111", secure = false))
    }

    @Test
    fun `case and whitespace do not defeat the check`() {
        assertEquals(true, PrivateHosts.isPrivate("  192.168.1.5 "))
        assertEquals(true, PrivateHosts.isPrivate("LOCALHOST"))
    }
}

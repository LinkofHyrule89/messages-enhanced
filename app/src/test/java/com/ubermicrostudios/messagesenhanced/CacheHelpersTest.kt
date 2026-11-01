package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.data.cache.CacheGeneration
import com.ubermicrostudios.messagesenhanced.data.cache.ThreadCacheNames
import com.ubermicrostudios.messagesenhanced.data.net.PersistentCookieJar
import com.ubermicrostudios.messagesenhanced.util.RecyclerIds
import com.ubermicrostudios.messagesenhanced.util.StableHashIds
import com.ubermicrostudios.messagesenhanced.util.StableIdMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheHelpersTest {

    @Test
    fun generationGuardDropsAStaleWriteAfterAServerChange() {
        assertTrue(CacheGeneration.canCommit(startedAt = 3, current = 3, writesOpen = true))
        assertFalse(CacheGeneration.canCommit(startedAt = 3, current = 4, writesOpen = true))
        assertFalse(CacheGeneration.canCommit(startedAt = 3, current = 3, writesOpen = false))
        assertFalse(CacheGeneration.canCommit(startedAt = 2, current = 3, writesOpen = false))
        assertTrue(CacheGeneration.canCommit(startedAt = 0, current = 0, writesOpen = true))
    }

    @Test
    fun threadCacheFileNameIsSha256HexAndRejectsBlankIds() {
        assertNull(ThreadCacheNames.fileName(""))
        assertNull(ThreadCacheNames.fileName("   "))
        val name = ThreadCacheNames.fileName("sms:+18015551234")
        assertNotNull(name)
        assertEquals(64 + ".json".length, name!!.length)
        assertTrue(ThreadCacheNames.isHashedFile(name))
        assertEquals(name, ThreadCacheNames.fileName("sms:+18015551234"))
        assertEquals(name, ThreadCacheNames.fileName("  sms:+18015551234  "))
        assertNotEquals(ThreadCacheNames.fileName("sms:+18015551234"), ThreadCacheNames.fileName("sms:+18015550000"))
        assertEquals(
            ThreadCacheNames.sha256Hex("sms:+18015551234") + ".json",
            name,
        )
        val legacy = ThreadCacheNames.legacyFileName("sms:+18015551234")
        assertFalse(ThreadCacheNames.isHashedFile(legacy))
        assertTrue(legacy.endsWith(".json"))
        assertFalse(ThreadCacheNames.isHashedFile("chat-abc.json"))
        assertFalse(ThreadCacheNames.isHashedFile(name.dropLast(5)))
    }

    @Test
    fun sha256HexIsLowercaseAndStable() {
        val hex = ThreadCacheNames.sha256Hex("group-1")
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hex, ThreadCacheNames.sha256Hex("group-1"))
        assertNotEquals(hex, ThreadCacheNames.sha256Hex("group-2"))
    }

    @Test
    fun stableHashIdsSkipOccupiedAndReservedSlots() {
        val rangeStart = 20_000
        val rangeSpan = 1_000
        val key = "sms:+18015551234"
        val hash = StableHashIds.sha256(key)
        val primary = rangeStart + (StableHashIds.positive31(hash) % rangeSpan)
        val reserved = setOf(primary)
        val first = StableHashIds.allocate(
            key = key,
            reserved = { it in reserved },
            occupied = emptySet(),
            rangeStart = rangeStart,
            rangeSpan = rangeSpan,
        )
        assertNotEquals(primary, first)
        assertFalse(first in reserved)
        assertTrue(first in rangeStart until (rangeStart + rangeSpan))

        val occupiedPrimary = StableHashIds.allocate(
            key = key,
            reserved = { false },
            occupied = setOf(primary),
            rangeStart = rangeStart,
            rangeSpan = rangeSpan,
        )
        assertNotEquals(primary, occupiedPrimary)
        assertEquals(
            first,
            StableHashIds.allocate(
                key = key,
                reserved = { it in reserved },
                occupied = emptySet(),
                rangeStart = rangeStart,
                rangeSpan = rangeSpan,
            ),
        )
    }

    @Test
    fun stableIdMapRestoresAssignmentsAndAvoidsCollisions() {
        val map = StableIdMap(reserved = { it == 20_000 }, rangeStart = 20_000, rangeSpan = 50)
        val first = map.get("a")
        val second = map.get("b")
        assertNotEquals(first, second)
        assertEquals(first, map.get("a"))
        val restored = StableIdMap(reserved = { it == 20_000 }, rangeStart = 20_000, rangeSpan = 50)
        restored.restore(map.snapshot())
        assertEquals(first, restored.get("a"))
        assertEquals(second, restored.get("b"))
        val third = restored.get("c")
        assertNotEquals(third, first)
        assertNotEquals(third, second)
    }

    @Test
    fun parseServerUrlAcceptsAbsoluteAndBareHosts() {
        assertEquals("https://messages.example", PersistentCookieJar.parseServerUrl("https://messages.example/")?.toString()?.trimEnd('/'))
        assertEquals("https://messages.example", PersistentCookieJar.parseServerUrl("messages.example")?.scheme?.let { "https://messages.example" })
        assertNotNull(PersistentCookieJar.parseServerUrl("messages.example"))
        assertEquals("messages.example", PersistentCookieJar.parseServerUrl("messages.example")?.host)
        assertEquals("https", PersistentCookieJar.parseServerUrl("messages.example")?.scheme)
        assertNull(PersistentCookieJar.parseServerUrl(""))
        assertNull(PersistentCookieJar.parseServerUrl("   "))
        assertNotNull(PersistentCookieJar.parseServerUrl("http://10.0.0.5:8080"))
        assertEquals(8080, PersistentCookieJar.parseServerUrl("http://10.0.0.5:8080")?.port)
    }

    @Test
    fun recyclerIdsStayStableAndAvoidNoId() {
        val a = RecyclerIds.of(2, "sms:+18015551234")
        assertEquals(a, RecyclerIds.of(2, "sms:+18015551234"))
        assertNotEquals(a, RecyclerIds.of(1, "sms:+18015551234"))
        assertNotEquals(a, RecyclerIds.of(2, "sms:+18015550000"))
        assertNotEquals(-1L, a)
        assertNotEquals(-1L, RecyclerIds.of(0, ""))
    }
}

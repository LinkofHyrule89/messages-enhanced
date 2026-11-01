package com.ubermicrostudios.messagesenhanced.util

import java.security.MessageDigest

/**
 * Stable positive ids from SHA-256. The first unused 31-bit window in
 * [[rangeStart], [rangeStart] + [rangeSpan]) that is not reserved and not already
 * taken is returned. A later call with the same key and the same occupied set
 * yields the same id.
 */
object StableHashIds {
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256(value: String): ByteArray = sha256(value.toByteArray(Charsets.UTF_8))

    /** Four bytes at [byteOffset] as a 31-bit non-negative int. */
    fun positive31(hash: ByteArray, byteOffset: Int = 0): Int {
        val start = byteOffset.coerceIn(0, (hash.size - 4).coerceAtLeast(0))
        var v = 0
        for (i in 0 until 4) {
            val index = (start + i).coerceAtMost(hash.lastIndex)
            v = (v shl 8) or (hash[index].toInt() and 0xff)
        }
        return v and 0x7fffffff
    }

    fun allocate(
        key: String,
        reserved: (Int) -> Boolean,
        occupied: Set<Int>,
        rangeStart: Int,
        rangeSpan: Int,
    ): Int {
        require(rangeSpan > 0) { "rangeSpan" }
        val hash = sha256(key)
        val lastOffset = (hash.size - 4).coerceAtLeast(0)
        for (offset in 0..lastOffset) {
            val id = rangeStart + (positive31(hash, offset) % rangeSpan)
            if (!reserved(id) && id !in occupied) return id
        }
        var id = rangeStart + (positive31(hash, 0) % rangeSpan)
        repeat(rangeSpan) {
            if (!reserved(id) && id !in occupied) return id
            id++
            if (id >= rangeStart + rangeSpan) id = rangeStart
        }
        error("id space exhausted")
    }
}

/**
 * In-memory assignment table. Restored snapshots keep prior ids so a process
 * restart does not swap two conversations that hashed to the same slot.
 */
class StableIdMap(
    private val reserved: (Int) -> Boolean,
    private val rangeStart: Int,
    private val rangeSpan: Int,
) {
    private val byKey = LinkedHashMap<String, Int>()
    private val used = HashSet<Int>()

    @Synchronized
    fun get(key: String): Int {
        byKey[key]?.let { return it }
        val id = StableHashIds.allocate(key, reserved, used, rangeStart, rangeSpan)
        byKey[key] = id
        used.add(id)
        return id
    }

    @Synchronized
    fun snapshot(): Map<String, Int> = LinkedHashMap(byKey)

    @Synchronized
    fun restore(assigned: Map<String, Int>) {
        byKey.clear()
        used.clear()
        for ((key, id) in assigned) {
            if (key.isBlank()) continue
            byKey[key] = id
            used.add(id)
        }
    }

    @Synchronized
    fun clear() {
        byKey.clear()
        used.clear()
    }
}

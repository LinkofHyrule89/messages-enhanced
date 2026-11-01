package com.ubermicrostudios.messagesenhanced.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadReceiptsTest {
    @Test fun dedupesByNameAndNumber() {
        assertEquals(listOf("Alex", "+1 555 010 0001"),
            ReadReceipts.dedupe(listOf("Alex", " alex ", "+1 555 010 0001", "5550100001", "")))
    }
    @Test fun caps() = assertEquals(2, ReadReceipts.dedupe(listOf("a", "b", "c"), max = 2).size)
}

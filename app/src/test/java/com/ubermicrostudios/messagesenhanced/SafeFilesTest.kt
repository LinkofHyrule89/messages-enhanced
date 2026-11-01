package com.ubermicrostudios.messagesenhanced

import com.ubermicrostudios.messagesenhanced.util.SafeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafeFilesTest {
    @Test
    fun fileWithinAcceptsAChildAndRejectsEscapes() {
        val root = File(System.getProperty("java.io.tmpdir"), "safe-files-" + System.nanoTime()).apply { mkdirs() }
        try {
            val child = File(root, "a/b.txt").apply {
                parentFile?.mkdirs()
                writeText("x")
            }
            assertEquals(child.canonicalFile, SafeFiles.fileWithin(root, child.absolutePath))
            assertEquals(child.canonicalFile, SafeFiles.fileWithin(root, "file://${child.absolutePath}"))

            val outside = File(root.parentFile, "outside-" + System.nanoTime() + ".txt").apply { writeText("y") }
            try {
                assertNull(SafeFiles.fileWithin(root, outside.absolutePath))
                val nested = File(root, "nested").apply { mkdirs() }
                assertNull(SafeFiles.fileWithin(root, File(nested, "../../${outside.name}").path))
            } finally {
                outside.delete()
            }

            assertNull(SafeFiles.fileWithin(root, root.absolutePath))
            assertNull(SafeFiles.fileWithin(root, "   "))
            assertNull(SafeFiles.fileWithin(root, "file://"))

            val evil = File(root.parentFile, root.name + "-evil").apply { mkdirs() }
            try {
                val evilFile = File(evil, "x.txt").apply { writeText("z") }
                assertNull(SafeFiles.fileWithin(root, evilFile.absolutePath))
            } finally {
                evil.deleteRecursively()
            }

            val link = File(root, "leave")
            val target = File(root.parentFile, "linked-" + System.nanoTime() + ".txt").apply { writeText("q") }
            try {
                Files.createSymbolicLink(link.toPath(), target.toPath())
                assertNull(SafeFiles.fileWithin(root, link.absolutePath))
            } finally {
                link.delete()
                target.delete()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

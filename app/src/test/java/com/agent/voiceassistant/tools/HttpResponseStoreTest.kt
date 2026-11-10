package com.agent.voiceassistant.tools

import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class HttpResponseStoreTest {
    @Test
    fun `large textual response is cached after preview threshold`() {
        val root = File(System.getProperty("java.io.tmpdir"), "http-response-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val raw = ByteArray((HttpResponseStore.MAX_PREVIEW_BYTES + 1234).toInt()) { (it % 251).toByte() }
            val store = HttpResponseStore(root)
            val capture = store.capture(Buffer().write(raw), raw.size.toLong(), "application/json")
            val artifact = capture.artifact ?: error("expected cached artifact")
            assertEquals(raw.size.toLong(), artifact.bytes)
            assertFalse(artifact.truncated)
            assertTrue(capture.displayTruncated)
            assertArrayEquals(raw, File(root, ".http-cache").resolve(artifact.virtualPath.substringAfterLast('/')).readBytes())
            assertArrayEquals(raw.copyOf(HttpResponseStore.MAX_PREVIEW_BYTES.toInt()), capture.preview)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `download target is persistent and cache cleanup is isolated`() {
        val root = File(System.getProperty("java.io.tmpdir"), "http-response-${UUID.randomUUID()}").apply { mkdirs() }
        var clock = 1_000_000L
        try {
            val target = File(root, "downloads/result.txt")
            val store = HttpResponseStore(root) { clock }
            val raw = "downloaded content".toByteArray()
            val capture = store.capture(
                Buffer().write(raw),
                raw.size.toLong(),
                "text/plain",
                downloadTarget = target,
                downloadVirtualPath = "/workspace/downloads/result.txt",
            )
            val artifact = capture.artifact ?: error("expected download artifact")
            assertEquals("/workspace/downloads/result.txt", artifact.virtualPath)
            assertNull(artifact.expiresAt)
            assertArrayEquals(raw, target.readBytes())

            val cacheRaw = ByteArray((HttpResponseStore.MAX_PREVIEW_BYTES + 1).toInt()) { 7 }
            val cached = store.capture(Buffer().write(cacheRaw), cacheRaw.size.toLong(), "text/plain").artifact
            val cacheFile = File(root, ".http-cache").resolve(cached!!.virtualPath.substringAfterLast('/'))
            assertTrue(cacheFile.exists())
            clock += HttpResponseStore.CACHE_TTL_MS + 1
            store.cleanup()
            assertFalse(cacheFile.exists())
            assertTrue(target.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `empty download still creates an atomic file`() {
        val root = File(System.getProperty("java.io.tmpdir"), "http-response-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val target = File(root, "downloads/empty.bin")
            val capture = HttpResponseStore(root).capture(
                Buffer(),
                contentLength = 0L,
                contentType = "application/octet-stream",
                downloadTarget = target,
                downloadVirtualPath = "/workspace/downloads/empty.bin",
            )
            val artifact = capture.artifact ?: error("expected empty download artifact")
            assertEquals(0L, artifact.bytes)
            assertEquals("/workspace/downloads/empty.bin", artifact.virtualPath)
            assertTrue(target.exists())
            assertEquals(0L, target.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `short network reads do not truncate a download`() {
        val root = File(System.getProperty("java.io.tmpdir"), "http-response-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val raw = ByteArray(20_000) { (it % 251).toByte() }
            val target = File(root, "downloads/short-read.bin")
            val source: BufferedSource = ShortReadSource(Buffer().write(raw), maxBytes = 7).buffer()
            val capture = HttpResponseStore(root).capture(
                source,
                contentLength = raw.size.toLong(),
                contentType = "application/octet-stream",
                downloadTarget = target,
                downloadVirtualPath = "/workspace/downloads/short-read.bin",
            )
            assertEquals(raw.size.toLong(), capture.artifact?.bytes)
            assertFalse(capture.artifact?.truncated == true)
            assertArrayEquals(raw, target.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private class ShortReadSource(
        private val delegate: Source,
        private val maxBytes: Long,
    ) : Source {
        override fun read(sink: Buffer, byteCount: Long): Long = delegate.read(sink, minOf(byteCount, maxBytes))
        override fun timeout(): Timeout = delegate.timeout()
        override fun close() = delegate.close()
    }
}

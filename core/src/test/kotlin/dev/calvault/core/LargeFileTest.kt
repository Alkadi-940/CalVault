package dev.calvault.core

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Opt-in disk-intensive regression: ./gradlew.bat :core:test -PlargeFileTest=true */
class LargeFileTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun twoGiBImportSeekExportUsesBoundedBuffers() {
        assumeTrue(java.lang.Boolean.getBoolean("calvault.largeTest"))
        val deviceKey = Crypto.random(32)
        val device = object : DeviceKey {
            override fun seal(plaintext: ByteArray, context: ByteArray) = Crypto.seal(deviceKey, plaintext, context)
            override fun open(ciphertext: ByteArray, context: ByteArray) = Crypto.open(deviceKey, ciphertext, context)
        }
        val vault = Vault(temp.root, device)
        vault.create("123456".toCharArray(), Crypto.recoverySecret())
        val length = 2L * 1024 * 1024 * 1024 + 123
        val expected = MessageDigest.getInstance("SHA-256")
        val pattern = ByteArray(64 * 1024) { (it * 31 + 17).toByte() }
        val source = object : InputStream() {
            var position = 0L
            override fun read(): Int {
                if (position == length) return -1
                val value = pattern[(position++ % pattern.size).toInt()].toInt() and 255
                expected.update(value.toByte()); return value
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position == length) return -1
                val count = minOf(len.toLong(), length - position).toInt()
                var copied = 0
                while (copied < count) {
                    val start = ((position + copied) % pattern.size).toInt()
                    val take = minOf(count - copied, pattern.size - start)
                    pattern.copyInto(b, off + copied, start, start + take); copied += take
                }
                expected.update(b, off, count); position += count; return count
            }
        }
        val started = System.nanoTime()
        val item = vault.importFile("large-synthetic.bin", "application/octet-stream", "", source)
        assertEquals(length, item.size)
        vault.openMedia(item.id).use { channel ->
            channel.position(length - 123)
            val tail = ByteBuffer.allocate(123)
            while (tail.hasRemaining()) assertTrue(channel.read(tail) > 0)
            assertArrayEquals(ByteArray(123) { pattern[((length - 123 + it) % pattern.size).toInt()] }, tail.array())
        }
        val actual = MessageDigest.getInstance("SHA-256")
        var written = 0L
        val output = object : OutputStream() {
            override fun write(value: Int) { actual.update(value.toByte()); written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { actual.update(b, off, len); written += len }
        }
        vault.export(item.id, output)
        assertEquals(length, written)
        assertArrayEquals(expected.digest(), actual.digest())
        println("2 GiB + 123 byte import, complete authentication, tail seek, and export passed in ${(System.nanoTime() - started) / 1_000_000} ms with a 1 GiB test-heap ceiling.")
    }
}

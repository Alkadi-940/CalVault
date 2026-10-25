package dev.calvault.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { it.block() }
    bytes.toByteArray()
}
internal fun <T> decode(bytes: ByteArray, block: DataInputStream.() -> T): T =
    DataInputStream(ByteArrayInputStream(bytes)).use {
        val result = it.block()
        require(it.read() == -1) { "Unexpected trailing data" }
        result
    }
internal fun DataOutputStream.blob(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
internal fun DataInputStream.blob(max: Int): ByteArray {
    val size = readInt()
    require(size in 0..max) { "Invalid record length" }
    return ByteArray(size).also(::readFully)
}
internal fun File.readBounded(max: Int): ByteArray {
    require(length() in 0..max.toLong()) { "Record is too large" }
    return inputStream().use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= max) { "Record is too large" }
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    }
}
internal fun atomicWrite(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

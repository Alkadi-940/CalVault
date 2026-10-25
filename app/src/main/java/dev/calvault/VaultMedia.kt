package dev.calvault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.calvault.core.Vault
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultDataSource(private val vault: Vault, private val id: String) : BaseDataSource(false) {
    private var channel: SeekableByteChannel? = null
    private var remaining = 0L
    private var uri: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val input = vault.openMedia(id)
            channel = input
            require(dataSpec.position <= input.size()) { "Invalid media position" }
            input.position(dataSpec.position)
            remaining = input.size() - dataSpec.position
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
            uri = dataSpec.uri
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) { channel?.close(); channel = null; throw IOException("Unable to open encrypted media", e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val count = checkNotNull(channel).read(ByteBuffer.wrap(buffer, offset, minOf(remaining, length.toLong()).toInt()))
            if (count < 0) return C.RESULT_END_OF_INPUT
            remaining -= count; bytesTransferred(count); return count
        } catch (e: Exception) { throw IOException("Media unavailable or vault locked", e) }
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        val wasOpen = channel != null
        try { channel?.close() } finally { channel = null; uri = null; if (wasOpen) transferEnded() }
    }
    class Factory(private val vault: Vault, private val id: String) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(vault, id)
    }
}

fun decodeVaultPhoto(vault: Vault, id: String, maxDimension: Int = 2048): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    vault.openMedia(id).use { BitmapFactory.decodeStream(Channels.newInputStream(it), null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format cannot be previewed" }
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
    return vault.openMedia(id).use {
        checkNotNull(BitmapFactory.decodeStream(Channels.newInputStream(it), null, BitmapFactory.Options().apply { inSampleSize = sample })) {
            "This image format cannot be previewed"
        }
    }
}

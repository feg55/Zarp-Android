package io.github.feg55.zarp.strategy

import io.github.feg55.zarp.core.Blob
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Real fake payloads from zapret2 (files/fake, MIT), shipped in assets/blobs.
 * Captured QUIC Initials / ClientHellos of google.com and vk.com, never random bytes.
 */
class BlobStore(private val open: (String) -> InputStream) : BlobSource {
    private val cache = ConcurrentHashMap<Blob, ByteArray>()

    override fun bytes(blob: Blob): ByteArray = cache.getOrPut(blob) { load(blob) }

    private fun load(blob: Blob): ByteArray {
        val asset = blob.asset ?: return ByteArray(64) // zero64
        val data = open("blobs/$asset").use { it.readBytes() }
        if (data.isEmpty()) throw IOException("blob $asset is empty")
        return data
    }
}

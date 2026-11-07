package dev.latestaiturn.core

import java.security.MessageDigest

/** Content-addressed text storage: the same text is stored once, whatever file it belongs to. */
interface BlobStore {
    fun put(text: String): String
    fun get(hash: String): String?
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class InMemoryBlobStore : BlobStore {
    private val blobs = HashMap<String, String>()
    override fun put(text: String): String = sha256(text).also { blobs[it] = text }
    override fun get(hash: String): String? = blobs[hash]
}

package app.localizeme.sdk

import java.security.MessageDigest

internal object Sha256 {
    fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

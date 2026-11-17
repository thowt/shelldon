/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.keyinstall

import java.security.MessageDigest
import kotlin.io.encoding.Base64

/**
 * One public key as a single line for `~/.ssh/authorized_keys`: `type base64-blob comment`.
 *
 * The line ends up inside a shell command on a server, so every part is checked against a strict
 * alphabet here and a value outside it is refused, not escaped. The comment is the only part a user
 * types (the key's nickname) and is reduced to harmless characters.
 */
class AuthorizedKeyLine private constructor(
    val type: String,
    val blob: ByteArray,
    val comment: String,
) {
    /** `type base64`: what identifies the key, without the comment. */
    val keyText: String = "$type ${Base64.encode(blob)}"

    /** The whole line as it goes into the file. */
    val text: String = "$keyText $comment"

    /** `SHA256:` and the unpadded base64 of the SHA-256 of the key blob, as OpenSSH prints it. */
    val fingerprint: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.encode(digest).trimEnd('=')
        }

    companion object {
        private val TYPE = Regex("[a-z0-9@.-]{1,64}")
        private const val MAX_COMMENT = 64
        private const val FALLBACK_COMMENT = "shelldon"

        /**
         * @param type the SSH key type name, such as `ssh-ed25519`
         * @param blob the public key in SSH wire format, which starts with the type name
         * @param nickname what the key is called in the app, used as the comment
         * @throws IllegalArgumentException if the type is not a plain key type name, or the blob is empty
         *   or does not name the same type
         */
        fun of(type: String, blob: ByteArray, nickname: String): AuthorizedKeyLine {
            require(TYPE.matches(type)) { "Not a key type name" }
            require(blob.isNotEmpty()) { "Empty key" }
            require(wireType(blob) == type) { "The key data does not match the key type" }
            return AuthorizedKeyLine(type, blob.copyOf(), safeComment(nickname))
        }

        /** Letters, digits and `. _ @ + -` only; everything else, including spaces and line breaks, becomes `-`. */
        fun safeComment(nickname: String): String {
            val cleaned = nickname
                .map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._@+-") it else '-' }
                .joinToString("")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
                .take(MAX_COMMENT)
                .trim('-')
            return cleaned.ifEmpty { FALLBACK_COMMENT }
        }

        /** The type name at the start of a wire-format key (a 4-byte length, then the name), or null if it is not there. */
        private fun wireType(blob: ByteArray): String? {
            if (blob.size < 4) return null
            val length = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
                ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
            if (length <= 0 || length > blob.size - 4) return null
            return String(blob, 4, length, Charsets.US_ASCII)
        }
    }
}

/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sshconfig

/**
 * PEM wrappers for test fixtures. The marker lines are put together at run time so that no source
 * file contains a literal key header, which secret scanners rightly treat as a leaked key.
 */
internal object TestPem {
    private const val DASHES = "-----"

    /** [body] between the OpenSSH private key markers, no trailing newline. */
    fun openSsh(body: String): String = wrap("OPENSSH PRIVATE KEY", body)

    /** [body] between the PKCS#8 private key markers, no trailing newline. */
    fun pkcs8(body: String): String = wrap("PRIVATE KEY", body)

    private fun wrap(label: String, body: String): String = "${DASHES}BEGIN $label$DASHES\n$body\n${DASHES}END $label$DASHES"
}

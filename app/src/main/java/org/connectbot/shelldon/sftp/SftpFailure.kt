/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.sftp

import org.connectbot.sshlib.SftpResult
import org.connectbot.sshlib.SftpStatusCode

/** Why a file operation failed, in terms the screen can turn into a sentence. */
enum class SftpProblem {
    NOT_FOUND,
    PERMISSION_DENIED,
    ALREADY_EXISTS,
    CONNECTION_LOST,
    UNSUPPORTED,

    /** The file got shorter while it was being read. */
    TRUNCATED,
    INVALID_NAME,
    TOO_DEEP,
    OTHER,
}

class SftpFailure(val problem: SftpProblem, val detail: String = "", cause: Throwable? = null) : Exception(detail.ifEmpty { problem.name }, cause)

internal fun <T> SftpResult<T>.orFail(): T = when (this) {
    is SftpResult.Success -> value

    is SftpResult.ServerError -> throw SftpFailure(
        when (statusCode) {
            SftpStatusCode.NO_SUCH_FILE -> SftpProblem.NOT_FOUND
            SftpStatusCode.PERMISSION_DENIED -> SftpProblem.PERMISSION_DENIED
            SftpStatusCode.NO_CONNECTION, SftpStatusCode.CONNECTION_LOST -> SftpProblem.CONNECTION_LOST
            SftpStatusCode.OP_UNSUPPORTED -> SftpProblem.UNSUPPORTED
            else -> SftpProblem.OTHER
        },
        message.ifBlank { statusCode.name },
    )

    is SftpResult.ProtocolError -> throw SftpFailure(SftpProblem.OTHER, message)

    is SftpResult.IoError -> throw SftpFailure(SftpProblem.CONNECTION_LOST, cause.message ?: "I/O error", cause)
}

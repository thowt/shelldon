/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.ssh

import org.connectbot.sshlib.SshClientConfig

/**
 * What Shelldon announces in the SSH identification line (RFC 4253 section 4.2), which every server
 * and every network observer can read before encryption starts.
 *
 * The library's own default names the project this app was forked from. The line is a fingerprint
 * either way, so it carries the app name and nothing else: no version, no device, no Android release.
 * Servers only use it to switch on workarounds for known buggy implementations (OpenSSH matches
 * patterns such as `OpenSSH_2.*` or `Cisco-1.*`), and an unknown name gets no workaround, the same
 * as the library default did.
 */
object ShelldonSshIdentity {
    /** `SSH-protoversion-softwareversion`; the software version is printable ASCII without space or `-`. */
    const val CLIENT_VERSION = "SSH-2.0-ShelldonSSH"

    /** Call from every [SshClientConfig] the app builds, jump hosts included. */
    fun applyTo(builder: SshClientConfig.Builder) {
        builder.clientVersion = CLIENT_VERSION
    }
}

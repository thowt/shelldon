/*
 * Shelldon SSH
 * Copyright 2026 the Shelldon SSH contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.connectbot.shelldon.vault

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VaultModule {
    /** App-private storage; excluded from backups because `allowBackup` is off. */
    @Provides
    @Singleton
    fun provideVault(@ApplicationContext context: Context): Vault = Vault(File(context.filesDir, "shelldon-vault.hdr"))
}

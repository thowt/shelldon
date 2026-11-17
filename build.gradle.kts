// Top-level build file where you can add configuration options common to all sub-projects/modules.

import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep

buildscript {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    extra.set("TRANSLATIONS_ONLY", System.getenv("TRANSLATIONS_ONLY")?.trim())
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.findLibrary("kotlin-gradle-plugin").get())
        classpath(libs.findLibrary("ksp-gradle-plugin").get())
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.spotless)
}

subprojects {
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
    }
}

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

// Shelldon-written files that sit in an upstream package because they test an upstream class.
val shelldonOwnedOutsideItsPackage = "app/src/test/kotlin/org/connectbot/data/PubkeyRepositoryVaultTest.kt"

spotless {
    // Local runs only look at files changed since main. CI passes -PspotlessFull and checks everything.
    if (!providers.gradleProperty("spotlessFull").isPresent) {
        ratchetFrom = "origin/main"
    }

    java {
        target("app/src/main/java/org/connectbot/**/*.java")
        importOrder()
        removeUnusedImports()
        googleJavaFormat()
        licenseHeaderFile("spotless/license-header.txt")
    }

    // One ktlint run over all Kotlin, upstream and Shelldon code alike. Spotless allows only one
    // kotlin block, so it carries no licence header; the headers come from the two blocks below.
    kotlin {
        target("app/src/**/*.kt", "build-logic/src/**/*.kt", "ctap2/src/**/*.kt", "ctap2-android/src/**/*.kt")
        ktlint("1.8.0")
            .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:0.6.8"))
    }

    // Licence headers, one block per owner. The delimiter lets a file start with @file: annotations.
    format("upstreamKotlinHeader") {
        target("app/src/**/*.kt", "build-logic/src/**/*.kt", "ctap2/src/**/*.kt", "ctap2-android/src/**/*.kt")
        // Project-owned code carries the Shelldon header instead (next block).
        targetExclude("app/src/**/org/connectbot/shelldon/**", shelldonOwnedOutsideItsPackage)
        licenseHeaderFile("spotless/license-header.txt", "(package |@file|import )")
    }

    // Shelldon-owned Kotlin files: their own licence header.
    format("shelldonKotlin") {
        target("app/src/**/org/connectbot/shelldon/**/*.kt", shelldonOwnedOutsideItsPackage)
        licenseHeaderFile("spotless/shelldon-license-header.txt", "(package |@file|import )")
        trimTrailingWhitespace()
        endWithNewline()
    }

    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint("1.8.0")
    }

    format("xml") {
        target("**/*.xml")
        targetExclude("**/.idea/**/*.xml", "**/build/**/*.xml")
        trimTrailingWhitespace()
        endWithNewline()
    }

    yaml {
        target(".github/**/*.yml", ".github/**/*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("toml") {
        target("**/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {
        target(listOf("**/*.md", "**/.gitignore", "**/.gitattributes", "**/.editorconfig"))
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

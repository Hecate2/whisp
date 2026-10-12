// SPDX-License-Identifier: LGPL-3.0-or-later
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    id("com.diffplug.spotless") version "8.10.4"
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktfmt("0.65").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktfmt("0.65").kotlinlangStyle()
    }
    format("text") {
        target(
            "*.md",
            "docs/*.md",
            ".gitignore",
            ".gitattributes",
            ".editorconfig",
            "*.properties",
            "app/*.pro",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.register<Exec>("pythonFormat") {
    commandLine("black", "tools")
}

tasks.register<Exec>("pythonFormatCheck") {
    commandLine("black", "--check", "tools")
}

tasks.register<Exec>("publicContentCheck") {
    commandLine("python3", "tools/check-public-content.py")
}

tasks.register("fmt") {
    group = "formatting"
    description = "Format Kotlin, Gradle scripts, Python tools and text files."
    dependsOn("spotlessApply", "pythonFormat")
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Check formatting, public content, unit tests and Android Lint."
    dependsOn(
        "spotlessCheck",
        "pythonFormatCheck",
        "publicContentCheck",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:lintRelease",
    )
}

// SPDX-License-Identifier: LGPL-3.0-or-later
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseSecretsFile = rootProject.file(".signing/signing.properties")
val supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val splitApks = providers.gradleProperty("splitApks").isPresent
val releaseSecrets =
    Properties().apply {
        if (releaseSecretsFile.isFile) releaseSecretsFile.inputStream().use { load(it) }
    }

android {
    namespace = "app.whisp"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.whisp"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "0.4.0-rc.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (!splitApks) ndk { abiFilters += supportedAbis }
    }
    splits {
        abi {
            isEnable = splitApks
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = true
        }
    }
    signingConfigs {
        if (releaseSecretsFile.isFile)
            create("localRelease") {
                storeFile = rootProject.file(".signing/${releaseSecrets.getProperty("storeFile")}")
                storePassword = releaseSecrets.getProperty("storePassword")
                keyAlias = releaseSecrets.getProperty("keyAlias")
                keyPassword = releaseSecrets.getProperty("keyPassword")
            }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSecretsFile.isFile) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    // Isolated on-device tests preserve the installed user's release and its library.
    if (providers.gradleProperty("smokeTest").isPresent) {
        buildTypes.create("smoke") {
            initWith(buildTypes.getByName("release"))
            applicationIdSuffix = ".smoketest"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += "release"
            proguardFiles("smoke-proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
        }
        testBuildType = "smoke"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.named("androidTest") { assets.srcDir("$projectDir/schemas") }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { compilerOptions { allWarningsAsErrors.set(true) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    constraints {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0") {
            because("Align the Navigation 3 serialization runtime with test serialization")
        }
    }
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Material 3 beta provides the public MotionScheme used by the app theme.
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation3:navigation3-runtime:1.2.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.paging:paging-compose:3.5.2")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okio:okio:3.18.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.hierynomus:smbj:0.15.0")
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

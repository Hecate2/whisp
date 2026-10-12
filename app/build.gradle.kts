// SPDX-License-Identifier: LGPL-3.0-or-later
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseSecretsFile = rootProject.file(".signing/signing.properties")
val releaseSecrets =
    Properties().apply {
        if (releaseSecretsFile.isFile) releaseSecretsFile.inputStream().use { load(it) }
    }

android {
    namespace = "app.whisp"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.whisp"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
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
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Public MotionScheme is hidden in stable 1.4.0. Pin the compatible expressive API
    // rather than accessing internal SheetState animation fields.
    implementation("androidx.compose.material3:material3:1.5.0-alpha06")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation3:navigation3-runtime:1.0.0")
    implementation("androidx.navigation3:navigation3-ui:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.hierynomus:smbj:0.15.0")
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The Play upload key, from keystore.properties at the project root (never committed; see
 * docs/PUBLISHING.md). Without that file, release builds use the debug key so local and CI builds run.
 */
val uploadKey = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.impeesa.finance"
    compileSdk = 36

    signingConfigs {
        if (!uploadKey.isEmpty) {
            create("upload") {
                storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.impeesa.finance"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.9.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones only; x86 native libs (emulators) stay in debug builds.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            // Play rejects debug-signed bundles, so a missing upload key can't slip through to the store.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

/**
 * Privacy guard (IMPLEMENTATION_PLAN.md §12): fails the build if any library sneaks the
 * INTERNET permission into the merged release manifest.
 */
val verifyNoInternetPermission by tasks.registering {
    group = "verification"
    description = "Fails if the merged release manifest requests network access."
    dependsOn("processReleaseMainManifest")
    val mergedDir = layout.buildDirectory.dir("intermediates/merged_manifest/release")
    doLast {
        val manifests = mergedDir.get().asFileTree.matching { include("**/AndroidManifest.xml") }.files
        check(manifests.isNotEmpty()) { "Merged release manifest not found under ${mergedDir.get()}" }
        val forbidden = listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
        manifests.forEach { file ->
            val text = file.readText()
            forbidden.forEach { permission ->
                check(!text.contains(permission)) { "$permission found in $file" }
            }
        }
        println("verifyNoInternetPermission: OK (${manifests.size} manifest checked)")
    }
}

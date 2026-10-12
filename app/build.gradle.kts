plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.impeesa.finance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.impeesa.finance"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones only; x86 native libs (emulators) stay in debug builds.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            // Signing is configured at release time (Phase 10); debug key keeps local release builds runnable.
            signingConfig = signingConfigs.getByName("debug")
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

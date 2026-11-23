// build.gradle.kts -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

import groovy.json.JsonSlurper
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

plugins {
    alias(libs.plugins.android.application)
}

abstract class PrepareManagedCoreAssets : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

// CI prepares this metadata once; local builds use the exact same version calculator.
val versionEnvironment = listOf("OMARCHY_ANDROID_VERSION_CODE", "OMARCHY_ANDROID_VERSION_NAME", "OMARCHY_ANDROID_SOURCE_SHA")
    .map { providers.environmentVariable(it).orNull }
val androidBuildVersion: Map<*, *> = if (versionEnvironment.all { it != null }) {
    mapOf("versionCode" to versionEnvironment[0], "versionName" to versionEnvironment[1], "sourceSha" to versionEnvironment[2])
} else {
    check(versionEnvironment.all { it == null }) { "Incomplete Android version metadata" }
    JsonSlurper().parseText(providers.exec {
        workingDir(rootProject.projectDir)
        commandLine("python3", rootProject.file("../../scripts/android-build.py").absolutePath, "version")
    }.standardOutput.asText.get()) as Map<*, *>
}
val androidSigningMode = providers.environmentVariable("OMARCHY_ANDROID_SIGNING_MODE").orElse("temporary-local").get()
check(androidSigningMode in setOf("stable-test", "temporary-pr", "temporary-local")) { "Unknown Android signing mode" }
val androidVersionCode = androidBuildVersion["versionCode"].toString().toInt()
check(androidVersionCode in 2..2_100_000_000) { "Android versionCode is outside supported bounds" }
val androidVersionName = androidBuildVersion["versionName"].toString().let {
    if (androidSigningMode != "stable-test" && !it.endsWith(".temporary")) "$it.temporary" else it
}

val managedAssetsDirectory = layout.buildDirectory.dir("generated/managedAssets")
val extraManagedPayload = providers.environmentVariable("OMARCHY_ANDROID_MANAGED_PAYLOAD_DIR").orNull
val prepareManagedCoreAssets by tasks.registering(PrepareManagedCoreAssets::class) {
    val repository = rootProject.file("../..")
    workingDir(repository)
    inputs.dir(repository.resolve("core/bin"))
    inputs.dir(repository.resolve("core/ai-suite"))
    inputs.file(repository.resolve("scripts/prepare-android-managed.py"))
    inputs.property("sourceSha", androidBuildVersion["sourceSha"].toString())
    inputs.property("extraManagedPayload", extraManagedPayload ?: "")
    if (extraManagedPayload != null) inputs.dir(repository.resolve(extraManagedPayload))
    outputDirectory.set(managedAssetsDirectory)
    commandLine("python3", repository.resolve("scripts/prepare-android-managed.py").absolutePath,
        "--output-dir", outputDirectory.get().asFile.absolutePath,
        "--source-sha", androidBuildVersion["sourceSha"].toString())
    if (extraManagedPayload != null) args("--extra-dir", repository.resolve(extraManagedPayload).absolutePath)
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareManagedCoreAssets, PrepareManagedCoreAssets::outputDirectory)
}

android {
    namespace = "com.fct.tc4"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.omarchy.android"
        minSdk = 28
        targetSdk = 37
        versionCode = androidVersionCode
        versionName = androidVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    val stableTestSigning = if (androidSigningMode == "stable-test") {
        signingConfigs.create("stableTest") {
            storeFile = file(providers.environmentVariable("OMARCHY_ANDROID_KEYSTORE_PATH").get())
            storePassword = providers.environmentVariable("OMARCHY_ANDROID_TEST_KEYSTORE_PASSWORD").get()
            keyAlias = "omarchy-android-test"
            keyPassword = storePassword
            storeType = "PKCS12"
        }
    } else null

    buildTypes {
        getByName("debug") {
            if (stableTestSigning != null) signingConfig = stableTestSigning
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        dataBinding = true // 此项目不使用dataBinding，这是为了AVNC依赖能正常工作
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    androidResources {
        noCompress += "zst"
        // Managed Node/npm payloads contain required dotfiles and underscore directories.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Preserve the exact native triplet validated against its source receipt.
            keepDebugSymbols += setOf(
                "**/libproot-omarchy-readonly.so",
                "**/libproot-omarchy-loader-aarch64.so",
                "**/libproot-omarchy-loader-arm.so"
            )
        }
    }
}

dependencies {
    implementation(libs.terminal.lib)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.animation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.material)
    implementation(libs.viewpagerindicator)
    implementation(libs.blurview)
    implementation(libs.snakeyaml)
    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.avnc)
    implementation(libs.termux.x11)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

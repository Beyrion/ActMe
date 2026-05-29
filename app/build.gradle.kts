import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val generatedAssetsDir = layout.buildDirectory.dir("generated/assets/main")
val importedSkillsDir = generatedAssetsDir.map { it.dir("skills/codex_import").asFile }

val importCodexSkills by tasks.registering {
    outputs.dir(importedSkillsDir)
    doLast {
        val sourceRoot = File(System.getProperty("user.home"), ".codex/skills")
        val targetRoot = importedSkillsDir.get()
        targetRoot.mkdirs()
        if (!sourceRoot.exists()) {
            logger.warn("未找到 ~/.codex/skills，跳过技能导入。")
            return@doLast
        }
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("md", true) && it.name.equals("SKILL.md", true) }
            .forEach { src ->
                val relative = src.relativeTo(sourceRoot).path.replace(File.separatorChar, '_')
                val dest = File(targetRoot, relative)
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
            }
        logger.lifecycle("已导入本地 Codex Skills 到: ${targetRoot.absolutePath}")
    }
}

android {
    namespace = "com.actme.app"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    androidResources {
        noCompress += listOf("mnn", "weight", "bin", "txt", "json")
    }

    defaultConfig {
        applicationId = "com.actme.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }


        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main").assets.srcDir(generatedAssetsDir)

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(importCodexSkills)
}

val buildMnn by tasks.registering {
    group = "actme"
    description = "Build libMNN.so with LLM+omni for Android via CMake+NDK (cross-platform)"

    val mnnRoot = rootProject.file("MNN")
    val buildDir = file("$mnnRoot/project/android/build_64")
    val libDir = file("$buildDir/lib")
    val outputSo = file("$libDir/libMNN.so")

    // Track MNN sources for incremental builds
    inputs.dir(file("$mnnRoot/include"))
    inputs.dir(file("$mnnRoot/source"))
    inputs.dir(file("$mnnRoot/express"))
    inputs.dir(file("$mnnRoot/transformers"))
    inputs.dir(file("$mnnRoot/tools"))
    inputs.dir(file("$mnnRoot/schema"))
    inputs.file(file("$mnnRoot/CMakeLists.txt"))

    outputs.file(outputSo)

    doLast {
        val sdkDir = android.sdkDirectory
        val ndkVersion = android.ndkVersion
            ?: throw GradleException("ndkVersion not set in app/build.gradle.kts")
        val ndkDir = file("$sdkDir/ndk/$ndkVersion")

        if (!ndkDir.exists()) {
            throw GradleException(
                "NDK $ndkVersion not found at ${ndkDir.absolutePath}. " +
                "Install it via SDK Manager (Tools > SDK Manager > SDK Tools > NDK)."
            )
        }

        // Find cmake from SDK first, fall back to system PATH
        val sdkCmakeDir = file("$sdkDir/cmake")
        val cmakeBin = if (sdkCmakeDir.isDirectory) {
            val versions = sdkCmakeDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sortedDescending()
            val best = versions?.firstOrNull()
            if (best != null) file("$sdkCmakeDir/$best/bin/cmake").absolutePath else "cmake"
        } else {
            "cmake"
        }
        logger.lifecycle("Using cmake: $cmakeBin")

        val toolchainFile = file("$ndkDir/build/cmake/android.toolchain.cmake")
        if (!toolchainFile.exists()) {
            throw GradleException("NDK toolchain not found: ${toolchainFile.absolutePath}")
        }

        buildDir.mkdirs()
        libDir.mkdirs()

        val cmakeArgs = listOf(
            cmakeBin,
            mnnRoot.absolutePath,
            "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.absolutePath}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DANDROID_ABI=arm64-v8a",
            "-DANDROID_STL=c++_static",
            "-DANDROID_NATIVE_API_LEVEL=android-26",
            "-DMNN_USE_LOGCAT=true",
            "-DMNN_BUILD_FOR_ANDROID_COMMAND=true",
            "-DMNN_LOW_MEMORY=ON",
            "-DMNN_BUILD_LLM=ON",
            "-DMNN_BUILD_LLM_OMNI=ON",
            "-DMNN_SUPPORT_TRANSFORMER_FUSE=ON",
            "-DMNN_SEP_BUILD=OFF",
            "-DMNN_OPENCL=ON",
            "-DMNN_BUILD_TEST=OFF",
            "-DMNN_BUILD_BENCHMARK=OFF",
            "-DMNN_BUILD_DIFFUSION=ON",
            "-DMNN_BUILD_OPENCV=ON",
            "-DMNN_IMGCODECS=ON",
            "-DMNN_KLEIDIAI=OFF",
            "-DNATIVE_LIBRARY_OUTPUT=lib",
            "-DNATIVE_INCLUDE_OUTPUT=."
        )

        logger.lifecycle("=== MNN CMake Configure ===")
        logger.lifecycle("  NDK: ${ndkDir.absolutePath}")
        logger.lifecycle("  Build dir: ${buildDir.absolutePath}")

        exec {
            workingDir = buildDir
            commandLine(cmakeArgs)
        }

        val numCores = Runtime.getRuntime().availableProcessors()
        logger.lifecycle("=== MNN CMake Build (parallel=$numCores) ===")

        exec {
            workingDir = buildDir
            commandLine(cmakeBin, "--build", ".", "--parallel", numCores.toString())
        }

        // MNN may put libMNN.so in the build root; copy to expected location
        val altSo = file("$buildDir/libMNN.so")
        if (!outputSo.exists() && altSo.exists()) {
            altSo.copyTo(outputSo, overwrite = true)
        }

        if (!outputSo.exists()) {
            throw GradleException(
                "MNN build completed but libMNN.so not found. " +
                "Expected: ${outputSo.absolutePath}"
            )
        }

        // Copy to jniLibs so AGP packages it into the APK
        val jniLibsDir = file("src/main/jniLibs/arm64-v8a")
        jniLibsDir.mkdirs()
        outputSo.copyTo(file("$jniLibsDir/libMNN.so"), overwrite = true)

        logger.lifecycle("=== MNN build complete: ${outputSo.absolutePath} ===")
    }
}

// Wire MNN native build into the app's CMake pipeline.
// Only rebuild MNN when the .so is missing or -PforceMnn is passed.
val mnnBuiltSo = file("${rootProject.file("MNN")}/project/android/build_64/lib/libMNN.so")
val forceMnn = project.hasProperty("forceMnn")

tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
    if (!mnnBuiltSo.exists() || forceMnn) {
        dependsOn(buildMnn)
    }
}

val pushAsrModel by tasks.registering {
    group = "actme"
    description = "Push Qwen3-ASR MNN model to device via adb"
    doLast {
        val modelDir = rootProject.file("model/Qwen3-ASR-0.6B-INT8-MNN")
        if (!modelDir.exists()) {
            throw GradleException("ASR model not found at ${modelDir.absolutePath}")
        }
        val devicePath = "/sdcard/actme/models/Qwen3-ASR-0.6B-INT8-MNN"
        exec {
            commandLine("adb", "shell", "mkdir", "-p", devicePath)
        }
        exec {
            commandLine("adb", "push", modelDir.absolutePath, devicePath)
        }
        logger.lifecycle("ASR model pushed to $devicePath")
    }
}

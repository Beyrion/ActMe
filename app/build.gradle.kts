import groovy.json.JsonSlurper
import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val generatedAssetsDir = layout.buildDirectory.dir("generated/assets/main")
val bundledAuthFile = generatedAssetsDir.map { it.file("secure/bundled_auth.enc").asFile }
val importedSkillsDir = generatedAssetsDir.map { it.dir("skills/codex_import").asFile }

val packSalt = "actme-pack-salt-v1".toByteArray(Charsets.UTF_8)

fun extractApiKey(authFile: File): String {
    val parsed = JsonSlurper().parse(authFile)
    if (parsed !is Map<*, *>) return ""
    val keyCandidates = listOf("OPENAI_API_KEY", "openai_api_key", "api_key", "key")
    for (candidate in keyCandidates) {
        val value = parsed[candidate] as? String
        if (!value.isNullOrBlank()) return value
    }
    return ""
}

fun derivePackKey(passphrase: String): SecretKeySpec {
    val spec: KeySpec = PBEKeySpec(passphrase.toCharArray(), packSalt, 120_000, 256)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    val keyBytes = factory.generateSecret(spec).encoded
    return SecretKeySpec(keyBytes, "AES")
}

fun encryptAesCbc(plainText: String, passphrase: String): ByteArray {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val iv = ByteArray(16)
    SecureRandom().nextBytes(iv)
    cipher.init(Cipher.ENCRYPT_MODE, derivePackKey(passphrase), IvParameterSpec(iv))
    val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
    return iv + encrypted
}

fun escapeForJson(input: String): String {
    return input.replace("\\", "\\\\").replace("\"", "\\\"")
}

val bundleCodexAuth by tasks.registering {
    outputs.file(bundledAuthFile)
    doLast {
        val authFile = File(System.getProperty("user.home"), ".codex/auth.json")
        val outputFile = bundledAuthFile.get()
        outputFile.parentFile.mkdirs()

        val localProp = rootProject.file("local.properties")
        val localPackKey = if (localProp.exists()) {
            localProp.readLines()
                .firstOrNull { it.startsWith("actme.packKey=") }
                ?.substringAfter("=")
                ?.trim()
        } else {
            null
        }
        val passphrase = System.getenv("ACTME_PACK_KEY")
            ?: localPackKey
            ?: "ACTME_DEV_ONLY_CHANGE_ME"

        if (!authFile.exists()) {
            logger.warn("未找到 ~/.codex/auth.json，已写入占位加密内容。")
            outputFile.writeBytes(encryptAesCbc("{\"OPENAI_API_KEY\":\"\"}", passphrase))
            return@doLast
        }

        val apiKey = extractApiKey(authFile)

        val plainJson = """{"OPENAI_API_KEY":"${escapeForJson(apiKey)}"}"""
        outputFile.writeBytes(encryptAesCbc(plainJson, passphrase))
        logger.lifecycle("已生成加密密钥资源: ${outputFile.absolutePath}")
    }
}

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

    defaultConfig {
        applicationId = "com.actme.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localProp = rootProject.file("local.properties")
        val localPackKey = if (localProp.exists()) {
            localProp.readLines()
                .firstOrNull { it.startsWith("actme.packKey=") }
                ?.substringAfter("=")
                ?.trim()
        } else {
            null
        }
        val passphrase = System.getenv("ACTME_PACK_KEY")
            ?: localPackKey
            ?: "ACTME_DEV_ONLY_CHANGE_ME"

        buildConfigField("String", "MODEL_PROVIDER", "\"Model_Studio_Token_Plan\"")
        buildConfigField("String", "MODEL_NAME", "\"qwen3.6-plus\"")
        buildConfigField("String", "MODEL_REASONING_EFFORT", "\"medium\"")
        buildConfigField("boolean", "DISABLE_RESPONSE_STORAGE", "true")
        buildConfigField("String", "PREFERRED_AUTH_METHOD", "\"apikey\"")
        buildConfigField("String", "CRS_BASE_URL", "\"https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1\"")
        buildConfigField("String", "CRS_WIRE_API", "\"chat_completions\"")
        buildConfigField("boolean", "CRS_REQUIRES_OPENAI_AUTH", "false")
        buildConfigField("String", "CUSTOM_AUTH_HEADER", "\"\"")
        buildConfigField("String", "CUSTOM_AUTH_PREFIX", "\"Bearer\"")
        val escapedPassphrase = passphrase
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "BUNDLE_KEY_PASSPHRASE", "\"$escapedPassphrase\"")
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

    packaging {
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

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(bundleCodexAuth)
    dependsOn(importCodexSkills)
}

import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 从 local.properties 读取（SDK 路径等）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}

// 从 .env 读取 LLM 配置（优先级高于 local.properties）
val envProps = Properties().apply {
    val f = rootProject.file(".env")
    if (f.exists()) {
        f.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                setProperty(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim())
            }
        }
    }
}

// LLM 配置：.env 优先，local.properties 回退，最后用默认值
fun llmProp(key: String, default: String): String =
    envProps.getProperty(key) ?: localProps.getProperty(key) ?: default

val gitCommit = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "--short=12", "HEAD")
}.standardOutput.asText.map(String::trim).getOrElse("unknown")

val generatedAgentAssets = layout.buildDirectory.dir("generated/agentAssets")
val generateAgentAssets by tasks.registering(Sync::class) {
    into(generatedAgentAssets)
    from(rootProject.projectDir) {
        include(
            "README.md",
            "开发日志.md",
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "app/proguard-rules.pro",
            "app/src/main/java/**/*.kt",
            "app/src/main/res/**/*.xml",
            "app/src/main/AndroidManifest.xml",
        )
        exclude(
            "**/.env",
            "**/local.properties",
            "**/BuildConfig.*",
            "**/build/**",
            "**/.gradle/**",
            "**/.git/**",
        )
        into("source")
    }
    doLast {
        val metadata = generatedAgentAssets.get().file("source/SNAPSHOT.txt").asFile
        metadata.parentFile.mkdirs()
        metadata.writeText(
            "version=${android.defaultConfig.versionName}\ncommit=$gitCommit\n",
        )
    }
}

android {
    namespace = "com.agent.voiceassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agent.voiceassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // LLM 配置注入 BuildConfig（从 .env 读取，回退到 local.properties，最后用默认值）
        buildConfigField("String", "STEPFUN_API_KEY", "\"${llmProp("LLM_API_KEY", "")}\"")
        buildConfigField("String", "STEPFUN_BASE_URL", "\"${llmProp("LLM_BASE_URL", "https://token-plan-cn.xiaomimimo.com/v1")}\"")
        buildConfigField("String", "STEPFUN_MODEL", "\"${llmProp("LLM_MODEL", "mimo-v2.5")}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${llmProp("LLM_API_KEY", "")}\"")
        buildConfigField("String", "OPENAI_BASE_URL", "\"${llmProp("LLM_BASE_URL", "https://token-plan-cn.xiaomimimo.com/v1")}\"")
        buildConfigField("String", "OPENAI_MODEL", "\"${llmProp("LLM_MODEL", "mimo-v2.5")}\"")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.ObsoleteCoroutinesApi"
        )
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedAgentAssets)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
        // 不压缩 onnx 模型文件，避免运行时解压耗内存/时间
        // assets 下的 .onnx 直接 mmap 读取
        androidResources {
            noCompress.addAll(listOf("onnx", "txt", "json"))
        }
    }

    // 自定义 APK 输出文件名（绕过默认 app-debug.apk 的文件锁）
    applicationVariants.all {
        outputs.forEach { output ->
            val out = output as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            out.outputFileName = "app-debug-ort1171.apk"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateAgentAssets)
}

dependencies {
    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // LangChain4j（只用核心模块，避开实验性 agentic 模块以规避 Android 反射兼容性问题）
    implementation("dev.langchain4j:langchain4j:0.34.0")
    implementation("dev.langchain4j:langchain4j-open-ai:0.34.0")  // OpenAI 兼容，含 StepFun

    // 日志
    implementation("com.jakewharton.timber:timber:5.0.1")

    // 本地 sherpa 模型链路已从最小闭环中移除；旧 processor 现在是无 sherpa 依赖的占位实现。

    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

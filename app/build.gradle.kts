import java.io.ByteArrayOutputStream

// Функция для безопасного выполнения команд через Providers API (совместимо с Gradle 8+)
fun getGitOutput(vararg command: String): String {
    return try {
        providers.exec {
            commandLine(*command)
            isIgnoreExitValue = true // Не ломать сборку, если git вернет ошибку (например, нет тегов)
        }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        ""
    }
}

val gitVersionCode: Int by lazy {
    // Используем HEAD, так как origin/main может быть не обновлен локально
    val output = getGitOutput("git", "rev-list", "--count", "HEAD")
    output.toIntOrNull() ?: 1
}

val gitVersionName: String by lazy {
    val output = getGitOutput("git", "describe", "--tags", "--dirty")

    // Если тегов нет (ошибка fatal или пусто), берем короткий хеш коммита
    if (output.isEmpty() || output.contains("fatal") || output.contains("не найдены")) {
        val commitHash = getGitOutput("git", "rev-parse", "--short", "HEAD")
        // Возвращаем временную версию, пока вы не создадите тег
        if (commitHash.isNotEmpty()) "0.0.1-dev-$commitHash" else "0.0.1"
    } else {
        output.replaceFirst("^v".toRegex(), "")
    }
}

val versionNameOverride = providers.gradleProperty("versionNameOverride").orNull
val versionCodeOverride = providers.gradleProperty("versionCodeOverride").orNull?.toIntOrNull()
val applicationIdSuffixOverride = providers.gradleProperty("applicationIdSuffixOverride").orNull

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// ── Единый нативный движок (native/ddd_engine, UNIFIED-ENGINE.md) ──
//
// Каталог native/ входит в репозиторий. FFmpeg при обычной Gradle-сборке не
// пересобирается: проверенные arm64-библиотеки лежат в native/prebuilt, а полный
// исходный код подключён pinned-сабмодулем для воспроизводимости и лицензии.
val nativeRoot = rootProject.file("native")
val ffmpegAbis = listOf("arm64-v8a")

/**
 * FFmpeg собирается отдельным скриптом, поэтому его .so надо принести в APK как
 * готовые библиотеки. Ожидаемая Gradle раскладка jniLibs — `<dir>/<abi>/<имя>.so`, а
 * сборочный скрипт кладёт их в `<abi>/stripped/`, поэтому нужна перекладка.
 *
 * Sync, а не Copy: при удалении библиотеки из сборки FFmpeg она должна исчезнуть
 * и из APK. Иначе в APK годами живёт .so, которую уже никто не собирает, — и
 * отладка «почему подхватилась старая версия» стоит дороже этой задачи.
 */
val syncFfmpegLibs = tasks.register<Sync>("syncFfmpegLibs") {
    description = "Раскладывает libav*_ddd.so из native/prebuilt/ffmpeg в вид jniLibs"
    into(layout.buildDirectory.dir("ffmpegJniLibs"))
    ffmpegAbis.forEach { abi ->
        val stripped = File(nativeRoot, "prebuilt/ffmpeg/$abi/stripped")
        val full = File(nativeRoot, "prebuilt/ffmpeg/$abi/lib")
        // Стрипнутые в APK: полные весят в разы больше, а символы нужны только
        // ndk-stack на хосте, где они и остаются.
        from(if (stripped.isDirectory) stripped else full) {
            include("*.so")
            into(abi)
        }
    }
}

android {
    namespace = "top.rootu.dddplayer"
    compileSdk = 36

    // Та же версия, на которой собран FFmpeg (native/scripts/build-ffmpeg.sh) и
    // нативный тест шага 3. Разные NDK — разные libc++, и ошибка вылезает не при
    // сборке, а падением в рантайме на чужом std::string.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "top.rootu.dddplayer"
        minSdk = 23
        targetSdk = 34
        versionCode = versionCodeOverride ?: gitVersionCode
        versionName = versionNameOverride ?: gitVersionName
        // Выводим в консоль при сборке
        println("Building Version: $versionName ($versionCode)")

        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Только arm64: FFmpeg собран под неё, и других целей у проекта нет —
            // Pico 4 и современные Android-TV приставки все arm64. Каждая лишняя
            // ABI — это ещё ~12 МБ библиотек FFmpeg в APK.
            abiFilters += ffmpegAbis
        }

        externalNativeBuild {
            cmake {
                // c++_shared, а не c++_static: libc++ в процессе должна быть одна.
                // При static её копия попадёт и в libddd_engine.so, и в чужие .so
                // (VLC), а два разных аллокатора в одном процессе — это падения на
                // передаче std::string через границу библиотек.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // Some Windows/Ninja combinations can leave CMake's tiny
                    // compiler-probe executable linker waiting forever. The
                    // engine is a library, so a static-library probe validates
                    // the compiler just as well and avoids that irrelevant link.
                    "-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = File(nativeRoot, "ddd_engine/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("ffmpegJniLibs"))
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("$projectDir/dddplayer-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            // Позволяет поставить тестовую сборку рядом с Play/GitHub-релизом,
            // подписанным другим ключом, не удаляя приложение и его данные.
            applicationIdSuffixOverride?.let { applicationIdSuffix = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        disable.add("MissingTranslation")
        disable.add("UnsafeOptInUsageError")
    }

    packaging {
        jniLibs {
            // Библиотеки FFmpeg уже стрипнуты сборочным скриптом; повторный
            // strip средствами AGP на них только тратит время сборки.
            keepDebugSymbols += "*/*/libav*_ddd.so"
            keepDebugSymbols += "*/*/libsw*_ddd.so"
        }
    }
}

// jniLibs надо разложить до того, как AGP начнёт собирать APK, иначе первая
// сборка после чистого клона уходит без библиотек FFmpeg — и падает не здесь, а
// в рантайме на System.loadLibrary.
tasks.named("preBuild") { dependsOn(syncFfmpegLibs) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Media3 dependencies
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.decoder)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    implementation(libs.media3.extractor)
    // Media3 Extensions (HLS, DASH, RTSP)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)

    // 3.7.5 is the first stable line we use whose arm64 ELF segments are all
    // 16 KiB aligned. Older 3.6.0-eap14 puts an Android 16 compatibility dialog
    // over PlayerActivity before playback can become visible.
    implementation("org.videolan.android:libvlc-all:3.7.5")
    // Локальные AAR (Декодеры audio и AV1)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.ui.graphics)
    ksp(libs.androidx.room.compiler)

    // Для загрузки изображений
    implementation(libs.coil)

    // поиск утечек
    debugImplementation(libs.leakcanary.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
}

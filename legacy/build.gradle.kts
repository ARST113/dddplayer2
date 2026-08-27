import org.gradle.api.tasks.Sync

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

val nativeRoot = rootProject.file("native")
val ffmpegAbis = listOf("arm64-v8a")

val syncLegacyFfmpegLibs = tasks.register<Sync>("syncLegacyFfmpegLibs") {
    description = "Prepare DDD FFmpeg libraries for the legacy HEVC fallback"
    into(layout.buildDirectory.dir("ffmpegJniLibs"))
    ffmpegAbis.forEach { abi ->
        val stripped = File(nativeRoot, "prebuilt/ffmpeg/$abi/stripped")
        val full = File(nativeRoot, "prebuilt/ffmpeg/$abi/lib")
        from(if (stripped.isDirectory) stripped else full) {
            include("*.so")
            into(abi)
        }
    }
}

android {
    namespace = "top.rootu.dddplayer"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 24
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "VERSION_NAME", "\"0.0.15-legacy\"")
        buildConfigField("int", "VERSION_CODE", "1500000")
        ndk {
            abiFilters += ffmpegAbis
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
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
            java.srcDir(rootProject.file("app/src/main/java"))
            res.srcDir(rootProject.file("app/src/main/res"))
            manifest.srcFile("src/main/AndroidManifest.xml")
            jniLibs.srcDir(layout.buildDirectory.dir("ffmpegJniLibs"))
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    packaging {
        jniLibs {
            excludes += "**/libc++_shared.so"
            keepDebugSymbols += "*/*/libav*_ddd.so"
            keepDebugSymbols += "*/*/libsw*_ddd.so"
        }
    }

    lint {
        disable.add("MissingTranslation")
        disable.add("UnsafeOptInUsageError")
    }
}

tasks.named("preBuild") { dependsOn(syncLegacyFfmpegLibs) }

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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.coil)
    ksp(libs.androidx.room.compiler)

    // Legacy DDD sources were written against Media3 1.9.0. Compile against that
    // API; Just+ supplies the Media3 runtime used by the final application.
    val media3 = "1.11.0-beta01"
    compileOnly("androidx.media3:media3-common:$media3")
    compileOnly("androidx.media3:media3-container:$media3")
    compileOnly("androidx.media3:media3-datasource:$media3")
    compileOnly("androidx.media3:media3-datasource-okhttp:$media3")
    compileOnly("androidx.media3:media3-decoder:$media3")
    compileOnly("androidx.media3:media3-extractor:$media3")
    compileOnly("androidx.media3:media3-session:$media3")
    compileOnly("androidx.media3:media3-exoplayer:$media3")
    compileOnly("androidx.media3:media3-exoplayer-hls:$media3")
    compileOnly("androidx.media3:media3-exoplayer-dash:$media3")
    compileOnly("androidx.media3:media3-exoplayer-smoothstreaming:$media3")
    compileOnly("androidx.media3:media3-exoplayer-rtsp:$media3")
    compileOnly(fileTree(mapOf(
        "dir" to rootProject.file(".justplus-upstream/app/libs"),
        "include" to listOf("lib-*.aar")
    )))

    implementation("org.videolan.android:libvlc-all:3.7.5")
}

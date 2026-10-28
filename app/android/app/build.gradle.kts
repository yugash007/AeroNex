plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Third-party native libraries come from Flutter, Vosk + JNA and sherpa-onnx, which all ship armeabi-v7a and arm64-v8a.
val shippingAbis = listOf("armeabi-v7a", "arm64-v8a")
// v0.4.0 on-device AI (spec 2026-10-15 A6): the first-party libaeronex_ai.so (LFM planner on llama.cpp) and
// libaeronex_cactus.so (Cactus Needle tool calls) are built for arm64-v8a only, because Cactus ships android-arm64
// only; armeabi-v7a and x86_64 APKs carry no AI libraries. Local sources, nothing is downloaded:
// `-PaeronexLlamaDir=<llama.cpp source, commit fc9ce6b9>` and `-PaeronexNeedleArchive=<android-arm64 libneedle.a>`
// (CMake checks its SHA-256) point elsewhere.
val aiAbis = listOf("arm64-v8a")
val aeronexLlamaDir: File = file(project.findProperty("aeronexLlamaDir") as String? ?: "../../../../tooling/llama.cpp")
val aeronexNeedleArchive: File = file(
    project.findProperty("aeronexNeedleArchive") as String? ?: "../../../../tooling/cactus/android-arm64-libneedle.a")
val splitPerAbi = project.findProperty("split-per-abi")?.toString()?.toBoolean() == true
// The Flutter tool expects one split APK per target platform, and its default list includes android-x64, which split
// APKs never contain: fail early with the fix instead of after the build. (x86_64 is the non-split debug APK.)
if (splitPerAbi && project.findProperty("target-platform")?.toString()?.contains("android-x64") == true) {
    throw GradleException("Split APKs are armeabi-v7a and arm64-v8a only: add " +
        "`--target-platform android-arm,android-arm64` to `flutter build apk --split-per-abi`.")
}

android {
    namespace = "com.aeronex.app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.aeronex.app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 26
        ndk {
            // Flutter pre-populates its supported ABIs before this app DSL runs. Replace that set so the AARs'
            // x86/x86_64 libraries never reach a shipping APK (spec 4.19). AGP rejects ndk.abiFilters next to
            // ABI splits, so `--split-per-abi` builds select their ABIs in `splits` below instead.
            abiFilters.clear()
            if (!splitPerAbi) abiFilters += shippingAbis
        }
        externalNativeBuild {
            cmake {
                // CMake runs for these ABIs only (src/main/cpp/CMakeLists.txt refuses any other).
                abiFilters += aiAbis
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DAERONEX_LLAMA_DIR=${aeronexLlamaDir.invariantSeparatorsPath}",
                    "-DAERONEX_NEEDLE_ARCHIVE=${aeronexNeedleArchive.invariantSeparatorsPath}",
                )
                targets += listOf("aeronex_ai", "aeronex_cactus")
            }
        }
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Field installs: one APK per shipping ABI (the emulator uses the non-split debug APK).
    splits {
        abi {
            if (splitPerAbi) {
                reset()
                include(*shippingAbis.toTypedArray())
            }
        }
    }

    packaging {
        jniLibs {
            // libsherpa-onnx-jni.so needs only libonnxruntime.so; the C and C++ API libraries are unused (W1).
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }

    buildTypes {
        debug {
            // x86_64 only in debug, for the emulator smoke tests and CI (spec 4.19).
            ndk { if (!splitPerAbi) abiFilters += "x86_64" }
        }
        release {
            proguardFiles("proguard-rules.pro")
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

// `-Psitl=1` runs the *Sitl* JVM tests against a running ArduCopter SITL; without it they are skipped.
tasks.withType<Test>().configureEach {
    systemProperty("aeronex.sitl", project.findProperty("sitl") ?: "")
}

// sherpa-onnx 1.13.8 (Apache-2.0, bundles ONNX Runtime 1.28.2): IndicConformer CTC and the stop keyword spotter.
// Not on Maven: the verified release AAR (sha256 633c2432...bd96, tooling/models/sherpa/manifest.json) is read from
// the tooling folder; `-PsherpaOnnxAar=<path>` points elsewhere.
val sherpaOnnxAar: File = file(
    project.findProperty("sherpaOnnxAar") as String? ?: "../../../../tooling/sherpa-onnx/sherpa-onnx-1.13.8.aar")
if (!sherpaOnnxAar.isFile) {
    throw GradleException(
        "sherpa-onnx AAR not found at ${sherpaOnnxAar.absolutePath}. Download sherpa-onnx-1.13.8.aar " +
            "(tools/bench/fetch_sherpa_assets.py) or pass -PsherpaOnnxAar=<path to the AAR>.")
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(files(sherpaOnnxAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

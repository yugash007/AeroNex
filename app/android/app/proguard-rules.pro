# JNA resolves classes, fields (including Pointer.peer), constructors and
# callbacks by name from native code and reflection. The Android AAR does not
# ship consumer rules. See JNA native/dispatch.c and the Vosk Android demo.
-keep class com.sun.jna.** { *; }

# Vosk uses direct native registration and reflectively constructed NativeMapped
# types (Model/PointerType). Keep their names, constructors and member signatures.
-keep class org.vosk.** { *; }

# JNA also includes desktop-only AWT helpers. Android has no AWT; the app never
# calls those helpers. Suppress only these optional desktop references.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

# sherpa-onnx 1.13.8: its JNI reads the Kotlin config classes' fields by name (GetFieldID) and binds the
# Java_com_k2fsa_sherpa_onnx_* natives by class and method name. The AAR's proguard.txt is empty (T11).
# tools/check_jni_contract.py checks every field and native method of the AAR's classes in the release DEX.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# v0.4.0 AI (arm64-v8a): libaeronex_ai.so binds Java_com_aeronex_app_ai_NativeAiEngine_* by class and
# method name (src/main/cpp/ai_jni.cpp). Keep the class name and its native methods unrenamed; the package checker
# verifies them in the release DEX of every APK that ships the AI libraries.
-keep class com.aeronex.app.ai.NativeAiEngine {
    native <methods>;
}

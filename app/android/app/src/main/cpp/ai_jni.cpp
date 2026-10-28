// libaeronex_ai.so (arm64-v8a only): the JNI side of com.aeronex.app.ai.NativeAiEngine.
// - plan / warmUp / unload: the LFM2.5 planner (lfm_planner.h, llama.cpp linked statically).
// - toolCall: one Cactus Needle tool call per planner step, through libaeronex_cactus.so (cactus_runtime.h).
// Every call returns UTF-8 JSON bytes or throws IllegalStateException with an operator-facing message.
#include <jni.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <stdexcept>
#include <string>
#include "cactus_runtime.h"
#include "lfm_planner.h"
#include "needle_result.h"

namespace {

std::atomic<bool> cancelled{false};
aeronex::Planner &planner() {
    // Never destroyed: a static destructor at process exit must not race a worker thread still inside llama.cpp.
    static auto *instance = new aeronex::Planner();
    return *instance;
}

std::string utf8(JNIEnv *env, jbyteArray value) {
    if (!value) throw std::runtime_error("Missing input.");
    const auto n = env->GetArrayLength(value);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(value, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

jbyteArray bytes(JNIEnv *env, const std::string &s) {
    auto out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

double since(std::chrono::steady_clock::time_point started) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - started).count();
}

}  // namespace

// Clears a previous cancel; the Kotlin caller checks its own request token after reset() and before plan().
extern "C" JNIEXPORT void JNICALL
Java_com_aeronex_app_ai_NativeAiEngine_reset(JNIEnv *, jobject) { cancelled = false; }

// Aborts a running plan() at the next token or graph node; safe from any thread.
extern "C" JNIEXPORT void JNICALL
Java_com_aeronex_app_ai_NativeAiEngine_cancel(JNIEnv *, jobject) { cancelled = true; }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aeronex_app_ai_NativeAiEngine_plan(
        JNIEnv *env, jobject, jbyteArray model, jbyteArray text, jint threads, jint timeoutMs) {
    try {
        const auto path = utf8(env, model);
        const auto input = utf8(env, text);
        auto &lfm = planner();
        std::lock_guard<std::mutex> lock(lfm.mutex());
        return bytes(env, lfm.plan(path, input, threads, timeoutMs, cancelled).json().dump());
    } catch (const std::exception &e) {
        fail(env, e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_aeronex_app_ai_NativeAiEngine_warmUp(JNIEnv *env, jobject, jbyteArray model, jint threads) {
    try {
        const auto path = utf8(env, model);
        auto &lfm = planner();
        std::lock_guard<std::mutex> lock(lfm.mutex());
        return lfm.warmUp(path, threads);
    } catch (const std::exception &e) {
        fail(env, e.what());
        return 0;
    }
}

// Frees the planner's context and weights; the next plan() loads them again.
extern "C" JNIEXPORT void JNICALL
Java_com_aeronex_app_ai_NativeAiEngine_unload(JNIEnv *, jobject) {
    auto &lfm = planner();
    std::lock_guard<std::mutex> lock(lfm.mutex());
    lfm.unload();
}

// One step -> {"call": {"name", "arguments"} | null, "reason": "...", "ms": ...}. The Needle model stays mapped for
// the process lifetime (upstream has no unload); it is file-backed and read-only.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aeronex_app_ai_NativeAiEngine_toolCall(JNIEnv *env, jobject, jbyteArray model, jbyteArray step) {
    try {
        const auto path = utf8(env, model);
        const auto input = utf8(env, step);
        if (cancelled) throw std::runtime_error("Operation cancelled.");
        const auto started = std::chrono::steady_clock::now();
        const auto output = aeronex_cactus("libaeronex_cactus.so", path.c_str(), input.c_str());
        auto result = aeronex_needle_result(nlohmann::json::parse(output), input);
        result["ms"] = since(started);
        return bytes(env, result.dump());
    } catch (const std::exception &e) {
        fail(env, e.what());
        return nullptr;
    }
}

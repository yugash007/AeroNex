// libaeronex_cactus.so: the Cactus Needle runtime behind one C entry point, loaded lazily by cactus_runtime.h.
// Linked with -fvisibility=hidden and --exclude-libs,ALL so Needle's internals never clash with llama.cpp/ggml in
// libaeronex_ai.so. Text tool calls only (no Whistle speech model in v0.4.0). The shim uses C library calls only (no
// C++ standard library types), so the host evaluation can pair it with the libc++ that its libneedle.a needs.
#include "vendor/needle/needle.h"
#include "needle_tools.h"
#include <cstdio>
#include <cstring>

static int cactus_fail(char *output, int capacity, const char *message) {
    if (output && capacity > 0) std::snprintf(output, capacity, "%s", message);
    return -1;
}

static int needle_fail(char *output, int capacity) {
    const char *error = needle_last_error();
    return cactus_fail(output, capacity, error && error[0] ? error : "Cactus runtime failed.");
}

static int cactus_call(const unsigned char *model, unsigned long long bytes, const char *input, char *output,
                       int capacity) {
    static bool poisoned = false;
    static bool loaded = false;
    if (!model || bytes == 0 || !output || capacity < 2) return cactus_fail(output, capacity, "Invalid Cactus input.");
    if (poisoned)
        return cactus_fail(output, capacity, "The tool-call model could not start. Restart the app before retrying.");
    if (!loaded) {
        // A failed load can leave a partial allocation: stay poisoned unless the load and kind check both pass.
        poisoned = true;
        if (needle_load(model, bytes) < 0) return needle_fail(output, capacity);
        if (!(needle_models() & NEEDLE_TEXT))
            return cactus_fail(output, capacity, "The installed Needle file is not a text tool-call model.");
        loaded = true;
        poisoned = false;
    }
    if (!input || !input[0] || std::strlen(input) > 2000) return cactus_fail(output, capacity, "Invalid tool-call step.");
    needle_reset();
    if (needle_init(aeronex_needle_system, aeronex_needle_tools, nullptr) < 0) return needle_fail(output, capacity);
    if (needle_complete(input, nullptr, 0, aeronex_needle_max_tokens, output, capacity) < 0)
        return needle_fail(output, capacity);
    return 0;
}

// One Needle tool call for one short step. The caller (cactus_runtime.h) serializes calls and keeps the model
// mapping alive for the process lifetime: upstream has no unload API. Returns 0 and Needle's JSON in output, or -1
// and an error message.
extern "C" __attribute__((visibility("default"))) int aeronex_cactus_run(
        const unsigned char *model, unsigned long long bytes, const char *input, char *output, int capacity) {
    try {
        return cactus_call(model, bytes, input, output, capacity);
    } catch (...) {
        return cactus_fail(output, capacity, "Cactus inference failed.");
    }
}

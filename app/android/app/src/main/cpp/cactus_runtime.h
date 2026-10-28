#pragma once
// Loads libaeronex_cactus.so on first use and runs one Needle tool call. Shared by ai_jni.cpp and tools/ai-eval.
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

// The host evaluation links Needle against libc++ next to a libstdc++ program and adds RTLD_DEEPBIND.
#ifndef AERONEX_CACTUS_DLOPEN_FLAGS
#define AERONEX_CACTUS_DLOPEN_FLAGS (RTLD_NOW | RTLD_LOCAL)
#endif

class CactusModelMapping {
    void *data_ = MAP_FAILED;
    size_t size_ = 0;
public:
    explicit CactusModelMapping(const char *path) {
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd < 0) throw std::runtime_error("Cannot open the Needle model. Import it again.");
        struct stat info{};
        if (fstat(fd, &info) != 0 || info.st_size <= 0 || info.st_size > 128 * 1024 * 1024) {
            close(fd);
            throw std::runtime_error("Invalid Needle model size.");
        }
        size_ = static_cast<size_t>(info.st_size);
        // File-backed and read-only: the kernel can drop these pages under memory pressure.
        data_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
        close(fd);
        if (data_ == MAP_FAILED) throw std::runtime_error("Cannot map the Needle model.");
    }
    CactusModelMapping(const CactusModelMapping &) = delete;
    ~CactusModelMapping() { if (data_ != MAP_FAILED) munmap(data_, size_); }
    const unsigned char *data() const { return static_cast<const unsigned char *>(data_); }
    unsigned long long size() const { return size_; }
};

inline std::string aeronex_cactus(const char *libraryPath, const char *modelPath, const char *text) {
    // Upstream has a process-global worker pool and no unload/cancel API. Keep one runtime and one verified text
    // model for the process lifetime and let Android reclaim them at exit: C++ destruction could invalidate the
    // mapping while upstream worker threads still exist.
    struct Cache {
        std::mutex mutex;
        void *library = nullptr;
        std::unique_ptr<CactusModelMapping> model;
        std::string path;
    };
    static Cache *cache = new Cache();
    std::lock_guard<std::mutex> lock(cache->mutex);
    if (cache->model && cache->path != modelPath)
        throw std::runtime_error("Restart the app before loading a different Needle model file.");
    if (!cache->library) cache->library = dlopen(libraryPath, AERONEX_CACTUS_DLOPEN_FLAGS);
    if (!cache->library) throw std::runtime_error(std::string("Cannot load the Cactus runtime: ") + dlerror());
    if (!cache->model) {
        cache->model = std::make_unique<CactusModelMapping>(modelPath);
        cache->path = modelPath;
    }
    using Run = int (*)(const unsigned char *, unsigned long long, const char *, char *, int);
    auto run = reinterpret_cast<Run>(dlsym(cache->library, "aeronex_cactus_run"));
    if (!run) throw std::runtime_error("The Cactus runtime interface is unavailable.");
    std::vector<char> output(64 * 1024, '\0');
    const int status = run(cache->model->data(), cache->model->size(), text, output.data(),
        static_cast<int>(output.size()));
    if (output.back() != '\0') throw std::runtime_error("Needle output exceeded its buffer.");
    if (status < 0) throw std::runtime_error(output.data()[0] ? output.data() : "Needle inference failed.");
    return std::string(output.data());
}

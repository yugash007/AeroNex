# v0.4.0 on-device AI, arm64-v8a only (build.gradle.kts limits this build to that ABI; armeabi-v7a and x86_64 APKs
# carry no AI libraries). Local sources only, nothing is downloaded:
#   libaeronex_ai.so      LFM2.5 planner (llama.cpp, static) + the JNI for NativeAiEngine
#   libaeronex_cactus.so  Cactus Needle text tool calls (the pinned libneedle.a), loaded lazily by libaeronex_ai.so
cmake_minimum_required(VERSION 3.22)
project(aeronex_ai LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The AI libraries are built for arm64-v8a only, not ${ANDROID_ABI}.")
endif()

set(AERONEX_LLAMA_DIR "" CACHE PATH "llama.cpp source tree (commit fc9ce6b9d52a8504edcb262abc92737c2289f96c)")
set(AERONEX_NEEDLE_ARCHIVE "" CACHE FILEPATH "Cactus Needle android-arm64 libneedle.a")
set(AERONEX_NEEDLE_SHA256 "4522a16eebaaf569ebacd0ac682712a8d91acc88a2a50a44fd65f332c8ebfdbb")
if(NOT EXISTS "${AERONEX_LLAMA_DIR}/include/llama.h")
    message(FATAL_ERROR "llama.cpp source not found at '${AERONEX_LLAMA_DIR}'. Pass -PaeronexLlamaDir=<path>.")
endif()
if(NOT EXISTS "${AERONEX_NEEDLE_ARCHIVE}")
    message(FATAL_ERROR "Needle runtime not found at '${AERONEX_NEEDLE_ARCHIVE}'. Pass -PaeronexNeedleArchive=<path>.")
endif()
file(SHA256 "${AERONEX_NEEDLE_ARCHIVE}" AERONEX_NEEDLE_ACTUAL)
if(NOT AERONEX_NEEDLE_ACTUAL STREQUAL AERONEX_NEEDLE_SHA256)
    message(FATAL_ERROR "Needle runtime checksum mismatch: ${AERONEX_NEEDLE_ARCHIVE} is ${AERONEX_NEEDLE_ACTUAL}, "
        "expected ${AERONEX_NEEDLE_SHA256} (Cactus-Compute/needle3 2ae11323 android-arm64/libneedle.a).")
endif()

# The debug APK must plan within the same time budget as a release: never build the model code at -O0.
string(APPEND CMAKE_C_FLAGS_DEBUG " -O2")
string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O2")

# llama.cpp + ggml as static CPU-only libraries. armv8-a is the baseline every arm64 phone runs (no dotprod/i8mm
# assumptions); no OpenMP runtime, no common/tools/server, no network.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
add_subdirectory("${AERONEX_LLAMA_DIR}" llama EXCLUDE_FROM_ALL)

set(AERONEX_16K_PAGES "-Wl,-z,max-page-size=16384")

add_library(aeronex_ai SHARED ai_jni.cpp)
# Only the JNIEXPORT entry points are exported.
target_compile_options(aeronex_ai PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_include_directories(aeronex_ai PRIVATE "${AERONEX_LLAMA_DIR}/vendor")
target_link_libraries(aeronex_ai PRIVATE llama dl log)
target_link_options(aeronex_ai PRIVATE ${AERONEX_16K_PAGES} "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")

# Separate and hidden: Needle's process-global model slots and its internals stay out of libaeronex_ai.so's symbols.
add_library(aeronex_cactus SHARED cactus_jni.cpp)
target_compile_options(aeronex_cactus PRIVATE -fvisibility=hidden)
target_link_libraries(aeronex_cactus PRIVATE "${AERONEX_NEEDLE_ARCHIVE}")
target_link_options(aeronex_cactus PRIVATE ${AERONEX_16K_PAGES} "-Wl,--exclude-libs,ALL")

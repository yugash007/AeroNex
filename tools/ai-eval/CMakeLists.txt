# Host (Linux x86_64, WSL) build of the exact on-device AI step: app/android/app/src/main/cpp/lfm_planner.h (LFM
# prompt, grammar, decoding), needle_tools.h + cactus_jni.cpp + cactus_runtime.h (Needle tool call) and
# needle_result.h (result mapping). See README.md for the commands.
cmake_minimum_required(VERSION 3.22)
project(aeronex_ai_eval LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

get_filename_component(AERONEX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(AERONEX_CPP "${AERONEX_ROOT}/app/android/app/src/main/cpp")
set(AERONEX_LLAMA_DIR "${AERONEX_ROOT}/../tooling/llama.cpp" CACHE PATH "llama.cpp source (commit fc9ce6b9)")
set(AERONEX_NEEDLE_ARCHIVE "${AERONEX_ROOT}/../tooling/cactus/linux-x86_64-libneedle.a" CACHE FILEPATH
    "Cactus Needle linux-x86_64 libneedle.a")
set(AERONEX_NEEDLE_SHA256 "8556785e3f3562bb6e402d48770f34c0797f23b9571c07457cdc577928b0b9d3")
# The Linux libneedle.a is built against LLVM libc++ 22 (std::__1, __hash_memory): the shim links that runtime (the
# libc++1-22 / libc++abi1-22 packages in tooling/cactus, extracted as README.md shows). The shim's own code uses C
# library calls only, so it needs no libc++ headers, and the program loads it with RTLD_DEEPBIND.
set(AERONEX_LIBCXX_DIR "" CACHE PATH "Directory with libc++.so.1 and libc++abi.so.1 from LLVM 22")
if(NOT EXISTS "${AERONEX_LIBCXX_DIR}/libc++.so.1" OR NOT EXISTS "${AERONEX_LIBCXX_DIR}/libc++abi.so.1")
    message(FATAL_ERROR "Set AERONEX_LIBCXX_DIR to the extracted LLVM 22 libc++ runtime (see README.md).")
endif()

file(SHA256 "${AERONEX_NEEDLE_ARCHIVE}" AERONEX_NEEDLE_ACTUAL)
if(NOT AERONEX_NEEDLE_ACTUAL STREQUAL AERONEX_NEEDLE_SHA256)
    message(FATAL_ERROR "Needle runtime checksum mismatch: ${AERONEX_NEEDLE_ARCHIVE} is ${AERONEX_NEEDLE_ACTUAL}")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
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

# The same shim as Android's libaeronex_cactus.so.
add_library(aeronex_cactus SHARED "${AERONEX_CPP}/cactus_jni.cpp")
target_compile_options(aeronex_cactus PRIVATE -fvisibility=hidden)
target_link_options(aeronex_cactus PRIVATE -nostdlib++ "-Wl,--exclude-libs,ALL" "-Wl,--no-undefined"
    "-Wl,-rpath,${AERONEX_LIBCXX_DIR}")
target_link_libraries(aeronex_cactus PRIVATE "${AERONEX_NEEDLE_ARCHIVE}"
    "${AERONEX_LIBCXX_DIR}/libc++.so.1" "${AERONEX_LIBCXX_DIR}/libc++abi.so.1" pthread m dl)

add_executable(aeronex-ai-eval eval_main.cpp)
target_include_directories(aeronex-ai-eval PRIVATE "${AERONEX_CPP}" "${AERONEX_LLAMA_DIR}/vendor")
# Needle's libc++ must not bind to this program's libstdc++ symbols.
target_compile_definitions(aeronex-ai-eval PRIVATE "AERONEX_CACTUS_DLOPEN_FLAGS=(RTLD_NOW|RTLD_LOCAL|RTLD_DEEPBIND)")
target_link_libraries(aeronex-ai-eval PRIVATE llama dl pthread)
add_dependencies(aeronex-ai-eval aeronex_cactus)

add_executable(aeronex-needle-result-test needle_result_test.cpp)
target_include_directories(aeronex-needle-result-test PRIVATE "${AERONEX_CPP}" "${AERONEX_LLAMA_DIR}/vendor")

enable_testing()
add_test(NAME needle_result_mapping COMMAND aeronex-needle-result-test)

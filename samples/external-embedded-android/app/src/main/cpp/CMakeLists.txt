cmake_minimum_required(VERSION 3.22.1)
project(harnex_embedded_voice LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The standalone application, NOT Harnex's runtime, owns this pinned ASR engine.
# Never share ggml native objects with the transitive Harnex llama.cpp AAR.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)

# Debug APKs must use optimized native inference, not unoptimized debug GGML.
add_compile_options(-O3 -fvisibility=hidden)

include(FetchContent)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG 9386f239401074690479731c1e41683fbbeac557
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(harnex_whisper_stt SHARED whisper_stt_jni.cpp)
target_include_directories(harnex_whisper_stt PRIVATE ${whisper_cpp_SOURCE_DIR}/include)
target_link_libraries(harnex_whisper_stt PRIVATE whisper log)
# The JNI entry point is the sole external contract; static ggml symbols must not
# interpose on the independent dynamic ggml libraries packaged by llama.cpp.
target_link_options(harnex_whisper_stt PRIVATE
    "-Wl,--exclude-libs,ALL"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
)

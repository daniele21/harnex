cmake_minimum_required(VERSION 3.22.1)
project(local_llm_jni LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(
    LLAMA_CPP_DIR
    "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp"
    ABSOLUTE
)
set(LLAMA_CPP_PIN_FILE "${CMAKE_CURRENT_LIST_DIR}/../../../llama-cpp-pin.json")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Pinned llama.cpp submodule is missing. Run git submodule update --init --recursive.")
endif()
if(NOT EXISTS "${LLAMA_CPP_PIN_FILE}")
    message(FATAL_ERROR "Canonical llama.cpp pin manifest is missing: ${LLAMA_CPP_PIN_FILE}")
endif()

file(READ "${LLAMA_CPP_PIN_FILE}" LLAMA_CPP_PIN_JSON)
string(JSON LLAMA_CPP_PIN_SCHEMA ERROR_VARIABLE LLAMA_CPP_PIN_SCHEMA_ERROR GET "${LLAMA_CPP_PIN_JSON}" schema_version)
string(JSON LLAMA_CPP_PIN_TAG ERROR_VARIABLE LLAMA_CPP_PIN_TAG_ERROR GET "${LLAMA_CPP_PIN_JSON}" tag)
string(JSON LLAMA_CPP_PIN_COMMIT ERROR_VARIABLE LLAMA_CPP_PIN_COMMIT_ERROR GET "${LLAMA_CPP_PIN_JSON}" commit)
if(LLAMA_CPP_PIN_SCHEMA_ERROR OR LLAMA_CPP_PIN_TAG_ERROR OR LLAMA_CPP_PIN_COMMIT_ERROR)
    message(FATAL_ERROR "Canonical llama.cpp pin manifest is invalid JSON or missing required fields")
endif()
if(NOT LLAMA_CPP_PIN_SCHEMA STREQUAL "1")
    message(FATAL_ERROR "Unsupported llama.cpp pin manifest schema: ${LLAMA_CPP_PIN_SCHEMA}")
endif()
if(NOT LLAMA_CPP_PIN_TAG MATCHES "^[^ \\t\\r\\n]+$")
    message(FATAL_ERROR "Canonical llama.cpp pin tag must be a non-empty token")
endif()
string(LENGTH "${LLAMA_CPP_PIN_COMMIT}" LLAMA_CPP_PIN_COMMIT_LENGTH)
if(NOT LLAMA_CPP_PIN_COMMIT MATCHES "^[0-9a-f]+$" OR NOT LLAMA_CPP_PIN_COMMIT_LENGTH EQUAL 40)
    message(FATAL_ERROR "Canonical llama.cpp pin commit must be an exact lowercase 40-character SHA")
endif()

set(
    LOCAL_LLM_LLAMA_CPP_QUALIFICATION_COMMIT
    ""
    CACHE STRING
    "Explicit exact llama.cpp candidate SHA for the LLRT qualification lane only"
)
if(LOCAL_LLM_LLAMA_CPP_QUALIFICATION_COMMIT)
    string(LENGTH "${LOCAL_LLM_LLAMA_CPP_QUALIFICATION_COMMIT}" LLAMA_CPP_QUALIFICATION_COMMIT_LENGTH)
    if(
        NOT LOCAL_LLM_LLAMA_CPP_QUALIFICATION_COMMIT MATCHES "^[0-9a-f]+$"
        OR NOT LLAMA_CPP_QUALIFICATION_COMMIT_LENGTH EQUAL 40
    )
        message(FATAL_ERROR "LOCAL_LLM_LLAMA_CPP_QUALIFICATION_COMMIT must be an exact lowercase 40-character SHA")
    endif()
    set(LLAMA_CPP_EXPECTED_COMMIT "${LOCAL_LLM_LLAMA_CPP_QUALIFICATION_COMMIT}")
    string(SUBSTRING "${LLAMA_CPP_EXPECTED_COMMIT}" 0 12 LLAMA_CPP_EXPECTED_SHORT_COMMIT)
    set(LLAMA_CPP_EFFECTIVE_TAG "candidate-${LLAMA_CPP_EXPECTED_SHORT_COMMIT}")
else()
    set(LLAMA_CPP_EXPECTED_COMMIT "${LLAMA_CPP_PIN_COMMIT}")
    set(LLAMA_CPP_EFFECTIVE_TAG "${LLAMA_CPP_PIN_TAG}")
endif()

find_package(Git REQUIRED)
execute_process(
    COMMAND "${GIT_EXECUTABLE}" -C "${LLAMA_CPP_DIR}" rev-parse HEAD
    RESULT_VARIABLE LLAMA_CPP_GIT_RESULT
    OUTPUT_VARIABLE LLAMA_CPP_ACTUAL_COMMIT
    ERROR_VARIABLE LLAMA_CPP_GIT_ERROR
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
if(NOT LLAMA_CPP_GIT_RESULT EQUAL 0)
    message(FATAL_ERROR "Unable to resolve llama.cpp checkout identity: ${LLAMA_CPP_GIT_ERROR}")
endif()
if(NOT LLAMA_CPP_ACTUAL_COMMIT STREQUAL LLAMA_CPP_EXPECTED_COMMIT)
    message(
        FATAL_ERROR
        "llama.cpp checkout identity mismatch: expected ${LLAMA_CPP_EXPECTED_COMMIT}, found ${LLAMA_CPP_ACTUAL_COMMIT}"
    )
endif()

# Runtime-selected Android CPU variants are emitted as shared backend libraries.
# The backend loader and BUILD_SHARED_LIBS must therefore stay enabled together.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL OFF CACHE BOOL "" FORCE)
set(LLAMA_TESTS_INSTALL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

# LLRT-7 is an opt-in experimental build lane. Release/default builds remain
# CPU-only. Enabling this requires OpenCL headers and a linkable OpenCL loader
# to be provided to CMake; upstream then emits ggml-opencl as a dynamic backend
# that the existing runtime loader can discover from the native library path.
option(LOCAL_LLM_EXPERIMENTAL_OPENCL "Build the experimental ggml OpenCL backend" OFF)
if(LOCAL_LLM_EXPERIMENTAL_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_PROFILING OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
else()
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
endif()

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama.cpp")

# Use the exact same pinned llama/ggml runtime and context for image inference.
# mtmd is a separate image-encoder library, not a second language model runtime.
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
add_subdirectory("${LLAMA_CPP_DIR}/tools/mtmd" "${CMAKE_BINARY_DIR}/llama-mtmd")

add_library(
    local_llm_jni
    SHARED
    backend_device_inventory_jni.cpp
    evaluation_multiseq_context_params.cpp
    evaluation_multiseq_generation.cpp
    gguf_metadata.cpp
    common_string_utils.cpp
    generation_output_buffer.cpp
    generation_sampler.cpp
    prepared_prompt_cache.cpp
    reasoning_transition.cpp
    llama_jni.cpp
)

target_compile_definitions(
    local_llm_jni
    PRIVATE
    LOCAL_LLM_LLAMA_CPP_TAG="${LLAMA_CPP_EFFECTIVE_TAG}"
    LOCAL_LLM_LLAMA_CPP_COMMIT="${LLAMA_CPP_ACTUAL_COMMIT}"
)

target_include_directories(
    local_llm_jni
    PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
    "${LLAMA_CPP_DIR}/common"
    "${LLAMA_CPP_DIR}/vendor"
    "${LLAMA_CPP_DIR}/tools/mtmd"
)

find_library(android_lib android REQUIRED)
find_library(log_lib log REQUIRED)
find_library(dl_lib dl REQUIRED)

target_link_libraries(
    local_llm_jni
    PRIVATE
    llama
    llama-common
    mtmd
    ggml
    ${android_lib}
    ${log_lib}
    ${dl_lib}
)

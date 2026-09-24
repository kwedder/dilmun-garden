# The on-device model: llama.cpp plus a small JNI layer (dilmun_llm.cpp).
#
# llama.cpp is fetched at a pinned release when the app is built. To build
# against a local checkout instead (the desktop tests do this), pass
# -DLLAMA_DIR=/path/to/llama.cpp.

cmake_minimum_required(VERSION 3.22)
project(dilmun_llm C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# only the library and its common helpers; no tools, servers, tests or network code
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS     OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # one CPU backend per instruction-set level, chosen at runtime for the phone's cores
    set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_OPENMP ON CACHE BOOL "" FORCE)
    else()
        set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    endif()
endif()

if(DEFINED LLAMA_DIR)
    add_subdirectory(${LLAMA_DIR} llama-build)
else()
    include(FetchContent)
    FetchContent_Declare(llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        b11160
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(llama)
endif()

add_library(dilmun_llm SHARED dilmun_llm.cpp)
target_link_libraries(dilmun_llm PRIVATE llama llama-common)

if(ANDROID)
    target_link_libraries(dilmun_llm PRIVATE android log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(dilmun_llm PRIVATE ${JNI_INCLUDE_DIRS})
endif()

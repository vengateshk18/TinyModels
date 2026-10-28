# CMakeLists for the GGUF/llama.cpp bridge.
# Requires llama.cpp source in ../llama.cpp (or set LLAMA_CPP_DIR).
# Produces libgguf_bridge.so loaded by LlamaCppBridge.kt.

cmake_minimum_required(VERSION 3.22)
project(gguf_bridge C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED LLAMA_CPP_DIR)
    set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
endif()

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_CPP_DIR}. Clone it there first.")
endif()

# Build llama.cpp as a static lib (core only, no server/examples).
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
add_subdirectory("${LLAMA_CPP_DIR}" llama.cpp-build EXCLUDE_FROM_ALL)

add_library(gguf_bridge SHARED gguf_bridge.cpp)
target_include_directories(gguf_bridge PRIVATE
    "${LLAMA_CPP_DIR}/include"
    "${LLAMA_CPP_DIR}/ggml/include"
    "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(gguf_bridge PRIVATE llama ggml android log)
EOF
echo done
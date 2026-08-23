cmake_minimum_required(VERSION 3.22)
project(transcribe_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# This repository builds libtranscribe_jni.so only.
# The transcribe.cpp sources/static libraries are provided by
# scripts/build_transcribe_cpp_android.sh through these variables.
if(NOT DEFINED TRANSCRIBE_SOURCE_DIR)
    set(TRANSCRIBE_SOURCE_DIR "$ENV{TRANSCRIBE_SOURCE_DIR}")
endif()
if(NOT DEFINED TRANSCRIBE_BUILD_DIR)
    set(TRANSCRIBE_BUILD_DIR "$ENV{TRANSCRIBE_BUILD_DIR}")
endif()

if(NOT TRANSCRIBE_SOURCE_DIR OR NOT TRANSCRIBE_BUILD_DIR)
    message(FATAL_ERROR "TRANSCRIBE_SOURCE_DIR and TRANSCRIBE_BUILD_DIR must be set")
endif()

add_library(transcribe_jni SHARED transcribe_jni.cpp)
target_include_directories(transcribe_jni PRIVATE "${TRANSCRIBE_SOURCE_DIR}/include")
target_link_libraries(transcribe_jni PRIVATE
    "${TRANSCRIBE_BUILD_DIR}/src/libtranscribe.a"
    "${TRANSCRIBE_BUILD_DIR}/ggml/src/libggml-cpu.a"
    "${TRANSCRIBE_BUILD_DIR}/ggml/src/libggml-base.a"
    "${TRANSCRIBE_BUILD_DIR}/ggml/src/libggml.a"
    android
    log
)

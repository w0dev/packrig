cmake_minimum_required(VERSION 3.22.1)

project(ft8vc LANGUAGES CXX)

# Phase 0: a stub shared library that only proves the NDK/JNI toolchain works.
# Phase 2 will add the vendored kgoba/ft8_lib sources (C) plus a PCM->magnitude
# DSP front-end here and link them into this same `ft8vc` library.
add_library(ft8vc SHARED
        ft8_jni.cpp)

find_library(log-lib log)

target_link_libraries(ft8vc
        ${log-lib})

cmake_minimum_required(VERSION 3.22.1)

# 16 KB page size (Android 15+ / Pixel emulators): arm64-v8a and x86_64 .so files
# must have ELF LOAD align 2**14. Apply at project level so all linked code uses it.
if (ANDROID)
    set(CMAKE_SHARED_LINKER_FLAGS
            "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
endif ()

project(packset LANGUAGES C CXX)

# kgoba/ft8_lib has no CMakeLists, so we download the sources (pinned commit)
# and compile the subset we need directly into libpackset.so. We deliberately omit
# common/audio.c (PortAudio) and common/wave.c (file IO) since we feed PCM from
# Android. Pin a commit for reproducible builds; bump deliberately — follow
# docs/FT8_LIB_UPGRADE.md. FT8LIB_COMMIT is the single place the pin lives:
# the short hash flows into the ft8_jni.cpp version string automatically.
set(FT8LIB_COMMIT 9fec6ca39886edbf96f4f5e71edc76da5074e871)
string(SUBSTRING "${FT8LIB_COMMIT}" 0 7 FT8LIB_SHORT_HASH)

include(FetchContent)
FetchContent_Declare(
        ft8lib
        GIT_REPOSITORY https://github.com/kgoba/ft8_lib.git
        GIT_TAG ${FT8LIB_COMMIT}
)
# CMake 4.1+: Populate() is deprecated; MakeAvailable() is the supported path.
FetchContent_MakeAvailable(ft8lib)

set(FT8_DIR ${ft8lib_SOURCE_DIR})

add_library(packset SHARED
        ft8_jni.cpp
        ${FT8_DIR}/ft8/constants.c
        ${FT8_DIR}/ft8/crc.c
        ${FT8_DIR}/ft8/decode.c
        ${FT8_DIR}/ft8/encode.c
        ${FT8_DIR}/ft8/ldpc.c
        ${FT8_DIR}/ft8/message.c
        ${FT8_DIR}/ft8/text.c
        ${FT8_DIR}/fft/kiss_fft.c
        ${FT8_DIR}/fft/kiss_fftr.c
        ${FT8_DIR}/common/monitor.c)

# Root include dir so "ft8/...", "fft/...", "common/..." style includes resolve.
target_include_directories(packset PRIVATE ${FT8_DIR})

# Upstream ft8_lib logs uint64_t with %llx, which -Wformat flags on LP64
# Android (uint64_t is unsigned long there). Pinned third-party source we
# don't patch — silence the warning for that one file only.
set_source_files_properties(${FT8_DIR}/ft8/message.c PROPERTIES COMPILE_OPTIONS "-Wno-format")

# bionic provides stpcpy; tell ft8_lib not to define its own.
target_compile_definitions(packset PRIVATE
        HAVE_STPCPY
        FT8LIB_SHORT_HASH="${FT8LIB_SHORT_HASH}")

find_library(log-lib log)
target_link_libraries(packset ${log-lib})

# Belt-and-suspenders on the final link (NDK r29 should default to 16 KB; explicit
# flags matter if an older NDK is picked up or caches are stale).
if (ANDROID)
    target_link_options(packset PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384")
endif ()

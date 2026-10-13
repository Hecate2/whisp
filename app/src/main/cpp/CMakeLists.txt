# SPDX-License-Identifier: LGPL-3.0-or-later
cmake_minimum_required(VERSION 3.31)
project(whisp_nfs LANGUAGES C CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_compile_options("-ffile-prefix-map=${CMAKE_SOURCE_DIR}=/whisp/source"
                    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=/whisp/build")
if(ANDROID)
    add_compile_options("-ffile-prefix-map=${CMAKE_ANDROID_NDK}=/android/ndk")
endif()

include(FetchContent)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_UTILS OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(ENABLE_MULTITHREADING OFF CACHE BOOL "" FORCE)
set(ENABLE_TLS OFF CACHE STRING "" FORCE)
FetchContent_Declare(libnfs
    URL https://codeload.github.com/sahlberg/libnfs/tar.gz/5b1bc60260b5ddac34900c5315010b88b73e7edd
    URL_HASH SHA256=80b67a8905f7e2051245c6fadc1c4beb7a6481da278e7664fee146e3bbe4b0f1
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(libnfs)
set_property(TARGET nfs PROPERTY VERSION)
set_property(TARGET nfs PROPERTY SOVERSION)

add_library(whisp_nfs SHARED nfs_bridge.cpp)
target_compile_features(whisp_nfs PRIVATE cxx_std_17)
target_compile_options(whisp_nfs PRIVATE -Wall -Wextra -Werror)
target_include_directories(whisp_nfs PRIVATE ${libnfs_SOURCE_DIR}/include)
target_link_libraries(whisp_nfs PRIVATE nfs)
if(NOT ANDROID)
    find_package(JNI REQUIRED COMPONENTS JVM)
    target_include_directories(whisp_nfs PRIVATE ${JNI_INCLUDE_DIRS})
endif()

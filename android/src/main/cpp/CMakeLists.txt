cmake_minimum_required(VERSION 3.22.1)

project(piko_xquic_native LANGUAGES C CXX)

include(FetchContent)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(PIKO_XQUIC_GIT_TAG "v1.9.2" CACHE STRING "XQUIC git tag used by Piko native transport")
set(PIKO_BORINGSSL_GIT_TAG "main" CACHE STRING "BoringSSL git tag used by Piko native transport")
set(PIKO_XQUIC_SOURCE_DIR "" CACHE PATH "Optional local XQUIC source checkout")
set(PIKO_BORINGSSL_SOURCE_DIR "" CACHE PATH "Optional local BoringSSL source checkout")

if(PIKO_BORINGSSL_SOURCE_DIR)
    set(boringssl_SOURCE_DIR "${PIKO_BORINGSSL_SOURCE_DIR}")
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory("${boringssl_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/boringssl")
else()
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
        boringssl
        GIT_REPOSITORY https://github.com/google/boringssl.git
        GIT_TAG ${PIKO_BORINGSSL_GIT_TAG}
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(boringssl)
endif()

if(PIKO_XQUIC_SOURCE_DIR)
    set(xquic_SOURCE_DIR "${PIKO_XQUIC_SOURCE_DIR}")
else()
    FetchContent_Declare(
        xquic
        GIT_REPOSITORY https://github.com/alibaba/xquic.git
        GIT_TAG ${PIKO_XQUIC_GIT_TAG}
        GIT_SHALLOW TRUE
    )
    FetchContent_Populate(xquic)
endif()

file(GLOB_RECURSE XQUIC_COMMON_SOURCES CONFIGURE_DEPENDS
    "${xquic_SOURCE_DIR}/src/common/*.c"
)
file(GLOB_RECURSE XQUIC_TRANSPORT_SOURCES CONFIGURE_DEPENDS
    "${xquic_SOURCE_DIR}/src/transport/*.c"
)
file(GLOB_RECURSE XQUIC_TLS_SOURCES CONFIGURE_DEPENDS
    "${xquic_SOURCE_DIR}/src/tls/*.c"
)
file(GLOB_RECURSE XQUIC_CC_SOURCES CONFIGURE_DEPENDS
    "${xquic_SOURCE_DIR}/src/congestion_control/*.c"
)

list(FILTER XQUIC_TLS_SOURCES EXCLUDE REGEX ".*/src/tls/babassl/.*")

add_library(xquic_core STATIC
    ${XQUIC_COMMON_SOURCES}
    ${XQUIC_TRANSPORT_SOURCES}
    ${XQUIC_TLS_SOURCES}
    ${XQUIC_CC_SOURCES}
)

target_include_directories(xquic_core PRIVATE
    "${xquic_SOURCE_DIR}"
    "${xquic_SOURCE_DIR}/include"
    "${xquic_SOURCE_DIR}/src"
    "${boringssl_SOURCE_DIR}/include"
)

target_compile_definitions(xquic_core PRIVATE
    XQC_ONLY_ERROR_LOG=1
)

target_compile_options(xquic_core PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-missing-field-initializers
)

add_library(piko_xquic SHARED
    piko_xquic_jni.cpp
    piko_xquic_shim.c
)

target_include_directories(piko_xquic PRIVATE
    "${xquic_SOURCE_DIR}"
    "${xquic_SOURCE_DIR}/include"
    "${xquic_SOURCE_DIR}/src"
)

target_link_libraries(piko_xquic PRIVATE
    xquic_core
    ssl
    crypto
    android
    log
)

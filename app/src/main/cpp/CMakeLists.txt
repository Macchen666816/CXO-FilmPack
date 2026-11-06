cmake_minimum_required(VERSION 3.22.1)
project(cxo_acceleration LANGUAGES C CXX)

# RAW is independent of the optional ARM64-only RGB entry accelerator.
set(RAW_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libraw")
file(GLOB_RECURSE RAW_SOURCES CONFIGURE_DEPENDS "${RAW_SOURCE}/src/*.cpp")
list(FILTER RAW_SOURCES EXCLUDE REGEX "_ph\\.cpp$")
add_library(cxo_raw_vendor STATIC ${RAW_SOURCES})
set_target_properties(cxo_raw_vendor PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(cxo_raw_vendor PUBLIC "${RAW_SOURCE}")
target_compile_definitions(cxo_raw_vendor PUBLIC LIBRAW_NODLL LIBRAW_USE_AUTOPTR USE_ZLIB)
target_compile_options(cxo_raw_vendor PRIVATE -O3)
target_link_libraries(cxo_raw_vendor PUBLIC z)
# Minimal embedded-profile Apple Linear DNG renderer. JPEG XL deliberately excluded.
set(DNG_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/vendor/dng-sdk/source")
file(GLOB DNG_SOURCES CONFIGURE_DEPENDS "${DNG_SOURCE}/*.cpp")
add_library(cxo_dng_sdk STATIC ${DNG_SOURCES})
set_target_properties(cxo_dng_sdk PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(cxo_dng_sdk PUBLIC "${DNG_SOURCE}" "${CMAKE_CURRENT_SOURCE_DIR}/vendor/dng-sdk/include")
target_compile_features(cxo_dng_sdk PUBLIC cxx_std_17)
target_compile_definitions(cxo_dng_sdk PUBLIC qAndroid=1 qMacOS=0 qWinOS=0 qLinux=0
    qDNGUseXMP=0 qDNGXMPFiles=0 qDNGXMPDocOps=0 qDNGUseLibJPEG=0 qDNGSupportVC5=0
    qDNGThreadSafe=1 qDNGValidate=0 qDNGValidateTarget=0 qDNGUseStdInt=1)
target_compile_options(cxo_dng_sdk PRIVATE -O3 -Wno-multichar -ffunction-sections -fdata-sections)
target_link_libraries(cxo_dng_sdk PUBLIC z)
add_library(cxo_raw SHARED raw_bridge.cpp apple_proraw_bridge.cpp)
target_compile_features(cxo_raw PRIVATE cxx_std_17)
target_compile_options(cxo_raw PRIVATE -O3 -Wall -Wextra -Werror)
target_link_options(cxo_raw PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(cxo_raw PRIVATE cxo_raw_vendor cxo_dng_sdk)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(cxo_entry SHARED unsupported.cpp)
    add_library(cxo_jpeg SHARED unsupported.cpp)
    target_link_options(cxo_entry PRIVATE -Wl,-z,max-page-size=16384)
    target_link_options(cxo_jpeg PRIVATE -Wl,-z,max-page-size=16384)
    return()
endif()

add_library(cxo_entry SHARED entry.cpp)
target_compile_features(cxo_entry PRIVATE cxx_std_17)
# Preserve all binary32 rounding points except the explicitly recovered FMA.
target_compile_options(cxo_entry PRIVATE -O3 -Wall -Wextra -Werror -fno-fast-math -ffp-contract=off -frounding-math)
target_link_options(cxo_entry PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(cxo_entry PRIVATE m)

# Upstream libjpeg-turbo explicitly forbids add_subdirectory(). Build its pinned,
# local source separately; no network access is performed by this build.
include(ExternalProject)
set(JPEG_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libjpeg-turbo")
set(JPEG_BINARY "${CMAKE_CURRENT_BINARY_DIR}/jpeg-build")
file(MAKE_DIRECTORY "${JPEG_BINARY}")
ExternalProject_Add(cxo_jpeg_vendor
    SOURCE_DIR "${JPEG_SOURCE}"
    BINARY_DIR "${JPEG_BINARY}"
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DCMAKE_BUILD_TYPE=Release"
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DANDROID_ABI=${ANDROID_ABI}"
        "-DANDROID_PLATFORM=android-26"
        "-DANDROID_STL=c++_static"
        "-DENABLE_SHARED=OFF" "-DENABLE_STATIC=ON"
        "-DWITH_TURBOJPEG=OFF" "-DWITH_TOOLS=OFF" "-DWITH_TESTS=OFF"
        "-DWITH_SIMD=ON" "-DREQUIRE_SIMD=ON"
    BUILD_COMMAND "${CMAKE_COMMAND}" --build <BINARY_DIR> --target jpeg-static --parallel 4
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${JPEG_BINARY}/libjpeg.a"
)
add_library(cxo_jpeg_static STATIC IMPORTED)
set_target_properties(cxo_jpeg_static PROPERTIES IMPORTED_LOCATION "${JPEG_BINARY}/libjpeg.a")
add_library(cxo_jpeg SHARED jpeg_bridge.cpp)
add_dependencies(cxo_jpeg cxo_jpeg_vendor)
target_compile_features(cxo_jpeg PRIVATE cxx_std_17)
target_include_directories(cxo_jpeg PRIVATE "${JPEG_SOURCE}/src" "${JPEG_BINARY}")
target_compile_options(cxo_jpeg PRIVATE -O3 -Wall -Wextra -Werror)
target_link_options(cxo_jpeg PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(cxo_jpeg PRIVATE cxo_jpeg_static)

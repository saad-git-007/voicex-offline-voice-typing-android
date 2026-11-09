# VoiceX native build: transcribe.cpp (git submodule) + a small JNI layer.
#
# transcribe.cpp has no official Android build. Everything Android-specific is set from THIS file;
# the submodule itself is unmodified (see BUILD_NOTES.md, "CMake changes").
cmake_minimum_required(VERSION 3.22)
project(voicex VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(TRANSCRIBE_SRC ${CMAKE_CURRENT_LIST_DIR}/transcribe.cpp)
set(THIRD_PARTY    ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

option(VOICEX_VULKAN  "Build the ggml Vulkan backend (Adreno GPU)"     ON)
option(VOICEX_OPENCL  "Build the ggml OpenCL backend (Adreno kernels)" OFF)
option(VOICEX_HEXAGON "Build the ggml Hexagon backend (Snapdragon NPU)" OFF)

# --- library shape --------------------------------------------------------------------------------
# Shared libtranscribe + every ggml backend as a separate dlopen()ed module. A backend whose vendor
# library is missing (libOpenCL.so / libcdsprpc.so on a non-Qualcomm phone or the emulator) then fails
# to load quietly instead of taking the whole app down with an UnsatisfiedLinkError.
set(TRANSCRIBE_BUILD_SHARED     ON  CACHE BOOL "" FORCE)
set(TRANSCRIBE_GGML_BACKEND_DL  ON  CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_INSTALL          OFF CACHE BOOL "" FORCE)
# No OpenBLAS/cblas on Android: the RNN-T decoder uses its scalar fallback.
set(TRANSCRIBE_USE_SYSTEM_BLAS  OFF CACHE BOOL "" FORCE)
# CPU: one module per ARM feature level, scored at run time (the emulator's arm64 translation picks the
# armv8.0 one). app/build.gradle.kts leaves the i8mm/SVE variants out of the APK, because ggml compiles
# tinyBLAS out of those (ggml-cpu.c: SVE || MATMUL_INT8 -> #undef GGML_USE_LLAMAFILE).
set(GGML_CPU_ALL_VARIANTS       ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP                 OFF CACHE BOOL "" FORCE)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "VoiceX ships arm64-v8a only (got ${ANDROID_ABI})")
endif()

# --- Vulkan: NDK libvulkan stub + NDK glslc; C++ headers (vulkan.hpp) from Khronos Vulkan-Headers ----------
if(VOICEX_VULKAN)
    set(TRANSCRIBE_VULKAN ON CACHE BOOL "" FORCE)
    set(Vulkan_INCLUDE_DIR ${THIRD_PARTY}/Vulkan-Headers/include CACHE PATH "" FORCE)
    find_library(VOICEX_NDK_VULKAN vulkan REQUIRED)
    set(Vulkan_LIBRARY ${VOICEX_NDK_VULKAN} CACHE FILEPATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE ${ANDROID_NDK}/shader-tools/linux-x86_64/glslc CACHE FILEPATH "" FORCE)
    set(SPIRV-Headers_DIR ${CMAKE_CURRENT_LIST_DIR}/cmake CACHE PATH "" FORCE)
    # vulkan-shaders-gen runs on the build machine, so it needs a host compiler, not the NDK
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN ${CMAKE_CURRENT_LIST_DIR}/cmake/host-toolchain.cmake CACHE FILEPATH "" FORCE)
else()
    set(TRANSCRIBE_VULKAN OFF CACHE BOOL "" FORCE)
endif()

# --- OpenCL: link against a Khronos ICD loader; the phone's vendor libOpenCL.so is used at run time --------
if(VOICEX_OPENCL)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(OpenCL_INCLUDE_DIR ${THIRD_PARTY}/OpenCL-Headers CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY ${THIRD_PARTY}/opencl-android/lib/libOpenCL.so CACHE FILEPATH "" FORCE)
endif()

# --- Hexagon NPU: host-side backend + DSP-side skels (libggml-htp-v73.so = Snapdragon 8 Gen 2) --------------
if(VOICEX_HEXAGON)
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    set(HEXAGON_SDK_ROOT   ${THIRD_PARTY}/hexagon/6.7.0.0 CACHE PATH "" FORCE)
    set(HEXAGON_TOOLS_ROOT ${THIRD_PARTY}/hexagon/6.7.0.0/tools/HEXAGON_Tools/21.0.04 CACHE PATH "" FORCE)
    set(PREBUILT_LIB_DIR android_aarch64 CACHE STRING "" FORCE)
endif()

add_subdirectory(${TRANSCRIBE_SRC} transcribe)

# transcribe.cpp/src/CMakeLists.txt:160 uses ${CMAKE_SOURCE_DIR}/include, which is only right when transcribe.cpp
# is the top-level project. As a subproject that points at app/src/main/cpp/include: add the real path.
target_include_directories(transcribe PUBLIC $<BUILD_INTERFACE:${TRANSCRIBE_SRC}/include>)

# Android packages only lib*.so: drop versioned SONAMEs (libtranscribe.so.0.4) if a toolchain adds them.
foreach(_t transcribe ggml ggml-base)
    if(TARGET ${_t})
        set_property(TARGET ${_t} PROPERTY VERSION)
        set_property(TARGET ${_t} PROPERTY SOVERSION)
    endif()
endforeach()

if(VOICEX_VULKAN)
    target_include_directories(ggml-vulkan PRIVATE ${THIRD_PARTY}/SPIRV-Headers/include)
endif()

if(VOICEX_HEXAGON)
    # The skels are ExternalProject outputs, not targets: copy them next to the other libs so they land
    # in the APK. FastRPC finds them through ADSP_LIBRARY_PATH (set to nativeLibraryDir by the app).
    set(_skels v73 v75 v79 v81)
    set(_cmds)
    set(_deps)
    foreach(v ${_skels})
        list(APPEND _cmds COMMAND ${CMAKE_COMMAND} -E copy_if_different
             ${CMAKE_BINARY_DIR}/transcribe/ggml/src/ggml-hexagon/libggml-htp-${v}.so
             ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libggml-htp-${v}.so)
        list(APPEND _deps htp-${v})
    endforeach()
    add_custom_target(voicex-htp-skels ALL ${_cmds} DEPENDS ${_deps})
endif()

add_library(voicex_jni SHARED voicex_jni.cpp)
target_include_directories(voicex_jni PRIVATE ${TRANSCRIBE_SRC}/include)
target_link_libraries(voicex_jni PRIVATE transcribe android log)
target_link_options(voicex_jni PRIVATE -Wl,-z,max-page-size=16384)

# Gradle only builds what it packages: hang the backend modules and skels off the JNI library.
foreach(_m ggml-cpu ggml-vulkan ggml-opencl ggml-hexagon)
    if(TARGET ${_m})
        add_dependencies(voicex_jni ${_m})
    endif()
endforeach()
if(GGML_CPU_ALL_VARIANTS)
    foreach(_v android_armv8.0_1 android_armv8.2_1 android_armv8.2_2)
        if(TARGET ggml-cpu-${_v})
            add_dependencies(voicex_jni ggml-cpu-${_v})
        endif()
    endforeach()
endif()
if(VOICEX_HEXAGON)
    add_dependencies(voicex_jni voicex-htp-skels)
endif()

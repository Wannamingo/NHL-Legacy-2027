cmake_minimum_required(VERSION 3.25)
project(nhllegacy_android LANGUAGES C CXX ASM)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "This directory requires the Android NDK, arm64-v8a")
endif()
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(NHL_ROOT "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
set(REX_SDK "${NHL_ROOT}/third_party/rexglue-sdk")
add_executable(nhl_device_probe native/device_probe.cpp)
target_include_directories(nhl_device_probe PRIVATE
  "${REX_SDK}/thirdparty/vulkan-headers/include")
target_link_libraries(nhl_device_probe PRIVATE vulkan)
target_link_options(nhl_device_probe PRIVATE -Wl,-z,max-page-size=16384)

set(NHL_ANDROID_SDK "${NHL_ROOT}/private/reference-rexglue-android" CACHE PATH
  "ReXGlue Android source, pinned by tools/prepare_android_sdk.py")
add_executable(nhl_fiber_test native/fiber_test.cpp
  "${NHL_ANDROID_SDK}/src/core/fiber_aarch64.cpp"
  "${NHL_ANDROID_SDK}/src/core/fiber_aarch64.S")
target_include_directories(nhl_fiber_test PRIVATE "${NHL_ANDROID_SDK}/include")
target_compile_options(nhl_fiber_test PRIVATE -ffp-model=strict)
target_link_options(nhl_fiber_test PRIVATE -Wl,-z,max-page-size=16384)

option(NHL_BUILD_GAME "Build the recompiled NHL game shared library" OFF)
if(NHL_BUILD_GAME)
  include(vp6.cmake)
  if(NOT EXISTS "${NHL_ROOT}/generated/default/sources.cmake")
    message(FATAL_ERROR "Run the patched NHL code generator first")
  endif()
  add_library(nhl_runtime SHARED IMPORTED)
  set_target_properties(nhl_runtime PROPERTIES
    IMPORTED_LOCATION "${NHL_ANDROID_SDK}/out/linux-arm64/librexruntime.so")
  add_library(nhl_sdk_headers INTERFACE)
  target_include_directories(nhl_sdk_headers INTERFACE
    "${NHL_ROOT}" "${NHL_ANDROID_SDK}/include"
    "${NHL_ROOT}/private/build-sdk-arm64/include"
    "${NHL_ANDROID_SDK}/thirdparty/simde"
    "${NHL_ANDROID_SDK}/thirdparty/fmt/include"
    "${NHL_ANDROID_SDK}/thirdparty/spdlog/include"
    "${NHL_ANDROID_SDK}/thirdparty/tomlplusplus/include"
    "${NHL_ANDROID_SDK}/thirdparty/imgui"
    "${NHL_ANDROID_SDK}/thirdparty/sdl3/include"
    "${NHL_ANDROID_SDK}/thirdparty/vulkan-headers/include"
    "${NHL_ANDROID_SDK}/thirdparty/volk")
  target_compile_definitions(nhl_sdk_headers INTERFACE
    REX_PLATFORM_LINUX=1 REX_HAS_VULKAN=1 SPDLOG_COMPILED_LIB
    SPDLOG_FMT_EXTERNAL _LIBCPP_ENABLE_EXPERIMENTAL
    REXGLUE_BUILD_CONFIG="Release")
  target_compile_options(nhl_sdk_headers INTERFACE
    -fno-strict-aliasing -ffp-model=strict -fno-char8_t)
  add_executable(nhl_trace_replay native/trace_replay.cpp)
  target_include_directories(nhl_trace_replay PRIVATE "${NHL_ANDROID_SDK}/thirdparty/renderdoc")
  target_link_libraries(nhl_trace_replay PRIVATE nhl_sdk_headers nhl_runtime log android)
  target_link_options(nhl_trace_replay PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_trace_replay PROPERTIES BUILD_RPATH "$ORIGIN")

  add_executable(nhl_memory_map_test native/memory_map_test.cpp)
  target_include_directories(nhl_memory_map_test PRIVATE "${NHL_ANDROID_SDK}/src/core")
  target_link_libraries(nhl_memory_map_test PRIVATE nhl_sdk_headers nhl_runtime log android)
  target_link_options(nhl_memory_map_test PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_memory_map_test PROPERTIES BUILD_RPATH "$ORIGIN")

  add_executable(nhl_shared_memory_test native/shared_memory_test.cpp)
  add_executable(nhl_write_watch_test native/write_watch_test.cpp)
  target_link_libraries(nhl_write_watch_test PRIVATE nhl_sdk_headers nhl_runtime log android)
  target_link_options(nhl_write_watch_test PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_write_watch_test PROPERTIES BUILD_RPATH "$ORIGIN")
  target_link_libraries(nhl_shared_memory_test PRIVATE nhl_sdk_headers nhl_runtime log android)
  target_link_options(nhl_shared_memory_test PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_shared_memory_test PROPERTIES BUILD_RPATH "$ORIGIN")

  add_executable(nhl_cache_stream_test native/cache_stream_test.cpp)
  target_link_libraries(nhl_cache_stream_test PRIVATE nhl_sdk_headers nhl_runtime log android)
  target_link_options(nhl_cache_stream_test PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_cache_stream_test PROPERTIES BUILD_RPATH "$ORIGIN")

  add_executable(nhl_shader_inspect native/shader_inspect.cpp)
  target_link_libraries(nhl_shader_inspect PRIVATE nhl_sdk_headers nhl_runtime log android)
  target_link_options(nhl_shader_inspect PRIVATE -Wl,-z,max-page-size=16384)

  include("${NHL_ROOT}/generated/default/sources.cmake")
  # Standalone Runtime::Setup registers PPCImageConfig.func_mappings itself.
  # The enormous *_register.cpp function is only used by guest DLL modules;
  # compiling it here would duplicate an unused registration mechanism.
  list(REMOVE_ITEM GENERATED_SOURCES "${NHL_ROOT}/generated/default/nhllegacy_register.cpp")
  add_library(nhl_guest OBJECT ${GENERATED_SOURCES})
  target_link_libraries(nhl_guest PRIVATE nhl_sdk_headers)
  # Guest chunks are large. O2 avoids excessive compiler memory consumption.
  target_compile_options(nhl_guest PRIVATE -O2)

  add_library(main SHARED native/nhl_app.cpp native/frame_diagnostics.cpp native/renderer_hooks.cpp native/crowd_diagnostics.cpp native/crowd_visibility.cpp native/asset_float_reader.cpp native/fence_wait.cpp native/touch_controls.cpp native/vp6_hooks.cpp "${NHL_ROOT}/src/kernel_stubs.cpp"
    "${NHL_ANDROID_SDK}/src/ui/rex_app.cpp"
    "${NHL_ANDROID_SDK}/src/ui/windowed_app_main_sdl.cpp"
    $<TARGET_OBJECTS:nhl_guest>)
  target_link_libraries(main PRIVATE nhl_sdk_headers nhl_runtime nhl_vp6 log android)
  target_link_options(main PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
  add_executable(nhl_vertex_upload_test native/vertex_upload_test.cpp)
  target_link_libraries(nhl_vertex_upload_test PRIVATE nhl_sdk_headers main nhl_runtime log android)
  target_link_options(nhl_vertex_upload_test PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_vertex_upload_test PROPERTIES BUILD_RPATH "$ORIGIN")
  add_executable(nhl_half_decode_test native/half_decode_test.cpp)
  target_link_libraries(nhl_half_decode_test PRIVATE nhl_sdk_headers main nhl_runtime log android)
  target_link_options(nhl_half_decode_test PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_half_decode_test PROPERTIES BUILD_RPATH "$ORIGIN")
  add_executable(nhl_asset_float_test native/asset_float_test.cpp)
  target_link_libraries(nhl_asset_float_test PRIVATE nhl_sdk_headers main nhl_runtime log android)
  target_link_options(nhl_asset_float_test PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_asset_float_test PROPERTIES BUILD_RPATH "$ORIGIN")
  add_executable(nhl_frame_diagnostics_test native/frame_diagnostics_test.cpp native/frame_diagnostics.cpp)
  target_link_libraries(nhl_frame_diagnostics_test PRIVATE nhl_sdk_headers nhl_runtime log android)
  target_link_options(nhl_frame_diagnostics_test PRIVATE -Wl,-z,max-page-size=16384)
  set_target_properties(nhl_frame_diagnostics_test PROPERTIES BUILD_RPATH "$ORIGIN")
endif()

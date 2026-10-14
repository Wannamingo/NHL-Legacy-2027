# Reuse the pinned runtime's LGPL FFmpeg core, with a separate video decoder.
# The runtime's audio codec registry and its XMA decoding stay independent.
set(NHL_FFMPEG "${NHL_ANDROID_SDK}/thirdparty/FFmpeg")
add_library(nhl_vp6 STATIC native/vp6_decoder.cpp
  "${NHL_FFMPEG}/libavcodec/vp6.c"
  "${NHL_FFMPEG}/libavcodec/vp56.c"
  "${NHL_FFMPEG}/libavcodec/vp56data.c"
  "${NHL_FFMPEG}/libavcodec/vp56rac.c"
  "${NHL_FFMPEG}/libavcodec/vp56dsp.c"
  "${NHL_FFMPEG}/libavcodec/vp6dsp.c"
  "${NHL_FFMPEG}/libavcodec/vp3dsp.c"
  "${NHL_FFMPEG}/libavcodec/huffman.c"
  "${NHL_FFMPEG}/libavcodec/h264chroma.c"
  "${NHL_FFMPEG}/libavcodec/hpeldsp.c"
  "${NHL_FFMPEG}/libavcodec/videodsp.c"
  "${NHL_FFMPEG}/libavcodec/aarch64/h264chroma_init_aarch64.c"
  "${NHL_FFMPEG}/libavcodec/aarch64/h264cmc_neon.S"
  "${NHL_FFMPEG}/libavcodec/aarch64/hpeldsp_init_aarch64.c"
  "${NHL_FFMPEG}/libavcodec/aarch64/hpeldsp_neon.S"
  "${NHL_FFMPEG}/libavcodec/aarch64/videodsp_init.c")
target_include_directories(nhl_vp6 PRIVATE "${NHL_FFMPEG}")
target_compile_definitions(nhl_vp6 PRIVATE HAVE_AV_CONFIG_H _USE_MATH_DEFINES)
target_compile_options(nhl_vp6 PRIVATE -fvisibility=hidden -w)
set_source_files_properties("${NHL_FFMPEG}/libavcodec/vp56dsp.c" PROPERTIES
  COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/native/vp6_ffmpeg_config.h")
target_link_libraries(nhl_vp6 PRIVATE
  "${NHL_ANDROID_SDK}/out/linux-arm64/liblibavcodec.a"
  "${NHL_ANDROID_SDK}/out/linux-arm64/liblibavutil.a" m)
add_executable(nhl_vp6_decode_test native/vp6_decode_test.cpp)
target_link_libraries(nhl_vp6_decode_test PRIVATE nhl_vp6)
target_link_options(nhl_vp6_decode_test PRIVATE -Wl,-z,max-page-size=16384)

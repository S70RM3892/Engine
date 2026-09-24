# ホスト (PC) 上でネイティブコアを検証するためのテスト。Android 非依存部分のみをビルドする。
cmake_minimum_required(VERSION 3.22)
project(EngineSimHostTests CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()
set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/cpp)
add_library(escore STATIC
  ${SRC}/core/Json.cpp
  ${SRC}/core/EngineSpec.cpp
  ${SRC}/core/CustomEngine.cpp
  ${SRC}/sim/EngineKinematics.cpp
  ${SRC}/sim/EngineSimulation.cpp
  ${SRC}/audio/EngineAcousticsDSP.cpp)
target_include_directories(escore PUBLIC ${SRC})
target_compile_options(escore PRIVATE -Wall -Wextra -Wno-unused-parameter -fno-exceptions -fno-rtti)
add_executable(host_tests host_tests.cpp)
target_link_libraries(host_tests escore)
target_compile_definitions(host_tests PRIVATE ASSET_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/engines")
add_executable(audio_check audio_check.cpp)
target_link_libraries(audio_check escore)
target_compile_definitions(audio_check PRIVATE ASSET_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/engines")

add_executable(game_balance game_balance.cpp)
target_link_libraries(game_balance escore)
target_compile_definitions(game_balance PRIVATE ASSET_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/engines")

# ヘッドレス描画 (EGL + GLES3 / Vulkan, Mesa) が使える環境のみ
find_library(EGL_LIB EGL)
find_library(GLES_LIB GLESv2)
find_package(Vulkan)
find_program(ES_GLSLC glslc
  HINTS "$ENV{ANDROID_NDK_HOME}/shader-tools/linux-x86_64"
        "$ENV{ANDROID_HOME}/ndk/29.0.14206865/shader-tools/linux-x86_64"
        "$ENV{ANDROID_SDK_ROOT}/ndk/29.0.14206865/shader-tools/linux-x86_64")
if(EGL_LIB AND GLES_LIB AND Vulkan_FOUND AND ES_GLSLC)
  include(${SRC}/VkShaders.cmake)
  add_executable(render_shots render_shots.cpp
    ${SRC}/render/Mesh.cpp ${SRC}/render/Scene.cpp ${SRC}/render/Camera.cpp ${SRC}/render/RenderFrontend.cpp
    ${SRC}/render/GLBackend.cpp ${SRC}/render/vk/VkBackend.cpp ${SRC}/render/Renderer.cpp)
  target_link_libraries(render_shots escore ${EGL_LIB} ${GLES_LIB} Vulkan::Vulkan)
  target_compile_definitions(render_shots PRIVATE ASSET_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/engines")
  es_vk_shaders(render_shots)
endif()

cmake_minimum_required(VERSION 3.22.1)
project(enginesim CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)
include(VkShaders.cmake)

add_library(enginesim SHARED
    core/Json.cpp
    core/EngineSpec.cpp
    core/CustomEngine.cpp
    sim/EngineKinematics.cpp
    sim/EngineSimulation.cpp
    audio/EngineAcousticsDSP.cpp
    audio/AudioEngine.cpp
    render/Mesh.cpp
    render/Scene.cpp
    render/Camera.cpp
    render/RenderFrontend.cpp
    render/GLBackend.cpp
    render/vk/VkBackend.cpp
    render/Renderer.cpp
    jni/NativeBridge.cpp)

target_include_directories(enginesim PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(enginesim PRIVATE -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -O2 -ffast-math -fno-finite-math-only)
target_link_libraries(enginesim oboe::oboe vulkan GLESv3 EGL android log)
es_vk_shaders(enginesim)
# Android 15+ の 16KB ページサイズ端末に対応
target_link_options(enginesim PRIVATE "-Wl,-z,max-page-size=16384")

cmake_minimum_required(VERSION 3.22.1)
project(enginesim CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(enginesim SHARED
    core/Json.cpp
    core/EngineSpec.cpp
    sim/EngineKinematics.cpp
    sim/EngineSimulation.cpp
    audio/EngineAcousticsDSP.cpp
    audio/AudioEngine.cpp
    render/Mesh.cpp
    render/Scene.cpp
    render/Camera.cpp
    render/Renderer.cpp
    jni/NativeBridge.cpp)

target_include_directories(enginesim PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(enginesim PRIVATE -Wall -Wextra -Wno-unused-parameter -O2 -ffast-math -fno-finite-math-only)
target_link_libraries(enginesim oboe::oboe GLESv3 EGL android log)
# Android 15+ の 16KB ページサイズ端末に対応
target_link_options(enginesim PRIVATE "-Wl,-z,max-page-size=16384")

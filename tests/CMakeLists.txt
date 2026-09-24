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
  ${SRC}/sim/EngineKinematics.cpp
  ${SRC}/sim/EngineSimulation.cpp
  ${SRC}/audio/EngineAcousticsDSP.cpp)
target_include_directories(escore PUBLIC ${SRC})
target_compile_options(escore PRIVATE -Wall -Wextra -Wno-unused-parameter -fno-exceptions -fno-rtti)
add_executable(host_tests host_tests.cpp)
target_link_libraries(host_tests escore)
target_compile_definitions(host_tests PRIVATE ASSET_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/engines")

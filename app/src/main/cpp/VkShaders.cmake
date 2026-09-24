# Vulkan シェーダ (GLSL 4.50) を glslc で SPIR-V の C 配列 (.inc) にコンパイルする。
# 使い方: include(VkShaders.cmake) → es_vk_shaders(<target>)
set(ES_SHADER_DIR ${CMAKE_CURRENT_LIST_DIR}/render/vk/shaders)
set(ES_NDK_ROOT "${ANDROID_NDK}")
if(NOT ES_NDK_ROOT AND DEFINED ENV{ANDROID_NDK_HOME})
  set(ES_NDK_ROOT "$ENV{ANDROID_NDK_HOME}")
endif()
find_program(ES_GLSLC glslc
  HINTS
    "${ES_NDK_ROOT}/shader-tools/linux-x86_64"
    "${ES_NDK_ROOT}/shader-tools/darwin-x86_64"
    "${ES_NDK_ROOT}/shader-tools/windows-x86_64"
    "$ENV{ANDROID_HOME}/ndk/29.0.14206865/shader-tools/linux-x86_64"
    "$ENV{ANDROID_SDK_ROOT}/ndk/29.0.14206865/shader-tools/linux-x86_64"
  NO_CMAKE_FIND_ROOT_PATH)
if(NOT ES_GLSLC)
  message(FATAL_ERROR "glslc not found (NDK shader-tools). Set ANDROID_NDK_HOME.")
endif()

function(es_vk_shaders target)
  set(out ${CMAKE_CURRENT_BINARY_DIR}/es_shaders)
  set(incs)
  foreach(s pbr.vert pbr.frag cap.vert cap.frag bg.vert bg.frag particle.vert particle.frag)
    add_custom_command(
      OUTPUT ${out}/${s}.inc
      COMMAND ${CMAKE_COMMAND} -E make_directory ${out}
      COMMAND ${ES_GLSLC} -O -mfmt=c ${ES_SHADER_DIR}/${s} -o ${out}/${s}.inc
      DEPENDS ${ES_SHADER_DIR}/${s}
      COMMENT "glslc ${s}")
    list(APPEND incs ${out}/${s}.inc)
  endforeach()
  add_custom_target(${target}_shaders DEPENDS ${incs})
  add_dependencies(${target} ${target}_shaders)
  target_include_directories(${target} PRIVATE ${out})
endfunction()

cmake_minimum_required(VERSION 3.18)
project(evo_nn LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
option(EVONN_ENABLE_CUDA "Build the CUDA dense-layer backend when available" ON)

include(CheckLanguage)
if(EVONN_ENABLE_CUDA)
  check_language(CUDA)
endif()

set(EVONN_SOURCES
  src/main.c
  src/dataset.c
  src/nn.c
  src/cpu_backend.c
  src/ga.c)

if(EVONN_ENABLE_CUDA AND CMAKE_CUDA_COMPILER)
  enable_language(CUDA)
  list(APPEND EVONN_SOURCES src/cuda_backend.cu)
  set(EVONN_HAS_CUDA 1)
else()
  list(APPEND EVONN_SOURCES src/cuda_stub.c)
  set(EVONN_HAS_CUDA 0)
endif()

add_executable(evonn ${EVONN_SOURCES})
target_include_directories(evonn PRIVATE include)
target_compile_definitions(evonn PRIVATE EVONN_HAS_CUDA=${EVONN_HAS_CUDA})
target_compile_options(evonn PRIVATE $<$<COMPILE_LANGUAGE:C>:-Wall;-Wextra;-Wpedantic>)
target_link_libraries(evonn PRIVATE m)

if(EVONN_HAS_CUDA)
  set_target_properties(evonn PROPERTIES CUDA_STANDARD 14 CUDA_STANDARD_REQUIRED ON)
endif()

enable_testing()
add_test(NAME smoke
  COMMAND evonn --data ${CMAKE_SOURCE_DIR}/examples/shapes.json
                --population 6 --generations 2 --epochs 30 --seed 7)

add_executable(cpu_backend_test tests/test_cpu_backend.c src/cpu_backend.c)
target_include_directories(cpu_backend_test PRIVATE include)
target_link_libraries(cpu_backend_test PRIVATE m)
add_test(NAME cpu_auto_benchmark COMMAND cpu_backend_test)
foreach(SIMD_BACKEND scalar sse sse2 avx avx2 avx512)
  add_test(NAME cpu_${SIMD_BACKEND} COMMAND cpu_backend_test)
  set_tests_properties(cpu_${SIMD_BACKEND} PROPERTIES
    ENVIRONMENT "EVONN_CPU_BACKEND=${SIMD_BACKEND}")
endforeach()

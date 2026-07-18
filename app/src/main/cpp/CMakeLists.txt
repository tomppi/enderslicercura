cmake_minimum_required(VERSION 3.20)
project(EnderSlicerNative LANGUAGES CXX)

add_library(enderslicer_native SHARED native_bridge.cpp)

target_compile_features(enderslicer_native PRIVATE cxx_std_20)

target_compile_definitions(
    enderslicer_native
    PRIVATE
    ENDERSLICER_ENGINE_TARGET="CuraEngine-5.11.0"
)

find_library(log-lib log)
target_link_libraries(enderslicer_native PRIVATE ${log-lib})

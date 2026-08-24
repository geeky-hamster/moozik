cmake_minimum_required(VERSION 3.22.1)
project(moozik LANGUAGES CXX)

add_library(moozik_dsp SHARED
    biquad.cpp
    dsp_engine.cpp
    audio_output.cpp
    moozik_jni.cpp)

target_include_directories(moozik_dsp PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(moozik_dsp PRIVATE cxx_std_20)
target_compile_options(moozik_dsp PRIVATE -O3 -fno-math-errno -funroll-loops)
target_link_libraries(moozik_dsp PRIVATE log aaudio)

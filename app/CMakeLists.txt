cmake_minimum_required(VERSION 3.22.1)

project("proxitap")

# We create a shared library containing our JNI wrapper and (eventually) the RNNoise C source.
add_library(
        proxitap
        SHARED
        src/main/cpp/RnnoiseProcessor.cpp)

# Search for the log library so we can use android logging
find_library(
        log-lib
        log)

# Link our library against the Android log library
target_link_libraries(
        proxitap
        ${log-lib})

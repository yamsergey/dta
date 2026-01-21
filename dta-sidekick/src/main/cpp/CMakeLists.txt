# CMakeLists.txt for adt-sidekick native agent

cmake_minimum_required(VERSION 3.22.1)

project("sidekick-agent")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Agent library
add_library(sidekick-agent SHARED
        agent.cpp
        hook_manager.cpp
        class_transformer.cpp
)

# Include directories
target_include_directories(sidekick-agent PRIVATE
        ${CMAKE_SOURCE_DIR}
)

# Link libraries
target_link_libraries(sidekick-agent
        android
        log
)

# 16 KB page alignment for Android 15 compatibility
target_link_options(sidekick-agent PRIVATE -Wl,-z,max-page-size=16384)

# Optimize for size in release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(sidekick-agent PRIVATE -Os -fvisibility=hidden)
    target_link_options(sidekick-agent PRIVATE -Wl,--gc-sections)
endif()

cmake_minimum_required(VERSION 3.22.1)
project(dta-agent CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB SLICER_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/third_party/slicer/*.cc)

add_library(slicer STATIC ${SLICER_SOURCES})
target_include_directories(slicer PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/slicer/export)
# Upstream builds with -Wall -Werror plus suppressions; we only need it to compile.
target_compile_options(slicer PRIVATE -w -fexceptions -fvisibility=hidden)

add_library(dta-agent SHARED agent.cc transform.cc control_entry_hook.cc)
target_compile_options(dta-agent PRIVATE -Wall -fexceptions -fvisibility=hidden)
target_link_libraries(dta-agent PRIVATE slicer android log z)
# 16 KB page alignment (Android 15+ devices).
target_link_options(dta-agent PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections)

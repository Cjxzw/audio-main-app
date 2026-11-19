cmake_minimum_required(VERSION 3.22.1)
project(hanwo_runtime C)

add_library(hanwo_paths SHARED virtual_paths.c)
target_link_libraries(hanwo_paths dl)
target_compile_options(hanwo_paths PRIVATE -Wall -Wextra -Werror)

set(QUICKJS_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/quickjs)
add_library(hanwo_quickjs SHARED
    quickjs_bridge.c
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/cutils.c
    ${QUICKJS_DIR}/dtoa.c
)
target_include_directories(hanwo_quickjs PRIVATE ${QUICKJS_DIR})
target_compile_definitions(hanwo_quickjs PRIVATE CONFIG_VERSION="2026-06-04")
target_compile_options(hanwo_quickjs PRIVATE -D_GNU_SOURCE -Wno-deprecated-non-prototype)
target_link_libraries(hanwo_quickjs log)

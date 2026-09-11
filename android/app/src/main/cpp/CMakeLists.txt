# Native build for the terminal pty layer.
#
# Only the JNI surface and the pty core are built here; dsh_pty_test.c is a host-side
# test and is deliberately excluded (it has a main() and would not link into a library).

cmake_minimum_required(VERSION 3.22.1)
project(dshpty C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(termux SHARED
        dsh_pty.c
        dsh_pty_jni.c)

# The library is named `termux` because the reference emulator's JNI class calls
# System.loadLibrary("termux"); see app/build.gradle.kts for why that name is
# required during phase 1 and when it changes.
target_compile_options(termux PRIVATE
        -Wall
        -Wextra
        -Werror
        -fvisibility=hidden)


# Log to logcat so a failure is visible in `adb logcat` rather than only on the pty.
find_library(log-lib log)
target_link_libraries(termux ${log-lib})

cmake_minimum_required(VERSION 3.22.1)
project("detassist_native" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags for low-RAM environments
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O2 -fvisibility=hidden")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O2 -fvisibility=hidden")

# Source files
set(NATIVE_SOURCES
    NativeBridge.cpp
)

# Build shared library
add_library(detassist_native SHARED ${NATIVE_SOURCES})

# Link required libraries
target_link_libraries(detassist_native
    android
    log
)

# Include headers
target_include_directories(detassist_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Strip symbols in release build to reduce binary size
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set_target_properties(detassist_native PROPERTIES
        LINK_FLAGS "-s"
    )
endif()

# ========================================
# PRODUCTION INTEGRATION: Keyword Spotter
# ========================================
# Uncomment and configure one of these:
#
# OPTION 1: Porcupine (Picovoice)
# add_library(porcupine STATIC IMPORTED)
# set_target_properties(porcupine PROPERTIES
#     IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libpv_porcupine.so)
# target_link_libraries(detassist_native porcupine)
#
# OPTION 2: Pocketsphinx (CMU)
# add_subdirectory(pocketsphinx)
# target_link_libraries(detassist_native pocketsphinx)
#
# OPTION 3: Custom Snowboy-style engine
# add_library(custom_kws STATIC custom_kws.cpp)
# target_link_libraries(detassist_native custom_kws)

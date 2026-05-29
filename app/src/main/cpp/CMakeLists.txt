cmake_minimum_required(VERSION 3.22.1)
project("actme_jni")

set(MNN_SOURCE_ROOT "${CMAKE_SOURCE_DIR}/../../../../MNN")
set(MNN_INSTALL_ROOT "${MNN_SOURCE_ROOT}/project/android/build_64")
set(LIB_PATH "${MNN_INSTALL_ROOT}/lib")

message(STATUS "MNN_SOURCE_ROOT: ${MNN_SOURCE_ROOT}")
message(STATUS "MNN_INSTALL_ROOT: ${MNN_INSTALL_ROOT}")

include_directories("${MNN_SOURCE_ROOT}/include/")
include_directories("${MNN_SOURCE_ROOT}/transformers/llm/engine/include")
include_directories("${MNN_SOURCE_ROOT}/tools/audio/include")
include_directories("${MNN_SOURCE_ROOT}/3rd_party/")

add_library(${CMAKE_PROJECT_NAME} SHARED
        llm_jni.cpp
        llm_session.cpp
        processor.cpp
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE GIT_COMMIT_ID="unknown")

target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")

# Glob all MNN shared libraries from the build output
file(GLOB MNN_SO_FILES "${LIB_PATH}/libMNN*.so")
message(STATUS "MNN shared libraries: ${MNN_SO_FILES}")

set(MNN_IMPORTED_TARGETS "")
foreach(MNN_SO ${MNN_SO_FILES})
    get_filename_component(SO_NAME ${MNN_SO} NAME_WE)
    set(TARGET_NAME "${SO_NAME}_imported")
    add_library(${TARGET_NAME} SHARED IMPORTED)
    set_target_properties(${TARGET_NAME} PROPERTIES IMPORTED_LOCATION "${MNN_SO}")
    list(APPEND MNN_IMPORTED_TARGETS ${TARGET_NAME})
    message(STATUS "  Imported: ${TARGET_NAME} -> ${MNN_SO}")
endforeach()

# System libraries that MNN may need at link time
find_library(OPENCL_LIB OpenCL)
find_library(MEDIANDK_LIB mediandk)
find_library(ZLIB_LIB z)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log
        ${MNN_IMPORTED_TARGETS}
)

if(OPENCL_LIB)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${OPENCL_LIB})
    message(STATUS "Linking OpenCL: ${OPENCL_LIB}")
endif()
if(MEDIANDK_LIB)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${MEDIANDK_LIB})
endif()
if(ZLIB_LIB)
    target_link_libraries(${CMAKE_PROJECT_NAME} ${ZLIB_LIB})
endif()

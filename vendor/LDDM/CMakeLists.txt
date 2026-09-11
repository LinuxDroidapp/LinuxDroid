cmake_minimum_required(VERSION 3.20)

project(LDDM
    VERSION 0.1.0
    DESCRIPTION "LinuxDroid Display Manager"
    HOMEPAGE_URL "https://github.com/LinuxDroidapp/LDDM"
    LANGUAGES CXX
)

include(GNUInstallDirs)
include(CMakePackageConfigHelpers)

# Project options
option(LDDM_BUILD_TESTS "Build LDDM unit and integration tests" ON)
option(LDDM_WARNINGS_AS_ERRORS "Treat compiler warnings as errors" ON)
option(LDDM_ENABLE_COVERAGE "Enable code coverage reporting" OFF)

# C++ Standard Configuration
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Version definition and Git hash discovery
set(LDDM_VERSION_SUFFIX "-alpha")
set(LDDM_VERSION_STRING "${PROJECT_VERSION}${LDDM_VERSION_SUFFIX}")

find_package(Git QUIET)
if(GIT_FOUND AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/.git")
    execute_process(
        COMMAND ${GIT_EXECUTABLE} rev-parse --short HEAD
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        OUTPUT_VARIABLE LDDM_GIT_COMMIT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
endif()
if(NOT LDDM_GIT_COMMIT)
    set(LDDM_GIT_COMMIT "release")
endif()

# Configure version header
configure_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/include/lddm/version.hpp.in"
    "${CMAKE_CURRENT_BINARY_DIR}/include/lddm/version.hpp"
    @ONLY
)

# Compiler Warnings
set(LDDM_COMMON_WARNINGS
    -Wall
    -Wextra
    -Wpedantic
    -Wshadow
    -Wnon-virtual-dtor
    -Wcast-align
    -Wunused
    -Woverloaded-virtual
    -Wformat=2
)

if(LDDM_WARNINGS_AS_ERRORS)
    list(APPEND LDDM_COMMON_WARNINGS -Werror)
endif()

# Core Library Sources
set(LDDM_CORE_SOURCES
    src/core/error.cpp
    src/core/lifecycle.cpp
    src/core/types.cpp
    src/logging/log_level.cpp
    src/logging/log_message.cpp
    src/logging/log_sink.cpp
    src/logging/logger.cpp
    src/config/config_types.cpp
    src/config/config_parser.cpp
    src/config/config_validator.cpp
    src/config/config_migrator.cpp
    src/config/config.cpp
    src/platform/unique_fd.cpp
    src/platform/clock.cpp
    src/platform/environment.cpp
    src/platform/paths.cpp
    src/platform/signal_handler.cpp
    src/platform/platform.cpp
    src/platform/process.cpp
    src/process/process_types.cpp
    src/process/process_spec.cpp
    src/process/process_diagnostics.cpp
    src/process/process.cpp
    src/process/process_registry.cpp
    src/process/process_supervisor.cpp
    src/session/session_id.cpp
    src/session/session_state.cpp
    src/session/session_config.cpp
    src/session/session_paths.cpp
    src/session/session_environment.cpp
    src/session/session_resource.cpp
    src/session/session_diagnostics.cpp
    src/session/session.cpp
    src/session/session_manager.cpp
    src/weston/weston_types.cpp
    src/weston/weston_config.cpp
    src/weston/weston_spec.cpp
    src/weston/weston_readiness.cpp
    src/weston/weston_diagnostics.cpp
    src/weston/weston_manager.cpp
    src/ldde/ldde_types.cpp
    src/ldde/ldde_config.cpp
    src/ldde/ldde_spec.cpp
    src/ldde/ldde_readiness.cpp
    src/ldde/ldde_diagnostics.cpp
    src/ldde/ldde_manager.cpp
    src/recovery/recovery_types.cpp
    src/recovery/recovery_config.cpp
    src/recovery/recovery_diagnostics.cpp
    src/recovery/recovery_manager.cpp
)

add_library(lddm_core STATIC ${LDDM_CORE_SOURCES})
add_library(lddm::core ALIAS lddm_core)

target_include_directories(lddm_core
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<BUILD_INTERFACE:${CMAKE_CURRENT_BINARY_DIR}/include>
        $<INSTALL_INTERFACE:${CMAKE_INSTALL_INCLUDEDIR}>
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

target_compile_options(lddm_core
    PRIVATE
        ${LDDM_COMMON_WARNINGS}
)

# Executable Target
add_executable(lddm src/main.cpp)
target_link_libraries(lddm PRIVATE lddm_core)

target_compile_options(lddm
    PRIVATE
        ${LDDM_COMMON_WARNINGS}
)

# Packaging / Installation
install(TARGETS lddm lddm_core
    EXPORT LddmTargets
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
)

# Symlink: linuxdroid-display-manager -> lddm
install(CODE "
    file(MAKE_DIRECTORY \"\$ENV{DESTDIR}\${CMAKE_INSTALL_PREFIX}/${CMAKE_INSTALL_BINDIR}\")
    execute_process(
        COMMAND \${CMAKE_COMMAND} -E create_symlink lddm \"\$ENV{DESTDIR}\${CMAKE_INSTALL_PREFIX}/${CMAKE_INSTALL_BINDIR}/linuxdroid-display-manager\"
    )
")

install(DIRECTORY include/
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}
    FILES_MATCHING PATTERN "*.hpp"
)

install(FILES "${CMAKE_CURRENT_BINARY_DIR}/include/lddm/version.hpp"
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/lddm
)

# Configuration: install to /etc/linuxdroid/
install(FILES config/lddm.conf.example
    DESTINATION ${CMAKE_INSTALL_SYSCONFDIR}/linuxdroid
    RENAME lddm.conf
)

install(FILES config/lddm.conf.defaults
    DESTINATION ${CMAKE_INSTALL_SYSCONFDIR}/linuxdroid
)

# Fallback symlink: /etc/lddm/lddm.conf -> /etc/linuxdroid/lddm.conf
install(CODE "
    file(MAKE_DIRECTORY \"\$ENV{DESTDIR}\${CMAKE_INSTALL_PREFIX}/${CMAKE_INSTALL_SYSCONFDIR}/lddm\")
    execute_process(
        COMMAND \${CMAKE_COMMAND} -E create_symlink ../linuxdroid/lddm.conf \"\$ENV{DESTDIR}\${CMAKE_INSTALL_PREFIX}/${CMAKE_INSTALL_SYSCONFDIR}/lddm/lddm.conf\"
    )
")

# Documentation
install(FILES README.md
    DESTINATION ${CMAKE_INSTALL_DATADIR}/doc/linuxdroid-display-manager
)
install(DIRECTORY docs/
    DESTINATION ${CMAKE_INSTALL_DATADIR}/doc/linuxdroid-display-manager
    FILES_MATCHING PATTERN "*.md"
)

# CMake export configuration
install(EXPORT LddmTargets
    FILE LddmTargets.cmake
    NAMESPACE lddm::
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/lddm
)

write_basic_package_version_file(
    "${CMAKE_CURRENT_BINARY_DIR}/lddmConfigVersion.cmake"
    VERSION ${PROJECT_VERSION}
    COMPATIBILITY SameMajorVersion
)

install(FILES "${CMAKE_CURRENT_BINARY_DIR}/lddmConfigVersion.cmake"
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/lddm
)

# CPack Debian Packaging Configuration
set(CPACK_PACKAGE_NAME "linuxdroid-display-manager")
set(CPACK_PACKAGE_VENDOR "LinuxDroid Project")
set(CPACK_PACKAGE_DESCRIPTION_SUMMARY "LinuxDroid Display Manager (LDDM)")
set(CPACK_PACKAGE_VERSION_MAJOR "${PROJECT_VERSION_MAJOR}")
set(CPACK_PACKAGE_VERSION_MINOR "${PROJECT_VERSION_MINOR}")
set(CPACK_PACKAGE_VERSION_PATCH "${PROJECT_VERSION_PATCH}")
set(CPACK_PACKAGE_VERSION "${PROJECT_VERSION}")
set(CPACK_GENERATOR "DEB")
set(CPACK_DEBIAN_PACKAGE_NAME "linuxdroid-display-manager")
set(CPACK_DEBIAN_PACKAGE_MAINTAINER "LinuxDroid Team <dev@linuxdroid.org>")
set(CPACK_DEBIAN_PACKAGE_SECTION "x11")
set(CPACK_DEBIAN_PACKAGE_PRIORITY "optional")
set(CPACK_DEBIAN_PACKAGE_HOMEPAGE "https://github.com/LinuxDroidapp/LDDM")
set(CPACK_DEBIAN_PACKAGE_DEPENDS "libc6 (>= 2.34), libstdc++6 (>= 13)")
set(CPACK_DEBIAN_PACKAGE_RECOMMENDS "weston (>= 10.0)")
set(CPACK_DEBIAN_PACKAGE_SUGGESTS "linuxdroid-desktop-environment | ldde")
set(CPACK_DEBIAN_PACKAGE_CONTROL_EXTRA
    "${CMAKE_CURRENT_SOURCE_DIR}/packaging/debian/postinst;${CMAKE_CURRENT_SOURCE_DIR}/packaging/debian/prerm;${CMAKE_CURRENT_SOURCE_DIR}/packaging/debian/postrm;${CMAKE_CURRENT_SOURCE_DIR}/packaging/debian/conffiles"
)
include(CPack)

# Custom target: package_deb
add_custom_target(package_deb
    COMMAND ${CMAKE_CURRENT_SOURCE_DIR}/packaging/build_package.sh --build-dir ${CMAKE_CURRENT_BINARY_DIR}
    DEPENDS lddm lddm_core
    COMMENT "Building production Debian package"
)

# Tests
if(LDDM_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()


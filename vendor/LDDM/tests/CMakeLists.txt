set(UNIT_TESTS
    test_version
    test_error
    test_logging
    test_config
    test_lifecycle
    test_platform
    test_resource
    test_session
    test_session_id
    test_session_state
    test_session_paths
    test_session_environment
    test_session_lifecycle
    test_session_manager
    test_session_diagnostics
    test_process_types
    test_process_spec
    test_process_registry
    test_process_diagnostics
    test_process_events
    test_weston_types
    test_weston_config
    test_weston_spec
    test_weston_readiness
    test_weston_diagnostics
    test_ldde_types
    test_ldde_config
    test_ldde_spec
    test_ldde_readiness
    test_ldde_diagnostics
    test_recovery_types
    test_recovery_config
    test_recovery_diagnostics
    test_recovery_preconditions
    test_config_migration
    test_package_paths
)

set(INTEGRATION_TESTS
    test_lifecycle_integration
    test_session_integration
    test_process_execution
    test_process_termination
    test_process_supervisor_integration
    test_session_supervisor_integration
    test_weston_manager_lifecycle
    test_weston_failure_recovery
    test_session_weston_integration
    test_ldde_manager_lifecycle
    test_ldde_failure_recovery
    test_graphical_session_coordination
    test_graphical_session_failures
    test_recovery_ldde_component
    test_recovery_weston_component
    test_recovery_session_restart
    test_recovery_exhaustion
    test_recovery_stability
    test_package_deb
    test_package_upgrade_lifecycle
)

foreach(test_name ${UNIT_TESTS})
    add_executable(${test_name} unit/${test_name}.cpp)
    target_link_libraries(${test_name} PRIVATE lddm_core)
    target_include_directories(${test_name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(${test_name} PRIVATE ${LDDM_COMMON_WARNINGS})
    add_test(NAME ${test_name} COMMAND ${test_name})
endforeach()

foreach(test_name ${INTEGRATION_TESTS})
    add_executable(${test_name} integration/${test_name}.cpp)
    target_link_libraries(${test_name} PRIVATE lddm_core)
    target_include_directories(${test_name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(${test_name} PRIVATE ${LDDM_COMMON_WARNINGS})
    add_test(NAME ${test_name} COMMAND ${test_name})
endforeach()

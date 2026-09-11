#include "lddm/process/process_registry.hpp"
#include "lddm/core/error.hpp"

namespace lddm {

Result<void> ProcessRegistry::register_process(std::shared_ptr<Process> process) {
    if (!process) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessRegistrationFailed,
            "Cannot register null process"));
    }

    std::lock_guard lock(mutex_);
    if (by_handle_.find(process->handle()) != by_handle_.end()) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessRegistrationFailed,
            "Process with handle '" + process->handle() + "' already registered",
            "handle=" + process->handle()));
    }

    by_handle_[process->handle()] = std::move(process);
    return Result<void>::success();
}

std::shared_ptr<Process> ProcessRegistry::find_by_pid(ProcessId pid) const {
    if (pid <= 0) {
        return nullptr;
    }
    std::lock_guard lock(mutex_);
    for (const auto& [_, proc] : by_handle_) {
        if (proc && proc->pid() == pid) {
            return proc;
        }
    }
    return nullptr;
}

std::shared_ptr<Process> ProcessRegistry::find_by_handle(const std::string& handle) const {
    std::lock_guard lock(mutex_);
    auto it = by_handle_.find(handle);
    if (it != by_handle_.end()) {
        return it->second;
    }
    return nullptr;
}

std::vector<std::shared_ptr<Process>> ProcessRegistry::find_by_name(const std::string& name) const {
    std::lock_guard lock(mutex_);
    std::vector<std::shared_ptr<Process>> matches;
    for (const auto& [_, proc] : by_handle_) {
        if (proc && proc->name() == name) {
            matches.push_back(proc);
        }
    }
    return matches;
}

Result<void> ProcessRegistry::remove_process(const std::string& handle) {
    std::lock_guard lock(mutex_);
    auto it = by_handle_.find(handle);
    if (it == by_handle_.end()) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessNotFound,
            "Process not found with handle: " + handle,
            "handle=" + handle));
    }
    by_handle_.erase(it);
    return Result<void>::success();
}

Result<void> ProcessRegistry::remove_by_pid(ProcessId pid) {
    std::lock_guard lock(mutex_);
    for (auto it = by_handle_.begin(); it != by_handle_.end(); ++it) {
        if (it->second && it->second->pid() == pid) {
            by_handle_.erase(it);
            return Result<void>::success();
        }
    }
    return Result<void>::failure(Error(
        ErrorCategory::Process,
        ErrorCode::ProcessNotFound,
        "Process not found with PID: " + std::to_string(pid),
        "pid=" + std::to_string(pid)));
}

std::vector<std::shared_ptr<Process>> ProcessRegistry::all_processes() const {
    std::lock_guard lock(mutex_);
    std::vector<std::shared_ptr<Process>> result;
    result.reserve(by_handle_.size());
    for (const auto& [_, proc] : by_handle_) {
        if (proc) {
            result.push_back(proc);
        }
    }
    return result;
}

std::size_t ProcessRegistry::count() const noexcept {
    std::lock_guard lock(mutex_);
    return by_handle_.size();
}

void ProcessRegistry::clear() noexcept {
    std::lock_guard lock(mutex_);
    by_handle_.clear();
}

} // namespace lddm


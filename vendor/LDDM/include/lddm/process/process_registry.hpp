#pragma once

#include "lddm/process/process.hpp"
#include "lddm/core/result.hpp"
#include <unordered_map>
#include <vector>
#include <memory>
#include <mutex>
#include <string>

namespace lddm {

class ProcessRegistry {
public:
    ProcessRegistry() = default;
    ~ProcessRegistry() = default;

    ProcessRegistry(const ProcessRegistry&) = delete;
    ProcessRegistry& operator=(const ProcessRegistry&) = delete;
    ProcessRegistry(ProcessRegistry&&) = delete;
    ProcessRegistry& operator=(ProcessRegistry&&) = delete;

    Result<void> register_process(std::shared_ptr<Process> process);

    [[nodiscard]] std::shared_ptr<Process> find_by_pid(ProcessId pid) const;
    [[nodiscard]] std::shared_ptr<Process> find_by_handle(const std::string& handle) const;
    [[nodiscard]] std::vector<std::shared_ptr<Process>> find_by_name(const std::string& name) const;

    Result<void> remove_process(const std::string& handle);
    Result<void> remove_by_pid(ProcessId pid);

    [[nodiscard]] std::vector<std::shared_ptr<Process>> all_processes() const;
    [[nodiscard]] std::size_t count() const noexcept;
    void clear() noexcept;

private:
    mutable std::mutex mutex_;
    std::unordered_map<std::string, std::shared_ptr<Process>> by_handle_;
};

namespace process {
using lddm::ProcessRegistry;
}

} // namespace lddm


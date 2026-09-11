#include "lddm/platform/process.hpp"
#include "lddm/core/error.hpp"
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <signal.h>
#include <cstring>
#include <cerrno>

namespace lddm::platform {

Result<ProcessId> spawn_process(const SpawnOptions& options) {
    if (options.executable.empty()) {
        return Result<ProcessId>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessSpawnFailed,
            "Executable path cannot be empty"));
    }

    int error_pipe[2];
#if defined(__linux__) && defined(O_CLOEXEC)
    if (pipe2(error_pipe, O_CLOEXEC) != 0) {
        return Result<ProcessId>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "pipe2 failed: " + std::string(strerror(errno))));
    }
#else
    if (pipe(error_pipe) != 0) {
        return Result<ProcessId>::failure(Error(
            ErrorCategory::Platform,
            ErrorCode::PlatformSyscallFailed,
            "pipe failed: " + std::string(strerror(errno))));
    }
    (void)fcntl(error_pipe[0], F_SETFD, FD_CLOEXEC);
    (void)fcntl(error_pipe[1], F_SETFD, FD_CLOEXEC);
#endif

    pid_t pid = fork();
    if (pid < 0) {
        int fork_err = errno;
        close(error_pipe[0]);
        close(error_pipe[1]);
        return Result<ProcessId>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessSpawnFailed,
            "fork failed: " + std::string(strerror(fork_err))));
    }

    if (pid == 0) {
        // --- Child Process ---
        close(error_pipe[0]);

        auto write_err_and_exit = [&](int err) {
            ssize_t written = write(error_pipe[1], &err, sizeof(err));
            (void)written;
            close(error_pipe[1]);
            _exit(127);
        };

        // 1. Process group isolation
        if (options.enable_process_group) {
            setpgid(0, 0);
        }

        // 2. Reset common signals to default
        signal(SIGTERM, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGPIPE, SIG_DFL);
        signal(SIGCHLD, SIG_DFL);

        // 3. Standard stream redirection
        if (options.stdin_fd >= 0) {
            if (dup2(options.stdin_fd, STDIN_FILENO) < 0) {
                write_err_and_exit(errno);
            }
        }
        if (options.stdout_fd >= 0) {
            if (dup2(options.stdout_fd, STDOUT_FILENO) < 0) {
                write_err_and_exit(errno);
            }
        }
        if (options.stderr_fd >= 0) {
            if (dup2(options.stderr_fd, STDERR_FILENO) < 0) {
                write_err_and_exit(errno);
            }
        }

        // 4. Change working directory
        if (!options.working_directory.empty()) {
            if (chdir(options.working_directory.c_str()) != 0) {
                write_err_and_exit(errno);
            }
        }

        // 5. Construct argv
        std::vector<char*> argv;
        argv.push_back(const_cast<char*>(options.executable.c_str()));
        for (const auto& arg : options.arguments) {
            argv.push_back(const_cast<char*>(arg.c_str()));
        }
        argv.push_back(nullptr);

        // 6. Construct envp
        std::vector<char*> envp;
        for (const auto& env_str : options.environment_vector) {
            envp.push_back(const_cast<char*>(env_str.c_str()));
        }
        envp.push_back(nullptr);

        // 7. Execute binary
        execve(options.executable.c_str(), argv.data(), envp.data());

        // If execve fails, notify parent via error_pipe
        write_err_and_exit(errno);
    }

    // --- Parent Process ---
    close(error_pipe[1]);

    int child_err = 0;
    ssize_t bytes_read = read(error_pipe[0], &child_err, sizeof(child_err));
    close(error_pipe[0]);

    if (bytes_read == 0) {
        // Pipe closed by execve's O_CLOEXEC: execution succeeded!
        return Result<ProcessId>::success(static_cast<ProcessId>(pid));
    }

    // Execution failed in child: reap child immediately to prevent zombies
    int status = 0;
    (void)waitpid(pid, &status, 0);

    if (bytes_read == sizeof(child_err)) {
        return Result<ProcessId>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessExecFailed,
            "execve failed: " + std::string(strerror(child_err)),
            "executable=" + options.executable));
    }

    return Result<ProcessId>::failure(Error(
        ErrorCategory::Process,
        ErrorCode::ProcessSpawnFailed,
        "Unknown child startup error during exec",
        "executable=" + options.executable));
}

Result<ProcessExitInfo> wait_process(ProcessId pid, bool blocking) {
    if (pid <= 0) {
        return Result<ProcessExitInfo>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessNotFound,
            "Invalid PID"));
    }

    int options = blocking ? 0 : WNOHANG;
    int status = 0;
    pid_t ret = waitpid(static_cast<pid_t>(pid), &status, options);

    if (ret == 0) {
        // Non-blocking call and child hasn't changed state yet
        return Result<ProcessExitInfo>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessTimeout,
            "Process still running"));
    }

    if (ret < 0) {
        int wait_err = errno;
        if (wait_err == ECHILD) {
            return Result<ProcessExitInfo>::failure(Error(
                ErrorCategory::Process,
                ErrorCode::ProcessNotFound,
                "Process not found or already reaped"));
        }
        return Result<ProcessExitInfo>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessWaitFailed,
            "waitpid error: " + std::string(strerror(wait_err))));
    }

    ProcessExitInfo info;
    info.pid = pid;
    info.exit_time = SystemClock::now();

    if (WIFEXITED(status)) {
        info.exited_normally = true;
        info.exit_code = WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        info.signaled = true;
        info.term_signal = WTERMSIG(status);
#ifdef WCOREDUMP
        info.core_dumped = WCOREDUMP(status);
#endif
    }

    return Result<ProcessExitInfo>::success(info);
}

Result<void> send_signal(ProcessId pid, int signal, bool to_process_group) {
    if (pid <= 0) {
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessNotFound,
            "Invalid PID for signal"));
    }

    pid_t target = to_process_group ? -static_cast<pid_t>(pid) : static_cast<pid_t>(pid);
    if (kill(target, signal) != 0) {
        int err = errno;
        if (err == ESRCH) {
            return Result<void>::failure(Error(
                ErrorCategory::Process,
                ErrorCode::ProcessNotFound,
                "Process or process group not found"));
        }
        return Result<void>::failure(Error(
            ErrorCategory::Process,
            ErrorCode::ProcessSignalFailed,
            "kill failed: " + std::string(strerror(err))));
    }

    return Result<void>::success();
}

bool is_process_alive(ProcessId pid) noexcept {
    if (pid <= 0) {
        return false;
    }
    if (kill(static_cast<pid_t>(pid), 0) == 0) {
        return true;
    }
    return errno != ESRCH;
}

} // namespace lddm::platform

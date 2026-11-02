/*
 * kgv_rootd: runs shell commands as root for KGV X Intelligence.
 *
 * init creates /dev/socket/kgv_rootd (root:system 0660, sepolicy lets only
 * system_app connect), and the daemon also checks that the peer is the system
 * user. One command per connection:
 *
 *   request:  "<timeout seconds>\n<command>", then the client shuts down writing
 *   response: the command's stdout and stderr as they come, then
 *             "\n\0kgv_rootd exit <status>\n" (status -1: killed after the timeout)
 *
 * Each command runs in its own process group under /system/bin/sh -c, and is
 * logged to logcat (tag kgv_rootd).
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <string>

#include <cutils/sockets.h>
#include <log/log.h>

#undef LOG_TAG
#define LOG_TAG "kgv_rootd"

static const uid_t SYSTEM_UID = 1000;
static const size_t MAX_REQUEST = 64 * 1024;
static const int MAX_TIMEOUT = 600;

static bool writeAll(int fd, const char* p, size_t n) {
    while (n > 0) {
        ssize_t w = TEMP_FAILURE_RETRY(write(fd, p, n));
        if (w <= 0) return false;
        p += w;
        n -= w;
    }
    return true;
}

static long nowMs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000;
}

static void serve(int client) {
    ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(client, SOL_SOCKET, SO_PEERCRED, &cred, &len) != 0 || cred.uid != SYSTEM_UID) {
        ALOGW("refused uid %d", len == sizeof(cred) ? (int)cred.uid : -1);
        return;
    }

    std::string req;
    char buf[4096];
    for (;;) {
        ssize_t r = TEMP_FAILURE_RETRY(read(client, buf, sizeof(buf)));
        if (r < 0) return;
        if (r == 0) break;
        req.append(buf, r);
        if (req.size() > MAX_REQUEST) return;
    }
    size_t nl = req.find('\n');
    if (nl == std::string::npos) return;
    int timeout = atoi(req.substr(0, nl).c_str());
    if (timeout <= 0 || timeout > MAX_TIMEOUT) timeout = 20;
    std::string command = req.substr(nl + 1);
    ALOGI("pid %d: %s", cred.pid, command.c_str());

    int out[2];
    if (pipe2(out, O_CLOEXEC) != 0) return;
    pid_t child = fork();
    if (child < 0) {
        close(out[0]);
        close(out[1]);
        return;
    }
    if (child == 0) {
        setpgid(0, 0);
        signal(SIGPIPE, SIG_DFL);
        dup2(out[1], STDOUT_FILENO);
        dup2(out[1], STDERR_FILENO);
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) dup2(devnull, STDIN_FILENO);
        execl("/system/bin/sh", "sh", "-c", command.c_str(), (char*)nullptr);
        _exit(127);
    }
    close(out[1]);

    long deadline = nowMs() + timeout * 1000L;
    bool timedOut = false;
    for (;;) {
        long left = deadline - nowMs();
        if (left <= 0) {
            timedOut = true;
            break;
        }
        pollfd pfd = {out[0], POLLIN, 0};
        int p = TEMP_FAILURE_RETRY(poll(&pfd, 1, (int)left));
        if (p == 0) continue;
        if (p < 0) break;
        ssize_t r = TEMP_FAILURE_RETRY(read(out[0], buf, sizeof(buf)));
        if (r <= 0) break;  // the command (and anything it left running) closed its output
        if (!writeAll(client, buf, r)) {
            timedOut = true;  // the client went away: stop the command
            break;
        }
    }
    close(out[0]);
    if (timedOut) kill(-child, SIGKILL);

    int status = 0;
    // The command may have exited while children it started still hold the pipe
    // open; once the pipe closes, don't wait on it longer than the deadline.
    while (TEMP_FAILURE_RETRY(waitpid(child, &status, WNOHANG)) == 0) {
        if (nowMs() > deadline) {
            kill(-child, SIGKILL);
            timedOut = true;
        }
        usleep(20 * 1000);
    }
    int code = timedOut ? -1 : WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    char trailer[64];
    int n = snprintf(trailer, sizeof(trailer), "\n%ckgv_rootd exit %d\n", '\0', code);
    writeAll(client, trailer, n);
}

int main() {
    signal(SIGPIPE, SIG_IGN);
    int listener = android_get_control_socket("kgv_rootd");
    if (listener < 0 || listen(listener, 8) != 0) {
        ALOGE("no control socket: %s", strerror(errno));
        return 1;
    }
    for (;;) {
        int client = TEMP_FAILURE_RETRY(accept4(listener, nullptr, nullptr, SOCK_CLOEXEC));
        if (client < 0) continue;
        // One child per connection, so a long command doesn't block the next one.
        pid_t worker = fork();
        if (worker == 0) {
            close(listener);
            serve(client);
            _exit(0);
        }
        close(client);
        while (waitpid(-1, nullptr, WNOHANG) > 0) {
        }
    }
}

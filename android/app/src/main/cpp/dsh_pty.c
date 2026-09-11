/*
 * dsh-handheld: pty allocation and child-process launch.
 *
 * This file is deliberately JNI-free. Everything here is plain POSIX, which buys
 * two things:
 *
 *   1. It can be compiled and *run* on a Linux host by the test in
 *      dsh_pty_test.c, so fd leaks, window-size propagation and exec failures are
 *      caught in seconds instead of through a CI round trip and a phone.
 *   2. It keeps the untestable part (marshalling Java objects) in one small file.
 *
 * The exported JNI names live in dsh_pty_jni.c and match the reference emulator's
 * interface exactly, so this library is a drop-in replacement while the in-house
 * emulator is being built (see docs/terminal-rewrite-plan.md).
 */

#define _GNU_SOURCE

#include "dsh_pty.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

/*
 * A stable, greppable marker. Release verification greps the APK's libtermux.so
 * for it to prove the packaged library is this build and not the upstream one
 * that also ships inside the terminal-view artifact.
 */
const char* dsh_pty_build_marker(void) {
    return "dsh-handheld-pty/1";
}

/* Write a message into the caller's buffer, always NUL-terminated. */
static void set_error(char* buffer, size_t length, const char* format, ...) {
    if (buffer == NULL || length == 0) {
        return;
    }
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, length, format, args);
    va_end(args);
}

/*
 * Put the pty into the state a terminal program expects.
 *
 * IUTF8 makes the line discipline treat multi-byte input as characters (without it
 * backspace can delete half a UTF-8 sequence). IXON/IXOFF are cleared because with
 * software flow control on, a Ctrl+S typed in a shell freezes output until Ctrl+Q —
 * a confusing failure that looks like a hang.
 */
static int configure_pty(int fd, int rows, int columns, char* error, size_t error_length) {
    struct termios attributes;
    if (tcgetattr(fd, &attributes) == 0) {
        attributes.c_iflag |= IUTF8;
        attributes.c_iflag &= ~(IXON | IXOFF);
        if (tcsetattr(fd, TCSANOW, &attributes) != 0) {
            set_error(error, error_length, "tcsetattr failed: %s", strerror(errno));
            return -1;
        }
    }
    if (dsh_pty_set_window_size(fd, rows, columns) != 0) {
        set_error(error, error_length, "TIOCSWINSZ failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

int dsh_pty_open(int rows, int columns, char* device, size_t device_length,
                 char* error, size_t error_length) {
    /* O_NOCTTY on the master: the parent must not acquire a controlling terminal. */
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        set_error(error, error_length, "posix_openpt failed: %s", strerror(errno));
        return -1;
    }
    /* grantpt/unlockpt before ptsname: the slave name is only usable afterwards. */
    if (grantpt(master) != 0) {
        set_error(error, error_length, "grantpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }
    if (unlockpt(master) != 0) {
        set_error(error, error_length, "unlockpt failed: %s", strerror(errno));
        close(master);
        return -1;
    }
#if defined(__ANDROID__) || defined(__GLIBC__)
    if (ptsname_r(master, device, device_length) != 0) {
        set_error(error, error_length, "ptsname_r failed: %s", strerror(errno));
        close(master);
        return -1;
    }
#else
    const char* name = ptsname(master);
    if (name == NULL || strlen(name) >= device_length) {
        set_error(error, error_length, "ptsname failed");
        close(master);
        return -1;
    }
    strcpy(device, name);
#endif
    if (configure_pty(master, rows, columns, error, error_length) != 0) {
        close(master);
        return -1;
    }
    return master;
}

int dsh_pty_set_window_size(int fd, int rows, int columns) {
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = (unsigned short) rows;
    size.ws_col = (unsigned short) columns;
    /*
     * TIOCSWINSZ also delivers SIGWINCH to the foreground process group, which is
     * how a full-screen program (top, vim, dsh-tui) learns to re-layout. Setting the
     * size without the signal would leave the program drawing for the old geometry.
     */
    return ioctl(fd, TIOCSWINSZ, &size);
}

int dsh_pty_set_utf8_mode(int fd) {
    struct termios attributes;
    if (tcgetattr(fd, &attributes) != 0) {
        return -1;
    }
    if ((attributes.c_iflag & IUTF8) == 0) {
        attributes.c_iflag |= IUTF8;
        return tcsetattr(fd, TCSANOW, &attributes);
    }
    return 0;
}

int dsh_pty_wait(int pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) {
            return -1;
        }
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        /*
         * Negative signal number, not 128+signal. The Java side maps this straight
         * onto its exit-status display, so a sign flip would misreport every crashed
         * or killed child as a successful one (or vice versa).
         */
        return -WTERMSIG(status);
    }
    return 0;
}

void dsh_pty_close(int fd) {
    if (fd >= 0) {
        close(fd);
    }
}

/*
 * Resolve an executable name to an absolute path *before* forking.
 *
 * Doing the PATH search in the parent means the child needs nothing but execve(),
 * so the post-fork window contains no allocation — a real concern because the JVM
 * is multi-threaded and a malloc taken after fork can deadlock on a lock another
 * thread held. It also lets a genuinely missing program be reported clearly.
 *
 * On failure the original name is returned unchanged, so the child's execve fails
 * the same way the reference implementation's would and the error still reaches the
 * terminal (the app relies on that, rather than on an exception, to report a missing
 * binary).
 */
static void resolve_executable(const char* command, const char* path_env,
                               char* output, size_t output_length) {
    if (command == NULL) {
        output[0] = '\0';
        return;
    }
    if (strchr(command, '/') != NULL) {
        snprintf(output, output_length, "%s", command);
        return;
    }
    const char* path = path_env != NULL ? path_env : "/system/bin:/system/xbin:/usr/bin:/bin";
    const char* cursor = path;
    while (*cursor != '\0') {
        const char* colon = strchr(cursor, ':');
        size_t segment = colon == NULL ? strlen(cursor) : (size_t) (colon - cursor);
        if (segment > 0) {
            char candidate[4096];
            snprintf(candidate, sizeof(candidate), "%.*s/%s", (int) segment, cursor, command);
            if (access(candidate, X_OK) == 0) {
                snprintf(output, output_length, "%s", candidate);
                return;
            }
        }
        if (colon == NULL) {
            break;
        }
        cursor = colon + 1;
    }
    snprintf(output, output_length, "%s", command);
}

/* Look up a variable in a NULL-terminated "K=V" array. */
static const char* env_value(char* const envp[], const char* name) {
    if (envp == NULL) {
        return NULL;
    }
    size_t length = strlen(name);
    for (size_t index = 0; envp[index] != NULL; index++) {
        if (strncmp(envp[index], name, length) == 0 && envp[index][length] == '=') {
            return envp[index] + length + 1;
        }
    }
    return NULL;
}

int dsh_pty_spawn(const char* command, const char* cwd, char* const argv[], char* const envp[],
                  int rows, int columns, int* out_pid, int* out_fd,
                  char* device, size_t device_length, char* error, size_t error_length) {
    if (out_pid == NULL || out_fd == NULL) {
        set_error(error, error_length, "dsh_pty_spawn: null output parameter");
        return -1;
    }
    *out_pid = 0;
    *out_fd = -1;

    int master = dsh_pty_open(rows, columns, device, device_length, error, error_length);
    if (master < 0) {
        return -1;
    }

    char resolved[4096];
    resolve_executable(command, env_value(envp, "PATH"), resolved, sizeof(resolved));

    pid_t pid = fork();
    if (pid < 0) {
        set_error(error, error_length, "fork failed: %s", strerror(errno));
        close(master);
        return -1;
    }
    if (pid > 0) {
        *out_pid = (int) pid;
        *out_fd = master;
        return 0;
    }

    /*
     * ── Child ────────────────────────────────────────────────────────────────
     * Only async-signal-safe calls from here on: no malloc, no stdio buffering that
     * could allocate, no JNI.
     */

    /* The parent may have blocked signals it uses; the child must start clean. */
    sigset_t unblock;
    sigfillset(&unblock);
    sigprocmask(SIG_UNBLOCK, &unblock, NULL);

    close(master);

    /*
     * setsid() first, then open the slave *without* O_NOCTTY: on Linux the first
     * terminal a new session leader opens becomes its controlling terminal. This is
     * what lets the slave deliver SIGINT/SIGWINCH to the child's process group, and
     * it is why no explicit TIOCSCTTY is needed.
     */
    if (setsid() < 0) {
        _exit(126);
    }
    int slave = open(device, O_RDWR);
    if (slave < 0) {
        _exit(126);
    }
    if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0
            || dup2(slave, STDERR_FILENO) < 0) {
        _exit(126);
    }
    if (slave > STDERR_FILENO) {
        close(slave);
    }

    /*
     * Close everything else the JVM leaked into this fork. Without this the child
     * inherits sockets and pipes that keep peers alive after the terminal exits.
     */
    DIR* self = opendir("/proc/self/fd");
    if (self != NULL) {
        int self_fd = dirfd(self);
        struct dirent* entry;
        while ((entry = readdir(self)) != NULL) {
            int fd = atoi(entry->d_name);
            if (fd > STDERR_FILENO && fd != self_fd) {
                close(fd);
            }
        }
        closedir(self);
    }

    /* TERM and HOME arrive through envp; a missing PATH would break child helpers. */
    if (cwd != NULL && chdir(cwd) != 0) {
        /* Not fatal: the reference implementation also continues on a bad cwd. */
        const char* message = "dsh-pty: chdir failed\n";
        ssize_t ignored = write(STDERR_FILENO, message, strlen(message));
        (void) ignored;
    }

    /*
     * execve, not execvp: the path was resolved and the environment assembled before
     * the fork, so nothing here can allocate or consult a lock.
     */
    execve(resolved, argv, envp);

    /* Reached only on failure; report it on the terminal, then exit like the parent expects. */
    const char* prefix = "dsh-pty: exec failed: ";
    ssize_t ignored = write(STDERR_FILENO, prefix, strlen(prefix));
    (void) ignored;
    const char* reason = strerror(errno);
    ignored = write(STDERR_FILENO, reason, strlen(reason));
    (void) ignored;
    ignored = write(STDERR_FILENO, "\r\n", 2);
    (void) ignored;
    _exit(127);
}

/* Count the descriptors this process holds, so a leak test has something to compare. */
int dsh_pty_open_descriptor_count(void) {
    DIR* self = opendir("/proc/self/fd");
    if (self == NULL) {
        return -1;
    }
    int count = 0;
    struct dirent* entry;
    while ((entry = readdir(self)) != NULL) {
        if (entry->d_name[0] != '.') {
            count++;
        }
    }
    closedir(self);
    /* The directory handle itself is counted; exclude it for a stable number. */
    return count - 1;
}

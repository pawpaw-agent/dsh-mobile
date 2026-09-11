/*
 * Host test for the pty layer.
 *
 * Runs the real dsh_pty.c on Linux, which is where the interesting failures live:
 * a leaked descriptor, a window size that never reaches the child, an exit status
 * with the wrong sign. These are exactly the things a CI build cannot tell you and
 * a phone tells you slowly, so they are asserted here first.
 *
 * The JNI layer is not exercised (it needs a JVM); its only job is marshalling, and
 * the signatures are checked by compilation against jni.h.
 *
 * Build and run: scripts/test-pty.sh
 */

#define _GNU_SOURCE

#include "dsh_pty.h"

#include <errno.h>
#include <poll.h>
#include <time.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static int failures = 0;
static int checks = 0;

static void check(int condition, const char* what) {
    checks++;
    if (condition) {
        printf("    ok   %s\n", what);
    } else {
        printf("    FAIL %s\n", what);
        failures++;
    }
}

static void check_int(int actual, int expected, const char* what) {
    checks++;
    if (actual == expected) {
        printf("    ok   %s (= %d)\n", what, actual);
    } else {
        printf("    FAIL %s: expected %d, got %d\n", what, expected, actual);
        failures++;
    }
}

/*
 * Send a newline to wake a blocked `read` in the child.
 *
 * The return value is checked rather than discarded: on a distribution that builds
 * with _FORTIFY_SOURCE, write() carries warn_unused_result and ignoring it is a
 * compile error under -Werror. Checking it is also simply correct — if the write
 * fails the test would otherwise fail later for a misleading reason.
 */
static void send_line(int fd) {
    ssize_t written = write(fd, "\n", 1);
    if (written != 1) {
        checks++;
        printf("    FAIL could not write to the pty: %s\n", strerror(errno));
        failures++;
    }
}

/* Read whatever the child has produced, up to a deadline. */
static int drain(int fd, char* out, size_t capacity, int timeout_ms) {
    size_t used = 0;
    struct pollfd pfd = { .fd = fd, .events = POLLIN };
    while (used + 1 < capacity) {
        int ready = poll(&pfd, 1, timeout_ms);
        if (ready <= 0) {
            break;
        }
        ssize_t got = read(fd, out + used, capacity - used - 1);
        if (got <= 0) {
            break;
        }
        used += (size_t) got;
        if (strstr(out, "\n") != NULL) {
            break;
        }
    }
    out[used] = '\0';
    return (int) used;
}

/*
 * Accumulate output until `needle` appears or the deadline passes.
 *
 * Needed because the pty echoes what we write: a bare newline sent to wake a blocked
 * `read` comes back as CRLF, so a helper that stops at the first newline would inspect
 * the echo instead of the program's answer.
 *
 * @return 1 when the needle was seen, 0 on timeout.
 */
static int wait_for(int fd, const char* needle, char* out, size_t capacity, int timeout_ms) {
    size_t used = 0;
    out[0] = '\0';
    struct pollfd pfd = { .fd = fd, .events = POLLIN };
    for (int elapsed = 0; elapsed < timeout_ms; elapsed += 50) {
        if (strstr(out, needle) != NULL) {
            return 1;
        }
        if (poll(&pfd, 1, 50) > 0 && used + 1 < capacity) {
            ssize_t got = read(fd, out + used, capacity - used - 1);
            if (got > 0) {
                used += (size_t) got;
                out[used] = '\0';
            }
        }
    }
    return strstr(out, needle) != NULL;
}

/* Wait for a child to exit, tolerating a descriptor that keeps producing output. */
static int reap(int fd, int pid) {
    char scratch[4096];
    struct pollfd pfd = { .fd = fd, .events = POLLIN };
    for (int spins = 0; spins < 200; spins++) {
        while (poll(&pfd, 1, 20) > 0) {
            if (read(fd, scratch, sizeof(scratch)) <= 0) {
                break;
            }
        }
        int status = dsh_pty_wait(pid);
        if (status != -1) {
            return status;
        }
        /* dsh_pty_wait blocks, so reaching here means it returned; nothing to do. */
        return status;
    }
    return -1;
}

/* ── Test 1: a child runs, writes to the pty, and its output is readable ────── */
static void test_echo(void) {
    printf("  [1] child runs on the pty and its output is readable\n");
    int pid = 0, fd = -1;
    char device[DSH_PTY_DEVICE_MAX], error[256];
    char* argv[] = { (char*) "/bin/sh", (char*) "-c", (char*) "echo hello-from-pty", NULL };
    char* envp[] = { (char*) "PATH=/usr/bin:/bin", NULL };

    int rc = dsh_pty_spawn("/bin/sh", "/", argv, envp, 24, 80, &pid, &fd, device,
                           sizeof(device), error, sizeof(error));
    check_int(rc, 0, "spawn succeeded");
    if (rc != 0) {
        printf("         error: %s\n", error);
        return;
    }
    check(fd >= 0, "master fd is valid");
    check(pid > 0, "child pid reported");
    check(strstr(device, "/dev/pts/") == device, "slave device path looks like a pty");

    char output[4096];
    drain(fd, output, sizeof(output), 2000);
    check(strstr(output, "hello-from-pty") != NULL, "pty carried the child's output");

    int status = reap(fd, pid);
    check_int(status, 0, "exit status");

    dsh_pty_close(fd);
}

/* ── Test 2: window size reaches the child, including later changes ─────────── */
static void test_window_size(void) {
    printf("  [2] window size is visible to the child and updates\n");
    int pid = 0, fd = -1;
    char device[DSH_PTY_DEVICE_MAX], error[256];
    /* The child prints its own winsize, so the assertion is about what the kernel
     * told the child rather than about what we think we set. */
    char* argv[] = { (char*) "/bin/sh", (char*) "-c", (char*) "stty size", NULL };
    char* envp[] = { (char*) "PATH=/usr/bin:/bin", NULL };

    if (dsh_pty_spawn("/bin/sh", "/", argv, envp, 24, 80, &pid, &fd, device,
                      sizeof(device), error, sizeof(error)) != 0) {
        check(0, "spawn succeeded");
        printf("         error: %s\n", error);
        return;
    }
    char output[4096];
    drain(fd, output, sizeof(output), 3000);
    check(strstr(output, "24 80") != NULL, "child sees the size given at spawn");
    reap(fd, pid);
    dsh_pty_close(fd);

    /* A long-lived child lets us change the size and observe the new value. */
    char* loop_argv[] = { (char*) "/bin/sh", (char*) "-c",
                          (char*) "read line; stty size; read line", NULL };
    if (dsh_pty_spawn("/bin/sh", "/", loop_argv, envp, 24, 80, &pid, &fd, device,
                      sizeof(device), error, sizeof(error)) != 0) {
        check(0, "second spawn succeeded");
        return;
    }
    usleep(150 * 1000);
    check_int(dsh_pty_set_window_size(fd, 40, 120), 0, "set_window_size returns success");
    send_line(fd);
    check(wait_for(fd, "40 120", output, sizeof(output), 3000),
          "child sees the resized geometry");
    send_line(fd);
    reap(fd, pid);
    dsh_pty_close(fd);
}

/* ── Test 3: exit status encoding, including a signalled child ─────────────── */
static void test_exit_status(void) {
    printf("  [3] exit status and signal encoding\n");
    int pid = 0, fd = -1;
    char device[DSH_PTY_DEVICE_MAX], error[256];
    char* envp[] = { (char*) "PATH=/usr/bin:/bin", NULL };

    char* argv[] = { (char*) "/bin/sh", (char*) "-c", (char*) "exit 42", NULL };
    if (dsh_pty_spawn("/bin/sh", "/", argv, envp, 24, 80, &pid, &fd, device,
                      sizeof(device), error, sizeof(error)) == 0) {
        check_int(reap(fd, pid), 42, "normal exit reports its code");
        dsh_pty_close(fd);
    } else {
        check(0, "spawn for exit-code test");
    }

    /* A child that kills itself must be reported as the negated signal, not 128+n. */
    char* sig_argv[] = { (char*) "/bin/sh", (char*) "-c", (char*) "kill -TERM $$", NULL };
    if (dsh_pty_spawn("/bin/sh", "/", sig_argv, envp, 24, 80, &pid, &fd, device,
                      sizeof(device), error, sizeof(error)) == 0) {
        check_int(reap(fd, pid), -15, "signalled child reports -SIGTERM");
        dsh_pty_close(fd);
    } else {
        check(0, "spawn for signal test");
    }
}

/* ── Test 4: no descriptor leaks across many sessions ─────────────────────── */
static void test_no_descriptor_leak(void) {
    printf("  [4] repeated sessions do not leak descriptors\n");
    const int rounds = 200;
    char* envp[] = { (char*) "PATH=/usr/bin:/bin", NULL };
    char* argv[] = { (char*) "/bin/sh", (char*) "-c", (char*) "true", NULL };
    char device[DSH_PTY_DEVICE_MAX], error[256];

    /* Warm up first: the first spawn may allocate something that is then reused. */
    for (int warm = 0; warm < 3; warm++) {
        int pid = 0, fd = -1;
        if (dsh_pty_spawn("/bin/sh", "/", argv, envp, 24, 80, &pid, &fd, device,
                          sizeof(device), error, sizeof(error)) == 0) {
            reap(fd, pid);
            dsh_pty_close(fd);
        }
    }
    int before = dsh_pty_open_descriptor_count();
    struct timespec started, finished;
    clock_gettime(CLOCK_MONOTONIC, &started);
    for (int round = 0; round < rounds; round++) {
        int pid = 0, fd = -1;
        if (dsh_pty_spawn("/bin/sh", "/", argv, envp, 24, 80, &pid, &fd, device,
                          sizeof(device), error, sizeof(error)) != 0) {
            printf("    FAIL spawn failed at round %d: %s\n", round, error);
            failures++;
            checks++;
            return;
        }
        reap(fd, pid);
        dsh_pty_close(fd);
    }
    clock_gettime(CLOCK_MONOTONIC, &finished);
    int after = dsh_pty_open_descriptor_count();
    check_int(after, before, "descriptor count is unchanged after 200 sessions");

    /*
     * Report the cost, because "this layer is not a performance factor" should be a
     * measurement rather than an opinion. It is called a handful of times per session
     * and never per byte, so a microsecond-scale number here is the expected result.
     */
    double elapsed_ms = (double) (finished.tv_sec - started.tv_sec) * 1000.0
                        + (double) (finished.tv_nsec - started.tv_nsec) / 1e6;
    printf("         %d sessions in %.1f ms = %.2f ms each (spawn + reap + close)\n",
           rounds, elapsed_ms, elapsed_ms / rounds);
}

/* ── Test 7: the master must not survive execve into unrelated processes ───── */
static void test_cloexec(void) {
    printf("  [7] the master fd does not leak into other processes\n");
    int fd = -1;
    char device[DSH_PTY_DEVICE_MAX], error[256];

    fd = dsh_pty_open(24, 80, device, sizeof(device), error, sizeof(error));
    if (fd < 0) {
        check(0, "pty opened for the cloexec test");
        printf("         error: %s\n", error);
        return;
    }

    /*
     * Two assertions, because they fail for different reasons. The flag check names
     * the cause; the exec check proves the consequence, and would still catch a
     * regression if the flag were replaced by some other mechanism.
     */
    int flags = fcntl(fd, F_GETFD);
    check((flags & FD_CLOEXEC) != 0, "FD_CLOEXEC is set on the master");

    /*
     * This is the failure that matters in this app: the JVM starts helper processes
     * (dropbearkey, the token fetch) while a session is live. If the master is
     * inherited, those processes hold the pty open and the slave never sees hangup.
     */
    char probe[256];
    snprintf(probe, sizeof(probe),
             "[ -e /proc/self/fd/%d ] && exit 1 || exit 0", fd);
    int rc = system(probe);
    int survived = (rc != -1) && WIFEXITED(rc) && WEXITSTATUS(rc) == 1;
    check(!survived, "the fd is absent in a child after exec");

    dsh_pty_close(fd);
}

/* ── Test 5: a missing program fails visibly rather than silently ─────────── */
static void test_missing_program(void) {
    printf("  [5] a missing program reports an error on the pty\n");
    int pid = 0, fd = -1;
    char device[DSH_PTY_DEVICE_MAX], error[256];
    char* argv[] = { (char*) "definitely-not-a-real-program-xyz", NULL };
    char* envp[] = { (char*) "PATH=/usr/bin:/bin", NULL };

    if (dsh_pty_spawn("definitely-not-a-real-program-xyz", "/", argv, envp, 24, 80,
                      &pid, &fd, device, sizeof(device), error, sizeof(error)) != 0) {
        check(0, "spawn still allocates a pty for a missing program");
        return;
    }
    char output[4096];
    drain(fd, output, sizeof(output), 2000);
    check(strstr(output, "exec failed") != NULL, "failure text reached the terminal");
    int status = reap(fd, pid);
    check_int(status, 127, "missing program exits 127");
    dsh_pty_close(fd);
}

/* ── Test 6: the child gets a controlling terminal ────────────────────────── */
static void test_controlling_terminal(void) {
    printf("  [6] the child owns a controlling terminal\n");
    int pid = 0, fd = -1;
    char device[DSH_PTY_DEVICE_MAX], error[256];
    /* A session leader with a controlling terminal reports its own tty via `tty`. */
    char* argv[] = { (char*) "/bin/sh", (char*) "-c", (char*) "tty; ps -o sid= -p $$", NULL };
    char* envp[] = { (char*) "PATH=/usr/bin:/bin", NULL };

    if (dsh_pty_spawn("/bin/sh", "/", argv, envp, 24, 80, &pid, &fd, device,
                      sizeof(device), error, sizeof(error)) != 0) {
        check(0, "spawn succeeded");
        printf("         error: %s\n", error);
        return;
    }
    char output[4096];
    drain(fd, output, sizeof(output), 3000);
    check(strstr(output, "/dev/pts/") != NULL, "child reports a pty as its tty");
    reap(fd, pid);
    dsh_pty_close(fd);
}

int main(void) {
    printf("dsh-pty host test (%s)\n\n", dsh_pty_build_marker());
    test_echo();
    test_window_size();
    test_exit_status();
    test_no_descriptor_leak();
    test_missing_program();
    test_controlling_terminal();
    test_cloexec();
    printf("\n%d checks, %d failure(s)%s\n", checks, failures, failures == 0 ? "  ok" : "");
    return failures == 0 ? 0 : 1;
}

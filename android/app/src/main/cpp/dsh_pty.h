/*
 * dsh-handheld: pty allocation for the SSH terminal mode.
 *
 * Plain POSIX on purpose — see dsh_pty.c. Nothing here includes JNI, so the whole
 * file compiles and runs on a Linux host under dsh_pty_test.c.
 */

#ifndef DSH_PTY_H
#define DSH_PTY_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/** How many bytes a slave device path needs; Linux uses /dev/pts/NNN. */
#define DSH_PTY_DEVICE_MAX 64

/**
 * Identify this build.
 *
 * Release verification greps the packaged library for the returned string to prove
 * the shipped .so is this implementation rather than the upstream one that also
 * arrives inside the terminal-view artifact.
 */
const char* dsh_pty_build_marker(void);

/**
 * Allocate a pty master and its slave device path.
 *
 * @param rows           initial terminal height in character cells.
 * @param columns        initial terminal width in character cells.
 * @param device         receives the NUL-terminated slave path.
 * @param device_length  capacity of {@code device}.
 * @param error          receives a message on failure.
 * @param error_length   capacity of {@code error}.
 * @return the master fd, or -1 with {@code error} set.
 */
int dsh_pty_open(int rows, int columns, char* device, size_t device_length,
                 char* error, size_t error_length);

/**
 * Apply a window size to a pty.
 *
 * Also raises SIGWINCH, which is how a full-screen program learns to re-layout.
 *
 * @return 0 on success, -1 on failure.
 */
int dsh_pty_set_window_size(int fd, int rows, int columns);

/**
 * Mark a pty as UTF-8 so the line discipline edits whole characters.
 *
 * @return 0 on success, -1 on failure.
 */
int dsh_pty_set_utf8_mode(int fd);

/**
 * Block until a child exits and report its status.
 *
 * @return the exit code for a normal exit, the negated signal number for a signalled
 *         child, or -1 if the wait itself failed.
 */
int dsh_pty_wait(int pid);

/** Close a pty fd, tolerating -1. */
void dsh_pty_close(int fd);

/**
 * Allocate a pty and run a child on its slave.
 *
 * @param command        program to run; a bare name is searched on {@code PATH}.
 * @param cwd            working directory for the child, or NULL.
 * @param argv           NULL-terminated argument vector; {@code argv[0]} is the program name.
 * @param envp           NULL-terminated "K=V" environment; the child inherits nothing else.
 * @param rows           initial terminal height.
 * @param columns        initial terminal width.
 * @param out_pid        receives the child pid.
 * @param out_fd         receives the master fd.
 * @param device         receives the slave path.
 * @param device_length  capacity of {@code device}.
 * @param error          receives a message on failure.
 * @param error_length   capacity of {@code error}.
 * @return 0 on success, -1 with {@code error} set.
 */
int dsh_pty_spawn(const char* command, const char* cwd, char* const argv[], char* const envp[],
                  int rows, int columns, int* out_pid, int* out_fd,
                  char* device, size_t device_length, char* error, size_t error_length);

/**
 * Count this process's open descriptors.
 *
 * Exists so the leak test can assert that opening and closing ptys in a loop is
 * flat, instead of relying on inspection.
 *
 * @return the descriptor count excluding the directory handle, or -1 if unavailable.
 */
int dsh_pty_open_descriptor_count(void);

#ifdef __cplusplus
}
#endif

#endif /* DSH_PTY_H */

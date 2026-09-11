/*
 * dsh-handheld: JNI surface for the pty layer.
 *
 * The exported names intentionally match the reference emulator's (`com.termux.terminal.JNI`)
 * so this library is a drop-in replacement while the in-house emulator is being
 * built — that is what makes phase 1 testable against the shipping emulator on a
 * real device instead of against nothing. Phase 5 renames both the library and the
 * class together; see docs/terminal-rewrite-plan.md.
 *
 * Signatures were taken from the *artifact* (terminal-emulator 0.118.1), not from
 * upstream's current source, because the two have diverged: upstream's
 * setPtyWindowSize now takes cell pixel sizes and createSubprocess takes two extra
 * arguments, neither of which this version's Java declares.
 */

#include "dsh_pty.h"

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Throw a RuntimeException so a failure surfaces as a Java exception, not a silent -1. */
static void throw_runtime_exception(JNIEnv* env, const char* message) {
    jclass type = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
    }
}

/*
 * Copy a Java String[] into a NULL-terminated char* array.
 *
 * Returns NULL on allocation failure; the caller releases it with free_string_array.
 */
static char** to_string_array(JNIEnv* env, jobjectArray source) {
    if (source == NULL) {
        return NULL;
    }
    jsize count = (*env)->GetArrayLength(env, source);
    char** out = (char**) calloc((size_t) count + 1, sizeof(char*));
    if (out == NULL) {
        throw_runtime_exception(env, "out of memory building argument vector");
        return NULL;
    }
    for (jsize index = 0; index < count; index++) {
        jstring element = (jstring) (*env)->GetObjectArrayElement(env, source, index);
        if (element == NULL) {
            continue;
        }
        const char* utf = (*env)->GetStringUTFChars(env, element, NULL);
        if (utf == NULL) {
            /* An exception is already pending (OutOfMemoryError); unwind quietly. */
            free(out);
            return NULL;
        }
        out[index] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, element, utf);
        (*env)->DeleteLocalRef(env, element);
        if (out[index] == NULL) {
            throw_runtime_exception(env, "out of memory copying argument");
            for (jsize done = 0; done < index; done++) {
                free(out[done]);
            }
            free(out);
            return NULL;
        }
    }
    return out;
}

static void free_string_array(char** array) {
    if (array == NULL) {
        return;
    }
    for (size_t index = 0; array[index] != NULL; index++) {
        free(array[index]);
    }
    free(array);
}

/*
 * Allocate a pty and start the child on it.
 *
 * Contract (from the reference emulator, which this must satisfy exactly):
 * returns the master fd, and writes the child pid into processIdArray[0]. The
 * emulator reads the fd, wraps it, and reads/writes it for the session's lifetime.
 */
JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env, jclass clazz,
        jstring cmd, jstring cwd, jobjectArray args, jobjectArray envVars,
        jintArray processIdArray, jint rows, jint columns) {
    (void) clazz;

    const char* command = cmd == NULL ? NULL : (*env)->GetStringUTFChars(env, cmd, NULL);
    const char* working = NULL;
    if (cwd != NULL) {
        working = (*env)->GetStringUTFChars(env, cwd, NULL);
    }
    char** argv = to_string_array(env, args);
    char** envp = to_string_array(env, envVars);

    int pid = 0;
    int master = -1;
    char device[DSH_PTY_DEVICE_MAX];
    char error[256];
    error[0] = '\0';
    device[0] = '\0';

    if (argv != NULL || args == NULL) {
        if (dsh_pty_spawn(command, working, argv, envp, (int) rows, (int) columns,
                          &pid, &master, device, sizeof(device), error, sizeof(error)) != 0) {
            /*
             * The marker is prefixed here on purpose: it keeps the identifying string
             * reachable from a code path that always exists, so release verification
             * can grep the packaged library for it and know which build shipped.
             */
            char message[320];
            snprintf(message, sizeof(message), "%s: %s", dsh_pty_build_marker(),
                     error[0] != '\0' ? error : "spawn failed");
            throw_runtime_exception(env, message);
        }
    }

    if (master >= 0 && processIdArray != NULL) {
        jint value = (jint) pid;
        (*env)->SetIntArrayRegion(env, processIdArray, 0, 1, &value);
    }

    free_string_array(argv);
    free_string_array(envp);
    if (command != NULL) {
        (*env)->ReleaseStringUTFChars(env, cmd, command);
    }
    if (working != NULL) {
        (*env)->ReleaseStringUTFChars(env, cwd, working);
    }
    return master;
}

/*
 * Apply a new window size.
 *
 * Beyond storing the size this raises SIGWINCH, which is the only notification a
 * full-screen program gets that it must re-layout.
 */
JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(
        JNIEnv* env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env;
    (void) clazz;
    dsh_pty_set_window_size((int) fd, (int) rows, (int) cols);
}

/*
 * Mark the pty as UTF-8.
 *
 * The 0.118.1 library exports this and the Java class in that version does not
 * declare it; it is kept because the emulator may call it through the older
 * three-argument contract and because a future emulator will.
 */
JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(
        JNIEnv* env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    dsh_pty_set_utf8_mode((int) fd);
}

/*
 * Wait for the child and report its status.
 *
 * The encoding matters: a normal exit yields the exit code, a signalled child yields
 * the negative signal number. The emulator stores this value directly and shows it
 * to the user, so returning the conventional 128+signal here would misreport every
 * killed process.
 */
JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(
        JNIEnv* env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    return (jint) dsh_pty_wait((int) pid);
}

/** Close the master fd. The emulator calls this when a session is torn down. */
JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(
        JNIEnv* env, jclass clazz, jint fileDescriptor) {
    (void) env;
    (void) clazz;
    dsh_pty_close((int) fileDescriptor);
}

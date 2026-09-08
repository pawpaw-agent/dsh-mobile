/*
 * dsh-mobile localoptions.h for Dropbear dbclient (Android).
 *
 * Based on the MIT-licensed localoptions.h from ribbons/android-dropbear
 * (https://github.com/ribbons/android-dropbear, SPDX-License-Identifier: MIT),
 * adjusted for the dsh-mobile use case:
 *   - Keep DROPBEAR_CLI_PASSWORD_AUTH 1 and DROPBEAR_USE_PASSWORD_ENV 1
 *     (defaults) so dbclient authenticates via the DROPBEAR_PASSWORD
 *     environment variable — no getpass()/tty needed, no askpass helper.
 *   - Disable server password auth only: crypt() is unavailable on Android.
 */

// Disable server password auth as crypt() isn't available under Android
#define DROPBEAR_SVR_PASSWORD_AUTH 0

// Speed up symmetrical ciphers and hashes at the expense of larger binaries
#define DROPBEAR_SMALL_CODE 0

// Include non-standard shell path prior to Android 11 in default shell list
#if __ANDROID_MIN_SDK_VERSION__ > 30
    #error "COMPAT_USER_SHELLS override no longer needed"
#endif
#define COMPAT_USER_SHELLS "/bin/sh","/system/bin/sh"

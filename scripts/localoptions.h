/*
 * dsh-mobile localoptions.h for Dropbear dbclient (Android).
 *
 * Based on the MIT-licensed localoptions.h from ribbons/android-dropbear
 * (https://github.com/ribbons/android-dropbear, SPDX-License-Identifier: MIT),
 * adjusted for the dsh-mobile use case:
 *   - Client authentication via private key (public key auth); dropbear's
 *     password-auth code path calls getpass(), which does not exist on
 *     Android (configure warns: "dbclient will only have public-key
 *     authentication"). The same approach as dropbear's own CI build.yml.
 *   - Server password auth disabled: crypt() is unavailable on Android.
 */

// Disable server password auth as crypt() isn't available under Android
#define DROPBEAR_SVR_PASSWORD_AUTH 0

// Client auth: public key only (getpass() unavailable on Android); some
// code paths still reference ssh-agent but that is guarded separately.
#define DROPBEAR_CLI_PASSWORD_AUTH 0
#define DROPBEAR_USE_PASSWORD_ENV 0
#define DROPBEAR_CLI_PUBKEY_AUTH 1

// Speed up symmetrical ciphers and hashes at the expense of larger binaries
#define DROPBEAR_SMALL_CODE 0

// Include non-standard shell path prior to Android 11 in default shell list
#if __ANDROID_MIN_SDK_VERSION__ > 30
    #error "COMPAT_USER_SHELLS override no longer needed"
#endif
#define COMPAT_USER_SHELLS "/bin/sh","/system/bin/sh"

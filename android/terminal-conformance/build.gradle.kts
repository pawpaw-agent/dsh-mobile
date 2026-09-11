// Terminal conformance harness — the differential test bed for the in-house
// terminal implementation (see docs/terminal-rewrite-plan.md, phase 0).
//
// This module is deliberately NOT an Android module and deliberately NOT a
// dependency of :app:
//
//  * Pure JVM, so it runs in seconds on CI and on a laptop, and so the emulator
//    core can be tested without a device or an emulator.
//  * Not an app dependency, so nothing here (least of all the vendored oracle jar
//    or the android.* stand-ins) can reach the APK.
//
// The `android.util` / `android.graphics` classes under src/main/java are stubs
// that exist only so the reference emulator can link on a plain JVM. Their
// presence is why this module must never be compiled into the app.

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // The reference emulator, used strictly as a black-box oracle for differential
    // testing: it is fed the corpus and its screen is compared against the in-house
    // implementation. Vendored rather than resolved so the harness is offline-capable
    // and the oracle's bytes are pinned; tools/fetch-oracle.sh regenerates it and
    // verifies the recorded SHA-256.
    implementation(files("libs/termux-terminal-emulator-0.118.1-classes.jar"))
}

/** Directory holding the recorded byte streams (see tools/). */
val corpusDir: Directory = layout.projectDirectory.dir("corpus")

/**
 * Register one harness invocation.
 *
 * @param taskName Gradle task name.
 * @param description one-line description shown by `gradle tasks`.
 * @param command harness subcommand (`check`, `coverage`, `selftest`, `list`).
 */
fun harnessTask(taskName: String, description: String, command: String) =
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        this.description = description
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("dsh.conformance.Harness")
        args(command, corpusDir.asFile.absolutePath)
        // The harness prints a report; a non-zero exit means real drift or an
        // uncovered capability, and must fail the build.
        isIgnoreExitValue = false
    }

harnessTask("conformanceCoverage", "Report which terminal capabilities the corpus exercises", "coverage")
harnessTask("conformanceSelftest", "Prove the harness can fail (determinism, discrimination, detection)", "selftest")
harnessTask("conformanceCheck", "Compare every registered terminal implementation", "check")
harnessTask("conformanceList", "List the corpus cases", "list")

/**
 * Everything that must hold before the harness can be trusted.
 *
 * `coverage` guards against a corpus that stops exercising a capability, and
 * `selftest` guards against a harness that has silently stopped being able to
 * fail. `check` is included because with one implementation registered it still
 * verifies determinism under sliced feeding.
 */
tasks.register("conformance") {
    group = "verification"
    description = "Run the full terminal conformance gate (coverage + selftest + check)"
    dependsOn("conformanceCoverage", "conformanceSelftest", "conformanceCheck")
}

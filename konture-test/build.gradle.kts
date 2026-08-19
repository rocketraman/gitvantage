import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

/**
 * The architecture rules, in a module of their own.
 *
 * This is Konture's documented layout, and it is not merely stylistic. The Gradle plugin shares the
 * generated project layout — the description of modules, source roots and classpaths every rule
 * reads — from the root project through an outgoing `archLayoutElements` configuration, and only
 * *subprojects* consume it: the code that copies it onto a test classpath is gated on
 * `project != project.rootProject`. Run from the root project's own test source set, as these rules
 * were until Konture 0.7.7, nothing puts the layout where the library looks, and every rule dies at
 * load time with "layout_v2.json was not found at /konture/layout_v2.json". 0.7.6 papered over that
 * by falling back to reading the build directory; 0.7.7 dropped the fallback.
 *
 * No `implementation(project(":"))`. Konture derives the whole topology from that shared layout —
 * the rules address production code by package and file name, never by importing it — and the
 * documentation is explicit that adding the dependency is wrong. It would also put a real edge into
 * the module graph that `assertNoCycles` walks, to buy nothing.
 *
 * Versions come from the root build: a plugin requested there is on this script's classpath
 * already, so asking for one again here would be a second opinion about which version to use.
 */
plugins {
    kotlin("jvm")
    // The rules assert with `assert`, and power-assert is what turns a bare `false` into a diagram
    // naming the offending file. Applied per-module, so it has to be repeated here.
    kotlin("plugin.power-assert")
    // TestBalloon: the suite is written against it, matching the git tests in the root module.
    id("de.infix.testBalloon")
    id("io.github.baole.konture")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.testBalloon.framework.core)
    testImplementation(libs.konture)
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions = listOf("kotlin.assert")
}

tasks.test {
    /**
     * The production sources are an input to these tests, and Gradle has no way to know it.
     *
     * The layout this module is handed lists source *roots*, not file contents; the rules then read
     * and parse the real files at run time. So editing production code changes nothing Gradle
     * tracks — not the test classes, not the test resources, not the classpath — and the task stays
     * UP-TO-DATE. Measured: with a `ProcessBuilder` added to a file the rules forbid it in, the
     * task was skipped and the suite reported ten passes without evaluating a single rule.
     *
     * In the root module this was free, because the production classes were on the test runtime
     * classpath and any edit invalidated the task. Moving out of that module lost the connection
     * silently, which is the failure this whole suite exists to catch, one level up.
     *
     * Deliberately the whole main source tree rather than the exact set any one rule reads:
     * over-declaring costs a re-run nobody notices, under-declaring costs a rule that stops running.
     */
    inputs.files(rootProject.fileTree("src/main") { include("**/*.kt") })
        .withPropertyName("productionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = false
    }
}

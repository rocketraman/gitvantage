// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import de.infix.testBalloon.framework.core.testSuite
import io.github.baole.konture.FilesRuleBuilder
import io.github.baole.konture.Konture
import io.github.baole.konture.SourceSets
import io.github.baole.konture.architecture
import io.github.baole.konture.assertNoCycles
import io.github.baole.konture.fileScopeFromPackage
import java.io.File

/**
 * Guards the boundary `com.gitvantage.git` exists to create: git is reached through that package,
 * and only `com.gitvantage.git.Git` starts a git process.
 *
 * Two mechanisms, because no single one covers it:
 *
 *  1. **Who may reach the git package, and who may start a process** — Konture rules. Both are
 *     type-level questions, which is what Konture is for.
 *  2. **Who may name the git binary** — a plain source scan, because the binary's name is a string
 *     literal rather than a type, and no type-level tool can see it.
 *
 * Every production type below is named in prose rather than linked, because this module does not
 * depend on the one it describes — see the comment in its build file. That is the point: rules that
 * could not compile without the code they police would be rules the code could break by moving.
 *
 * This file shares the `com.gitvantage` package with the code it describes but is compiled
 * separately, so it can see none of it. Konture reads everything it needs from the layout the root
 * project generates and hands to this module.
 */
val Architecture by testSuite {

    // ---- 1. Who may reach the git package ------------------------------------------------

    /**
     * Nothing outside `com.gitvantage.git` may reach the runner itself.
     *
     * The rule targets `Git` rather than the whole package, deliberately. Banning the package would
     * be wrong: `DetailPanel` renders branch and worktree lists, `Derive` builds badges from them,
     * `DiffView` draws a `Diff`. Consuming the git layer's *data* is what the git layer is
     * for. What must stay confined is the thing that starts a git process, and that is `Git`.
     *
     * Two exceptions, both asking `Git.isRepo` — a predicate over a directory that starts no
     * process. `Registry` needs it to discover repositories while walking a folder tree, and
     * `AppState` to do the same for a dropped path. If either ever calls `run`/`read`, that is a
     * design change worth making deliberately rather than something this rule waves through.
     *
     * The `files` scope, not `classes`: Konture attributes a dependency to the *file* it appears
     * in, then reports it against every class declared there. Written over `classes`, excluding
     * `Registry.kt` would mean naming all five of the classes it declares, and the list would rot
     * the moment a sixth was added.
     */
    test("only the state layer reaches the git runner") {
        architecture {
            files {
                that().notResideInAPackage("com.gitvantage.git")
                    .and().notHaveName { it in setOf("AppState.kt", "Registry.kt") }
                should().notReferenceClass("com.gitvantage.git.Git")
            }
        }
    }

    /**
     * Module dependency cycles.
     *
     * Worth stating plainly rather than letting a green tick imply more than it does: GitVantage is
     * a single Gradle module and this walks the *module* graph, so today it passes with nothing to
     * traverse. It is here as a guard for the day the project is split — the moment a cycle becomes
     * possible is the moment nobody thinks to add the check. The package-level question, which is
     * the live one, is the test below.
     */
    test("the module graph has no dependency cycles") {
        Konture.assertNoCycles()
    }

    /**
     * The package-level cycle check `assertNoCycles` cannot do, written out explicitly.
     *
     * The git layer may reach *down* into `com.gitvantage.model` — it produces `Repo` and consumes
     * `RegistryEntry`, and shared model types are what that package exists to hold. It may not
     * reach *up*: nothing in `com.gitvantage.git` may name application state, a service, or the UI.
     *
     * This used to pin a list of allowed back-edge symbols, because `Repo`, `RegistryEntry` and
     * `Meta` sat in the application package and the arrow genuinely pointed both ways. Moving them
     * into `model` removed the cycle rather than documenting it, so the rule is now absolute and
     * the allowlist is gone.
     */
    test("the git package never reaches up into the application") {
        val gitFiles = Konture.fileScopeFromPackage("com.gitvantage.git").files
        // Guard against a silent empty scope, which would pass while checking nothing.
        assert(gitFiles.isNotEmpty())

        val offenders = gitFiles.flatMap { file ->
            file.imports
                .filter { it.startsWith("com.gitvantage.") }
                .filterNot { it.startsWith("com.gitvantage.git.") || it.startsWith("com.gitvantage.model.") }
                .map { "${file.name} -> $it" }
        }.sorted()

        assert(offenders.isEmpty())
    }

    /**
     * `git.model` holds what the git layer *returns*, so it must not know how the git layer works.
     *
     * It may reach down into `model` — `StatusResult` is a list of `ChangedFile`, and `RemoteBranch`
     * asks `Meta` whether a branch is a shared integration branch. It may not
     * reach sideways into `com.gitvantage.git` or up into the application: a data type that calls
     * the runner is no longer a data type, and the UI consumes these freely on that understanding.
     *
     * Note this is stricter than the rule above, which covers `com.gitvantage.git` *and* its
     * subpackages and therefore lets `git.model` import the runner. This is what forbids it.
     */
    test("the git model package depends only on the shared model") {
        val files = Konture.fileScopeFromPackage("com.gitvantage.git.model").files
        assert(files.isNotEmpty())

        val offenders = files.flatMap { file ->
            file.imports
                .filter { it.startsWith("com.gitvantage.") }
                .filterNot { it.startsWith("com.gitvantage.git.model.") || it.startsWith("com.gitvantage.model.") }
                .map { "${file.name} -> $it" }
        }.sorted()

        assert(offenders.isEmpty())
    }

    /**
     * `model` is the bottom of the stack: it may not name anything else in the application.
     *
     * That is what lets both the git layer and the UI depend on it without either creating a cycle,
     * and it is the property most easily lost — a single KDoc link into the git layer was enough to
     * make `Model.kt` import `RepoScanner` before this package existed.
     */
    test("the model package depends on nothing else in the application") {
        val modelFiles = Konture.fileScopeFromPackage("com.gitvantage.model").files
        assert(modelFiles.isNotEmpty())

        val offenders = modelFiles.flatMap { file ->
            file.imports
                .filter { it.startsWith("com.gitvantage.") }
                .filterNot { it.startsWith("com.gitvantage.model.") }
                .map { "${file.name} -> $it" }
        }.sorted()

        assert(offenders.isEmpty())
    }

    /**
     * The UI may not run git.
     *
     * This is the rule the whole package split was for. It used to need `AppState.kt` and
     * `Registry.kt` written in as exceptions, because they sat in the same package as the Compose
     * files and legitimately drive the git layer. Now that `ui` holds only the view and `app` holds
     * state and services, the exceptions are structural rather than listed: a Compose file that
     * wants git has to go through `AppState`, and there is nothing to allowlist.
     *
     * `com.gitvantage.git.model` is deliberately *not* covered — rendering a `Branch` or a `Diff` is
     * exactly what the UI is for. What it must not touch is the package that runs commands.
     */
    test("the UI never reaches the git layer") {
        val uiFiles = Konture.fileScopeFromPackage("com.gitvantage.ui").files
        assert(uiFiles.isNotEmpty())

        val offenders = uiFiles.flatMap { file ->
            file.imports
                .filter { it.startsWith("com.gitvantage.git.") }
                .filterNot { it.startsWith("com.gitvantage.git.model.") }
                .map { "${file.name} -> $it" }
        }.sorted()

        assert(offenders.isEmpty())
    }

    /**
     * The application layer may not depend on the view.
     *
     * State, services and the presentation model exist without a window; the direction of that
     * relationship is what makes them testable and what stops a Compose type leaking into a
     * background scan. `app` holds `Tokens`, `Theme` and `Derive`, which are Compose-*coupled* —
     * they name `Color` — but that is a value type, not the view.
     */
    test("the application layer never depends on the UI") {
        val appFiles = Konture.fileScopeFromPackage("com.gitvantage.app").files
        assert(appFiles.isNotEmpty())

        val offenders = appFiles.flatMap { file ->
            file.imports.filter { it.startsWith("com.gitvantage.ui.") }.map { "${file.name} -> $it" }
        }.sorted()

        assert(offenders.isEmpty())
    }

    // ---- 2. Who may start a process ------------------------------------------------------

    /**
     * The production sources, read as text.
     *
     * Only the git-binary rule needs this. The process-spawning rule below used to as well, because
     * `ProcessBuilder` arrives through Kotlin's implicit `java.lang.*` import and Konture could not
     * resolve those; naming the git binary is a string literal, so it stays a text scan whatever
     * Konture learns to see.
     *
     * The roots come from Konture's layout rather than a written-down path. From this module a
     * literal path would have to climb out of its own directory — `../src/main/kotlin` — which is
     * both fragile and silent when wrong: a scan that walks nothing reports no offenders and passes.
     * Asking the layout means these roots are the same ones every rule above is evaluated against,
     * and a source root added to the root build is picked up without being named twice.
     *
     * Konture hands these back absolute, so none of it depends on the working directory — which is
     * this module's own directory, where a relative `src/main/kotlin` would find nothing at all.
     *
     * `distinct` because the same root can arrive more than once: Konture resolves every module's
     * source dirs against the root project, so this module's non-existent `src/main` and the real
     * one can both land on the same path. Without it a single offender would be reported twice.
     */
    fun mainSources(): List<File> {
        val roots = Konture.projectGraph.builds.values.flatten()
            .flatMap { module -> module.sourceSets }
            .filter { it.production }
            .flatMap { it.srcDirs }
            .distinct()
            .map { File(it) }

        val files = roots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }
        // A scan that silently found nothing would pass every rule below while checking nothing —
        // the exact failure this whole file exists to prevent.
        assert(files.size > 20)
        return files
    }

    /**
     * Starting a process is allowed in four places, and only one of them is about git:
     *
     *  - `git/Git` runs the git binary — the subject of this file;
     *  - `Actions` launches what the user picked from "Open in …" (a terminal, an IDE, `xdg-open`,
     *    and `git gui`) detached, capturing nothing;
     *  - `GitHub` runs the `gh` CLI, a different binary with a different failure model.
     *  - `app/Theme` asks the Linux desktop for its light/dark preference through `gdbus`,
     *    `gsettings` and `kreadconfig`, because Skiko cannot: it dlopens an unversioned
     *    `libdbus-1.so` that only a machine with the -devel package installed has, and reports
     *    UNKNOWN otherwise (SKIKO-1177). Shells out only on that UNKNOWN, and only on Linux.
     *
     * Each entry has a reason, which is what makes a new name appearing in a failure worth stopping
     * on rather than adding to the list.
     *
     * This was a regex over the source text until Konture 0.7.7. Konture resolves a bare type name
     * through the file's imports plus a table of Kotlin's implicit ones, and that table used to
     * carry exactly one `java.lang` entry — `Appendable` — where Kotlin's real JVM defaults are the
     * whole package. `ProcessBuilder` therefore resolved to nothing: `notReferenceClass`, `notCall`
     * and `notDependOnClassesInAnyPackage` all reported clean on a file that plainly constructs
     * one, and only a redundant `import java.lang.ProcessBuilder` made them fire — no fix, since a
     * new violator would not write that import either. Fixed upstream in
     * https://github.com/baole/konture/issues/47.
     *
     * The `files` scope rather than `classes`, for the same reason as the rule at the top of this
     * file: Konture attributes a dependency to the file it appears in, so an allowlist over classes
     * would have to name every class each allowed file declares.
     */
    test("process spawning is confined to Git and the three non-git callers") {
        architecture {
            files {
                that().notHaveName { it in setOf("Git.kt", "Actions.kt", "GitHub.kt", "Theme.kt") }
                should().notReferenceClass("java.lang.ProcessBuilder")
            }
        }
    }

    /**
     * That the rule above can see what it is looking for.
     *
     * A Konture rule that resolves nothing reports no violations, which is indistinguishable from a
     * codebase with nothing to find. That is not a hypothetical: it is exactly what
     * `notReferenceClass("java.lang.ProcessBuilder")` did before 0.7.7, and why the rule above had
     * to be a regex until now. So this asserts the positive case: run the same rule *against*
     * `com.gitvantage.git.Git`, which unquestionably constructs a `ProcessBuilder`, and require it
     * to fail.
     *
     * `Git.kt` is deliberate rather than a fixture written for the test. A fixture can drift out of
     * step with how production code is actually written; the file this rule exists to permit cannot.
     * It also imports nothing from `java.lang`, so what is being proved is specifically that the
     * implicit import resolves.
     *
     * Only a failure that names the type counts. Konture also throws when a rule selects no files
     * at all, and reading that as "detection works" would defeat the whole point.
     *
     * Built from [FilesRuleBuilder] directly rather than through `architecture { files { … } }`,
     * because that entry point asserts on the spot and there is no result to inspect.
     */
    test("the process-spawning rule can see an unimported ProcessBuilder") {
        val rule = FilesRuleBuilder(Konture.projectGraph, SourceSets.production()).apply {
            that().haveName("Git.kt")
            should().notReferenceClass("java.lang.ProcessBuilder")
        }
        val failure = runCatching { rule.check() }.exceptionOrNull()

        assert(failure != null && "ProcessBuilder" in (failure.message ?: ""))
    }

    /**
     * Naming the git binary — the invariant in its most literal form.
     *
     * `Actions` is here for `git gui`: a detached launch that captures nothing and waits for
     * nothing, so it has none of the timeout, streams, or logging concerns `Git` exists to hold,
     * and routing it through `Git` would mean pretending a fire-and-forget launch is a command.
     * Every other way of running git in this app goes through `Git`.
     */
    test("only Git and the git gui launcher name the git binary") {
        val allowed = setOf("Git.kt", "Actions.kt")

        val offenders = mainSources()
            .filter { it.name !in allowed && "\"git\"" in it.readText() }
            .map { it.name }
            .sorted()

        assert(offenders.isEmpty())
    }
}

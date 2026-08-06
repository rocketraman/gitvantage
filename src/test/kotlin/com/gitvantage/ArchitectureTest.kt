// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import com.gitvantage.app.AppState
import com.gitvantage.app.Registry
import com.gitvantage.git.model.Branch
import com.gitvantage.git.model.Diff
import com.gitvantage.git.model.RemoteBranch
import com.gitvantage.git.model.StatusResult
import com.gitvantage.model.RegistryEntry
import com.gitvantage.model.Repo
import de.infix.testBalloon.framework.core.testSuite
import io.github.baole.konture.FilesRuleBuilder
import io.github.baole.konture.Konture
import io.github.baole.konture.SourceSets
import io.github.baole.konture.architecture
import io.github.baole.konture.assertNoCycles
import io.github.baole.konture.fileScope
import io.github.baole.konture.fileScopeFromPackage
import java.io.File

/**
 * Guards the boundary `com.gitvantage.git` exists to create: git is reached through that package,
 * and only [com.gitvantage.git.Git] starts a git process.
 *
 * Two mechanisms, because no single one covers it:
 *
 *  1. **Who may reach the git package** — a Konture rule, on the package rather than on any one
 *     class. This is a type-level question, which is what Konture is for.
 *  2. **Who may start a process, and who may name the git binary** — plain source scans. Konture
 *     cannot see either, for reasons documented at [processSpawningIsConfinedToGit].
 *
 * Konture needs `generateArchitectureLayout` to have run; the `test` task depends on it.
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
     * The git layer may reach *down* into `com.gitvantage.model` — it produces [Repo] and consumes
     * [RegistryEntry], and shared model types are what that package exists to hold. It may not
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
     * It may reach down into `model` — [StatusResult] is a list of [com.gitvantage.model.ChangedFile],
     * and [RemoteBranch] asks `Meta` whether a branch is a shared integration branch. It may not
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
     * `com.gitvantage.git.model` is deliberately *not* covered — rendering a [Branch] or a [Diff] is
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
     * Why this is a source scan and not a Konture rule.
     *
     * **Konture issue #47** — https://github.com/baole/konture/issues/47
     *
     * Konture turns a bare type name into a fully-qualified one through the file's imports, plus a
     * hardcoded table of Kotlin's implicit imports (`KotlinDefaultTypes`). That table has 60
     * entries and exactly one of them is from `java.lang` — `Appendable`. Kotlin's real JVM default
     * imports are the whole of `java.lang`, so `ProcessBuilder`, `Thread`, `Runtime`, `System` and
     * the rest resolve to nothing, and a rule written against them silently matches nothing.
     *
     * Measured, on a file that plainly constructs a `ProcessBuilder`: `notReferenceClass`,
     * `notCall` and `notDependOnClassesInAnyPackage` all report clean. Adding a redundant
     * `import java.lang.ProcessBuilder` makes the first two fire immediately — which is exactly why
     * it is no fix, since a *new* violator would not have written that import either.
     *
     * [kontureIssue47StillOpen] fails the moment that is fixed upstream, so this workaround gets
     * revisited rather than quietly outliving its reason.
     *
     * The remaining rule below cannot move to Konture whatever happens to #47: naming the git
     * binary is a string literal (`listOf("git") + args`), not a type.
     */
    fun mainSources(): List<File> {
        // Gradle runs tests with the project directory as the working directory.
        val files = File("src/main/kotlin/com/gitvantage")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // A scan that silently found nothing would pass every rule below while checking nothing —
        // the exact failure this whole file exists to prevent.
        assert(files.size > 20)
        return files
    }

    /**
     * Starting a process is allowed in three places, and only one of them is about git:
     *
     *  - `git/Git` runs the git binary — the subject of this file;
     *  - `Actions` launches what the user picked from "Open in …" (a terminal, an IDE, `xdg-open`,
     *    and `git gui`) detached, capturing nothing;
     *  - `GitHub` runs the `gh` CLI, a different binary with a different failure model.
     *
     * Each entry has a reason, which is what makes a new name appearing in a failure worth stopping
     * on rather than adding to the list.
     */
    test("process spawning is confined to Git and the two non-git launchers") {
        val allowed = setOf("Git.kt", "Actions.kt", "GitHub.kt")

        val offenders = mainSources()
            .filter { it.name !in allowed && Regex("\\bProcessBuilder\\b").containsMatchIn(it.readText()) }
            .map { it.name }
            .sorted()

        assert(offenders.isEmpty())
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

    /**
     * A canary for Konture issue #47 — https://github.com/baole/konture/issues/47
     *
     * **This test asserts that a bug still exists, and is meant to start failing.** Its subject is
     * [CanarySpawner] at the foot of this file: a class whose only job is to construct a
     * `ProcessBuilder` without an explicit import. A working Konture must report that; today it
     * reports nothing, so the rule below finds a clean file and this passes.
     *
     * When this starts failing, #47 has been fixed. At that point:
     *
     *  1. replace [processSpawningIsConfinedToGit]'s source scan with this Konture rule, keeping
     *     the same four-name allowlist;
     *  2. drop the #47 references from the KDoc above;
     *  3. delete this test and [CanarySpawner].
     *
     * A comment saying "revisit after a Konture upgrade" is a note nobody re-reads. A failing build
     * is not.
     *
     * Built from [FilesRuleBuilder] directly rather than through `architecture { files { … } }`,
     * because that entry point hardcodes the production source sets and this file is a test one.
     */
    test("KONTURE #47 canary: implicitly imported JDK types are still invisible to Konture") {
        // The rule is worthless if this file is not in scope — an unscanned source set reports no
        // violations for the same reason a fixed Konture would report none.
        val inScope = Konture.fileScope(SourceSets.tests()).files.map { it.name }
        assert("ArchitectureTest.kt" in inScope)

        val rule = FilesRuleBuilder(Konture.projectGraph, SourceSets.tests()).apply {
            that().haveName("ArchitectureTest.kt")
            should().notReferenceClass("java.lang.ProcessBuilder")
        }
        val failure = runCatching { rule.check() }.exceptionOrNull()

        // Only a failure that actually names the type counts. Konture also throws for an empty
        // selection, and reading that as "fixed" would retire the workaround early.
        val kontureDetectedIt = failure != null && "ProcessBuilder" in (failure.message ?: "")

        assert(!kontureDetectedIt)
    }
}

/**
 * The violation the Konture #47 canary above looks for, kept in this file so the rule and its
 * subject cannot drift apart.
 *
 * It constructs a `ProcessBuilder` the way ordinary Kotlin does: with no import, because `java.lang`
 * is imported implicitly. That is precisely what Konture cannot resolve today.
 *
 * Two things must stay true or the canary quietly stops meaning anything, and both are the sort of
 * thing a well-meaning cleanup would "fix":
 *  - **no `import java.lang.ProcessBuilder` in this file** — adding it is what makes Konture see
 *    the reference, which is the very thing being tested for;
 *  - the call stays, so there is something to detect.
 *
 * Never invoked. Nothing here should ever start a process.
 */
internal object CanarySpawner {
    fun spawn(): String =
        ProcessBuilder(listOf("true")).start().inputStream.bufferedReader().readText()
}

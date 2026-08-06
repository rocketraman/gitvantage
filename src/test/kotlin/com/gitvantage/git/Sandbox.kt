// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.model.RegistryEntry
import java.io.File
import java.nio.file.Files

/**
 * A throwaway git world for one test: real repositories on disk, driven by the real `git` binary.
 *
 * The operations under test are thin wrappers over git, and almost everything worth asserting about
 * them — that a push is refused when the branch diverged, that `branch -d` refuses unmerged work,
 * that removing a worktree leaves its branch alone — is git's behaviour, not ours. Faking git would
 * only assert that the fake matches our belief about it, which is the belief most likely to be
 * wrong. So these tests spawn git for real, and the only thing that is faked is the network: an
 * "origin" is a bare repository in the same temp directory, which pushes and fetches exactly like a
 * remote one while needing no credentials and no connection.
 *
 * Every test gets a fresh instance (see the `asContextForEach` fixtures), so no test can observe
 * state another one left behind, and they remain safe to reorder.
 *
 * [git] is for *arranging* state, never for the behaviour under test: a test that both sets up and
 * verifies through the same code path can pass while that path is broken.
 */
class Sandbox : AutoCloseable {

    val root: File = Files.createTempDirectory("gitvantage-test").toRealPath().toFile()

    /** Run git directly, failing loudly — a broken arrangement must not look like a failed assertion. */
    fun git(dir: File, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "arrangement failed: git ${args.joinToString(" ")}\n$out" }
        return out
    }

    /** git's exit code, for arrangements whose failure is expected and not interesting. */
    fun gitAllowingFailure(dir: File, vararg args: String): Int {
        val proc = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        proc.inputStream.readBytes()
        return proc.waitFor()
    }

    /** A bare repository to push to and clone from — an "origin" that needs no network. */
    fun bare(name: String): File {
        val dir = File(root, "$name.git")
        git(root, "init", "-q", "--bare", "-b", MAIN, dir.path)
        return dir
    }

    /**
     * A working clone of [origin] with git identity configured locally.
     *
     * The identity has to be local rather than global because the test JVM runs with both config
     * files pointed at nothing (see the `test` task), which is what keeps a developer's real
     * ~/.gitconfig from leaking in. `protocol.file.allow` is set for the same reason submodules
     * need it: git refuses file:// transport for submodule clones by default since 2.38, and every
     * "remote" here is a local path.
     */
    fun clone(origin: File, name: String): File {
        val dir = File(root, name)
        git(root, "clone", "-q", origin.path, dir.path)
        git(dir, "config", "user.email", "test@example.invalid")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "commit.gpgsign", "false")
        git(dir, "config", "protocol.file.allow", "always")
        return dir
    }

    /**
     * The common arrangement: a bare origin plus a clone holding one commit, pushed and tracking.
     * Returns the working clone; reach its origin with [originOf].
     */
    fun repo(name: String = "repo"): File {
        val origin = bare(name)
        val work = clone(origin, name)
        commit(work, "a.txt", "one\n", "first commit")
        git(work, "push", "-q", "-u", "origin", MAIN)
        return work
    }

    fun originOf(work: File): File = File(root, "${work.name}.git")

    /** Write [content] to [path] within [dir] and commit it. */
    fun commit(dir: File, path: String, content: String, message: String) {
        File(dir, path).apply { parentFile?.mkdirs() }.writeText(content)
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", message)
    }

    /** Make [dir]'s working tree dirty without committing. */
    fun dirty(dir: File, path: String = "a.txt") {
        File(dir, path).appendText("uncommitted\n")
    }

    /** The commit [rev] resolves to, for asserting that an operation did or didn't move a ref. */
    fun rev(dir: File, rev: String): String = git(dir, "rev-parse", rev).trim()

    fun branches(dir: File): List<String> =
        git(dir, "for-each-ref", "--format=%(refname:short)", "refs/heads")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun entry(dir: File): RegistryEntry = RegistryEntry(path = dir.path)

    override fun close() {
        root.deleteRecursively()
    }

    companion object {
        /** Pinned rather than inherited: the test JVM has no config, and git's built-in default varies. */
        const val MAIN = "main"
    }
}

/**
 * The git commands [GitLog] recorded for [repoName] during [action], oldest first.
 *
 * Filtered by repo rather than sliced by position: [GitLog.entries] is process-wide, so a suite
 * running alongside this one would otherwise show up in the slice. Callers give their repository a
 * name unique to the test, which makes the filter exact.
 */
internal inline fun recordedFor(repoName: String, action: () -> Unit): List<String> {
    val before = GitLog.entries.value.size
    action()
    return GitLog.entries.value.drop(before).filter { it.repo == repoName }.map { it.command }
}

// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Side-effecting git operations (as opposed to [RepoScanner], which only reads state).
 * Everything runs on [Dispatchers.IO]; callers re-scan the affected repos afterwards so
 * the dashboard reflects the new state (e.g. ahead → 0 after a push).
 */
object GitOps {

    data class Result(val id: String, val ok: Boolean, val message: String)

    /** `git push` in the repo. If the current branch has no tracking upstream, publishes it
     *  with `push -u origin HEAD` (sets the upstream too). Fails (not throws) on rejection,
     *  auth, offline, or detached HEAD. */
    suspend fun push(entry: RegistryEntry): Result = withContext(Dispatchers.IO) {
        val dir = File(entry.path)
        if (!File(dir, ".git").exists()) return@withContext Result(entry.path, false, "Not a git repo")
        val name = dir.name
        val hasUpstream = run(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}").code == 0
        val (code, out, err) =
            if (hasUpstream) GitLog.exec(name, dir, listOf("push"), timeoutSeconds = 90)
            else GitLog.exec(name, dir, listOf("push", "-u", "origin", "HEAD"), timeoutSeconds = 90)
        if (code == 0) {
            Result(entry.path, true, "$name pushed")
        } else {
            Result(entry.path, false, "$name: ${firstMeaningfulLine(err, out) ?: "push failed"}")
        }
    }

    /** Stage (optionally) then commit with [title] (subject) + optional [body]. */
    suspend fun commit(entry: RegistryEntry, title: String, body: String, stageAll: Boolean): Result =
        withContext(Dispatchers.IO) {
            val dir = File(entry.path)
            val name = dir.name
            if (!File(dir, ".git").exists()) return@withContext Result(entry.path, false, "$name: not a git repo")
            if (stageAll) {
                val (addCode, _, addErr) = GitLog.exec(name, dir, listOf("add", "-A"))
                if (addCode != 0) return@withContext Result(entry.path, false, "$name: ${firstMeaningfulLine(addErr, "") ?: "git add failed"}")
            }
            // -m subject [-m body]: git inserts the blank line between them.
            val args = buildList {
                add("commit"); add("-m"); add(title.trim())
                if (body.isNotBlank()) { add("-m"); add(body.trim()) }
            }
            val (code, out, err) = GitLog.exec(name, dir, args)
            if (code == 0) Result(entry.path, true, "$name committed")
            else Result(entry.path, false, "$name: ${firstMeaningfulLine(err, out) ?: "commit failed"}")
        }

    /** `git merge --ff-only @{upstream}` — advances the branch to its upstream when possible. */
    suspend fun fastForward(entry: RegistryEntry): Result = withContext(Dispatchers.IO) {
        val dir = File(entry.path)
        val name = dir.name
        if (!File(dir, ".git").exists()) return@withContext Result(entry.path, false, "$name: not a git repo")
        val (code, out, err) = GitLog.exec(name, dir, listOf("merge", "--ff-only", "@{upstream}"))
        if (code == 0) Result(entry.path, true, "$name fast-forwarded")
        else Result(entry.path, false, "$name: ${firstMeaningfulLine(err, out) ?: "fast-forward failed"}")
    }

    /** `git stash apply <ref>` — restores the stash's changes, keeping the stash. */
    suspend fun stashApply(entry: RegistryEntry, ref: String): Result = withContext(Dispatchers.IO) {
        val (code, out, err) = GitLog.exec(File(entry.path).name, File(entry.path), listOf("stash", "apply", ref))
        if (code == 0) Result(entry.path, true, "Applied $ref")
        else Result(entry.path, false, firstMeaningfulLine(err, out) ?: "stash apply failed")
    }

    /** `git stash drop <ref>` — deletes the stash. */
    suspend fun stashDrop(entry: RegistryEntry, ref: String): Result = withContext(Dispatchers.IO) {
        val (code, out, err) = GitLog.exec(File(entry.path).name, File(entry.path), listOf("stash", "drop", ref))
        if (code == 0) Result(entry.path, true, "Dropped $ref")
        else Result(entry.path, false, firstMeaningfulLine(err, out) ?: "stash drop failed")
    }

    private val ANSI = Regex("\\u001B\\[[0-9;]*m")

    /** First non-blank, non-noise line — the useful part of git's stderr for a toast.
     *  Strips ANSI color escapes (git output is colored for the console). */
    private fun firstMeaningfulLine(err: String, out: String): String? {
        fun clean(s: String) = s.lineSequence().map { ANSI.replace(it, "").trim() }
        return clean(err + "\n" + out)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("To ") && !it.startsWith("remote:") }
            ?: clean(err).firstOrNull { it.isNotEmpty() }
    }

    private data class Run(val code: Int, val out: String, val err: String)

    private fun run(dir: File, vararg args: String, timeoutSeconds: Long = 30): Run = try {
        val proc = ProcessBuilder(listOf("git", *args)).directory(dir).start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            Run(-1, out, "timed out")
        } else {
            Run(proc.exitValue(), out, err)
        }
    } catch (e: Exception) {
        Run(-1, "", e.message ?: "failed to run git")
    }
}

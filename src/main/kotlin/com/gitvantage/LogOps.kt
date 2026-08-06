// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads the commits in an arbitrary revision range (`git log <range>`) into a list of
 * [Commit]s for [LogView]. The first consumer is "incoming" commits when a branch is behind
 * its upstream (`HEAD..@{upstream}`), but the range is a plain string so any set of commits
 * works. All process work runs on [Dispatchers.IO] so the UI thread is never blocked.
 */
object LogOps {

    /** What a ref pointing at a commit is, so the UI can style them differently. */
    enum class RefKind { HEAD, LOCAL_BRANCH, REMOTE_BRANCH, TAG }

    data class Ref(val label: String, val kind: RefKind)

    /** A commit's parent: full hash for git commands, git's own abbreviation for display. */
    data class Parent(val fullHash: String, val shortHash: String)

    data class Commit(
        val fullHash: String,
        val shortHash: String,
        val author: String,
        val relDate: String,   // "3 days ago" (committer, relative)
        val isoDate: String,   // absolute ISO-8601 (committer)
        val subject: String,
        val body: String,      // remaining message lines (may be blank)
        val refs: List<Ref> = emptyList(),   // branches/tags pointing here (git's %D decoration)
        val parents: List<Parent> = emptyList(),
        // Reachable from one of origin's remote-tracking refs — i.e. the commit exists on the
        // remote as of the last fetch, so a link to it on the web will actually resolve.
        val pushed: Boolean = true,
    ) {
        /** More than one parent means this commit merged history together. */
        val isMerge: Boolean get() = parents.size > 1
    }

    private const val US = "\u001F"   // field separator within a record
    private const val RS = "\u001E"   // record separator between commits
    // %D is the ref decoration ("HEAD -> main, origin/main, tag: v1.0.0"). It sits before %s
    // because %b must stay last — the body is the only field that can contain newlines.
    // %P/%p are the parent hashes (full and abbreviated), space-separated. Two or more means a
    // merge. They sit before %s for the same reason %D does: %b must stay last.
    private const val FMT = "%H$US%h$US%an$US%cr$US%cI$US%D$US%P$US%p$US%s$US%b$RS"

    /**
     * Parse git's `%D` decoration into typed refs.
     *
     * Read with `--decorate=full`, so every ref arrives fully qualified:
     *
     *     HEAD -> refs/heads/main, refs/remotes/origin/main, tag: refs/tags/v1.0.0, refs/stash
     *
     * The full paths matter. Classifying by shape instead — "contains a slash means remote" —
     * mislabels the very common `feat/x` and `fix/y` local branches, and turns `refs/stash` into
     * a branch. Prefixes leave nothing to guess.
     */
    internal fun parseRefs(decoration: String): List<Ref> =
        decoration.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { raw ->
            when {
                raw.startsWith("HEAD ->") ->
                    listOf(Ref("HEAD", RefKind.HEAD)) + parseRefs(raw.substringAfter("->").trim())
                raw == "HEAD" -> listOf(Ref("HEAD", RefKind.HEAD))
                raw.startsWith("tag:") ->
                    listOf(Ref(raw.removePrefix("tag:").trim().removePrefix("refs/tags/"), RefKind.TAG))
                raw.startsWith("refs/tags/") -> listOf(Ref(raw.removePrefix("refs/tags/"), RefKind.TAG))
                raw.startsWith("refs/heads/") -> listOf(Ref(raw.removePrefix("refs/heads/"), RefKind.LOCAL_BRANCH))
                // origin/HEAD is the remote's default-branch symref, not a branch you can be on.
                raw.startsWith("refs/remotes/") -> raw.removePrefix("refs/remotes/")
                    .takeIf { !it.endsWith("/HEAD") }
                    ?.let { listOf(Ref(it, RefKind.REMOTE_BRANCH)) }.orEmpty()
                // refs/stash and any other plumbing ref: not something to show as a branch.
                raw.startsWith("refs/") -> emptyList()
                else -> listOf(Ref(raw, RefKind.LOCAL_BRANCH))
            }
        }

    /** Zip git's full and abbreviated parent lists. A root commit has none. */
    private fun parseParents(full: String, short: String): List<Parent> {
        val fulls = full.trim().split(' ').filter { it.isNotBlank() }
        val shorts = short.trim().split(' ').filter { it.isNotBlank() }
        return fulls.mapIndexed { i, f -> Parent(f, shorts.getOrElse(i) { f.take(7) }) }
    }

    /** Commits a branch [ref] introduced since it forked from mainline (`git log <mainline>..<ref>`)
     *  — the log companion to [DiffOps.loadBranchDiff]. */
    suspend fun loadBranchLog(repoPath: String, ref: String): List<Commit> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext emptyList()
        val base = DiffOps.mainlineFor(dir, ref) ?: return@withContext emptyList()
        loadRange(repoPath, "$base..$ref")
    }

    /** Commits reachable in [range] (e.g. "HEAD..origin/main"), newest first, capped at [max]. */
    suspend fun loadRange(repoPath: String, range: String, max: Int = 500): List<Commit> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!File(dir, ".git").exists()) return@withContext emptyList()
        val out = git(dir, "log", "--max-count=$max", "--decorate=full", "--format=$FMT", range)
        val unpushed = unpushedIn(dir, range, max)
        out.split(RS).mapNotNull { rec ->
            val r = rec.trim('\n', '\r')
            if (r.isBlank()) return@mapNotNull null
            val f = r.split(US)
            if (f.size < 9) return@mapNotNull null
            Commit(
                fullHash = f[0].trim(),
                shortHash = f[1].trim(),
                author = f[2].trim(),
                relDate = f[3].trim(),
                isoDate = f[4].trim(),
                refs = parseRefs(f[5]),
                parents = parseParents(full = f[6], short = f[7]),
                subject = f[8].trim(),
                body = f.getOrElse(9) { "" }.trim(),
                pushed = f[0].trim() !in unpushed,
            )
        }
    }

    /**
     * The commits in [range] that no `origin` remote-tracking ref reaches — everything that only
     * exists locally, as of the last fetch. One `rev-list` for the whole range rather than a
     * `branch --contains` per commit, which would be a process per row.
     *
     * `origin` specifically (not `--remotes`) because the GitHub links the caller draws are built
     * from origin's URL: reachable from some *other* remote says nothing about whether the link
     * will resolve. When the command fails, the empty set means "assume pushed" — the same thing
     * the UI did before it could tell, and better than hiding a link that would have worked.
     */
    private fun unpushedIn(dir: File, range: String, max: Int): Set<String> =
        git(dir, "rev-list", "--max-count=$max", range, "--not", "--remotes=origin")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun git(dir: File, vararg args: String): String = try {
        val proc = ProcessBuilder(listOf("git", *args))
            .directory(dir).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(30, TimeUnit.SECONDS)) { proc.destroyForcibly(); "" }
        else if (proc.exitValue() != 0) "" else out
    } catch (e: Exception) {
        ""
    }
}

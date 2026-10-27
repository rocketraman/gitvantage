// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.Branch
import com.gitvantage.git.model.OpResult
import com.gitvantage.git.model.RemoteBranch
import com.gitvantage.model.Meta
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists a repo's branches. For local branches we compute two independent relationships:
 *   - to the mainline (main/master): current / mainline / merged / stale / behind
 *   - to the branch's own tracking upstream: ahead / behind / diverged / in-sync / gone
 * Also lists remote branches (with last author) and switches/creates branches.
 */
object BranchOps {

    private const val SEP = ""

    suspend fun load(repoPath: String): List<Branch> = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (!Git.isRepo(dir)) return@withContext emptyList()

        // name, unix date, "*" for HEAD, relative date, upstream short, upstream track
        // ("[ahead N, behind M]"), and the working tree holding the branch (empty unless one does).
        // %(worktreepath) needs git 2.23 — older git fails the whole command and the branch list
        // comes back empty, which is the same way this file already treats a git too old to ask.
        val raw = Git.read(dir, "for-each-ref", "--sort=-committerdate",
            "--format=%(refname:short)$SEP%(committerdate:unix)$SEP%(HEAD)$SEP%(committerdate:relative)$SEP%(upstream:short)$SEP%(upstream:track)$SEP%(worktreepath)$SEP%(objectname)$SEP%(upstream:remotename)$SEP%(push:remotename)$SEP%(push:short)",
            "refs/heads")
        val here = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
        val rows = raw.lineSequence().filter { it.isNotBlank() }.map { line ->
            val p = line.split(SEP)
            Row(
                name = p.getOrElse(0) { "" },
                epoch = p.getOrNull(1)?.toLongOrNull() ?: 0L,
                isCurrent = p.getOrNull(2)?.trim() == "*",
                relative = p.getOrElse(3) { "" },
                upstream = p.getOrNull(4)?.takeIf { it.isNotBlank() },
                track = p.getOrElse(5) { "" },
                // git names the current tree here too; only *another* tree blocks a switch, so
                // this repo's own path is dropped rather than reported back at the user.
                worktree = p.getOrNull(6)?.trim()?.takeIf {
                    it.isNotEmpty() && runCatching { File(it).canonicalPath }.getOrDefault(it) != here
                },
                sha = p.getOrElse(7) { "" }.trim(),
                upstreamRemote = p.getOrElse(8) { "" }.trim(),
                pushRemote = p.getOrElse(9) { "" }.trim(),
                push = p.getOrElse(10) { "" }.trim(),
            )
        }.filter { it.name.isNotEmpty() && !it.name.startsWith("gitbutler/") }.toList() // ignore GitButler's virtual branches
        if (rows.isEmpty()) return@withContext emptyList()

        val names = rows.map { it.name }.toSet()
        val mainline = listOf("main", "master").firstOrNull { it in names }
            ?: rows.firstOrNull { it.isCurrent }?.name ?: rows.first().name
        val nowSecs = System.currentTimeMillis() / 1000
        // Trees that detached from their branch without leaving it — see Branch.detachedWorktreePath.
        val detachedTrees =
            if (WorktreeOps.mayHaveOtherWorktrees(dir)) WorktreeOps.list(repoPath).filter { it.detached && !it.isCurrent }
            else emptyList()
        // The mainline as it stands on each remote, for offering to pull it from a different one.
        val mainlineOnRemotes = Git.read(dir, "for-each-ref", "--format=%(refname:short)", "refs/remotes/*/$mainline")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        rows.map { r ->
            val isMainline = r.name == mainline
            val behind = if (isMainline) 0 else count(dir, "${r.name}..$mainline")
            val ahead = if (isMainline) 0 else count(dir, "$mainline..${r.name}")
            // merged = every commit on the branch is already in mainline (ancestor), excluding mainline itself
            val contained = !isMainline && Git.exitCode(dir, "merge-base", "--is-ancestor", r.name, mainline) == 0
            // Not in mainline's history, yet its changes are: squash- or rebase-merged.
            val squashed = !isMainline && !contained && landedByContent(dir, r.name, mainline)
            val merged = squashed || (contained && hasOwnWork(dir, r.name, mainline))
            val ageDays = (nowSecs - r.epoch) / 86_400
            val stale = !r.isCurrent && !isMainline && ageDays > Meta.STALE_DAYS && behind > Meta.VERY_BEHIND
            val (uAhead, uBehind, gone) = parseTrack(r.track)
            val push = pushSide(dir, r.name, r.upstream, r.upstreamRemote, r.pushRemote, r.push)
            Branch(
                r.name, r.isCurrent, isMainline, behind, ahead, merged, stale, r.relative,
                upstream = r.upstream, upstreamAhead = uAhead, upstreamBehind = uBehind, upstreamGone = gone,
                worktreePath = r.worktree,
                detachedWorktreePath = detachedTrees
                    .firstOrNull { it.head == r.sha && WorktreeOps.namedFor(it.name, r.name) }?.path,
                empty = contained && !merged,
                squashed = squashed,
                pushRef = push?.ref, pushAhead = push?.ahead ?: 0, pushBehind = push?.behind ?: 0,
                pushMissing = push?.missing ?: false,
                pullCandidates = if (isMainline) pullCandidates(mainlineOnRemotes, r.upstream) else emptyList(),
            )
        }
    }

    /**
     * Where `git push` would send [branch], when that's somewhere other than its [upstream] — or null.
     *
     * `%(push)` can't be taken at its word. Under `push.default=simple`, git's default, it resolves to
     * nothing at all once the push remote differs from the upstream's ("cannot resolve 'simple' push
     * to a single destination"), even though `git push` itself goes on to push the branch under its
     * own name. `%(push:remotename)` still resolves, so the ref is rebuilt from it the way the push
     * builds it. A branch with no upstream isn't triangular, whatever `remote.pushDefault` says: it
     * has nothing to pull from, and the ordinary publish covers it.
     */
    internal fun pushSide(
        dir: File, branch: String, upstream: String?, upstreamRemote: String, pushRemote: String, push: String,
    ): PushSide? {
        if (upstream == null || pushRemote.isEmpty()) return null
        val ref = push.ifEmpty { if (pushRemote != upstreamRemote) "$pushRemote/$branch" else "" }
        if (ref.isEmpty() || ref == upstream) return null
        val exists = Git.exitCode(dir, "rev-parse", "--verify", "-q", "refs/remotes/$ref") == 0
        if (!exists) {
            // Nothing there to compare against: count what the remote has nowhere at all.
            return PushSide(ref, count(dir, branch, "--not", "--remotes=$pushRemote"), 0, missing = true)
        }
        val counts = Git.read(dir, "rev-list", "--left-right", "--count", "$branch...refs/remotes/$ref")
            .trim().split(Regex("\\s+")).map { it.toIntOrNull() ?: 0 }
        return PushSide(ref, counts.getOrElse(0) { 0 }, counts.getOrElse(1) { 0 }, missing = false)
    }

    internal data class PushSide(val ref: String, val ahead: Int, val behind: Int, val missing: Boolean)

    /**
     * The mainline on each remote other than the one [upstream] is on — what it could pull from
     * instead. A remote called `upstream` goes first, being the usual name for the project a fork
     * came from; that ordering is the only thing any name is given.
     */
    internal fun pullCandidates(onRemotes: List<String>, upstream: String?): List<String> {
        val current = upstream?.substringBefore('/')
        return onRemotes
            .filter { it.contains('/') && !it.endsWith("/HEAD") && it.substringBefore('/') != current }
            .sortedWith(compareBy({ it.substringBefore('/') != "upstream" }, { it }))
    }

    /**
     * Whether [branch] — already known to be an ancestor of [mainline] — ever carried commits of its
     * own, which is the difference between "merged" and "nothing was ever done here".
     *
     * `merge-base --is-ancestor` can't tell them apart: a branch cut from main and never committed to
     * is contained in main just as surely as one whose work landed. Two things can:
     *  - Where the tip sits. Work that came in through a merge commit hangs off mainline's
     *    *first-parent* line rather than lying on it, so a tip that isn't on that line had commits.
     *  - The branch's reflog, for a tip that is on it. That is either an untouched branch or a
     *    fast-forward merge, and only the second ever recorded a commit. Reflogs expire, so a
     *    fast-forwarded branch left alone for months eventually reads as empty — the answer git can
     *    still support by then, and either way one that says deleting it loses nothing.
     */
    internal fun hasOwnWork(dir: File, branch: String, mainline: String): Boolean {
        val tip = Git.read(dir, "rev-parse", "--verify", "-q", branch).trim()
        // The oldest commit on mainline's first-parent line that the branch doesn't have: its parent
        // on that line is the branch tip exactly when the tip lies on the line.
        val oldest = Git.read(dir, "rev-list", "--first-parent", "--parents", "$branch..$mainline")
            .lineSequence().lastOrNull { it.isNotBlank() }
        val onFirstParentLine = oldest == null || oldest.split(' ').getOrNull(1) == tip
        if (!onFirstParentLine) return true
        return Git.read(dir, "reflog", "show", "--format=%gs", "refs/heads/$branch").lineSequence()
            .any { it.isNotBlank() && !it.startsWith("branch:") && !it.startsWith("reset:") }
    }

    /**
     * Whether [branch]'s changes are already in [mainline] by content, though not by history — what a
     * squash or rebase merge leaves behind. git's own "merged" is ancestry, and a squash writes a new
     * commit, so the branch's commits never reach mainline and git calls it unmerged forever.
     *
     * First: does mainline hold every file the branch changed exactly as the branch has it? That
     * covers squash, rebase and cherry-pick alike, as long as mainline hasn't touched those files
     * since. Once it has, the question becomes: is there a commit on mainline whose diff is the
     * branch's whole diff — the squash commit itself — compared the way `git patch-id` does,
     * ignoring line numbers and whitespace. A squash the maintainer edited on the way in matches
     * neither, and nor does a rebase merge whose files mainline has edited since.
     *
     * Strictly read-only, because this runs on every scan and git reads never touch `.git`. It used
     * `merge-tree --write-tree`, which writes the merge result into the object store and, for objects
     * already there, touches their mtime — and the repo's watch covers `.git`, so every scan set off
     * the next one, about once a second for as long as the app was open.
     *
     * Callers only ask for a branch that isn't an ancestor of mainline; that case is [hasOwnWork]'s.
     */
    internal fun landedByContent(dir: File, branch: String, mainline: String): Boolean {
        val base = Git.read(dir, "merge-base", mainline, branch).trim()
        if (base.isEmpty()) return false
        val files = Git.read(dir, "diff", "--no-renames", "--name-only", base, branch)
            .lines().filter { it.isNotBlank() }
        if (files.isEmpty()) return false
        val paths = files.toTypedArray()
        // `diff --quiet` exits 0 when the two agree on every one of those paths, 1 when they don't.
        if (Git.exitCode(dir, "diff", "--quiet", "--no-renames", "--no-ext-diff", mainline, branch, "--", *paths) == 0) {
            return true
        }
        val want = patchKey(Git.read(dir, "diff", *DIFF_OPTS, base, branch))
        // Only mainline commits that touched those files, and only their diff of those files: a
        // squash commit carrying the branch plus a fix elsewhere still matches.
        return Git.read(dir, "log", "-p", *DIFF_OPTS, "--no-merges", "--format=%x00", "$base..$mainline", "--", *paths)
            .split('\u0000').any { it.isNotBlank() && patchKey(it) == want }
    }

    private val DIFF_OPTS = arrayOf("--no-renames", "--no-color", "--no-ext-diff")

    /** A diff reduced the way `git patch-id` reduces it: no blob ids, line numbers or whitespace. */
    private fun patchKey(diff: String): String = diff.lineSequence()
        .filter { it.isNotEmpty() && !it.startsWith("index ") && !it.startsWith("@@") }
        .map { it.filterNot(Char::isWhitespace) }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    /** Remote branches (those under `refs/remotes`), newest first, each with its tip author. */
    suspend fun loadRemotes(repoPath: String, localNames: Set<String>): List<RemoteBranch> =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            if (!Git.isRepo(dir)) return@withContext emptyList()
            // Mainline to test "merged" against: prefer local main/master, else the remote's.
            val mainline = listOf("main", "master", "origin/main", "origin/master")
                .firstOrNull { Git.read(dir, "rev-parse", "--verify", "-q", it).isNotBlank() }
            val raw = Git.read(dir, "for-each-ref", "--sort=-committerdate",
                "--format=%(refname:short)$SEP%(committerdate:relative)$SEP%(authorname)", "refs/remotes")
            raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split(SEP)
                val name = p.getOrElse(0) { "" }
                // Skip the remote HEAD symref. Git shortens `refs/remotes/origin/HEAD` to just the
                // remote name ("origin"), so a no-slash entry is the symref, not a real branch;
                // some git versions instead keep it as "origin/HEAD".
                if (name.isEmpty() || name.endsWith("/HEAD") || !name.contains('/')) return@mapNotNull null
                val short = name.substringAfter('/') // drop the remote name prefix
                // The mainline itself, on whichever remote, is never "merged" into itself.
                val against = mainline?.takeIf { it.substringAfter('/') != short }
                val contained = against != null && Git.exitCode(dir, "merge-base", "--is-ancestor", name, against) == 0
                val squashed = against != null && !contained && landedByContent(dir, name, against)
                RemoteBranch(
                    name = name,
                    shortName = short,
                    author = p.getOrElse(2) { "" }.ifBlank { "—" },
                    lastRelative = p.getOrElse(1) { "" },
                    hasLocal = short in localNames,
                    merged = contained || squashed,
                    squashed = squashed,
                )
            }.toList()
        }

    /** Check out an existing local branch. Fails (not throws) if the working tree would be clobbered. */
    suspend fun switch(repoPath: String, name: String): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val res = Git.run(dir, listOf("switch", name))
        if (res.ok) OpResult(repoPath, true, "Switched to $name")
        else OpResult(repoPath, false, res.firstError("switch failed"))
    }

    /** Create a local branch tracking [remoteName] (e.g. "origin/feature/login") and switch to it.
     *  If a local branch of the same short name already exists, just switches to it. */
    suspend fun checkoutRemote(repoPath: String, remoteName: String, hasLocal: Boolean): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val short = remoteName.substringAfter('/')
            val res =
                if (hasLocal) Git.run(dir, listOf("switch", short))
                else Git.run(dir, listOf("switch", "-c", short, "--track", remoteName))
            if (res.ok) OpResult(repoPath, true, "Checked out $short")
            else OpResult(repoPath, false, res.firstError("checkout failed"))
        }

    /**
     * Send a local branch to the remote. A null [upstream] means the branch isn't tracking
     * anything yet, so this publishes it — `push -u <remote> <name>`, to the remote [publishRemote]
     * picks — the same operation the repo-level Push does for the current branch, spelled out here
     * for a branch that may not be checked out. That's safe: pushing a ref reads it, so the working
     * tree is never involved and a branch held by another worktree pushes fine.
     *
     * With an upstream we push to exactly the ref the branch tracks, as an explicit refspec: a
     * branch may track a remote branch of a different name, and `push <remote> <name>` would
     * quietly create a second one beside it. Never `--force` — a diverged branch is refused by
     * git rather than overwritten, which is why the caller doesn't offer this on one.
     *
     * A [pushRef] (see [Branch.pushRef]) wins over the upstream: a fork's branch pulls from one
     * remote and is pushed to another. That push never takes `-u`, even when it creates the ref —
     * `-u` would re-point the upstream at the fork and quietly undo the setup.
     */
    suspend fun push(repoPath: String, name: String, upstream: String?, pushRef: String? = null): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val target = pushRef ?: upstream
            val args =
                if (target == null) listOf("push", "-u", publishRemote(dir), name)
                else listOf("push", target.substringBefore('/'), "refs/heads/$name:refs/heads/${target.substringAfter('/')}")
            val res = Git.run(dir, args, Git.NETWORK_TIMEOUT)
            if (res.ok) {
                OpResult(repoPath, true, if (target == null) "Published $name" else "Pushed $name to $target")
            } else {
                OpResult(repoPath, false, res.firstError("push failed"))
            }
        }

    /**
     * The remote a branch with no upstream is published to: `remote.pushDefault` when it's set —
     * a fork's own remote, whatever it's called — and `origin` otherwise.
     */
    internal fun publishRemote(dir: File): String =
        Git.readOrNull(dir, "config", "--get", "remote.pushDefault")?.trim()?.takeIf { it.isNotEmpty() } ?: "origin"

    /**
     * Make [branch] pull from [candidate] (e.g. "upstream/master") while still pushing where it does
     * now — git's own fork setup, so a terminal `git pull` / `git push` agree with the app.
     *
     * `remote.pushDefault` goes first and only when it's unset: pinned to the remote the branch
     * tracks today, it keeps pushes going there once the upstream moves. Set in that order, a
     * failure of the second step leaves pushing exactly as it was. An existing `pushDefault` is the
     * user's own choice and left alone.
     */
    suspend fun trackUpstream(repoPath: String, branch: String, candidate: String): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val current = Git.readOrNull(dir, "config", "--get", "branch.$branch.remote")?.trim().orEmpty()
            val pushDefault = Git.readOrNull(dir, "config", "--get", "remote.pushDefault")?.trim().orEmpty()
            if (pushDefault.isEmpty() && current.isNotEmpty() && current != ".") {
                val set = Git.run(dir, listOf("config", "remote.pushDefault", current))
                if (!set.ok) return@withContext OpResult(repoPath, false, set.firstError("config failed"))
            }
            val res = Git.run(dir, listOf("branch", "--set-upstream-to=$candidate", branch))
            if (res.ok) OpResult(repoPath, true, "$branch now tracks $candidate")
            else OpResult(repoPath, false, res.firstError("set upstream failed"))
        }

    /**
     * Bring a fork's branch up to date with its upstream and pass that on to its push target, in one
     * go — `merge --ff-only @{u}` then `git push`, without the branch having to be checked out.
     *
     * The fast-forward is the half that can't be done to a branch that isn't checked out by merging,
     * so for one that isn't, `fetch . <upstream>:<branch>` moves the ref instead: git refuses it
     * unless it's a fast-forward, and refuses it for a branch checked out in any working tree. Only
     * offered where the branch has no commits of its own against its upstream, so neither step ever
     * needs a force.
     */
    suspend fun sync(repoPath: String, b: Branch): OpResult {
        val upstream = b.upstream ?: return OpResult(repoPath, false, "${b.name} has no upstream")
        val pushRef = b.pushRef ?: return OpResult(repoPath, false, "${b.name} pushes to its upstream")
        return sync(repoPath, b.name, upstream, pushRef, b.isCurrent, b.upstreamBehind > 0)
    }

    /** [sync] for a branch known by name — the repo's current one, from the scan rather than the branch list. */
    suspend fun sync(
        repoPath: String, name: String, upstream: String, pushRef: String, isCurrent: Boolean, behind: Boolean,
    ): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        if (behind) {
            val ff =
                if (isCurrent) Git.run(dir, listOf("merge", "--ff-only", "refs/remotes/$upstream"))
                else Git.run(dir, listOf("fetch", ".", "refs/remotes/$upstream:refs/heads/$name"))
            if (!ff.ok) return@withContext OpResult(repoPath, false, ff.firstError("fast-forward failed"))
        }
        val pushed = Git.run(
            dir,
            listOf("push", pushRef.substringBefore('/'), "refs/heads/$name:refs/heads/${pushRef.substringAfter('/')}"),
            Git.NETWORK_TIMEOUT,
        )
        if (pushed.ok) OpResult(repoPath, true, "Synced $name from $upstream to $pushRef")
        else OpResult(repoPath, false, "Updated $name, but not $pushRef: ${pushed.firstError("push failed")}")
    }

    /** Delete a local branch. [force] uses -D (also deletes unmerged). Never the current branch. */
    suspend fun delete(repoPath: String, name: String, force: Boolean): OpResult = withContext(Dispatchers.IO) {
        val dir = File(repoPath)
        val flag = if (force) "-D" else "-d"
        val res = Git.run(dir, listOf("branch", flag, name))
        if (res.ok) OpResult(repoPath, true, "Deleted $name")
        else OpResult(repoPath, false, res.firstError("delete failed"))
    }

    /**
     * Delete a branch *on the remote* — `git push <remote> --delete refs/heads/<branch>`. Unlike
     * the local delete this reaches other people's clones, and git has no undo for it: the ref is
     * gone from the server, and only whoever still holds the commits can put it back.
     *
     * The full `refs/heads/` form rather than the bare name: `--delete foo` asks the remote to
     * resolve "foo", which is ambiguous when a tag of the same name is also there, and git refuses
     * rather than guessing. Spelling out the namespace deletes the branch and never the tag.
     *
     * There's no force flag to mirror — deleting a ref doesn't merge anything, so git accepts it
     * whether or not the branch landed. That's exactly why the caller confirms an unmerged one.
     * The push also prunes the local `refs/remotes/...` copy, so the list is right after a reload.
     */
    suspend fun deleteRemote(repoPath: String, rb: RemoteBranch): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val args = listOf("push", rb.remote, "--delete", "refs/heads/${rb.shortName}")
            val res = Git.run(dir, args, Git.NETWORK_TIMEOUT)
            if (res.ok) OpResult(repoPath, true, "Deleted ${rb.name}")
            else OpResult(repoPath, false, res.firstError("remote delete failed"))
        }

    /**
     * Delete a local branch *and* the remote branch it tracks, for a branch whose work has landed
     * — the clean-up after a merged PR, which otherwise takes two deletes in two lists.
     *
     * The remote goes first, because it's the half that can fail (network, permissions, a
     * protected branch): if it does, the local branch is left alone and the whole thing can just be
     * tried again. Deleting local first would strand the remote branch with nothing on this side
     * left to offer the retry from.
     *
     * The remote delete carries a lease on the tip we last fetched. The caller only offers this
     * when that tip had nothing the local branch lacks, but the fetch may be old — if someone has
     * pushed to the branch since, git refuses the delete instead of throwing their commits away.
     */
    suspend fun deleteWithUpstream(repoPath: String, name: String, upstream: String): OpResult =
        withContext(Dispatchers.IO) {
            val dir = File(repoPath)
            val pushed = deleteUpstreamLeased(dir, upstream)
            if (!pushed.ok) return@withContext OpResult(repoPath, false, pushed.firstError("remote delete failed"))
            val local = Git.run(dir, listOf("branch", "-D", name))
            if (local.ok) OpResult(repoPath, true, "Deleted $name and $upstream")
            else OpResult(repoPath, false, "Deleted $upstream, but not $name: ${local.firstError("delete failed")}")
        }

    /**
     * `git push <remote> --delete refs/heads/<branch>` for [upstream] (e.g. "origin/foo"), leased on
     * the tip last fetched into `refs/remotes/<upstream>`: a branch someone pushed to since is
     * refused rather than deleted. Shared by [deleteWithUpstream] and the worktree "Remove + branch".
     *
     * A branch the remote has already deleted — GitHub does, on merging a PR — fails the lease too
     * ("stale info"): fetches don't prune, so the local copy still names a tip the remote no longer
     * has. That is the outcome asked for, not a conflict, so when `ls-remote` confirms the branch is
     * gone the stale `refs/remotes/` copy is dropped (only if it's still the tip leased on) and it
     * counts as deleted. A branch that is there at another tip stays refused.
     */
    internal fun deleteUpstreamLeased(dir: File, upstream: String): Git.Result {
        val remote = upstream.substringBefore('/')
        val ref = "refs/heads/${upstream.substringAfter('/')}"
        val seen = Git.read(dir, "rev-parse", "--verify", "-q", "refs/remotes/$upstream").trim()
        val lease = if (seen.isNotEmpty()) "--force-with-lease=$ref:$seen" else "--force-with-lease"
        val pushed = Git.run(dir, listOf("push", lease, remote, "--delete", ref), Git.NETWORK_TIMEOUT)
        if (pushed.ok) return pushed
        // --exit-code: 2 means the remote answered and has no such ref; anything else proves nothing.
        val gone = Git.exitCode(dir, "ls-remote", "--exit-code", remote, ref, timeoutSeconds = Git.NETWORK_TIMEOUT) == 2
        if (!gone) return pushed
        if (seen.isNotEmpty()) Git.run(dir, listOf("update-ref", "-d", "refs/remotes/$upstream", seen))
        return Git.Result(0, "$upstream was already deleted on $remote", "")
    }

    /**
     * The remote copy of local [branch] when deleting it on the remote loses nothing *beyond* the
     * local branch: it still exists, has no commits [branch] lacks (so if [branch] is merged, so is
     * it), and isn't a shared integration branch. Null otherwise, and for a branch with no upstream.
     *
     * The remote copy is where the branch is pushed, which is its upstream unless it's a fork's
     * branch pulling from elsewhere (see [pushSide]). Then it's the push ref — the branch's own copy
     * on the fork — and never the upstream, which belongs to someone else.
     */
    internal fun deletableUpstream(dir: File, branch: String): String? {
        val p = Git.read(
            dir, "for-each-ref",
            "--format=%(upstream:short)$SEP%(upstream:track)$SEP%(upstream:remotename)$SEP%(push:remotename)$SEP%(push:short)",
            "refs/heads/$branch",
        ).trim().split(SEP)
        val upstream = p.getOrElse(0) { "" }
        if (upstream.isEmpty() || !upstream.contains('/')) return null
        val push = pushSide(dir, branch, upstream, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.getOrElse(4) { "" })
        val (target, behind, gone) =
            if (push != null) Triple(push.ref, push.behind, push.missing)
            else parseTrack(p.getOrElse(1) { "" }).let { (_, behind, gone) -> Triple(upstream, behind, gone) }
        if (gone || behind > 0 || Meta.isIntegrationBranch(target.substringAfter('/'))) return null
        return target
    }

    /** Parse git's `%(upstream:track)` — "[ahead 2, behind 1]", "[gone]", or "". */
    private fun parseTrack(track: String): Triple<Int, Int, Boolean> {
        if ("gone" in track) return Triple(0, 0, true)
        val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Triple(ahead, behind, false)
    }

    private data class Row(
        val name: String,
        val epoch: Long,
        val isCurrent: Boolean,
        val relative: String,
        val upstream: String?,
        val track: String,
        val worktree: String? = null,
        val sha: String = "",
        val upstreamRemote: String = "",
        val pushRemote: String = "",
        val push: String = "",
    )

    private fun count(dir: File, vararg range: String): Int =
        Git.read(dir, "rev-list", "--count", *range).trim().toIntOrNull() ?: 0
}

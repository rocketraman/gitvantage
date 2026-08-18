// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.git.model.Branch
import com.gitvantage.git.model.BranchStatus
import com.gitvantage.git.model.StatusResult
import com.gitvantage.model.ChangedFile
import com.gitvantage.model.DETACHED_BRANCH
import com.gitvantage.model.Meta
import com.gitvantage.model.Perf
import com.gitvantage.model.RegistryEntry
import com.gitvantage.model.Repo
import com.gitvantage.model.Stash
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans a registered repo into a [Repo]. Every command goes through [Git]; all process work
 * runs on [Dispatchers.IO] so the UI thread is never blocked (README § State
 * Management: "shell out to git … ideally off the UI thread").
 *
 * Git surface used: `git fetch` (optional — the one mutating command here, so the only one
 * that can be logged), `symbolic-ref`, `rev-list` (ahead/behind), `status --porcelain`,
 * `stash list`, `log -1`. The reads run on every poll, which is why they stay out of [GitLog].
 */
object RepoScanner {

    private const val SEP = "\u001F" // %x1f delimiter between log fields

    /**
     * Scan one repo. [fetch] adds a `git fetch` first; [userInitiated] says whether that fetch
     * came from something the user did.
     *
     * Only the trigger can answer that, which is why it's a parameter rather than a property of
     * the command: the identical `git fetch` is worth a console entry when it came from the
     * Refresh button, and is pure noise when it came from the 5-minute timer — which fires per
     * repo, forever, and would otherwise evict every real entry from a 500-deep console within
     * a couple of idle hours.
     */
    suspend fun scan(entry: RegistryEntry, fetch: Boolean, userInitiated: Boolean = true): Repo =
        withContext(Dispatchers.IO) { Perf.timed("scan.repo") { scanNow(entry, fetch, userInitiated) } }

    private fun scanNow(entry: RegistryEntry, fetch: Boolean, userInitiated: Boolean): Repo {
        val dir = File(entry.path)
        val name = dir.name.ifEmpty { entry.path }
        val id = entry.path
        val baseTags = entry.tags
        val note = entry.note.ifEmpty { null }
        val now = System.currentTimeMillis()
        val (snoozed, snoozedFor) = Meta.snoozeState(entry, now)
        val reminder = Meta.reminder(entry, now)

        if (!Git.isRepo(dir)) {
            return Repo(
                id = id, name = name, branch = "—", tags = baseTags,
                isGitRepo = false, warning = "Not a git repo", note = note,
                snoozed = snoozed, snoozedFor = snoozedFor, reminder = reminder,
            )
        }

        // Best-effort: ignore offline/errors. Logged only when the user asked for it (see [scan]).
        if (fetch) Git.run(dir, listOf("fetch"), Git.NETWORK_TIMEOUT, log = userInitiated, repoName = name)

        // Whether the repo has ANY remote configured, and origin's URL for the web links. Gates
        // Push/Fetch/Fast-forward: a branch can be pushable (via `push -u origin`) even without a
        // tracking upstream, as long as a remote exists.
        val (hasRemote, remoteUrl) = parseRemotes(Git.readOrNull(dir, "remote", "-v").orEmpty())
        val (webBase, isGitHub) = Meta.webBase(remoteUrl)
        val hasWorkflows = File(dir, ".github/workflows").isDirectory

        // One command for the working tree *and* the branch it sits on — see [parseBranch] for what
        // that folds in and why it is worth folding.
        val statusOut = Git.readOrNull(dir, "status", "--porcelain", "--branch").orEmpty()
        val status = parseStatus(statusOut)
        val head = parseBranch(statusOut)
        val symbolic = head?.branch.orEmpty()
        val detached = symbolic.isEmpty()
        val branch = if (detached) DETACHED_BRANCH else symbolic

        // Upstream + ahead/behind come with the header when one is configured. Only the fallback
        // still costs anything: a branch pushed but never `-u`-tracked has no upstream to report,
        // and origin/<branch> is the one git would have used had it been asked.
        var upstream = head?.upstream
        var ahead = head?.ahead ?: 0
        var behind = head?.behind ?: 0
        if (upstream == null && !detached) {
            val implied = "origin/$symbolic"
            if (Git.readOrNull(dir, "rev-parse", "--verify", "-q", implied) != null) {
                upstream = implied
                Git.readOrNull(dir, "rev-list", "--left-right", "--count", "HEAD...$implied")?.trim()?.let { line ->
                    val parts = line.split(Regex("\\s+"))
                    ahead = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    behind = parts.getOrNull(1)?.toIntOrNull() ?: 0
                }
            }
        }

        val warning = when {
            detached -> "Detached HEAD"
            upstream == null -> "No upstream"
            else -> null
        }
        // "Dirty for how long": the registry records when the tree first went dirty; keep it only
        // while still dirty (AppState reconciles/persists the timestamp after each scan).
        val dirtyNow = status.staged + status.unstaged + status.untracked > 0
        val dirtySince = entry.dirtySinceEpoch?.takeIf { dirtyNow }
        val dirtyForLabel = dirtySince?.let { Meta.compactDuration(now - it) }
        // Newest working-tree change, for the "Recently Modified" filter. Uses the mtime of the
        // currently-changed files rather than "when it first went dirty" (dirtySince), so a repo
        // you're actively editing stays "recently modified" even if the tree went dirty days ago.
        //
        // One `stat` per distinct path, where this used to cost two per *entry*. `exists()` was the
        // first of them and bought nothing: `lastModified()` already answers 0 for a file that isn't
        // there, which is the same "drop it from the max" the filter below performs — a deleted path
        // simply has no mtime to contribute. And a file that is both staged and modified appears
        // once per section, so it was being stat'd twice over on top of that.
        //
        // Bounded by [MAX_FILES] along with the list itself. Above that the answer is the newest of
        // the files examined, which can only under-report how recently the repo was touched — and a
        // repo with five hundred changed files is not one the "recently modified" filter is telling
        // anybody anything they don't know.
        val modifiedAt = status.files.asSequence()
            .map { it.path }
            .distinct()
            .map { File(dir, it).lastModified() }
            .filter { it > 0 }
            .maxOrNull()

        val stashes = Git.readOrNull(dir, "stash", "list").orEmpty()
            .lineSequence().filter { it.isNotBlank() }
            .map { line ->
                // "stash@{0}: WIP on main: 1a2b3c message"
                val label = line.substringBefore(":").trim()
                val rest = line.substringAfter(":", "").trim()
                Stash(label.ifEmpty { "stash" }, rest.ifEmpty { line })
            }.toList()

        // Working trees sharing this repository. One cheap call when there are none but this one;
        // when there are others it also looks inside them, because work sitting in another
        // checkout is invisible from here and the row has no other way to learn of it. The rest of
        // the per-worktree detail (last commits, merged-ness) stays lazy — see WorktreeOps.load.
        // Skipped entirely when the filesystem already says there is nothing to find — see
        // [WorktreeOps.mayHaveOtherWorktrees]. An empty list then means "this checkout, and nothing
        // else", which is exactly what every derivation below reads out of it anyway.
        val worktrees = if (WorktreeOps.mayHaveOtherWorktrees(dir)) WorktreeOps.listWithWork(id) else emptyList()
        val currentTree = worktrees.firstOrNull { it.isCurrent }
        val isWorktree = currentTree != null && !currentTree.isMain
        val worktreeMain = if (isWorktree) worktrees.firstOrNull { it.isMain }?.path else null
        // The other working trees that sit *inside* this one's folder. Only these are ambiguous to
        // the filesystem watcher: a worktree elsewhere on disk is outside the watch root and can't
        // be mistaken for this repo changing, while one under `.claude/worktrees/` is gitignored —
        // invisible to every git command here — yet fully visible to a recursive watch.
        // Stored canonical, because that is the spelling they get compared against: the watcher is
        // handed each repo's real path and echoes event paths back under it, so a worktree reached
        // through a symlink would otherwise never match and its churn would count as this repo's.
        val nestedWorktrees = worktrees
            .filter { !it.isCurrent && !it.bare }
            .filter { isUnder(dir, it.path) }
            .map { runCatching { File(it.path).canonicalPath }.getOrDefault(it.path) }

        // A linked worktree's folder is named for the branch it holds, and a branch name alone says
        // nothing about which project it belongs to — "fix-login" sitting in the repo list is a
        // guess. Tracked worktrees are shown as "<main checkout>/<folder>", which both supplies the
        // missing context and sorts them next to the repo they were added from. A bare main repo
        // loses its ".git" suffix — "gitvantage.git/fix-login" reads like a path, not a name — and a
        // worktree folder that already matches its parent isn't doubled up.
        val displayName = worktreeMain
            ?.let { File(it).name.removeSuffix(".git") }
            ?.takeIf { it.isNotEmpty() && it != name }
            ?.let { "$it/$name" }
            ?: name

        val log = Git.readOrNull(dir, "log", "-1", "--format=%cr%x1f%an%x1f%ct")?.trim().orEmpty()
        val logParts = log.split(SEP)
        val lastRel = logParts.getOrNull(0)?.trim().orEmpty()
        val author = logParts.getOrNull(1)?.trim().orEmpty()
        val commitEpoch = logParts.getOrNull(2)?.trim()?.toLongOrNull()
        // Repo is "stale" when it hasn't been committed to in a long time. The threshold is the
        // per-repo override (staleThresholdDays) or the global default (Meta.STALE_DAYS).
        // Meta.STALE_NEVER opts the repo out entirely, however old its last commit is.
        val staleDays = entry.staleThresholdDays ?: Meta.STALE_DAYS.toInt()
        val isStale = staleDays != Meta.STALE_NEVER &&
            commitEpoch != null && (now / 1000 - commitEpoch) > staleDays.toLong() * 86_400

        return Repo(
            id = id, name = displayName, branch = branch, tags = baseTags,
            ahead = ahead, behind = behind,
            staged = status.staged, unstaged = status.unstaged, untracked = status.untracked,
            stash = stashes.size,
            last = lastRel.ifEmpty { "—" }, lastCommitEpoch = commitEpoch, author = author.ifEmpty { "—" },
            dirtyFor = dirtyForLabel, dirtySince = dirtySince, modifiedAt = modifiedAt,
            upstream = upstream, hasRemote = hasRemote,
            webBase = webBase, isGitHub = isGitHub, hasWorkflows = hasWorkflows,
            hasSubmodules = File(dir, ".gitmodules").exists(),
            superproject = Git.readOrNull(dir, "rev-parse", "--show-superproject-working-tree")
                ?.trim()?.takeIf { it.isNotEmpty() },
            isWorktree = isWorktree,
            worktreeMain = worktreeMain,
            // The list the row's sub-rows, the card's strip and the pane's cards all render. This
            // checkout is left out on purpose: it is the row they hang under, and a tree that
            // appeared as its own sub-row is exactly the duplication this redesign removes. A bare
            // main repo goes too — there are no files in it to report on.
            worktrees = worktrees.filter { !it.isCurrent && !it.bare },
            nestedWorktrees = nestedWorktrees,
            warning = warning, stale = isStale, staleDays = staleDays,
            staleImportant = entry.staleImportant,
            snoozed = snoozed, snoozedFor = snoozedFor, reminder = reminder, note = note,
            files = status.files, stashes = stashes,
        )
    }

    /**
     * Whether [path] names something inside [root] (and not [root] itself).
     *
     * Both sides are canonicalized first: `git worktree list` reports real paths, while a repo is
     * registered under whatever path the user picked, and on a machine where either crosses a
     * symlink — /tmp and /var on macOS, any checkout the user symlinked — the two spellings of the
     * same directory would otherwise fail to match.
     */
    fun isUnder(root: File, path: String): Boolean {
        fun canon(f: File) = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)
        val r = canon(root)
        val p = canon(File(path))
        return p != r && generateSequence(p.parentFile) { it.parentFile }.any { it == r }
    }

    /**
     * Parse the `## ` header of `git status --porcelain --branch`, or null if there isn't one.
     *
     * This one line answers what `symbolic-ref`, `rev-parse @{upstream}` and `rev-list --count`
     * were three separate subprocesses to ask, and it comes attached to a `status` the scan already
     * runs. On a machine where spawning is cheap that is a tidy-up; on a Mac with an endpoint
     * security agent, where every `exec` is a synchronous callout to a scanner, it is most of the
     * scan's cost.
     *
     * The shapes git actually emits, all of which have to be told apart:
     *
     * ```
     * ## main                                  no upstream configured
     * ## main...origin/main                    tracking, in sync
     * ## main...origin/main [ahead 1]          one side only
     * ## main...origin/main [ahead 1, behind 2]
     * ## main...origin/main [gone]             tracking a branch that no longer exists
     * ## HEAD (no branch)                      detached
     * ## No commits yet on main                a branch with no commits on it yet
     * ```
     *
     * `[gone]` reads as **no upstream**, which is not obvious and is what keeps this faithful to the
     * commands it replaces: `rev-parse @{upstream}` fails outright for a deleted upstream, so the
     * repo has always shown "No upstream" for it. Reporting the dead branch's name instead would be
     * a quieter dashboard and a wronger one.
     *
     * `...` is safe as the separator because git forbids consecutive dots in a ref name, so it can
     * never appear inside either half.
     */
    fun parseBranch(out: String): BranchStatus? {
        val line = out.lineSequence().firstOrNull { it.startsWith("## ") }?.removePrefix("## ") ?: return null
        if (line == "HEAD (no branch)") return BranchStatus(branch = null, upstream = null)
        NO_COMMITS.matchEntire(line)?.let { return BranchStatus(branch = it.groupValues[1], upstream = null) }
        if ("..." !in line) return BranchStatus(branch = line, upstream = null)

        val branch = line.substringBefore("...")
        val rest = line.substringAfter("...")
        val upstream = rest.substringBefore(" [")
        val tracking = rest.substringAfter(" [", "").removeSuffix("]")
        if (tracking == "gone") return BranchStatus(branch = branch, upstream = null)
        return BranchStatus(
            branch = branch,
            upstream = upstream,
            ahead = TRACK.find(tracking, "ahead"),
            behind = TRACK.find(tracking, "behind"),
        )
    }

    private val NO_COMMITS = Regex("No commits yet on (.+)")
    private val TRACK = Regex("(ahead|behind) (\\d+)")

    /** The count [which] reports in a `[ahead 1, behind 2]` clause, or 0 when it isn't mentioned. */
    private fun Regex.find(tracking: String, which: String): Int =
        findAll(tracking).firstOrNull { it.groupValues[1] == which }?.groupValues?.get(2)?.toIntOrNull() ?: 0

    /**
     * Whether `origin` is configured, and its fetch URL, from one `git remote -v`.
     *
     * Replaces a `remote` (is there any?) and a `remote get-url origin` (what is it?) with the one
     * command that answers both. Lines read `origin\t<url> (fetch)`, and the fetch URL is taken
     * because that is the one `get-url` hands back by default.
     */
    fun parseRemotes(out: String): Pair<Boolean, String?> {
        val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
        val origin = lines.firstOrNull { it.startsWith("origin\t") && it.endsWith("(fetch)") }
            ?.substringAfter('\t')?.substringBeforeLast(" (fetch)")?.trim()?.takeIf { it.isNotEmpty() }
        return lines.isNotEmpty() to origin
    }

    /**
     * How many changed files a scan keeps.
     *
     * The counts are always exact — they are what the row's badges and every filter read, and a
     * wrong one is a wrong dashboard. It is the *list* that is bounded, and the list is only ever
     * read to be looked at: the detail panel prints a row per entry, and past a few hundred nobody
     * is reading them, they are just being paid for.
     *
     * Paid for three times over, in fact, which is why the cap sits here rather than at the point of
     * use. Every entry is an object retained in observable state for as long as the repo is tracked;
     * it is a `stat` in [scan]'s newest-change scan; and it is a row composed eagerly, so a repo with
     * tens of thousands of them stops being slow and starts being a hang.
     *
     * Tens of thousands is not far-fetched. `git status` lists untracked files one per line, and
     * whether it descends into an untracked directory at all is decided by the user's *global*
     * `status.showUntrackedFiles` — so a single un-ignored `node_modules` can turn one line into
     * forty thousand, on a setting most people do not remember choosing.
     */
    const val MAX_FILES = 500

    /**
     * Parse `git status --porcelain` (v1). X = index (staged), Y = worktree (unstaged).
     *
     * Counting and collecting are deliberately separate: every line is counted, only the first
     * [maxFiles] are kept. See [MAX_FILES].
     */
    fun parseStatus(out: String, maxFiles: Int = MAX_FILES): StatusResult {
        val files = mutableListOf<ChangedFile>()
        var staged = 0; var unstaged = 0; var untracked = 0
        fun keep(f: ChangedFile) { if (files.size < maxFiles) files += f }
        out.lineSequence().forEach { raw ->
            // The `--branch` header, when the caller asked for one. Left to [parseBranch]; counted
            // here it would read as an index *and* worktree change to a file called "main...".
            if (raw.startsWith("## ")) return@forEach
            if (raw.length < 3) return@forEach
            val x = raw[0]; val y = raw[1]
            var path = raw.substring(3)
            if (" -> " in path) path = path.substringAfter(" -> ")   // rename/copy
            path = path.trim('"')

            if (x == '?' && y == '?') {
                untracked++
                keep(ChangedFile("?", "untracked", path))
                return@forEach
            }
            if (x != ' ' && x != '?') {   // staged (index) change
                staged++
                keep(ChangedFile(x.toString(), "staged", path))
            }
            if (y != ' ' && y != '?') {   // unstaged (worktree) change
                unstaged++
                keep(ChangedFile(y.toString(), "unstaged", path))
            }
        }
        return StatusResult(files, staged, unstaged, untracked)
    }
}

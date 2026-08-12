// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import com.gitvantage.model.WatchPolicy
import de.infix.testBalloon.framework.core.testSuite
import dev.nucleusframework.fswatcher.FsWatchEvent
import dev.nucleusframework.fswatcher.FsWatchers
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What the scan reports about working trees nested inside the repo's own folder.
 *
 * These paths are what lets the filesystem watcher tell "a file in this checkout changed" from "a
 * coding session wrote something in its worktree". Nothing on screen shows them, so a regression
 * here is silent — it comes back as the detail panel going stale while a session runs, which is
 * exactly the bug they were added for.
 */
val NestedWorktreeScanning by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("a worktree inside the repo folder is reported as nested") {
            val work = repo()
            val tree = File(work, ".claude/worktrees/feat")
            git(work, "worktree", "add", "-q", "-b", "claude/feat", tree.path)

            val scanned = RepoScanner.scan(entry(work), fetch = false)

            assert(scanned.nestedWorktrees.size == 1) { "got ${scanned.nestedWorktrees}" }
            assert(File(scanned.nestedWorktrees.single()).canonicalFile == tree.canonicalFile)
        }

        test("a worktree elsewhere on disk is not nested — it can't be confused for this repo") {
            val work = repo()
            val tree = File(root, "wt")
            git(work, "worktree", "add", "-q", "-b", "side", tree.path)

            val scanned = RepoScanner.scan(entry(work), fetch = false)

            assert(scanned.nestedWorktrees.isEmpty()) { "got ${scanned.nestedWorktrees}" }
            // Still a worktree of this repo, just not one under its folder — so it's a sub-row of it.
            assert(scanned.worktrees.size == 1) { "got ${scanned.worktrees.map { it.path }}" }
            assert(scanned.worktrees.single().branch == "side")
        }

        test("the repo never lists itself, so its own files are never filtered out") {
            val work = repo()
            git(work, "worktree", "add", "-q", "-b", "claude/feat", File(work, ".claude/worktrees/feat").path)

            val scanned = RepoScanner.scan(entry(work), fetch = false)

            assert(scanned.nestedWorktrees.none { File(it).canonicalFile == work.canonicalFile })
        }

        test("a repo with no worktrees reports none") {
            val scanned = RepoScanner.scan(entry(repo()), fetch = false)
            assert(scanned.nestedWorktrees.isEmpty())
        }

        test("a nested worktree does not hide the main checkout's own changes") {
            val work = repo()
            // As a real repo has it: the session worktrees are gitignored, which is what makes them
            // invisible to git and visible only to the recursive filesystem watch.
            commit(work, ".gitignore", ".claude/worktrees/\n", "ignore session worktrees")
            git(work, "worktree", "add", "-q", "-b", "claude/feat", File(work, ".claude/worktrees/feat").path)
            dirty(work)
            File(work, "new.txt").writeText("untracked\n")

            val scanned = RepoScanner.scan(entry(work), fetch = false)

            // The reported symptom, asserted at the source: uncommitted work on the main branch has
            // to survive a session's worktree existing, or the panel shows nothing and the Diff
            // button — gated on exactly these counts — goes dead.
            assert(scanned.unstaged == 1) { "got ${scanned.files}" }
            assert(scanned.untracked == 1) { "got ${scanned.files}" }
            assert(scanned.files.any { it.path == "a.txt" && it.section == "unstaged" })
        }
    }
}

/**
 * The whole path, on the real watcher: a write inside a session's worktree must not read as the
 * parent repo changing.
 *
 * The three pieces are unit-tested apart — the scan finds the nested paths, [WatchPolicy] decides
 * what they mean — but the bug lived in the seam between them, and the premise underneath it is
 * something only the real watcher can confirm: that a write in a nested worktree is delivered under
 * the *parent's* registration at all. It is, which is why the parent used to be rescanned for it.
 */
val NestedWorktreeWatching by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("writes inside a session's worktree arrive as the parent's, and are filtered out") {
            if (!runCatching { FsWatchers.isSupported() }.getOrDefault(false)) return@test
            val work = repo()
            commit(work, ".gitignore", ".claude/worktrees/\n", "ignore session worktrees")
            val tree = File(work, ".claude/worktrees/feat")
            git(work, "worktree", "add", "-q", "-b", "claude/feat", tree.path)

            val nested = RepoScanner.scan(entry(work), fetch = false).nestedWorktrees.map(Path::of)
            assert(nested.isNotEmpty())

            val w = FsWatchers.create()
            val events = CopyOnWriteArrayList<FsWatchEvent>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                scope.launch {
                    runCatching { w.events.collect { if (it.source?.name == "MAIN") events += it } }
                }
                Thread.sleep(300)
                // Exactly what AppState registers: one recursive watch on the repo, named for it.
                w.watch(Path.of(work.canonicalPath), true, "MAIN")
                Thread.sleep(500)

                // A coding session at work in its worktree.
                repeat(5) { i -> File(tree, "session-$i.kt").writeText("fun main() {}\n") }
                Thread.sleep(2_000)

                // The premise: these really do arrive under the parent's registration.
                assert(events.isNotEmpty()) { "the watcher delivered nothing for the parent repo" }
                // The fix: and every one of them is judged not to concern the parent, so the
                // debounce that would have been reset by each is never touched.
                val leaked = events.filter { ev ->
                    WatchPolicy.concernsRepo(nested, eventPathsOf(ev))
                }
                assert(leaked.isEmpty()) { "session writes still counted as the repo changing: $leaked" }
            } finally {
                scope.cancel()
                runCatching { w.close() }
            }
        }
    }
}

/** Mirrors AppState's event-to-paths mapping, which is not visible from here. */
private fun eventPathsOf(ev: FsWatchEvent): List<Path> = when (ev) {
    is FsWatchEvent.Created -> listOf(ev.path)
    is FsWatchEvent.Modified -> listOf(ev.path)
    is FsWatchEvent.Removed -> listOf(ev.path)
    is FsWatchEvent.Moved -> listOf(ev.from, ev.to)
    is FsWatchEvent.Other -> ev.paths
    is FsWatchEvent.Overflow -> emptyList()
}

/** [RepoScanner.isUnder], which decides what counts as nested. */
val PathNesting by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("a child path is under its root") {
            assert(RepoScanner.isUnder(root, File(root, "a/b/c").path))
        }

        test("a root is not under itself") {
            assert(!RepoScanner.isUnder(root, root.path))
        }

        test("a sibling sharing a name prefix is not under the root") {
            // The string-prefix trap: "/x/repo-two" starts with "/x/repo" but is not inside it.
            val repoDir = File(root, "repo").apply { mkdirs() }
            val sibling = File(root, "repo-two").apply { mkdirs() }
            assert(!RepoScanner.isUnder(repoDir, sibling.path))
        }

        test("a symlinked spelling of the same tree still resolves as nested") {
            val real = File(root, "real/inner").apply { mkdirs() }
            val link = File(root, "link")
            java.nio.file.Files.createSymbolicLink(link.toPath(), File(root, "real").toPath())
            // Reached through the symlink, but the same directory — canonicalizing is what makes
            // this match, and macOS hands out both spellings routinely (/tmp, /var).
            assert(RepoScanner.isUnder(File(root, "real"), File(link, "inner").path))
            assert(real.isDirectory)
        }
    }
}

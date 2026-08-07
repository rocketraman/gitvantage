// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.git

import de.infix.testBalloon.framework.core.testSuite

/**
 * The console's contract, which is the reason [Git] and [GitLog] were split apart: everything the
 * app *changes* on the user's behalf is recorded, and nothing it merely *reads* is.
 *
 * Both halves matter. A mutation missing from the console is a change the user cannot account for;
 * a read that reaches it buries those changes under polling noise, which is what makes the console
 * worth opening at all.
 *
 * "On the user's behalf" is the part that mutating-or-not cannot decide by itself: `git fetch` is
 * a mutation either way, but it is worth recording when it came from the Refresh button and is
 * pure noise when it came from the five-minute timer. Only the caller knows which, so the trigger
 * travels with the scan and both directions are pinned below.
 */
val GitConsoleRecording by testSuite {
    testFixture { Sandbox() } asContextForEach {

        test("mutating operations are recorded with the command that ran") {
            val work = repo("log-mutations")
            java.io.File(work, "new.txt").writeText("x\n")

            val commands = recordedFor("log-mutations") {
                RepoOps.commit(entry(work), "subject", "", stageAll = true)
                BranchOps.switch(work.path, Sandbox.MAIN)
                WorktreeOps.prune(work.path)
            }

            assert(commands.any { it == "git add -A" })
            assert(commands.any { it.startsWith("git commit -m") })
            assert(commands.any { it == "git switch ${Sandbox.MAIN}" })
            assert(commands.any { it == "git worktree prune -v" })
        }

        test("a failed mutation is recorded too, with its exit code") {
            val work = repo("log-failure")

            recordedFor("log-failure") { BranchOps.switch(work.path, "nope") }

            val entry = GitLog.entries.value.last { it.repo == "log-failure" }
            assert(entry.command == "git switch nope")
            assert(entry.exitCode != 0)
            // The output is kept, which is what makes the console useful after a failure.
            assert(entry.output.isNotBlank())
        }

        test("the recorded command excludes the colour flags the console needs") {
            val work = repo("log-colour")

            val commands = recordedFor("log-colour") { WorktreeOps.prune(work.path) }

            // `-c color.ui=always` is passed to git but is noise in a console listing.
            assert(commands.isNotEmpty())
            assert(commands.none { "color.ui" in it })
        }

        test("read-only inspection is not recorded") {
            val work = repo("log-reads")
            dirty(work)

            val commands = recordedFor("log-reads") {
                BranchOps.load(work.path)
                BranchOps.loadRemotes(work.path, emptySet())
                WorktreeOps.load(work.path)
                SubmoduleOps.load(work.path)
                LogOps.loadRange(work.path, "HEAD")
                DiffOps.load(work.path)
                RepoScanner.scan(entry(work), fetch = false)
            }

            assert(commands.isEmpty())
        }

        test("a scan records its fetch and nothing else") {
            val work = repo("log-scan")

            val commands = recordedFor("log-scan") { RepoScanner.scan(entry(work), fetch = true) }

            // The one mutating command in a scan. Every status/rev-list/log around it stays out,
            // which is what keeps a polling dashboard from filling the console.
            assert(commands == listOf("git fetch"))
        }

        test("a background scan's fetch stays out of the console") {
            val work = repo("log-scan-bg")

            val commands = recordedFor("log-scan-bg") {
                RepoScanner.scan(entry(work), fetch = true, userInitiated = false)
            }

            // The same command as the test above, and the same mutation — only the trigger differs.
            // The auto-refresh fires one of these per repo every five minutes for as long as the app
            // is open; recorded, they are all a 500-deep console would have left by lunchtime.
            assert(commands.isEmpty())
        }

        test("a launched git client is recorded even though its output cannot be") {
            GitLog.recordLaunch("log-launch", listOf("git", "gui"), started = true)

            val entry = GitLog.entries.value.last { it.repo == "log-launch" }
            assert(entry.command == "git gui")
            assert(entry.exitCode == 0)
            // The user commits from inside it, so the launch is the console's only trace of what
            // follows. The missing output is stated rather than left blank, which would read as
            // "git printed nothing".
            assert(entry.output.isNotBlank())
        }

        test("a launched client that is not git is recorded verbatim") {
            GitLog.recordLaunch("log-launch-but", listOf("but", "--path", "/x"), started = false)

            val entry = GitLog.entries.value.last { it.repo == "log-launch-but" }
            // Not captioned "git but --path /x": these launchers are not all git subcommands, and
            // the console must not misreport which program ran.
            assert(entry.command == "but --path /x")
            assert(entry.exitCode != 0)
        }

        test("push checks for an upstream without recording the question") {
            val work = repo("log-push")

            val commands = recordedFor("log-push") { RepoOps.push(entry(work)) }

            // `rev-parse @{upstream}` only asks which form of push to use; it changes nothing.
            assert(commands.none { "rev-parse" in it })
            assert(commands.any { it.startsWith("git push") })
        }
    }
}

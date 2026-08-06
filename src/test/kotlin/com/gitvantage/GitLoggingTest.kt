// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import de.infix.testBalloon.framework.core.testSuite

/**
 * The console's contract, which is the reason [Git] and [GitLog] were split apart: everything the
 * app *changes* is recorded, and nothing it merely *reads* is.
 *
 * Both halves matter. A mutation missing from the console is a change the user cannot account for;
 * a read that reaches it buries those changes under polling noise, which is what makes the console
 * worth opening at all.
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

            val entry = GitLog.entries.last { it.repo == "log-failure" }
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

        test("push checks for an upstream without recording the question") {
            val work = repo("log-push")

            val commands = recordedFor("log-push") { RepoOps.push(entry(work)) }

            // `rev-parse @{upstream}` only asks which form of push to use; it changes nothing.
            assert(commands.none { "rev-parse" in it })
            assert(commands.any { it.startsWith("git push") })
        }
    }
}

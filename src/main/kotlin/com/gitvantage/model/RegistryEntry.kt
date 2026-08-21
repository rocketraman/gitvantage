// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.model

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializes a [java.time.Instant] as an ISO-8601 string (e.g. "2026-07-25T02:24:00Z"),
 *  so timestamps in registry.json stay human-readable and hand-editable. */
object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/**
 * Persisted per-repo registry (README § State Management: "Tag edits and notes must
 * persist (registry file / local store)"). Stored as JSON at
 * `$XDG_CONFIG_HOME/gitvantage/registry.json` (falls back to `~/.config`).
 */
@Serializable
data class RegistryEntry(
    val path: String,
    val tags: List<String> = emptyList(),
    val note: String = "",
    // Per-repo "stale after N days": null = use the global default, Meta.STALE_NEVER = never flag
    // this repo as stale, N = after N days.
    val staleThresholdDays: Int? = null,
    // Escalate staleness from the default informational (amber) signal to an important (red) one,
    // for repos where going quiet really does mean something is wrong.
    val staleImportant: Boolean = false,
    // Whether to poll GitHub for this repo's open issues/PRs: null = follow the global default,
    // false = never (forks you don't triage, mirrors, repos whose issue tracker isn't used),
    // true = always, even if the global default is off.
    val issuesTracked: Boolean? = null,
    // Escalate open issues one level — informational (blue) becomes important (amber), and
    // "awaiting you" (amber) becomes critical (red). For the repos you're actually on the hook for.
    val issuesImportant: Boolean = false,
    // What this repo is to you, which decides how much of its tracker is even fetched:
    // "mine" = one you maintain, so every open issue and PR is fetched and anyone's unanswered
    // comment counts as awaiting you; "contributing" = one you only file into, so only the items
    // you authored are fetched at all. null = infer it from whether the token can push here.
    // Inferred rather than defaulted because the answer is already knowable, and asking someone
    // to hand-classify forty repos before the dashboard is useful is a poor trade.
    val repoRole: String? = null,
    // Labels whose issues and PRs this repo's list leaves out entirely — matched case-insensitively
    // against the label's name. For the categories that are open by design and aren't waiting on
    // you: a tracker where every user request sits open until *they* act would otherwise bury the
    // handful of items that are actually yours to answer. Unlike hideBranchPatterns these are
    // filtered rather than hidden-behind-a-toggle, because the count is the point — an "awaiting
    // you" number you have to mentally discount isn't worth showing.
    val ignoreLabels: List<String> = emptyList(),
    // Regexes for branches this repo's lists hide behind the "Show hidden" toggle: null = use
    // Meta.DEFAULT_HIDE_BRANCH_PATTERNS (bot branches), a list = this repo's own choice. An empty
    // list is a real answer — "hide nothing here" — and is why this is nullable rather than
    // defaulting to the list: the two need to be tellable apart.
    val hideBranchPatterns: List<String>? = null,
    // Per-worktree alert overrides and snoozes, keyed by the worktree's absolute path. Lives on the
    // parent's entry because a worktree has no entry of its own: it is a branch this repository has
    // checked out somewhere else, not a repository. Entries are dropped when they go back to
    // all-inherit (see [WorktreeAlerts.isDefault]), so a repo whose worktrees were never touched
    // stores nothing at all — and a worktree that is removed takes its overrides with it only when
    // the user says so, which is what makes moving one in the list non-destructive.
    val worktreeAlerts: Map<String, WorktreeAlerts> = emptyMap(),
    // Whether this repo's worktree sub-rows are showing in the table view. Persisted per repo
    // because it's a statement about this project ("I'm working across three branches here"), not a
    // transient peek like "Show hidden branches".
    val worktreesExpanded: Boolean = false,
    val snoozeUntilEpoch: Long? = null,
    val reminderText: String = "",
    val reminderDueEpoch: Long? = null,
    val dirtySinceEpoch: Long? = null, // when the working tree first went dirty (for the "Aging" signal)
    val notifyUpstream: Boolean = false, // desktop-notify when this repo's upstream advances (opt-in)
    // Auto-fetch bookkeeping, both epoch millis. Persisted rather than in-memory because
    // [FetchPolicy] schedules in hours and days: rebuilt at every launch, a 24-hour interval would
    // really mean "on every start", which for an app opened several times a day is more traffic
    // than the fixed timer it replaced, not less.
    val lastFetchedEpoch: Long? = null,
    // When a fetch last brought new upstream commits in. Tiering on local activity alone would
    // silence exactly the repo you left a month ago that your team is still pushing to; this is
    // what lets such a repo promote itself back out of the dormant tier. See [FetchPolicy].
    val lastUpstreamAdvanceEpoch: Long? = null,
    // When this repo was first tracked (for the "Recently Added" filter). ISO-8601 in JSON.
    @Serializable(with = InstantIsoSerializer::class)
    val addedAt: Instant? = null,
)

/** App-level UI preferences, persisted alongside the repo list. */
@Serializable
data class Settings(
    val windowWidth: Int = 1480,
    val windowHeight: Int = 860,
    val detailPaneWidth: Int = 466,
    val sortBy: String = "name", // name | commit | attention
    // Appearance: system | light | dark. "system" follows the desktop's own light/dark
    // preference (see ThemeMode / SystemAppearance) and is the default, so a fresh install
    // matches the rest of the session rather than asserting a look of its own.
    val theme: String = "system",
    // How big to draw the UI, as a percentage of the density the display reports. 100 leaves the
    // windowing layer's own answer alone. Hand-editable like the rest of this file, so UiScale
    // clamps whatever lands here rather than trusting it.
    val uiScalePercent: Int = 100,
    // Poll GitHub for open issues/PRs by default (per-repo overrides in RegistryEntry win).
    // Only ever applies to repos with a github.com remote, and only when `gh` is authenticated.
    val githubIssues: Boolean = true,
    // Count only issues/PRs you're involved in — you opened it, you're assigned, your review is
    // requested, or the last comment mentions you. Off = every open issue in the repo counts.
    val githubMineOnly: Boolean = false,
    // Whether the detail pane's Settings disclosure is open. App-wide rather than per repo: it's a
    // statement about how you use the pane, not about any one project, and having it remember per
    // repo would mean the pane's length changed as you arrowed down the list.
    val settingsExpanded: Boolean = false,
)

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
    // Regexes for branches this repo's lists hide behind the "Show hidden" toggle: null = use
    // Meta.DEFAULT_HIDE_BRANCH_PATTERNS (bot branches), a list = this repo's own choice. An empty
    // list is a real answer — "hide nothing here" — and is why this is nullable rather than
    // defaulting to the list: the two need to be tellable apart.
    val hideBranchPatterns: List<String>? = null,
    val snoozeUntilEpoch: Long? = null,
    val reminderText: String = "",
    val reminderDueEpoch: Long? = null,
    val dirtySinceEpoch: Long? = null,   // when the working tree first went dirty (for the "Aging" signal)
    val notifyUpstream: Boolean = false, // desktop-notify when this repo's upstream advances (opt-in)
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
    val sortBy: String = "name",   // name | commit | attention
    // Appearance: system | light | dark. "system" follows the desktop's own light/dark
    // preference (see ThemeMode / SystemAppearance) and is the default, so a fresh install
    // matches the rest of the session rather than asserting a look of its own.
    val theme: String = "system",
    // Poll GitHub for open issues/PRs by default (per-repo overrides in RegistryEntry win).
    // Only ever applies to repos with a github.com remote, and only when `gh` is authenticated.
    val githubIssues: Boolean = true,
    // Count only issues/PRs you're involved in — you opened it, you're assigned, your review is
    // requested, or the last comment mentions you. Off = every open issue in the repo counts.
    val githubMineOnly: Boolean = false,
)

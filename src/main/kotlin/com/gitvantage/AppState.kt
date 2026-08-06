// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import dev.nucleusframework.fswatcher.FsWatchRegistration
import dev.nucleusframework.fswatcher.FsWatcher
import dev.nucleusframework.fswatcher.FsWatchers
import java.io.File
import java.nio.file.Path

/** A transient modal driven by [AppState.popup]; targets the given repo [ids]. */
sealed interface Popup {
    val ids: Set<String>
    data class Tag(override val ids: Set<String>) : Popup
    data class Untag(override val ids: Set<String>) : Popup
    data class Snooze(override val ids: Set<String>) : Popup
    data class Remind(override val ids: Set<String>, val text: String, val due: Long?) : Popup
    data class Commit(val id: String) : Popup { override val ids: Set<String> = setOf(id) }
    /** The appearance picker (Match system / Light / Dark). App-wide, so it owns no repos. */
    data object Appearance : Popup { override val ids: Set<String> = emptySet() }
    data class Confirm(
        val title: String, val message: String, val confirmLabel: String,
        val danger: Boolean, val onConfirm: () -> Unit,
    ) : Popup {
        override val ids: Set<String> = emptySet()
    }
}

/**
 * Marks a repo that's a linked worktree of another. Stored namespaced because [AppState.addTag]
 * normalizes a bare tag the same way, so this is byte-identical to typing "worktree" into "+ Tag"
 * — it filters, groups, and renders as the plain chip "worktree", with no second class of tag.
 */
const val WORKTREE_TAG = "tag:worktree"

/**
 * Observable app state (README § State Management) plus the derived filtering,
 * grouping and count logic. Repos are populated by [RepoScanner] shelling out to
 * `git` off the UI thread; tags/notes are persisted to the [Registry].
 *
 * [scope] must be the composition's UI-dispatched scope (rememberCoroutineScope) so
 * that state writes after a scan land on the recomposition thread — the git work
 * itself is dispatched to [Dispatchers.Default]/IO inside [refreshAll].
 */
class AppState(private val scope: CoroutineScope) {
    // Tweakable design props (reference § "Tweakable design props").
    // A getter, not a stored value: the accent moves when the theme does, and a `val` captured at
    // construction would pin the whole app to whichever palette was active when the window opened.
    val accent: Color get() = Tokens.accent
    val emphasis = Emphasis.MEDIUM
    val showNotes = true

    // Registry-backed source of truth for path order + persisted tags/notes/snooze.
    private val entries = mutableStateMapOf<String, RegistryEntry>()  // path -> entry
    private val order = mutableStateListOf<String>()                  // path, in list order

    /** Git-scanned repos (reactive). Rebuilt by [refreshAll]. */
    val repos = mutableStateListOf<Repo>()

    var scanning by mutableStateOf(false)
        private set
    var lastFetchedEpoch by mutableStateOf(0L)
        private set

    // UI state
    var selectedId by mutableStateOf<String?>(null)
    var view by mutableStateOf(ViewMode.TABLE)
    var status by mutableStateOf("all")
    var groupBy by mutableStateOf("none")
    var sortBy by mutableStateOf(Registry.settings().sortBy)   // name | commit | attention
        private set
    fun setSort(s: String) { sortBy = s; Registry.saveSettings(Registry.settings().copy(sortBy = s)) }
    var tagFilter by mutableStateOf<Set<String>>(emptySet())       // include: repo must carry these
    var tagExclude by mutableStateOf<Set<String>>(emptySet())      // exclude: repo must NOT carry these
    var searchText by mutableStateOf("")
    var addingTag by mutableStateOf(false)

    // "Add repo" step 2 — the multi-select chooser populated from a picked parent folder.
    var chooserOpen by mutableStateOf(false)
        private set
    var chooserParent by mutableStateOf("")
        private set
    var chooserScanning by mutableStateOf(false)
        private set
    val chooserCandidates = mutableStateListOf<RepoCandidate>()
    var chooserSelected by mutableStateOf<Set<String>>(emptySet())
        private set
    var chooserFilter by mutableStateOf("")   // live search over the discovered candidates

    // Bulk multi-select (checkboxes in the main list), transient op feedback, and the
    // active Tag/Snooze/Remind/Confirm modal.
    var bulkSelected by mutableStateOf<Set<String>>(emptySet())
        private set
    var opStatus by mutableStateOf<String?>(null)
        private set
    var popup by mutableStateOf<Popup?>(null)

    // Resizable detail pane (remembered across sessions).
    var detailPaneWidth by mutableStateOf(Registry.settings().detailPaneWidth.toFloat())
        private set
    fun resizeDetailPane(w: Float) { detailPaneWidth = w.coerceIn(360f, 1000f) }
    fun persistDetailPaneWidth() { Registry.saveSettings(Registry.settings().copy(detailPaneWidth = detailPaneWidth.toInt())) }
    fun saveWindowSize(w: Int, h: Int) { Registry.saveSettings(Registry.settings().copy(windowWidth = w, windowHeight = h)) }

    // Branches for the currently-open detail panel (loaded lazily on selection).
    var branchesRepo by mutableStateOf<String?>(null)
        private set
    var branches by mutableStateOf<List<BranchOps.Branch>>(emptyList())
        private set
    var branchesLoading by mutableStateOf(false)
        private set
    var remoteBranches by mutableStateOf<List<BranchOps.RemoteBranch>>(emptyList())
        private set
    var showRemoteBranches by mutableStateOf(false)
        private set
    var switchingBranch by mutableStateOf(false)
        private set
    /** Name of the branch currently being pushed, so its row can say so. One at a time. */
    var pushingBranch by mutableStateOf<String?>(null)
        private set
    /** Full ref of the remote branch currently being deleted (e.g. "origin/foo") — a network round
     *  trip, so the row says "Deleting…" rather than looking like the click missed. */
    var deletingRemoteBranch by mutableStateOf<String?>(null)
        private set

    // Submodules for the currently-open detail panel (loaded lazily on selection).
    var submodulesRepo by mutableStateOf<String?>(null)
        private set
    var submodules by mutableStateOf<List<SubmoduleOps.Submodule>>(emptyList())
        private set
    var submodulesBusy by mutableStateOf(false)
        private set

    // Working trees for the currently-open detail panel (loaded lazily on selection).
    var worktreesRepo by mutableStateOf<String?>(null)
        private set
    var worktrees by mutableStateOf<List<WorktreeOps.Worktree>>(emptyList())
        private set
    var worktreesBusy by mutableStateOf(false)
        private set

    // Diff viewer (GitHub-style overlay).
    var diffOpen by mutableStateOf(false)
        private set
    var consoleOpen by mutableStateOf(false)
        private set
    var consoleHeight by mutableStateOf(260f)   // docked-panel height in dp; drag the top edge to resize
        private set
    fun toggleConsole() { consoleOpen = !consoleOpen }
    fun closeConsole() { consoleOpen = false }
    fun clearConsole() = GitLog.clear()
    fun resizeConsole(deltaDp: Float) { consoleHeight = (consoleHeight + deltaDp).coerceIn(120f, 620f) }
    var diffRepoName by mutableStateOf("")
        private set
    var diffTitle by mutableStateOf("Changes")
        private set
    var diffLoading by mutableStateOf(false)
        private set
    var diff by mutableStateOf(DiffOps.Diff(emptyList(), emptyList()))
        private set

    // Log viewer (commit-list overlay) — shows the commits in an arbitrary revision range.
    var logOpen by mutableStateOf(false)
        private set
    var logRepoName by mutableStateOf("")
        private set
    var logRepoId by mutableStateOf("")           // repo path, so a commit row can open its own diff
        private set
    var logWebBase by mutableStateOf<String?>(null)   // GitHub base, so a commit row can link out
        private set
    var logTitle by mutableStateOf("Log")
        private set
    var logLoading by mutableStateOf(false)
        private set
    var logCommits by mutableStateOf<List<LogOps.Commit>>(emptyList())
        private set

    private val scanLimiter = Semaphore(8)   // cap concurrent git subprocesses
    private var toastJob: Job? = null

    // Open GitHub issues/PRs, keyed by repo id. Deliberately NOT folded into [repos]: those are
    // rebuilt from scratch by RepoScanner on every filesystem event, which would discard network
    // data many times a minute. Kept reactive so a completed fetch recomposes the list.
    private val githubState = mutableStateMapOf<String, GitHub.RepoState>()
    var githubStatus by mutableStateOf<GitHub.Status>(GitHub.Status.Unknown)
        private set
    var githubFetching by mutableStateOf(false)
        private set
    private var githubRerun = false   // a refresh was asked for while one was already running
    var githubFetchedEpoch by mutableStateOf(0L)
        private set
    /** Global default for polling open issues, overridable per repo. Registry-only (like the
     *  global stale threshold): the per-repo control is the one with a UI. */
    var githubEnabled = Registry.settings().githubIssues
        private set
    var githubMineOnly by mutableStateOf(Registry.settings().githubMineOnly)
        private set
    /** Hosts `gh` holds a working login for, from the last probe — see [issuesSupported]. */
    private var githubHosts by mutableStateOf<Set<String>>(emptySet())

    // Notification bookkeeping.
    private val prevBehind = HashMap<String, Int>()    // last-seen "behind" per repo
    private var alertsPrimed = false                   // suppress upstream alerts on the first scan
    private val remindAt = HashMap<String, Long>()     // path -> next time to (re)notify
    private val agingNotified = HashSet<String>()      // repos already alerted for aging (cleared when clean)
    private var agingPrimed = false                    // don't alert for work already aging at startup
    private val staleNotified = HashSet<String>()      // repos already alerted for staleness (cleared when fresh)
    private var stalePrimed = false                    // don't alert for repos already stale at startup

    /** Re-remind interval after a reminder first fires and isn't marked Done (see fireReminder). */
    private val remindAgainMs = 60 * 60_000L

    // Filesystem watcher (real-time rescans on disk changes).
    private var fsWatcher: FsWatcher? = null
    private val watchRegs = HashMap<String, FsWatchRegistration>()  // repo id -> registration
    private val watchDebounce = HashMap<String, Job>()             // repo id -> pending rescan

    init {
        // Load only what the user has curated. A missing/empty registry stays empty —
        // we do NOT auto-discover, so deleting registry.json gives a clean slate.
        // (Bulk import is still available by picking a parent folder in "Add repo".)
        Notify.init()
        Registry.load().forEach { entries[it.path] = it; order.add(it.path) }
        refreshAll(fetch = false)   // fast initial scan (no network)
        startAutoRefresh()
        startReminderChecker()
        startFsWatcher()
    }

    // ---- filesystem watcher: rescan a repo shortly after its files change ----

    private fun startFsWatcher() {
        if (!runCatching { FsWatchers.isSupported() }.getOrDefault(false)) return
        val w = runCatching { FsWatchers.create() }.getOrNull() ?: return
        fsWatcher = w
        // Collect BEFORE registering paths (events are a hot flow with no replay).
        scope.launch {
            runCatching {
                w.events.collect { ev -> ev.source?.name?.let { scheduleWatchRescan(it) } }
            }
        }
        syncWatches()
    }

    /**
     * The path to hand the watcher for a repo, with symlinks resolved.
     *
     * macOS delivers filesystem events with *canonical* paths (FSEvents), while the watcher matches
     * events against the root it was given by prefix. Hand it a symlinked path and the two never
     * line up: registration succeeds, and then no event ever arrives. Silently — the repo just
     * stops refreshing on disk changes. Linux and Windows echo back whatever path they were given,
     * so their prefix always matches and this only ever bites on macOS.
     *
     * Not exotic: /tmp and /var are symlinks on macOS, and a repo checked out under either — or
     * under any symlink the user made themselves — hits it.
     *
     * Only the watch root is resolved. `name` stays the repo id, so event routing and the watchRegs
     * keys are unaffected (see startFsWatcher, which routes on source.name alone). If the repo has
     * since moved, fall back to the raw path and let watch() fail the way it always did.
     */
    private fun watchRootFor(id: String): Path {
        val raw = Path.of(id)
        return runCatching { raw.toRealPath() }.getOrDefault(raw)
    }

    /** Register/unregister repo watches so they match the tracked set. */
    private fun syncWatches() {
        val w = fsWatcher ?: return
        val want = order.toSet()
        want.forEach { id ->
            if (id !in watchRegs) {
                runCatching { watchRegs[id] = w.watch(watchRootFor(id), true, id) }  // name = id, for event mapping
            }
        }
        (watchRegs.keys - want).forEach { id ->
            runCatching { watchRegs.remove(id)?.close() }
            watchDebounce.remove(id)?.cancel()
        }
    }

    /** Debounced per-repo rescan: coalesces bursts of file events into one scan. */
    private fun scheduleWatchRescan(id: String) {
        if (entries[id] == null) return
        watchDebounce[id]?.cancel()
        watchDebounce[id] = scope.launch {
            delay(800)
            rescanRepos(listOf(id), fetch = false)
        }
    }

    /** Fire a desktop notification for any repo whose upstream advanced since the last scan
     *  (i.e. new commits to pull). Opt-in per repo (default off) and skipped while snoozed;
     *  the first scan only primes the baseline. */
    private fun notifyUpstreamAdvances(scanned: List<Repo>) {
        scanned.forEach { r ->
            val prev = prevBehind[r.id]
            val enabled = entries[r.id]?.notifyUpstream == true
            if (alertsPrimed && enabled && prev != null && r.behind > prev && !r.snoozed) {
                val n = r.behind - prev
                Notify.show(
                    "${r.name}: upstream advanced",
                    "$n new commit${if (n == 1) "" else "s"} on ${r.upstream ?: "upstream"} — pull to catch up",
                    onClick = { selectedId = r.id },
                )
            }
            prevBehind[r.id] = r.behind
        }
        alertsPrimed = true
    }

    /** Fire a one-shot desktop notification when a repo crosses the staleness threshold while the
     *  app is running. Repos already stale at startup are primed (no alert); snoozed repos are
     *  skipped until they resume; recovering (a new commit) re-arms the alert. */
    private fun notifyStaleCrossings(scanned: List<Repo>) {
        val staleNow = scanned.filter { it.stale }.map { it.id }.toSet()
        staleNotified.retainAll(staleNow)   // a repo that got a fresh commit can alert again later
        if (!stalePrimed) { staleNotified.addAll(staleNow); stalePrimed = true; return }
        scanned.forEach { r ->
            if (!r.stale || r.id in staleNotified || r.snoozed) return@forEach
            staleNotified.add(r.id)
            // Tone follows the repo's own severity setting: neutral by default (stable code sits
            // untouched), pointed when the user marked staleness important for this repo.
            Notify.show(
                "${r.name}: marked stale",
                if (r.staleImportant) "No commit in over ${r.staleDays} days — flagged as important"
                else "No commit in over ${r.staleDays} days — expected if the code is stable",
                onClick = { selectedId = r.id },
            )
        }
    }

    /** Fire a one-shot desktop notification when a repo's uncommitted work crosses the "aging"
     *  threshold. Driven by the clock (not a file change), so it's checked on the periodic timer.
     *  Work already aging at startup is primed (no alert); snoozed repos wait; going clean re-arms. */
    private fun checkAgingNotifications(now: Long) {
        val agedNow = order.mapNotNull { id ->
            val since = entries[id]?.dirtySinceEpoch ?: return@mapNotNull null
            if (now - since >= Meta.AGING_MS) id else null
        }.toSet()
        agingNotified.retainAll(agedNow)   // repos that went clean can alert again on the next cycle
        if (!agingPrimed) { agingNotified.addAll(agedNow); agingPrimed = true; return }
        agedNow.forEach { id ->
            val e = entries[id] ?: return@forEach
            if (id in agingNotified || Meta.snoozeState(e, now).first) return@forEach
            agingNotified.add(id)
            Notify.show(
                "${File(id).name}: work aging",
                "Uncommitted changes have been sitting for ${Meta.compactDuration(now - e.dirtySinceEpoch!!)} — commit or stash them",
                onClick = { selectedId = id },
            )
        }
    }

    fun notifyUpstreamEnabled(id: String): Boolean = entries[id]?.notifyUpstream == true

    /** Toggle per-repo "alert when upstream advances". Enabling re-baselines the behind count so
     *  the very next *new* upstream commit alerts (not the backlog that already exists). */
    fun setNotifyUpstream(id: String, on: Boolean) {
        val e = entries[id] ?: return
        entries[id] = e.copy(notifyUpstream = on)
        if (on) prevBehind[id] = repos.firstOrNull { it.id == id }?.behind ?: 0
        persist()
    }

    /** Every 30s: refresh relative snooze/reminder labels (so the detail pane tracks the clock)
     *  and fire any reminder that's come due, re-reminding every hour until the user marks it Done. */
    private fun startReminderChecker() {
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                refreshMeta(order.toList())   // live "in 3m" / "overdue" labels
                checkAgingNotifications(now)  // clock-driven aging crossings
                order.mapNotNull { entries[it] }.forEach { e ->
                    val due = e.reminderDueEpoch
                    if (due != null && e.reminderText.isNotBlank()) {
                        val at = remindAt.getOrPut(e.path) { due }
                        if (now >= at) {
                            fireReminder(e)
                            remindAt[e.path] = now + remindAgainMs
                        }
                    }
                }
                delay(30_000)
            }
        }
    }

    /** Fire a reminder notification with Done (clear it) / Ok (dismiss; re-reminds in 1h) actions.
     *  Action callbacks arrive on a notification thread, so they marshal back onto [scope]. */
    private fun fireReminder(e: RegistryEntry) {
        val id = e.path
        Notify.show(
            title = "Reminder — ${File(id).name}",
            message = e.reminderText,
            onClick = { scope.launch { selectedId = id }; Unit },
            buttons = listOf<Pair<String, () -> Unit>>(
                "Done" to { scope.launch { clearReminder(listOf(id)) }; Unit },
                "Ok" to { /* dismiss only — already rescheduled for +1h */ },
            ),
        )
    }

    /** When the next (re)notification for [id] is scheduled, epoch-millis; null if none pending.
     *  Once a reminder is overdue it's rescheduled to now+1h, so this drives the
     *  "Next reminder in …" label the detail panel shows for overdue reminders. */
    fun nextReminderAt(id: String): Long? = remindAt[id]

    // ---- persisted per-repo edits ----

    fun tagsOf(id: String): List<String> = entries[id]?.tags ?: emptyList()
    fun noteOf(id: String): String = entries[id]?.note ?: ""

    /** Distinct tags in use across all tracked repos (for tag autocomplete). */
    fun allTagsInUse(): List<String> = order.flatMap { tagsOf(it) }.distinct().sorted()

    /** Existing tags matching [query] that [id] doesn't already have — for autocomplete. Prefix
     *  matches sort ahead of mid-string matches so the first result is the best inline completion. */
    fun tagSuggestions(query: String, id: String, limit: Int = 6): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val have = tagsOf(id).toSet()
        return allTagsInUse()
            .filter { it !in have && it.lowercase().contains(q) }
            .sortedBy { if (it.lowercase().startsWith(q)) 0 else 1 }   // stable: keeps alphabetical within a group
            .take(limit)
    }

    /** Existing tags matching [query], no per-repo exclusion — for the bulk "Tag" dialog. */
    fun tagSuggestions(query: String, limit: Int = 6): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return allTagsInUse()
            .filter { it.lowercase().contains(q) }
            .sortedBy { if (it.lowercase().startsWith(q)) 0 else 1 }
            .take(limit)
    }

    fun setNote(id: String, text: String) {
        val e = entries[id] ?: return
        entries[id] = e.copy(note = text)
        persist()
    }

    /** This repo's per-repo "stale after N days" override, or null when it uses the global default. */
    fun staleThresholdDays(id: String): Int? = entries[id]?.staleThresholdDays

    /** Set (days) or clear (null → use the global default) the per-repo stale threshold; rescans so
     *  the stale flag and "flags as Stale in …" outlook update immediately. Clamped to a sane range. */
    fun setStaleThresholdDays(id: String, days: Int?) {
        val e = entries[id] ?: return
        // Meta.STALE_NEVER passes through the clamp untouched — it's a sentinel, not a duration.
        val stored = days?.let { if (it == Meta.STALE_NEVER) it else it.coerceIn(1, 3650) }
        entries[id] = e.copy(staleThresholdDays = stored)
        persist()
        scope.launch { rescanRepos(listOf(id), fetch = false) }
    }

    /** Whether staleness is treated as important (red) rather than informational (amber). */
    fun staleImportant(id: String): Boolean = entries[id]?.staleImportant ?: false

    /** Set how severe staleness is for this repo; rescans so the dot/badge recolour immediately. */
    fun setStaleImportant(id: String, important: Boolean) {
        val e = entries[id] ?: return
        entries[id] = e.copy(staleImportant = important)
        persist()
        scope.launch { rescanRepos(listOf(id), fetch = false) }
    }

    // ---- hidden branches -----------------------------------------------------------------

    /**
     * Whether the branch lists are currently showing the branches their patterns hide — the
     * "Show hidden" half of the dotfile bargain.
     *
     * Deliberately not persisted, and deliberately app-wide rather than per-repo: it's a peek, not
     * a preference. A restart puts every repo back to the quiet list, which is the state the
     * dashboard is for; leaving it latched on across sessions would mean rediscovering why a repo
     * is full of bot branches weeks later.
     */
    var showHiddenBranches by mutableStateOf(false)
        private set

    fun toggleShowHiddenBranches() { showHiddenBranches = !showHiddenBranches }

    /** This repo's own hide patterns, or null when it follows [Meta.DEFAULT_HIDE_BRANCH_PATTERNS]. */
    fun hideBranchPatternsOverride(id: String): List<String>? = entries[id]?.hideBranchPatterns

    /** The patterns actually in force for this repo: its override if it set one, else the default. */
    fun hideBranchPatterns(id: String): List<String> =
        hideBranchPatternsOverride(id) ?: Meta.DEFAULT_HIDE_BRANCH_PATTERNS

    /**
     * Store this repo's pattern list, collapsing one that says the same thing as the defaults back
     * to "no override". Without that, editing a pattern and undoing the edit by hand would leave
     * the repo permanently marked as customised — still offering "Reset to default" when it's
     * already at the default, and no longer labelled as one.
     *
     * Compared as sets: the patterns are OR'd together when matching, so order carries no meaning
     * and two lists with the same patterns in a different order are the same answer.
     */
    private fun setHideBranchPatterns(id: String, patterns: List<String>) {
        val e = entries[id] ?: return
        val default = patterns.toSet() == Meta.DEFAULT_HIDE_BRANCH_PATTERNS.toSet()
        entries[id] = e.copy(hideBranchPatterns = if (default) null else patterns)
        persist()
    }

    /**
     * Add one pattern to this repo's list. Adding to a repo still on the defaults keeps them and
     * appends — otherwise the first custom pattern would read as replacing the defaults, and the
     * bot branches would come flooding back.
     */
    fun addHideBranchPattern(id: String, pattern: String) {
        val p = pattern.trim()
        if (p.isEmpty()) return
        val cur = hideBranchPatterns(id)
        if (p in cur) return
        setHideBranchPatterns(id, cur + p)
    }

    /** Remove one pattern. Removing the last one leaves an empty list — "hide nothing in this
     *  repo" — which is a different answer from clearing back to the defaults. */
    fun removeHideBranchPattern(id: String, pattern: String) {
        setHideBranchPatterns(id, hideBranchPatterns(id) - pattern)
    }

    /** Drop this repo's override so it follows [Meta.DEFAULT_HIDE_BRANCH_PATTERNS] again. */
    fun resetHideBranchPatterns(id: String) {
        val e = entries[id] ?: return
        entries[id] = e.copy(hideBranchPatterns = null)
        persist()
    }

    // ---- GitHub issues / PRs -------------------------------------------------------------

    /** This repo's own "track open issues" choice, or null when it follows the global default. */
    fun issuesTrackedOverride(id: String): Boolean? = entries[id]?.issuesTracked

    /**
     * Whether this repo's remote is one we can read issues from at all — i.e. whether the issues
     * UI should exist for it. True when the remote parses to an owner/repo *and* its host is
     * GitHub: either by name (github.com, github.*) or because `gh` holds a working login for
     * that host, which is the only way to recognise an Enterprise install at an arbitrary
     * hostname like `git.company.com`.
     *
     * Anything else — GitLab, Azure DevOps, Gitea, no remote — is unsupported rather than empty,
     * and shows no issues section at all.
     */
    fun issuesSupported(repo: Repo): Boolean {
        val host = GitHub.coordOf(repo.webBase)?.host ?: return false
        return repo.isGitHub || host.lowercase() in githubHosts
    }

    /** Whether open issues/PRs are actually polled for this repo: [issuesSupported], plus the
     *  per-repo override if set, else the global default. */
    fun issuesTracked(repo: Repo): Boolean =
        issuesSupported(repo) && (entries[repo.id]?.issuesTracked ?: githubEnabled)

    /** Set (or clear, with null) this repo's issue tracking; refetches so the row updates now. */
    fun setIssuesTracked(id: String, tracked: Boolean?) {
        val e = entries[id] ?: return
        entries[id] = e.copy(issuesTracked = tracked)
        // Turning it off drops the cached data too, so the counts vanish immediately rather than
        // lingering until something else evicts them.
        if (tracked == false) githubState.remove(id)
        persist()
        if (tracked != false) refreshGitHub()
    }

    /** Whether open issues escalate one attention level (blue→amber, amber→red) for this repo. */
    fun issuesImportant(id: String): Boolean = entries[id]?.issuesImportant ?: false

    /** Set how loud open issues are for this repo. Purely presentational — no refetch needed,
     *  since the severity is applied when the summary is assembled, not when it's fetched. */
    fun setIssuesImportant(id: String, important: Boolean) {
        val e = entries[id] ?: return
        entries[id] = e.copy(issuesImportant = important)
        persist()
    }

    /** Toggle "only count issues that involve me" (global). Re-derives from cached data. */
    fun setMineOnly(on: Boolean) {
        githubMineOnly = on
        Registry.saveSettings(Registry.settings().copy(githubMineOnly = on))
    }

    /**
     * The open-issue picture for one repo, or null when issues aren't tracked for it or nothing
     * has been fetched yet. Applies the "only mine" filter here rather than at fetch time, so
     * flipping that toggle re-derives instantly from cached data instead of hitting the network.
     */
    fun ghSummary(repo: Repo): GhSummary? {
        if (!issuesTracked(repo)) return null
        val st = githubState[repo.id] ?: return null
        val important = issuesImportant(repo.id)
        if (st.error != null) {
            return GhSummary(0, 0, 0, emptyList(), important = important, error = st.error)
        }
        val mine = githubMineOnly
        val issues = st.issues.filter { !mine || it.involvesYou }
        val prs = st.prs.filter { !mine || it.involvesYou }
        // GitHub's totalCount is authoritative for "how many are open", but it can't be filtered
        // client-side — so with "only mine" on, the fetched-and-filtered list *is* the count.
        val openIssues = if (mine) issues.size else st.issueTotal
        val openPrs = if (mine) prs.size else st.prTotal
        // More were open than one fetch inspects. Independent of `mine`: the cap is applied by
        // the fetch, before any filtering, so an unexamined item could have involved you either
        // way — which also makes the filtered "only mine" counts floors rather than totals.
        val capped = st.issueTotal > st.issues.size || st.prTotal > st.prs.size
        return GhSummary(
            openIssues = openIssues,
            openPrs = openPrs,
            awaiting = (issues + prs).count { it.awaitingYou },
            items = (issues + prs).sortedByDescending { it.updatedAt },
            truncated = capped,
            countIsFloor = mine && capped,
            important = important,
        )
    }

    /**
     * Re-probe `gh` and refetch every tracked repo's open issues/PRs. Safe to call at any time;
     * overlapping calls are dropped rather than queued (the next tick will pick up anything
     * missed). Failures are surfaced through [githubStatus] and never disturb the git dashboard.
     */
    fun refreshGitHub() {
        if (githubFetching) {
            // Don't just drop it: "Check now" and the Track toggle both promise a refresh, and a
            // background poll happening to be in flight would silently turn them into no-ops for
            // up to five minutes. Remember that someone asked, and run once more on the way out.
            githubRerun = true
            return
        }
        // Claimed here, not inside the coroutine: the guard and the launch would otherwise
        // straddle a suspension point, so a refreshAll and a "Check now" click in the same frame
        // would both see false and fire duplicate fetches.
        githubFetching = true
        githubRerun = false
        scope.launch {
            try {
                val status = GitHub.status()
                githubStatus = status
                if (status !is GitHub.Status.Ok) return@launch
                // Before building the coordinate list, not after: issuesTracked() consults these
                // to recognise Enterprise hosts, so setting them later would skip every such repo
                // on the first pass and only pick them up a refresh cycle later.
                githubHosts = status.hosts
                val coords = repos.filter { issuesTracked(it) }
                    .mapNotNull { r -> GitHub.coordOf(r.webBase)?.let { r.id to it } }
                    .toMap()
                if (coords.isEmpty()) { githubState.clear(); return@launch }
                val fetched = GitHub.fetch(coords)
                // Replace wholesale rather than merge: a repo that dropped out of `coords`
                // (untracked, turned off, remote changed) must not keep stale counts on screen.
                githubState.keys.retainAll(fetched.keys)
                githubState.putAll(fetched)
                githubFetchedEpoch = System.currentTimeMillis()
            } finally {
                githubFetching = false
                // Someone asked while this one was in flight (see the guard above). Re-run once
                // — the flag is cleared on entry, so this can't spin.
                if (githubRerun) refreshGitHub()
            }
        }
    }

    fun addTag(id: String, raw: String) {
        var v = raw.trim()
        if (v.isEmpty()) return
        if (!v.contains(':')) v = "tag:$v"
        val e = entries[id] ?: return
        if (v in e.tags) return
        entries[id] = e.copy(tags = e.tags + v)
        persist()
    }

    fun removeTag(id: String, t: String) {
        val e = entries[id] ?: return
        entries[id] = e.copy(tags = e.tags.filter { it != t })
        persist()
        pruneTagFilter()
    }

    /** Drop any active tag-filter entries no longer carried by any repo — otherwise deleting
     *  a tag that's being filtered on would leave the list filtering by a tag that exists
     *  nowhere, showing nothing. */
    private fun pruneTagFilter() {
        if (tagFilter.isEmpty() && tagExclude.isEmpty()) return
        val live = order.flatMap { tagsOf(it) }.toSet()
        tagFilter.filter { it in live }.toSet().let { if (it.size != tagFilter.size) tagFilter = it }
        tagExclude.filter { it in live }.toSet().let { if (it.size != tagExclude.size) tagExclude = it }
    }

    private fun persist() = Registry.save(order.mapNotNull { entries[it] })

    // ---- scanning ----

    /** Re-scan every registered repo concurrently. Git runs on Default/IO; the state
     *  updates run on [scope] (the UI dispatcher) so the list actually recomposes. */
    fun refreshAll(fetch: Boolean) {
        if (scanning) return
        scope.launch {
            scanning = true
            try {
                val snapshot = order.mapNotNull { entries[it] }
                val results = withContext(Dispatchers.Default) {
                    snapshot.map { e ->
                        async { scanLimiter.withPermit { runCatching { RepoScanner.scan(e, fetch) }.getOrNull() } }
                    }.awaitAll()
                }.filterNotNull().associateBy { it.id }
                val ordered = order.mapNotNull { results[it] }
                repos.clear()
                repos.addAll(ordered)
                notifyUpstreamAdvances(ordered)
                notifyStaleCrossings(ordered)
                reconcileDirtySince(ordered)
                lastFetchedEpoch = System.currentTimeMillis()
                // Piggyback the GitHub poll on the full refresh rather than giving it its own
                // timer. This is exactly the right cadence — startup, the manual refresh button,
                // and the 5-minute auto-refresh — while the *frequent* rescans (fs-watcher
                // events, several a minute while you type) go through rescanRepos and correctly
                // don't touch the network. It also runs after `repos` is populated, which is
                // what tells it which repos have GitHub remotes. Fire-and-forget: it has its own
                // in-flight guard and must never hold up the git dashboard.
                refreshGitHub()
            } finally {
                scanning = false
            }
        }
    }

    /** "Add repo" step 1: show FileKit's native single-directory picker for a *parent*
     *  folder, then open the repo chooser listing the git repos found beneath it.
     *  (FileKit has no multi-directory mode, so we discover-then-multi-select instead.) */
    fun pickAndAddRepo() {
        scope.launch {
            val parent = withContext(Dispatchers.IO) { FolderPicker.pickParent(Registry.defaultRoot().absolutePath) }
                ?: return@launch
            openChooserFor(parent)
        }
    }

    /** "Add repo" step 2: discover [parent]'s git repos and open the multi-select chooser.
     *  Uses [Registry.discover] (the parent itself if it's a repo, plus its immediate git
     *  children). Already-tracked repos are shown but locked off. */
    private suspend fun openChooserFor(parent: String) {
        chooserParent = parent
        chooserCandidates.clear()
        chooserSelected = emptySet()   // default: nothing checked — the user opts in
        chooserFilter = ""
        chooserScanning = true
        chooserOpen = true
        val found = withContext(Dispatchers.IO) { Registry.discover(File(parent)) }
        val candidates = found.map { RepoCandidate(it.path, File(it.path).name, it.path in entries) }
        chooserCandidates.addAll(candidates)
        chooserScanning = false
    }

    /** Candidates matching [chooserFilter] (name or path substring), in discovery order. */
    fun chooserVisible(): List<RepoCandidate> {
        val q = chooserFilter.trim().lowercase()
        return if (q.isEmpty()) chooserCandidates.toList()
        else chooserCandidates.filter { it.name.lowercase().contains(q) || it.path.lowercase().contains(q) }
    }

    fun toggleChooserSelection(path: String) {
        val c = chooserCandidates.find { it.path == path } ?: return
        if (c.alreadyTracked) return   // can't re-add what's already tracked
        chooserSelected = if (path in chooserSelected) chooserSelected - path else chooserSelected + path
    }

    /** Select every currently-visible (filtered) candidate that isn't already tracked. */
    fun chooserSelectAll() {
        chooserSelected = chooserSelected + chooserVisible().filterNot { it.alreadyTracked }.map { it.path }
    }

    fun chooserSelectNone() {
        chooserSelected = emptySet()
    }

    fun cancelChooser() {
        chooserOpen = false
        chooserCandidates.clear()
        chooserSelected = emptySet()
        chooserFilter = ""
        chooserParent = ""
    }

    /** Register + scan the checked repos, then close the chooser. */
    fun confirmChooser() {
        val paths = chooserCandidates.map { it.path }.filter { it in chooserSelected }
        cancelChooser()
        if (paths.isEmpty()) return
        scope.launch { addPaths(paths) }
    }

    private suspend fun addPaths(picked: List<String>) {
        val candidates = picked.flatMap { p ->
            val dir = File(p)
            when {
                Git.isRepo(dir) -> listOf(dir.absolutePath)          // a git repo → add it
                else -> Registry.discover(dir).map { it.path }                   // a parent → add its git children
                    .ifEmpty { listOf(dir.absolutePath) }                        // neither → add as-is (scanner flags "Not a git repo")
            }
        }.distinct()
        val now = java.time.Instant.now()
        val added = candidates.filter { it !in entries }.map { RegistryEntry(it, addedAt = now) }
        if (added.isEmpty()) return
        added.forEach { entries[it.path] = it; order.add(it.path) }
        persist()
        syncWatches()
        val scanned = withContext(Dispatchers.Default) {
            added.map { e -> async { scanLimiter.withPermit { runCatching { RepoScanner.scan(e, false) }.getOrNull() } } }.awaitAll()
        }.filterNotNull()
        repos.addAll(scanned)
        added.firstOrNull()?.let { selectedId = it.path }   // open detail on the first added
    }

    /** "Remove Repo": stop tracking a repo (does not touch the repo on disk). */
    fun removeRepo(id: String) {
        entries.remove(id)
        order.remove(id)
        repos.removeAll { it.id == id }
        if (selectedId == id) selectedId = null
        bulkSelected = bulkSelected - id
        persist()
        syncWatches()
    }

    // ---- bulk selection (checkbox + Ctrl/Shift-click multi-select) ----

    private var bulkAnchor: String? = null   // origin row for Shift-range (not observable)

    val bulkCount get() = bulkSelected.size
    fun toggleBulk(id: String) {
        bulkSelected = if (id in bulkSelected) bulkSelected - id else bulkSelected + id
        bulkAnchor = id
    }
    fun clearBulk() { bulkSelected = emptySet() }
    fun bulkSelectAllVisible() { bulkSelected = filtered().map { it.id }.toSet() }

    /** A click on a list row: plain opens the detail panel; Ctrl/⌘ toggles the row in the
     *  bulk selection; Shift extends the selection over the visible range from the anchor. */
    fun clickRow(id: String, ctrl: Boolean, shift: Boolean) {
        when {
            shift -> {
                val order = visibleOrder()
                val b = order.indexOf(id)
                val a = bulkAnchor?.let { order.indexOf(it) } ?: -1
                bulkSelected = when {
                    a >= 0 && b >= 0 -> bulkSelected + order.subList(minOf(a, b), maxOf(a, b) + 1)
                    b >= 0 -> bulkSelected + id
                    else -> bulkSelected
                }
                // keep the anchor so the range can be re-extended from the same origin
            }
            ctrl -> toggleBulk(id)          // also updates the anchor
            else -> { selectedId = id; bulkAnchor = id }
        }
    }

    /** Ids in the order rows are displayed (used to resolve Shift-ranges). */
    private fun visibleOrder(): List<String> =
        if (groupBy == "none") filtered().map { it.id }
        else groups().flatMap { g -> g.repos.map { it.id } }.distinct()

    // ---- transient op feedback (toast) ----

    private fun toast(msg: String) {
        opStatus = msg
        toastJob?.cancel()
        toastJob = scope.launch { delay(4500); opStatus = null }
    }
    fun dismissToast() { toastJob?.cancel(); opStatus = null }
    private fun plural(n: Int) = if (n == 1) "repo" else "repos"

    // ---- snooze / reminder (persisted metadata; recomputed in place, no git) ----

    /** [until] = epoch to snooze until ([Meta.SNOOZE_FOREVER] = indefinite), or null to resume. */
    fun setSnoozeUntil(ids: Collection<String>, until: Long?) {
        ids.forEach { id -> entries[id]?.let { entries[id] = it.copy(snoozeUntilEpoch = until) } }
        persist(); refreshMeta(ids)
        toast(if (until == null) "Resumed ${ids.size} ${plural(ids.size)}" else "Snoozed ${ids.size} ${plural(ids.size)}")
    }

    fun setReminder(ids: Collection<String>, text: String, dueEpoch: Long?) {
        val t = text.trim()
        ids.forEach { id ->
            val newDue = if (t.isEmpty()) null else dueEpoch
            entries[id]?.let { entries[id] = it.copy(reminderText = t, reminderDueEpoch = newDue) }
            if (t.isEmpty() || newDue == null) remindAt.remove(id) else remindAt[id] = newDue   // (re)schedule next notify
        }
        persist(); refreshMeta(ids)
        toast(if (t.isEmpty()) "Reminder cleared" else "Reminder set on ${ids.size} ${plural(ids.size)}")
    }

    fun clearReminder(ids: Collection<String>) = setReminder(ids, "", null)

    /** Recompute snooze/reminder view fields on affected repos without a git rescan. */
    private fun refreshMeta(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        ids.forEach { id ->
            val e = entries[id] ?: return@forEach
            val idx = repos.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val (sn, snFor) = Meta.snoozeState(e, now)
                repos[idx] = repos[idx].copy(snoozed = sn, snoozedFor = snFor, reminder = Meta.reminder(e, now))
            }
        }
    }

    // ---- tags / remove (bulk) ----

    fun addTagToAll(ids: Collection<String>, raw: String) {
        if (raw.isBlank()) return
        ids.forEach { addTag(it, raw) }
        toast("Tagged ${ids.size} ${plural(ids.size)}")
    }

    /** Union of tags across the given repos — the choices offered in the bulk "Untag" popup. */
    fun tagsAcross(ids: Collection<String>): List<String> =
        ids.flatMap { tagsOf(it) }.distinct().sorted()

    /** Remove each of [tags] from every repo in [ids] that carries it. */
    fun removeTagFromAll(ids: Collection<String>, tags: Collection<String>) {
        if (tags.isEmpty()) return
        ids.forEach { id -> tags.forEach { t -> if (t in tagsOf(id)) removeTag(id, t) } }
        val n = tags.size
        toast("Removed $n ${if (n == 1) "tag" else "tags"} from ${ids.size} ${plural(ids.size)}")
    }

    fun removeRepos(ids: Collection<String>) {
        val n = ids.size
        ids.toList().forEach { removeRepo(it) }
        toast("Removed $n ${plural(n)}")
    }

    // ---- push / fetch (git side effects; run off-thread, then rescan) ----

    /** Push each repo that has a remote; skips the rest, reports per-repo outcome.
     *  (Branches without a tracking upstream are still pushed — see [RepoOps.push].) */
    fun push(ids: Collection<String>) {
        val pushable = ids.filter { id -> repos.find { it.id == id }?.hasRemote == true }
        val skipped = ids.size - pushable.size
        val es = pushable.mapNotNull { entries[it] }
        if (es.isEmpty()) { toast("Nothing to push — no remote"); return }
        scope.launch {
            toast("Pushing ${es.size} ${plural(es.size)}…")
            val results = withContext(Dispatchers.Default) {
                es.map { e -> async { scanLimiter.withPermit { RepoOps.push(e) } } }.awaitAll()
            }
            rescanRepos(results.map { it.id }, fetch = false)
            val ok = results.count { it.ok }
            val fail = results.filter { !it.ok }
            toast(buildString {
                append("Pushed $ok/${results.size}")
                if (skipped > 0) append(" · $skipped skipped (no remote)")
                fail.firstOrNull()?.let { append(" · ${it.message}") }
            })
        }
    }

    // ---- "Open in …" launches (fire-and-forget; toast if nothing could start) ----

    fun openTerminal(id: String) { if (!Actions.openTerminal(id)) toast("Couldn't open a terminal") }
    fun openFolder(id: String) { if (!Actions.openFolder(id)) toast("Couldn't open the folder") }
    fun openIde(id: String) { if (!Actions.openIde(id)) toast("No IDE found (idea / code)") }
    fun openGitGui(id: String) { if (!Actions.openGitGui(id)) toast("git gui not available") }
    fun openGitButler(id: String) { if (!Actions.openGitButler(id)) toast("GitButler not found") }
    fun openUrl(url: String) { if (!Actions.openUrl(url)) toast("Couldn't open the browser") }

    // ---- branches (detail panel) ----

    /** Load the branch list for [id] (the detail panel calls this when it opens a repo). */
    fun loadBranches(id: String) {
        if (branchesRepo == id && branches.isNotEmpty()) return
        branchesRepo = id
        branches = emptyList()
        remoteBranches = emptyList()
        showRemoteBranches = false
        branchesLoading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { BranchOps.load(id) }
            if (branchesRepo == id) { branches = list; branchesLoading = false }
        }
    }

    private fun reloadBranches(id: String) {
        scope.launch {
            val list = withContext(Dispatchers.IO) { BranchOps.load(id) }
            if (branchesRepo == id) {
                branches = list
                if (showRemoteBranches) {   // keep the remote list's "hasLocal" flags fresh
                    val locals = list.map { it.name }.toSet()
                    remoteBranches = withContext(Dispatchers.IO) { BranchOps.loadRemotes(id, locals) }
                }
            }
        }
    }

    /** Toggle the collapsible remote-branches list; loads it lazily the first time. */
    fun toggleRemoteBranches(id: String) {
        showRemoteBranches = !showRemoteBranches
        if (showRemoteBranches && remoteBranches.isEmpty()) {
            scope.launch {
                val locals = branches.map { it.name }.toSet()
                val list = withContext(Dispatchers.IO) { BranchOps.loadRemotes(id, locals) }
                if (branchesRepo == id) remoteBranches = list
            }
        }
    }

    /** Check out an existing local branch (caller ensures the working tree is clean). */
    fun switchBranch(id: String, name: String) {
        if (switchingBranch) return
        switchingBranch = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { BranchOps.switch(id, name) }
            toast(r.message)
            reloadBranches(id)
            rescanRepos(listOf(id), fetch = false)
            switchingBranch = false
        }
    }

    /** Send one branch to the remote — publishing it when it has no upstream yet (see
     *  [BranchOps.push]). Unlike a switch this isn't gated on a clean working tree: pushing a ref
     *  doesn't touch the tree, so there's nothing to clobber. */
    fun pushBranch(id: String, b: BranchOps.Branch) {
        if (pushingBranch != null) return
        pushingBranch = b.name
        scope.launch {
            val r = withContext(Dispatchers.IO) { BranchOps.push(id, b.name, b.upstream) }
            toast(r.message)
            reloadBranches(id)
            rescanRepos(listOf(id), fetch = false)
            pushingBranch = null
        }
    }

    /** Create (or switch to) a local branch tracking a remote branch, then check it out. */
    fun checkoutRemoteBranch(id: String, rb: BranchOps.RemoteBranch) {
        if (switchingBranch) return
        switchingBranch = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { BranchOps.checkoutRemote(id, rb.name, rb.hasLocal) }
            toast(r.message)
            reloadBranches(id)
            rescanRepos(listOf(id), fetch = false)
            switchingBranch = false
        }
    }

    /**
     * Delete a branch on the remote (see [BranchOps.deleteRemote]). Reloads both branch lists, not
     * just the remote one: a local branch that tracked it now reads "upstream gone", and that's the
     * only thing left on screen saying the delete happened.
     */
    fun deleteRemoteBranch(id: String, rb: BranchOps.RemoteBranch) {
        if (deletingRemoteBranch != null) return
        deletingRemoteBranch = rb.name
        scope.launch {
            val r = withContext(Dispatchers.IO) { BranchOps.deleteRemote(id, rb) }
            toast(r.message)
            reloadBranches(id)
            rescanRepos(listOf(id), fetch = false)
            deletingRemoteBranch = null
        }
    }

    // ---- submodules (detail panel) ----

    fun loadSubmodules(id: String) {
        if (submodulesRepo == id && submodules.isNotEmpty()) return
        submodulesRepo = id
        submodules = emptyList()
        scope.launch {
            val list = withContext(Dispatchers.IO) { SubmoduleOps.load(id) }
            if (submodulesRepo == id) submodules = list
        }
    }

    private fun reloadSubmodules(id: String) {
        scope.launch {
            val list = withContext(Dispatchers.IO) { SubmoduleOps.load(id) }
            if (submodulesRepo == id) submodules = list
        }
    }

    /** Fetch one submodule's remote, then refresh its "behind" count. */
    fun fetchSubmodule(id: String, path: String) {
        if (submodulesBusy) return
        submodulesBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { SubmoduleOps.fetch(id, path) }
            toast(r.message)
            reloadSubmodules(id)
            submodulesBusy = false
        }
    }

    /** Fetch every initialized submodule. */
    fun fetchAllSubmodules(id: String) {
        if (submodulesBusy) return
        submodulesBusy = true
        val subs = submodules
        scope.launch {
            toast("Fetching submodules…")
            val r = withContext(Dispatchers.IO) { SubmoduleOps.fetchAll(id, subs) }
            toast(r.message)
            reloadSubmodules(id)
            submodulesBusy = false
        }
    }

    /** Initialize a not-yet-checked-out submodule. */
    fun initSubmodule(id: String, path: String) {
        if (submodulesBusy) return
        submodulesBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { SubmoduleOps.init(id, path) }
            toast(r.message)
            reloadSubmodules(id)
            rescanRepos(listOf(id), fetch = false)
            submodulesBusy = false
        }
    }

    /** Advance a submodule to its latest remote commit and stage the new pointer in the parent. */
    fun updateSubmodulePointer(id: String, path: String) {
        if (submodulesBusy) return
        submodulesBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { SubmoduleOps.updatePointer(id, path) }
            toast(r.message)
            reloadSubmodules(id)
            rescanRepos(listOf(id), fetch = false)   // parent now has a staged gitlink change
            submodulesBusy = false
        }
    }

    /** `git submodule sync` — re-point at the URL in .gitmodules. */
    fun syncSubmodule(id: String, path: String) {
        if (submodulesBusy) return
        submodulesBusy = true
        scope.launch {
            toast(withContext(Dispatchers.IO) { SubmoduleOps.sync(id, path) }.message)
            reloadSubmodules(id); submodulesBusy = false
        }
    }

    /** `git submodule deinit -f` — remove the submodule's working tree (re-init to restore). */
    fun deinitSubmodule(id: String, path: String) {
        if (submodulesBusy) return
        submodulesBusy = true
        scope.launch {
            toast(withContext(Dispatchers.IO) { SubmoduleOps.deinit(id, path) }.message)
            reloadSubmodules(id); rescanRepos(listOf(id), fetch = false); submodulesBusy = false
        }
    }

    // ---- worktrees (detail panel) ----

    fun loadWorktrees(id: String) {
        if (worktreesRepo == id && worktrees.isNotEmpty()) return
        worktreesRepo = id
        worktrees = emptyList()
        scope.launch {
            val list = withContext(Dispatchers.IO) { WorktreeOps.load(id) }
            if (worktreesRepo == id) worktrees = list
        }
    }

    private fun reloadWorktrees(id: String) {
        scope.launch {
            val list = withContext(Dispatchers.IO) { WorktreeOps.load(id) }
            if (worktreesRepo == id) worktrees = list
        }
    }

    /**
     * `git worktree remove` — delete a worktree's folder from disk. Destructive; the detail panel
     * confirms first, spelling out any uncommitted work that goes with it.
     *
     * Removing the worktree you're *viewing* is allowed: the command just has to run from a
     * different working tree, and afterwards this repo's own entry points at a folder that no
     * longer exists, so it's untracked too (registry-only — nothing else on disk is touched).
     *
     * [removeBranch] also deletes the branch it held — offered only for an agent worktree whose
     * branch has already landed, where the branch is leftover bookkeeping rather than work.
     */
    fun removeWorktree(id: String, wt: WorktreeOps.Worktree, removeBranch: Boolean = false) {
        if (worktreesBusy) return
        val runFrom = if (!wt.isCurrent) id
        else worktrees.firstOrNull { !it.isCurrent && !it.missing }?.path
        if (runFrom == null) { toast("No other working tree to run the removal from"); return }
        val trackedId = trackedRepoAt(wt.path)
        worktreesBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                WorktreeOps.remove(
                    runFrom, wt.path, force = wt.dirtyCount > 0, locked = wt.locked,
                    alsoBranch = wt.branch?.takeIf { removeBranch },
                )
            }
            toast(r.message)
            if (r.ok) trackedId?.let { removeRepo(it) }
            // Refresh whichever tracked repo the removal ran from — its worktree count just changed.
            trackedRepoAt(runFrom)?.let { other ->
                reloadWorktrees(other)
                rescanRepos(listOf(other), fetch = false)
            }
            worktreesBusy = false
        }
    }

    /** `git worktree prune` — forget the entries whose directory is gone. Repo-wide, not per-path,
     *  which is why it's a section-level action rather than a per-row one. */
    fun pruneWorktrees(id: String) {
        if (worktreesBusy) return
        worktreesBusy = true
        scope.launch {
            toast(withContext(Dispatchers.IO) { WorktreeOps.prune(id) }.message)
            reloadWorktrees(id)
            rescanRepos(listOf(id), fetch = false)   // the worktree count just changed
            worktreesBusy = false
        }
    }

    /** True if a path is already tracked (used by the submodule "Add Repo" action). */
    fun isTracked(path: String): Boolean = entries.containsKey(path)

    /** The tracked repo id whose path resolves to [path] (canonicalized), or null. Used to link a
     *  submodule to its parent superproject when that parent is also tracked. */
    fun trackedRepoAt(path: String): String? {
        val target = runCatching { File(path).canonicalPath }.getOrDefault(path)
        return order.firstOrNull { runCatching { File(it).canonicalPath }.getOrDefault(it) == target }
    }

    /** Track a submodule's own repo as a first-class tracked entry. */
    fun trackSubmodule(parentId: String, sub: SubmoduleOps.Submodule) =
        trackRepoAt(File(parentId, sub.path).absolutePath)

    /**
     * The tags a worktree starts life with when it's tracked as a repo of its own: everything its
     * main checkout carries, plus a "worktree" marker.
     *
     * Inheriting matters because the copies are the *same project* — a worktree of a repo tagged
     * `owner:me lang:kotlin` is still owned by you and still Kotlin, and without the tags it drops
     * out of every filter and grouping that found the original. The marker is what keeps them
     * separable again afterwards.
     *
     * Falls back to the repo it was added from when the main checkout isn't tracked; that's the
     * same repository either way, so its tags are the right ones to copy.
     */
    fun worktreeTags(addedFrom: String): List<String> {
        val source = worktrees.firstOrNull { it.isMain }?.path?.let { trackedRepoAt(it) } ?: addedFrom
        return (tagsOf(source) + WORKTREE_TAG).distinct()
    }

    /** Register a checkout the app already knows about — a submodule, a linked worktree — as a
     *  tracked repo of its own, then select it. */
    fun trackRepoAt(path: String, tags: List<String> = emptyList()) {
        if (entries.containsKey(path)) { toast("Already added"); return }
        scope.launch {
            val e = RegistryEntry(path, tags = tags, addedAt = java.time.Instant.now())
            entries[path] = e; order.add(path)
            persist(); syncWatches()
            val scanned = withContext(Dispatchers.Default) {
                scanLimiter.withPermit { runCatching { RepoScanner.scan(e, false) }.getOrNull() }
            }
            scanned?.let { repos.add(it) }
            // The scan's name, not the folder's: a worktree is listed under its main checkout's
            // name, and the confirmation should say what the list is about to show.
            toast("Added ${scanned?.name ?: File(path).name}")
            selectedId = path
        }
    }

    /** Show the file changes a submodule's pending pointer move would advance across
     *  (`git diff <recorded>..<remote>` inside the submodule). */
    fun openSubmoduleDiff(parentId: String, sub: SubmoduleOps.Submodule) {
        val target = sub.remoteRef ?: return
        val subPath = File(parentId, sub.path).absolutePath
        diffRepoName = "${File(parentId).name}/${sub.path}"
        diffTitle = "${sub.path}: pending pointer move"
        diff = DiffOps.Diff(emptyList(), emptyList())
        diffLoading = true
        diffOpen = true
        scope.launch {
            val d = withContext(Dispatchers.IO) {
                DiffOps.loadRangeDiff(subPath, sub.recordedFull, target, "${sub.recorded} → $target")
            }
            diff = d; diffLoading = false
        }
    }

    /** Delete a branch (force = -D). Reloads the branch list + rescans the repo. */
    fun deleteBranch(id: String, name: String, force: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { BranchOps.delete(id, name, force) }
            toast(result.message)
            reloadBranches(id)
            rescanRepos(listOf(id), fetch = false)
        }
    }

    /** Open the diff viewer for a repo and load its working-tree changes off the UI thread. */
    fun openDiff(id: String) {
        diffRepoName = File(id).name
        diffTitle = "Changes"
        diff = DiffOps.Diff(emptyList(), emptyList())
        diffLoading = true
        diffOpen = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { DiffOps.load(id) }
            diff = d
            diffLoading = false
        }
    }

    /** Open the diff viewer showing what a branch introduced vs mainline (`git diff main...ref`). */
    fun openBranchDiff(id: String, ref: String, displayName: String) {
        diffRepoName = File(id).name
        diffTitle = "$displayName — branch changes"
        diff = DiffOps.Diff(emptyList(), emptyList())
        diffLoading = true
        diffOpen = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { DiffOps.loadBranchDiff(id, ref) }
            diff = d
            diffLoading = false
        }
    }

    /** Open the diff viewer showing a stash's contents (`git stash show -p`). */
    fun openStashDiff(id: String, ref: String) {
        diffRepoName = File(id).name
        diffTitle = "Stash $ref"
        diff = DiffOps.Diff(emptyList(), emptyList())
        diffLoading = true
        diffOpen = true
        scope.launch {
            val d = withContext(Dispatchers.IO) { DiffOps.loadStash(id, ref) }
            diff = d
            diffLoading = false
        }
    }

    /** Open the diff viewer for an arbitrary commit range (`git diff <base>..<target>`). */
    fun openRangeDiff(id: String, base: String, target: String, title: String, sectionLabel: String) {
        diffRepoName = File(id).name
        diffTitle = title
        diff = DiffOps.Diff(emptyList(), emptyList())
        diffLoading = true
        diffOpen = true
        scope.launch {
            val d = DiffOps.loadRangeDiff(id, base, target, sectionLabel)
            diff = d
            diffLoading = false
        }
    }

    /** Diff a single commit (`git diff <hash>^..<hash>`) — used from a log row. Renders on top of
     *  the log overlay, so closing it returns to the commit list. */
    fun openCommitDiff(id: String, fullHash: String, shortHash: String) {
        diffRepoName = File(id).name
        diffTitle = "Commit $shortHash"
        diff = DiffOps.Diff(emptyList(), emptyList())
        diffLoading = true
        diffOpen = true
        scope.launch {
            val d = DiffOps.loadRangeDiff(id, "$fullHash^", fullHash, "commit $shortHash")
            diff = d
            diffLoading = false
        }
    }

    fun closeDiff() {
        diffOpen = false
        diff = DiffOps.Diff(emptyList(), emptyList())
    }

    /** Open the log viewer listing the commits in an arbitrary range (`git log <range>`). */
    fun openRangeLog(id: String, range: String, title: String) {
        logRepoName = File(id).name
        logRepoId = id
        logWebBase = repos.firstOrNull { it.id == id }?.takeIf { it.isGitHub }?.webBase
        logTitle = title
        logCommits = emptyList()
        logLoading = true
        logOpen = true
        scope.launch {
            val c = LogOps.loadRange(id, range)
            logCommits = c
            logLoading = false
        }
    }

    /** Open the log viewer on a repo's own commit history (`git log HEAD`). */
    fun openRepoLog(id: String) = openRangeLog(id, "HEAD", "Commit history")

    /** Open the log viewer with the commits a branch introduced vs mainline (`git log main..ref`). */
    fun openBranchLog(id: String, ref: String, displayName: String) {
        logRepoName = File(id).name
        logRepoId = id
        logWebBase = repos.firstOrNull { it.id == id }?.takeIf { it.isGitHub }?.webBase
        logTitle = "$displayName — branch commits"
        logCommits = emptyList()
        logLoading = true
        logOpen = true
        scope.launch {
            val c = LogOps.loadBranchLog(id, ref)
            logCommits = c
            logLoading = false
        }
    }

    /** Open the log viewer with the commits a submodule's pending pointer move would advance across. */
    fun openSubmoduleLog(parentId: String, sub: SubmoduleOps.Submodule) {
        val target = sub.remoteRef ?: return
        val subPath = File(parentId, sub.path).absolutePath
        logRepoName = "${File(parentId).name}/${sub.path}"
        logRepoId = subPath
        logWebBase = null   // submodule's own remote isn't scanned here; skip external links
        logTitle = "${sub.path}: pending pointer commits"
        logCommits = emptyList()
        logLoading = true
        logOpen = true
        scope.launch {
            val c = LogOps.loadRange(subPath, "${sub.recordedFull}..$target")
            logCommits = c
            logLoading = false
        }
    }

    fun closeLog() {
        logOpen = false
        logCommits = emptyList()
    }

    // ---- stash actions ----

    fun stashApply(id: String, ref: String) {
        val e = entries[id] ?: return
        scope.launch {
            val r = withContext(Dispatchers.Default) { scanLimiter.withPermit { RepoOps.stashApply(e, ref) } }
            rescanRepos(listOf(id), fetch = false)
            toast(r.message)
        }
    }

    fun stashDrop(id: String, ref: String) {
        val e = entries[id] ?: return
        scope.launch {
            val r = withContext(Dispatchers.Default) { scanLimiter.withPermit { RepoOps.stashDrop(e, ref) } }
            rescanRepos(listOf(id), fetch = false)
            toast(r.message)
        }
    }

    /** Commit [id] with the given message; optionally stage everything first. */
    fun commit(id: String, title: String, body: String, stageAll: Boolean) {
        val e = entries[id] ?: return
        if (title.isBlank()) return
        scope.launch {
            toast("Committing ${File(id).name}…")
            val result = withContext(Dispatchers.Default) { scanLimiter.withPermit { RepoOps.commit(e, title, body, stageAll) } }
            rescanRepos(listOf(id), fetch = false)
            toast(result.message)
        }
    }

    /** Fast-forward a repo to its upstream (only meaningful when behind>0 and ahead==0). */
    fun fastForward(id: String) {
        val e = entries[id] ?: return
        scope.launch {
            toast("Fast-forwarding ${File(id).name}…")
            val r = withContext(Dispatchers.Default) { scanLimiter.withPermit { RepoOps.fastForward(e) } }
            rescanRepos(listOf(id), fetch = false)
            toast(r.message)
        }
    }

    /** Fetch one repo and re-scan it (per-repo counterpart of "Fetch all"). */
    fun fetchRepo(id: String) {
        scope.launch {
            toast("Fetching ${File(id).name}…")
            rescanRepos(listOf(id), fetch = true)
            toast("Fetched ${File(id).name}")
        }
    }

    var refreshingId by mutableStateOf<String?>(null)
        private set

    /** Manual detail-panel refresh: re-scan the repo WITH fetch (so remote advances/behind are
     *  picked up too) and reload its branches + submodules. A safety net for anything the
     *  filesystem watcher didn't catch. */
    fun refreshRepo(id: String) {
        if (refreshingId != null) return
        refreshingId = id
        scope.launch {
            rescanRepos(listOf(id), fetch = true)   // also reloads branches + submodules (see rescanRepos)
            refreshingId = null
            toast("Refreshed ${File(id).name}")
        }
    }

    /** Re-scan the given repos in place (git state may have changed after push/fetch). */
    private suspend fun rescanRepos(ids: Collection<String>, fetch: Boolean) {
        val es = ids.mapNotNull { entries[it] }
        val scanned = withContext(Dispatchers.Default) {
            es.map { e -> async { scanLimiter.withPermit { runCatching { RepoScanner.scan(e, fetch) }.getOrNull() } } }.awaitAll()
        }.filterNotNull()
        scanned.forEach { s ->
            val idx = repos.indexOfFirst { it.id == s.id }
            if (idx >= 0) repos[idx] = s else repos.add(s)
            prevBehind[s.id] = s.behind   // keep baseline current so auto-refresh won't false-alert
        }
        reconcileDirtySince(scanned)
        // Keep the open detail panel's branch, submodule + worktree lists in sync — a rescan (from
        // a git action or an fs-watcher event, including submodule updates made outside the app)
        // can change branch tracking state, submodule pointers, and which worktrees exist, so
        // reload those too.
        branchesRepo?.let { open -> if (open in ids) reloadBranches(open) }
        submodulesRepo?.let { open -> if (open in ids) reloadSubmodules(open) }
        worktreesRepo?.let { open -> if (open in ids) reloadWorktrees(open) }
    }

    /** Track when each repo's working tree first went dirty (persisted), so the "Aging" signal and
     *  the "dirty for …" label reflect how long uncommitted work has been sitting. Reflects the new
     *  timestamp onto the in-memory repos so labels appear without waiting for the next scan. */
    private fun reconcileDirtySince(scanned: List<Repo>) {
        val now = System.currentTimeMillis()
        var changed = false
        scanned.forEach { r ->
            val e = entries[r.id] ?: return@forEach
            val dirty = r.staged + r.unstaged + r.untracked > 0
            val newSince = when {
                dirty && e.dirtySinceEpoch == null -> now
                !dirty -> null
                else -> e.dirtySinceEpoch
            }
            if (newSince != e.dirtySinceEpoch) { entries[r.id] = e.copy(dirtySinceEpoch = newSince); changed = true }
            val idx = repos.indexOfFirst { it.id == r.id }
            if (idx >= 0 && repos[idx].dirtySince != newSince) {
                repos[idx] = repos[idx].copy(dirtySince = newSince, dirtyFor = newSince?.let { Meta.compactDuration(now - it) })
            }
        }
        if (changed) persist()
    }

    private fun startAutoRefresh(intervalSeconds: Long = 300) {
        scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000)
                refreshAll(fetch = true)
            }
        }
    }

    /** Toolbar timestamp text. */
    fun fetchedLabel(): String {
        if (scanning) return "fetching…"
        if (lastFetchedEpoch == 0L) return "not fetched yet"
        val secs = (System.currentTimeMillis() - lastFetchedEpoch) / 1000
        val rel = when {
            secs < 5 -> "just now"
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            else -> "${secs / 3600}h ago"
        }
        return "fetched $rel"
    }

    // ---- derived views / filtering / grouping ----

    private fun views(): List<RepoView> = repos.map { deriveView(it, accent, tagsOf(it.id), ghSummary(it)) }

    /** Namespaces in first-seen order across all repo tags. */
    fun namespaces(): List<String> {
        val out = LinkedHashSet<String>()
        views().forEach { v -> v.tags.forEach { out.add(it.substringBefore(':')) } }
        return out.toList()
    }

    /** Global status counts — computed over all repos, unaffected by the active filter. */
    fun counts(): Map<String, Int> {
        val vms = views()
        return mapOf(
            "all" to vms.size,
            "dirty" to vms.count { it.isDirty },
            "aging" to vms.count { it.aging },
            "unpushed" to vms.count { it.ahead > 0 },
            "behind" to vms.count { it.repo.behind > 0 },
            "stale" to vms.count { it.isStale },
            // "problems" is the local git state that needs fixing (detached HEAD, no upstream, not
            // a repo) — distinct from "ghopen", which is the GitHub issue tracker. They were both
            // called "issues" until the GitHub integration landed and the collision became real.
            "problems" to vms.count { it.hasIssue },
            "ghopen" to vms.count { it.openIssues > 0 },
            "ghawaiting" to vms.count { it.awaitingYou > 0 },
            "stashes" to vms.count { it.repo.stash > 0 },
            "worktrees" to vms.count { it.repo.hasWorktrees },
            "reminders" to vms.count { it.hasReminder },
            "notes" to vms.count { noteOf(it.id).isNotBlank() },
            "snoozed" to vms.count { it.snoozed },
            "recent" to vms.count { isRecentlyAdded(it.id) },
            "recentmod" to vms.count { isRecentlyModified(it) },
        )
    }

    /** True if the repo's working tree changed within the [Meta.RECENT_MODIFIED_MS] window. */
    fun isRecentlyModified(v: RepoView): Boolean =
        v.repo.modifiedAt?.let { System.currentTimeMillis() - it < Meta.RECENT_MODIFIED_MS } ?: false

    /** True if a repo was first tracked within the "Recently Added" window (see [Meta.RECENT_MS]). */
    fun isRecentlyAdded(id: String): Boolean =
        entries[id]?.addedAt?.let { System.currentTimeMillis() - it.toEpochMilli() < Meta.RECENT_MS } ?: false

    private fun statusPredicate(v: RepoView): Boolean = when (status) {
        "dirty" -> v.isDirty
        "aging" -> v.aging
        "unpushed" -> v.ahead > 0
        "behind" -> v.repo.behind > 0
        "stale" -> v.isStale
        "problems" -> v.hasIssue
        "ghopen" -> v.openIssues > 0
        "ghawaiting" -> v.awaitingYou > 0
        "stashes" -> v.repo.stash > 0
        "worktrees" -> v.repo.hasWorktrees
        "reminders" -> v.hasReminder
        "notes" -> noteOf(v.id).isNotBlank()
        "snoozed" -> v.snoozed
        "recent" -> isRecentlyAdded(v.id)
        "recentmod" -> isRecentlyModified(v)
        else -> true
    }

    /** Tag filter: OR within a namespace, AND across namespaces. */
    private fun tagPredicate(v: RepoView): Boolean {
        // Negative filters win: a repo carrying any excluded tag is hidden outright.
        if (tagExclude.any { it in v.tags }) return false
        if (tagFilter.isEmpty()) return true
        val byNs = tagFilter.groupBy { it.substringBefore(':') }
        return byNs.values.all { group -> group.any { it in v.tags } }
    }

    /** Live text filter over name / branch / tags. */
    private fun searchPredicate(v: RepoView): Boolean {
        val q = searchText.trim().lowercase()
        if (q.isEmpty()) return true
        return v.repo.name.lowercase().contains(q) ||
            v.repo.branch.lowercase().contains(q) ||
            v.tags.any { it.lowercase().contains(q) }
    }

    /** Sort rank for "attention" order — mirrors the status-dot colours in [deriveView]:
     *  red (problem, or an important repo's issue awaiting you) → amber (dirty, unpushed,
     *  important-stale, or an issue awaiting you) → blue (behind, stale, or open issues) →
     *  green (clean). Keep the two in step. */
    private fun attentionRank(v: RepoView): Int = when {
        v.repo.warning != null || v.issueLevel == IssueLevel.CRITICAL -> 0
        v.isDirty || v.repo.ahead > 0 || (v.repo.stale && v.repo.staleImportant) ||
            v.issueLevel == IssueLevel.IMPORTANT -> 1
        v.repo.behind > 0 || v.repo.stale || v.issueLevel == IssueLevel.INFO -> 2
        else -> 3
    }

    private fun sorted(vs: List<RepoView>): List<RepoView> = when (sortBy) {
        "commit" -> vs.sortedWith(
            compareByDescending<RepoView> { it.repo.lastCommitEpoch ?: Long.MIN_VALUE }
                .thenBy { it.repo.name.lowercase() })
        "attention" -> vs.sortedWith(
            compareBy<RepoView> { attentionRank(it) }.thenBy { it.repo.name.lowercase() })
        else -> vs.sortedBy { it.repo.name.lowercase() }
    }

    fun filtered(): List<RepoView> =
        sorted(views().filter { statusPredicate(it) && tagPredicate(it) && searchPredicate(it) })

    /** Grouped list. None = single flat group; a namespace = one section per tag value
     *  (a repo appears under every value it has), with an "untagged" catch-all. */
    fun groups(): List<RepoGroup> {
        val f = filtered()
        if (groupBy == "none") {
            return listOf(RepoGroup("", "", showHeader = false, repos = f))
        }
        val vals = LinkedHashSet<String>()
        f.forEach { v -> v.tags.filter { it.substringBefore(':') == groupBy }.forEach { vals.add(it.substringAfter(':')) } }
        val out = vals.sorted().map { value ->
            RepoGroup(value, groupBy, showHeader = true, repos = f.filter { "$groupBy:$value" in it.tags })
        }.toMutableList()
        val untagged = f.filter { v -> v.tags.none { it.substringBefore(':') == groupBy } }
        if (untagged.isNotEmpty()) out.add(RepoGroup("untagged", groupBy, showHeader = true, repos = untagged))
        return out
    }

    fun selected(): RepoView? = selectedId?.let { id -> views().find { it.id == id } }

    /** Tag chips for the tag-filter bar, grouped by namespace. */
    fun namespaceGroups(): List<Pair<String, List<TagChip>>> {
        val vms = views()
        return namespaces().map { ns ->
            val vals = LinkedHashSet<String>()
            vms.forEach { v -> v.tags.forEach { if (it.substringBefore(':') == ns) vals.add(it.substringAfter(':')) } }
            ns to vals.map { tagStyle("$ns:$it") }
        }
    }

    /** Plain click on a tag chip: toggle it as an *include* filter (and drop any exclude on it). */
    fun toggleTagFilter(t: String) {
        tagFilter = if (t in tagFilter) tagFilter - t else tagFilter + t
        if (t in tagExclude) tagExclude = tagExclude - t
    }

    /** Ctrl/⌘/Shift click on a tag chip: toggle it as an *exclude* filter (and drop any include). */
    fun toggleTagExclude(t: String) {
        tagExclude = if (t in tagExclude) tagExclude - t else tagExclude + t
        if (t in tagFilter) tagFilter = tagFilter - t
    }

    fun clearTags() { tagFilter = emptySet(); tagExclude = emptySet() }
}

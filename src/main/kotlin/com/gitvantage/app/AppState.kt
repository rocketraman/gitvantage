// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import com.gitvantage.git.BranchOps
import com.gitvantage.git.DiffOps
import com.gitvantage.git.Git
import com.gitvantage.git.GitLog
import com.gitvantage.git.LogOps
import com.gitvantage.git.RepoOps
import com.gitvantage.git.RepoScanner
import com.gitvantage.git.SubmoduleOps
import com.gitvantage.git.WorktreeOps
import com.gitvantage.git.model.Branch
import com.gitvantage.git.model.Commit
import com.gitvantage.git.model.Diff
import com.gitvantage.git.model.RemoteBranch
import com.gitvantage.git.model.Submodule
import com.gitvantage.model.FetchPolicy
import com.gitvantage.model.Meta
import com.gitvantage.model.RegistryEntry
import com.gitvantage.model.Reminder
import com.gitvantage.model.Repo
import com.gitvantage.model.RepoCandidate
import com.gitvantage.model.Stash
import com.gitvantage.model.WatchAction
import com.gitvantage.model.WatchPolicy
import com.gitvantage.model.Worktree
import com.gitvantage.model.WorktreeAlert
import com.gitvantage.model.WorktreeAlerts
import com.gitvantage.model.WorktreeChange
import dev.nucleusframework.fswatcher.FsWatchDeliveryMode
import dev.nucleusframework.fswatcher.FsWatchError
import dev.nucleusframework.fswatcher.FsWatchEvent
import dev.nucleusframework.fswatcher.FsWatchRegistration
import dev.nucleusframework.fswatcher.FsWatcher
import dev.nucleusframework.fswatcher.FsWatcherConfig
import dev.nucleusframework.fswatcher.FsWatchers
import java.io.File
import java.nio.file.Path
import java.time.Duration
import com.gitvantage.git.model.GitCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/** A transient modal driven by [AppState.popup]; targets the given repo [ids]. */
sealed interface Popup {
    val ids: Set<String>
    data class Tag(override val ids: Set<String>) : Popup
    data class Untag(override val ids: Set<String>) : Popup
    data class Snooze(override val ids: Set<String>) : Popup
    data class Remind(override val ids: Set<String>, val text: String, val due: Long?) : Popup

    /**
     * The working-note editor for one repo.
     *
     * A dialog now rather than a text area mounted permanently in the detail pane. The pane shows
     * what the note *says* in one line, which is what it's read for; a multi-line editor is what it
     * needs when you're actually writing one, and a dialog is where there's room for that.
     */
    data class Note(val id: String) : Popup { override val ids: Set<String> = setOf(id) }
    data class Commit(val id: String) : Popup { override val ids: Set<String> = setOf(id) }

    /**
     * Commit inside one of [repoId]'s linked worktrees. Carries the worktree's path and the branch
     * it holds because there is no [Repo] to look either up on — that is the point of the redesign,
     * and the dialog needs the same two facts a repo commit reads off its row.
     */
    data class CommitWorktree(val repoId: String, val path: String, val branch: String) : Popup {
        override val ids: Set<String> = setOf(repoId)
    }

    /** Snooze one worktree's alerts, from its Alerts popover. Same duration menu as [Snooze]. */
    data class SnoozeWorktree(val repoId: String, val path: String, val label: String) : Popup {
        override val ids: Set<String> = setOf(repoId)
    }
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
 * The marker the old model stamped on a worktree tracked as a repo of its own, kept only so
 * [AppState.foldTrackedWorktrees] can strip it on upgrade. Nothing writes it any more: a worktree
 * isn't a repo, so there is no row left for it to label.
 */
private const val LEGACY_WORKTREE_TAG = "tag:worktree"

/**
 * How long a coalesced registry write waits (see [AppState.persistDebounced]). Long enough that
 * ordinary typing lands as one write, short enough that a pause mid-note still reaches disk well
 * before the user does anything else about it.
 */
private const val SAVE_DEBOUNCE_MS = 500L

/**
 * The [AppState.watchBursts] key standing for "every tracked repo", used when the watcher reports
 * dropping events without saying which repo they belonged to.
 *
 * A NUL is the one character a filesystem path cannot contain, so this can never collide with a
 * repo id — which is what lets the sweep share the per-repo debounce instead of needing its own.
 */
private const val ALL_REPOS = "\u0000all-repos"

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
    /**
     * Remember the window size. Recorded now, written soon — the one settings write that lands
     * repeatedly during a single interaction.
     *
     * It is driven by a `snapshotFlow` over the window size debounced at 600ms (see `Main.kt`), so a
     * drag-resize is a run of these, each of which used to encode the registry and `fsync` it on the
     * UI thread — inside the very gesture whose smoothness is the thing being judged. The others
     * (`setSort`, the settings toggles) fire once per deliberate click and keep writing inline,
     * where an immediate answer is worth more than the microseconds it costs.
     */
    fun saveWindowSize(w: Int, h: Int) {
        Registry.recordSettings { it.copy(windowWidth = w, windowHeight = h) }
        writeQueue.trySend(Unit)
    }

    // Branches for the currently-open detail panel (loaded lazily on selection).
    var branchesRepo by mutableStateOf<String?>(null)
        private set
    var branches by mutableStateOf<List<Branch>>(emptyList())
        private set
    var branchesLoading by mutableStateOf(false)
        private set
    var remoteBranches by mutableStateOf<List<RemoteBranch>>(emptyList())
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
    var submodules by mutableStateOf<List<Submodule>>(emptyList())
        private set
    var submodulesBusy by mutableStateOf(false)
        private set

    // Working trees for the currently-open detail panel (loaded lazily on selection).
    var worktreesRepo by mutableStateOf<String?>(null)
        private set
    var worktrees by mutableStateOf<List<Worktree>>(emptyList())
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
    /**
     * The git console's contents. Surfaced here rather than let the console reach into [GitLog]
     * directly: the view layer talks to application state, and that is what keeps "the UI never
     * reaches the git layer" true without an exception written into the architecture tests.
     */
    val gitLog: StateFlow<List<GitCommand>> get() = GitLog.entries

    fun clearConsole() = GitLog.clear()
    fun resizeConsole(deltaDp: Float) { consoleHeight = (consoleHeight + deltaDp).coerceIn(120f, 620f) }
    var diffRepoName by mutableStateOf("")
        private set
    var diffTitle by mutableStateOf("Changes")
        private set
    var diffLoading by mutableStateOf(false)
        private set
    var diff by mutableStateOf(Diff(emptyList(), emptyList()))
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
    var logCommits by mutableStateOf<List<Commit>>(emptyList())
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
    /**
     * Where every part of the watcher runs: receiving events, registering and closing watches, and
     * the loop that decides when a burst has ended.
     *
     * Not [scope]. That is the composition's UI dispatcher, and the whole of this used to run on it
     * — an event arrives for every file written anywhere under any tracked repo, so a build, an
     * `npm ci` or a dev server put tens of thousands of them through the UI thread, each one
     * cancelling and allocating a coroutine. Registration ran there too, and takes a lock the
     * watcher's own callback thread holds while delivering events, so opening the app with every
     * repo to register contended directly against the firehose.
     *
     * Only the scan a burst finally earns goes back to [scope], because that is the part that writes
     * observable state.
     */
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Where the registry is actually written. Its own scope because the write outlives [scope] by
     * design — see [flushPendingWrites], which runs as the composition is being torn down.
     */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Requests to get what has been recorded onto disk. Carries nothing, because there is nothing to
     * carry: [Registry] holds the document, so a request is only ever "it changed, write it".
     *
     * Conflated for the same reason. Two pending requests would write the same file twice, so the
     * second is not work still owed — it is work the first will already have done.
     */
    private val writeQueue = Channel<Unit>(Channel.CONFLATED)

    // Both live up here, away from the persistence functions that use them, because `init` starts
    // the writer and Kotlin runs initializers in declaration order: declared alongside [persist]
    // they are still null when `init` reaches them, and the app dies constructing its own state.

    /** Volatile because [syncWatches] now reads it from [watchScope] rather than from the caller. */
    @Volatile
    private var fsWatcher: FsWatcher? = null
    private val watchRegs = HashMap<String, FsWatchRegistration>()  // repo id -> registration
    /** Serializes [syncWatches]; [watchRegs] is touched from watch registration alone. */
    private val watchMutex = Mutex()

    /**
     * Repos with a burst in progress, and when each one is due to be scanned.
     *
     * A map plus one loop, rather than the coroutine-per-event this replaces: an event now costs a
     * hash write and a wakeup nudge instead of a [Job] cancellation and allocation, which is what
     * makes the cost of a burst proportional to the repos in it rather than to the files written.
     * Concurrent because the watcher's collector writes it while [watchDrainLoop] reads it.
     */
    private val watchBursts = ConcurrentHashMap<String, WatchBurst>()

    /**
     * The wakeup nudge for [watchDrainLoop]. Conflated on purpose: it carries no information beyond
     * "the map changed, look again", so a burst of events collapses to a single pending signal and
     * an event never blocks on a full channel.
     */
    private val watchWake = Channel<Unit>(Channel.CONFLATED)

    /** One repo's in-progress burst: the ceiling it must scan by, and when it is currently due. */
    private class WatchBurst(val deadline: Long, @Volatile var dueAt: Long)

    /**
     * Each repo's nested worktrees, as [actionFor] needs them — kept beside [repos] rather than read
     * out of it.
     *
     * [repos] is Compose state and a list, so consulting it per event meant a linear scan of every
     * tracked repo on whichever thread the event landed on. This is one hash lookup, off the
     * snapshot system entirely, which is what lets the collector run without touching UI state at
     * all.
     */
    private val nestedByRepo = ConcurrentHashMap<String, List<String>>()

    init {
        // Load only what the user has curated. A missing/empty registry stays empty —
        // we do NOT auto-discover, so deleting registry.json gives a clean slate.
        // (Bulk import is still available by picking a parent folder in "Add repo".)
        Notify.init()
        Registry.load().forEach { entries[it.path] = it; order.add(it.path) }
        // An empty list because the registry wouldn't parse looks identical to an empty list
        // because you haven't added anything yet. Say which one this is, and don't time it out.
        Registry.loadFailure?.let { toast(it, sticky = true) }
        startRegistryWriter()
        refreshAll(fetch = false)   // fast initial scan (no network)
        startAutoRefresh()
        startGitHubRefresh()
        startSelectionFetch()
        startReminderChecker()
        startFsWatcher()
    }

    // ---- filesystem watcher: rescan a repo shortly after its files change ----

    private fun startFsWatcher() {
        // Not the library defaults: a dashboard watching every tracked repo recursively overruns a
        // 64-event buffer on any build, and what it loses when it does is silent. See [WatchPolicy].
        val config = FsWatcherConfig(
            eventBufferCapacity = WatchPolicy.EVENT_BUFFER,
            deliveryMode = FsWatchDeliveryMode.Debounced(Duration.ofMillis(WatchPolicy.NATIVE_DEBOUNCE_MS)),
        )
        if (!runCatching { FsWatchers.isSupported(config) }.getOrDefault(false)) return
        val w = runCatching { FsWatchers.create(config) }.getOrNull() ?: return
        fsWatcher = w
        // Collect BEFORE registering paths (events are a hot flow with no replay).
        watchScope.launch {
            runCatching {
                w.events.collect { ev ->
                    when (val action = actionFor(ev)) {
                        is WatchAction.Rescan -> noteWatchEvent(action.id)
                        WatchAction.RescanAll -> scheduleFullRescan()
                        WatchAction.Ignore -> Unit
                    }
                }
            }
        }
        // The watcher's own failures, which nothing used to read. A watch that dies takes the repo's
        // live refresh with it, and the dashboard then goes quietly stale — the same class of silent
        // loss as the dropped overflow, and just as invisible without somewhere for it to surface.
        watchScope.launch { runCatching { w.errors.collect(::reportWatchError) } }
        watchScope.launch { watchDrainLoop() }
        syncWatches()
    }

    /** Set once a watcher failure has been surfaced, so a failing watch reports once, not per event. */
    private var watchErrorReported = false

    /**
     * Report a watcher failure.
     *
     * Recoverable ones go to stderr only — the watcher is saying it hit something and carried on,
     * and a toast for each would train the user to dismiss the one that matters. stderr for the same
     * reason [pickAndAddRepo] uses it: the app deliberately carries no logging framework, and a
     * terminal run is where this is worth having.
     *
     * An unrecoverable one is worth interrupting for, because the symptom otherwise is a dashboard
     * that simply stops noticing changes. Said once: a broken watch can report per event, and a
     * toast per event is not a report.
     *
     * Arrives on the watcher's thread, so the toast is handed to [scope] — it is the one line here
     * that writes state the UI renders.
     */
    private fun reportWatchError(e: FsWatchError) {
        val kind = if (e.recoverable) "warning" else "error"
        System.err.println(
            "GitVantage: filesystem watch $kind${e.source?.name?.let { " on $it" }.orEmpty()} — ${e.message}",
        )
        if (e.recoverable || watchErrorReported) return
        watchErrorReported = true
        scope.launch { toast("Live refresh stopped for one or more repos — reopen GitVantage to restore it") }
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

    /**
     * Register/unregister repo watches so they match the tracked set.
     *
     * Every part of this is slow or contended and none of it belongs on the UI thread: [watchRootFor]
     * touches the filesystem, and `watch` walks the tree while holding the same lock the watcher's
     * callback thread takes to deliver events. Doing it for every repo at startup was a stall
     * proportional to how many repos the user tracks, which is exactly the axis that was reported.
     *
     * The tracked set is read on the caller's thread — it is [order], which is Compose state — and
     * the registration itself is handed to [watchScope], serialized by [watchMutex] so overlapping
     * calls from `init`, an add and a remove cannot interleave over [watchRegs].
     */
    private fun syncWatches() {
        val want = order.toSet()
        // Pruned here because every path that stops tracking a repo comes through this function,
        // so one line covers them all and cannot be forgotten by the next one that does.
        nestedByRepo.keys.retainAll(want)
        watchScope.launch {
            watchMutex.withLock {
                val w = fsWatcher ?: return@withLock
                want.forEach { id ->
                    if (id !in watchRegs) {
                        runCatching { watchRegs[id] = w.watch(watchRootFor(id), true, id) }  // name = id, for event mapping
                    }
                }
                (watchRegs.keys - want).forEach { id ->
                    runCatching { watchRegs.remove(id)?.close() }
                    watchBursts.remove(id)
                }
            }
        }
    }

    /**
     * What [ev] asks of the app — see [WatchPolicy.actionFor].
     *
     * The filtering half of that decision is worth more than the scans it saves. A coding session in
     * a worktree nested under a repo writes more or less continuously, and each of those events used
     * to push the parent's debounce back another quiet period, so the parent stopped being rescanned
     * for as long as the session ran and the user's own uncommitted changes never reached the detail
     * panel. [WatchPolicy] bounds that on its own now; this keeps the pointless scans from being
     * scheduled at all.
     *
     * Only worktrees a scan has already found are filtered, so one that has just appeared still gets
     * through and triggers the scan that discovers it.
     */
    private fun actionFor(ev: FsWatchEvent): WatchAction = WatchPolicy.actionFor(
        id = ev.source?.name,
        needsRescan = ev.needsRescan,
        nested = ev.source?.name?.let(nestedByRepo::get).orEmpty(),
        paths = { eventPaths(ev) },
    )

    /** The paths an [FsWatchEvent] concerns; empty for an Overflow, which names none. */
    private fun eventPaths(ev: FsWatchEvent): List<Path> = when (ev) {
        is FsWatchEvent.Created -> listOf(ev.path)
        is FsWatchEvent.Modified -> listOf(ev.path)
        is FsWatchEvent.Removed -> listOf(ev.path)
        is FsWatchEvent.Moved -> listOf(ev.from, ev.to)
        is FsWatchEvent.Other -> ev.paths
        is FsWatchEvent.Overflow -> emptyList()
    }

    /**
     * Record that [id] saw a file event, pushing its scan out to the end of the quiet period.
     *
     * All an event costs now. The debounce it feeds is the same one [WatchPolicy] has always
     * described — quiet period, with a ceiling so a burst cannot postpone its scan forever — but the
     * waiting is done once by [watchDrainLoop] instead of by a coroutine per event that cancels the
     * one before it. That is the difference between a burst costing what its repos cost and a burst
     * costing what its *files* cost, which is what a build made unaffordable.
     *
     * Runs on the watcher's thread and touches no Compose state: [nestedByRepo] carries what
     * [actionFor] needs, and whether the repo is still tracked is settled by [watchDrainLoop] on
     * the way out, where [entries] can be read safely.
     */
    private fun noteWatchEvent(id: String) {
        val now = System.currentTimeMillis()
        val burst = watchBursts.computeIfAbsent(id) { WatchBurst(WatchPolicy.deadlineFor(now), now) }
        burst.dueAt = WatchPolicy.dueAt(now, burst.deadline)
        watchWake.trySend(Unit)
    }

    /**
     * The one loop that turns bursts into scans: sleep until the earliest repo is due, scan whatever
     * has come due, sleep again.
     *
     * It sleeps rather than ticks — with nothing pending it blocks on [watchWake] and costs nothing
     * at all, which matters for an app that sits open all day. A new event only has to nudge it
     * awake to be taken into account, because the map already holds the answer.
     *
     * Due repos are scanned as one batch, so a checkout that touches ten repos is one pass through
     * [scanLimiter] rather than ten queued behind it. The hop to [scope] is deliberate and is the
     * only one: [rescanRepos] writes the repo list, the registry and the open detail panel, all of
     * which the UI renders.
     */
    private suspend fun watchDrainLoop() {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val due = watchBursts.entries.filter { now >= it.value.dueAt }.map { it.key }
            // Removed as the scan starts, so the next event opens a fresh burst window rather than
            // inheriting a deadline that has already passed (which would scan on every event).
            due.forEach { watchBursts.remove(it) }
            if (due.isNotEmpty()) {
                // Untracked repos are dropped here rather than at the event, because `entries` is
                // Compose state and this is the point at which we are back on its thread anyway.
                withContext(scope.coroutineContext) {
                    val ids =
                        if (ALL_REPOS in due) order.toList()
                        else due.filter { entries[it] != null }
                    if (ids.isNotEmpty()) rescanRepos(ids, fetch = false)
                }
            }
            when (val next = watchBursts.values.minOfOrNull { it.dueAt }) {
                null -> watchWake.receive()                                        // idle: wait for an event
                else -> withTimeoutOrNull(next - System.currentTimeMillis()) { watchWake.receive() }
            }
        }
    }

    /**
     * Rescan every tracked repo, after the watcher reported dropping events without saying whose.
     *
     * A burst like any other, under a key no repo path can take, so it inherits the debounce, the
     * ceiling and the batching from [watchDrainLoop] rather than restating them. That matters more
     * here than for a single repo: an overflow only happens when the app is already failing to keep
     * up, and answering each one with an immediate sweep of every repo is how a dropped event turns
     * into a scan storm that guarantees the next one. The ceiling still applies, so a tree churning
     * hard enough to overflow repeatedly cannot postpone the sweep it is the whole reason for.
     *
     * Not a fetch: the watcher is reporting local filesystem churn, and a sweep that opened a
     * network connection per repo would make an overload worse in a way the user pays for twice.
     */
    private fun scheduleFullRescan() = noteWatchEvent(ALL_REPOS)

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
                kind = if (r.staleImportant) Notify.Kind.ALERT else Notify.Kind.INFO,
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
                delay(30_000.milliseconds)
            }
        }
    }

    /** Fire a reminder notification with Done (clear it) / Snooze 1h (re-fire an hour from the
     *  click) actions. Firing already rescheduled to +1h, but the button re-stamps from *now* so
     *  its label stays true however long the notification sat unread — otherwise "Snooze 1h" would
     *  mean 20 minutes to someone who came back to it 40 minutes late.
     *  Action callbacks arrive on a notification thread, so they marshal back onto [scope]. */
    private fun fireReminder(e: RegistryEntry) {
        val id = e.path
        Notify.show(
            title = "Reminder — ${File(id).name}",
            message = e.reminderText,
            kind = Notify.Kind.REMINDER,
            onClick = { scope.launch { selectedId = id } },
            buttons = listOf(
                "Done" to { scope.launch { clearReminder(listOf(id)) } },
                "Snooze 1h" to { scope.launch { remindAt[id] = System.currentTimeMillis() + remindAgainMs } },
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

    /** Edited a character at a time from the detail panel's notes field, so the write is
     *  coalesced — see [persistDebounced]. */
    fun setNote(id: String, text: String) {
        val e = entries[id] ?: return
        entries[id] = e.copy(note = text)
        persistDebounced()
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

    // ---- worktree alerts and disclosure --------------------------------------------------

    /**
     * One worktree's overrides, from its parent's registry entry. An absent key is all-inherit, so a
     * repo whose worktrees were never touched — nearly every repo — answers from a default instance
     * without storing anything.
     */
    fun worktreeAlerts(repoId: String, path: String): WorktreeAlerts =
        entries[repoId]?.worktreeAlerts?.get(path) ?: WorktreeAlerts()

    /**
     * Store (or drop) one worktree's overrides.
     *
     * An entry that has gone back to all-inherit is removed rather than written as an empty object,
     * for the same reason [setHideBranchPatterns] collapses a list matching the defaults: otherwise
     * setting an override and clearing it again leaves the worktree permanently marked as
     * customised, still wearing its ⚙ and still offering "Reset to inherit".
     */
    private fun setWorktreeAlerts(repoId: String, path: String, alerts: WorktreeAlerts) {
        val e = entries[repoId] ?: return
        entries[repoId] = e.copy(
            worktreeAlerts = if (alerts.isDefault) e.worktreeAlerts - path else e.worktreeAlerts + (path to alerts),
        )
        persist()
    }

    /** Set one alert to On (true), Off (false) or Inherit (null) for one worktree. */
    fun setWorktreeAlert(repoId: String, path: String, alert: WorktreeAlert, value: Boolean?) =
        setWorktreeAlerts(repoId, path, worktreeAlerts(repoId, path).with(alert, value))

    /**
     * Drop every alert override on one worktree, leaving its own snooze alone.
     *
     * The snooze survives deliberately: it has its own control (the banner's "Resume now") and its
     * own meaning — "quiet for two days" is a different statement from "answer for yourself", and a
     * Reset that silently un-snoozed would fire the alerts the user had just asked to stop.
     */
    fun resetWorktreeAlerts(repoId: String, path: String) =
        setWorktreeAlerts(repoId, path, worktreeAlerts(repoId, path).copy(aging = null, unlanded = null, reminders = null))

    /** Snooze (or resume, with null) one worktree's alerts, leaving the rest of the repo running. */
    fun setWorktreeSnooze(repoId: String, path: String, until: Long?) {
        setWorktreeAlerts(repoId, path, worktreeAlerts(repoId, path).copy(snoozeUntilEpoch = until))
        toast(if (until == null) "Resumed ${File(path).name}" else "Snoozed ${File(path).name}")
    }

    /**
     * Whether a repo's worktree sub-rows are showing in the table.
     *
     * The Worktrees filter forces them open without touching the stored choice: the filter is a
     * question ("show me what's checked out elsewhere") and answering it by rewriting every matching
     * repo's preference would leave the list permanently expanded once the filter was cleared.
     */
    fun worktreeRowsOpen(id: String): Boolean =
        status == "worktrees" || entries[id]?.worktreesExpanded == true

    fun toggleWorktreeRows(id: String) {
        val e = entries[id] ?: return
        entries[id] = e.copy(worktreesExpanded = !worktreeRowsOpen(id))
        persist()
    }

    /** Whether the detail pane's Settings disclosure is open (remembered app-wide). */
    var settingsExpanded by mutableStateOf(Registry.settings().settingsExpanded)
        private set

    fun toggleSettingsExpanded() {
        settingsExpanded = !settingsExpanded
        Registry.saveSettings(Registry.settings().copy(settingsExpanded = settingsExpanded))
    }

    /**
     * How many of a repo's settings differ from their defaults — the "N customized" chip on the
     * collapsed Settings row.
     *
     * Counts the settings the disclosure actually contains, so the number and what's behind the
     * chevron can't disagree. A snooze deliberately doesn't count — it isn't in there any more: it's
     * a button in the action row that says how long it's silenced for, and its own banner underneath.
     * Counting it here would put a number on the chip with nothing behind the chevron to explain it.
     */
    fun customizedSettings(id: String): Int {
        val e = entries[id] ?: return 0
        return listOf(
            e.staleThresholdDays != null,
            e.staleImportant,
            e.issuesTracked != null,
            e.issuesImportant,
            e.repoRole != null,
            e.ignoreLabels.isNotEmpty(),
            e.hideBranchPatterns != null,
            e.notifyUpstream,
            e.worktreeAlerts.isNotEmpty(),
        ).count { it }
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

    /**
     * This repo's stated role — [ROLE_MINE], [ROLE_CONTRIBUTING], or null for "work it out from
     * my push access". See [RegistryEntry.repoRole].
     */
    fun repoRole(id: String): String? = entries[id]?.repoRole

    /**
     * Which way an unstated repo was read from your push access — true for maintained, false for
     * contributed, null when nothing has been fetched for it yet. Only meaningful while
     * [repoRole] is null; the UI uses it to say what "Auto" currently resolves to.
     */
    fun repoRoleInferred(repo: Repo): Boolean? =
        GitHub.coordOf(repo.webBase)?.let { GitHub.inferredMine(it) }

    /** [repoRole] as the fetch layer wants it: true/false when stated, null to infer. */
    private fun repoRoleIsMine(id: String): Boolean? = when (repoRole(id)) {
        Meta.ROLE_MINE -> true
        Meta.ROLE_CONTRIBUTING -> false
        else -> null
    }

    /**
     * Set (or clear, with null) what this repo is to you. Refetches, because the role decides
     * which items are asked for at all — unlike the presentational toggles, the cached data for
     * the other role simply isn't there to re-derive from.
     */
    fun setRepoRole(id: String, role: String?) {
        val e = entries[id] ?: return
        if (e.repoRole == role) return
        entries[id] = e.copy(repoRole = role)
        // The cached items were fetched under the old role, so they're the wrong set entirely —
        // a maintained repo's full tracker, or a contributed repo's handful. Drop them rather
        // than leave them on screen looking authoritative until the refetch lands.
        githubState.remove(id)
        persist()
        refreshGitHub()
    }

    /** Labels whose issues and PRs this repo leaves out. See [RegistryEntry.ignoreLabels]. */
    fun ignoreLabels(id: String): List<String> = entries[id]?.ignoreLabels ?: emptyList()

    /**
     * Every label seen on this repo's fetched items, for the ignore-list autocomplete. Drawn from
     * what came back rather than from the repo's full label set: it costs no extra request, and
     * the labels actually in use on open items are the ones worth offering — a tracker's label
     * list is mostly historical.
     */
    fun knownLabels(repo: Repo): List<String> {
        val st = githubState[repo.id] ?: return emptyList()
        return (st.issues + st.prs).flatMap { it.labels }.distinct().sortedBy { it.lowercase() }
    }

    /**
     * Replace the ignored-label list. No refetch: labels come back with every item, so this
     * re-derives from what's already cached — which is what makes editing the list feel immediate
     * rather than costing a round trip per keystroke.
     */
    fun setIgnoreLabels(id: String, labels: List<String>) {
        val e = entries[id] ?: return
        val cleaned = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (e.ignoreLabels == cleaned) return
        entries[id] = e.copy(ignoreLabels = cleaned)
        persist()
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
        val ignored = ignoreLabels(repo.id)
        // Label filtering happens before the "only mine" one and applies whatever it's set to:
        // an ignored label says "this category is never mine to answer", which is a statement
        // about the item, not about which view you're looking at.
        val kept = { it: GitHub.Item -> !it.hasAnyLabel(ignored) && (!mine || it.involvesYou) }
        val issues = st.issues.filter(kept)
        val prs = st.prs.filter(kept)
        // GitHub's totalCount is authoritative for "how many are open", but it can't be filtered
        // client-side — so once anything *is* filtered, the fetched-and-filtered list is the count.
        val filtering = mine || ignored.isNotEmpty()
        val openIssues = if (filtering) issues.size else st.issueTotal
        val openPrs = if (filtering) prs.size else st.prTotal
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
            countIsFloor = filtering && capped,
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
                    .mapNotNull { r ->
                        GitHub.coordOf(r.webBase)?.let { r.id to it.copy(mine = repoRoleIsMine(r.id)) }
                    }
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

    // ---- persistence -------------------------------------------------------------------------

    /** In flight [persistDebounced] timer, if any. Not observable — nothing renders it. */
    private var saveJob: Job? = null

    private fun startRegistryWriter() {
        writeScope.launch {
            for (request in writeQueue) {
                Registry.flush().onFailure { e ->
                    // Off the UI thread now, and a toast is state the UI renders.
                    scope.launch { toast("Couldn't save ${Registry.file.name}: ${e.message ?: e::class.simpleName}") }
                }
            }
        }
    }

    /** Record the current registry and ask for it to reach disk. See [persist] for the why. */
    private fun requestWrite() {
        Registry.recordRepos(order.mapNotNull { entries[it] })
        writeQueue.trySend(Unit)
    }

    /**
     * Write now. Cancels any deferred write first: this encodes the entire registry from the
     * current [entries], so whatever that timer was going to write is already included.
     *
     * "Now" means the edit is recorded now, not that the disk write happens before this returns.
     * Building the document and calling `fsync` on it used to happen inline on the UI thread, which
     * meant every tag, every snooze and — through [reconcileDirtySince] — every scan that found a
     * tree had gone dirty stalled the interface for as long as the filesystem took to answer. That
     * is unbounded on a busy or networked disk, and it fires many times a minute across a dashboard
     * of active repos.
     *
     * Reading [entries] stays here, because it is Compose state and this is its thread.
     */
    private fun persist() {
        saveJob?.cancel(); saveJob = null
        requestWrite()
    }

    /**
     * Same, but coalesced — for state that changes on every keystroke. [entries] is still updated
     * synchronously (the UI reads it straight back), only the snapshot waits, and each call restarts
     * the timer so a burst of typing costs one write instead of one per character.
     *
     * Still worth having on top of the conflated queue: this keeps the document from being *built*
     * per keystroke, where the queue only keeps it from being written.
     *
     * A write left pending when the window closes is flushed by [flushPendingWrites].
     */
    private fun persistDebounced() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS.milliseconds)
            saveJob = null
            persist()
        }
    }

    /**
     * Flush pending writes synchronously. Called from the window's close handler, where [scope] is
     * about to be cancelled along with the composition — a pending timer would never fire, and the
     * note the user just typed would be lost.
     *
     * This one really does write before it returns, and on the caller's thread: the process is about
     * to exit, so handing the work to [writeScope] would be a way of not doing it. A queued write
     * still in flight is harmless — both write the same recorded document.
     *
     * Unconditional, where it used to run only for a pending debounce. Now that [persist] only
     * records the edit and asks for a write, an ordinary change made in the last moments before
     * closing can still be waiting on that queue — so "nothing was typed recently" no longer means
     * "nothing is unwritten".
     */
    fun flushPendingWrites() {
        saveJob?.cancel(); saveJob = null
        Registry.recordRepos(order.mapNotNull { entries[it] })
        Registry.flush()
    }

    // ---- scanning ----

    /** Re-scan every registered repo concurrently. Git runs on Default/IO; the state
     *  updates run on [scope] (the UI dispatcher) so the list actually recomposes. */
    fun refreshAll(fetch: Boolean, userInitiated: Boolean = true) {
        if (scanning) return
        scope.launch {
            scanning = true
            try {
                val snapshot = order.mapNotNull { entries[it] }
                val results = withContext(Dispatchers.Default) {
                    snapshot.map { e ->
                        async {
                            scanLimiter.withPermit {
                                runCatching { RepoScanner.scan(e, fetch, userInitiated) }.getOrNull()
                            }
                        }
                    }.awaitAll()
                }.filterNotNull().associateBy { it.id }
                val ordered = order.mapNotNull { results[it] }
                repos.clear()
                repos.addAll(ordered)
                rememberNested(ordered)
                // Needs a completed scan to know which entries are worktrees of which; runs once.
                foldTrackedWorktrees()
                // Before notifyUpstreamAdvances moves the prevBehind baseline it compares against.
                if (fetch) stampFetched(ordered, System.currentTimeMillis())
                notifyUpstreamAdvances(ordered)
                notifyStaleCrossings(ordered)
                reconcileDirtySince(ordered)
                lastFetchedEpoch = System.currentTimeMillis()
                // Poll GitHub alongside a refresh the *user* asked for, so the Refresh button and
                // startup update the PR pills as well as the git state, and so the poll runs after
                // `repos` is populated — that is what tells it which repos have GitHub remotes.
                //
                // It no longer rides the background timer. `gh` is rate-limited per token and has
                // nothing to do with git transport, so it keeps its own cadence in
                // [startGitHubRefresh] rather than inheriting whatever the fetch policy decides.
                // Fire-and-forget: it has its own in-flight guard and must never hold up the git
                // dashboard.
                if (userInitiated) refreshGitHub()
            } finally {
                scanning = false
            }
        }
    }

    /** "Add repo" step 1: show FileKit's native single-directory picker for a *parent*
     *  folder, then open the repo chooser listing the git repos found beneath it.
     *  (FileKit has no multi-directory mode, so we discover-then-multi-select instead.)
     *
     *  The picker reaches the OS through JNA and platform-specific native interfaces, and in a
     *  GraalVM native image any of that can be missing (see [FolderPicker] and the checks in
     *  `com.gitvantage.smoke`). Launched bare, such a failure would go to the coroutine scope and
     *  never be seen — leaving a button that does nothing and says nothing, which is a far harder
     *  thing to report than an error message.
     */
    fun pickAndAddRepo() {
        scope.launch {
            val picked = runCatching {
                withContext(Dispatchers.IO) { FolderPicker.pickParent(Registry.defaultRoot().absolutePath) }
            }
            // A null result is the user cancelling, and is the common case — only a thrown failure
            // is worth saying anything about.
            val parent = picked.getOrElse { e ->
                // Also to stderr: the app deliberately carries no logging framework, and this is
                // the one thing worth having when someone runs it from a terminal to find out why
                // "Add repo" did nothing.
                System.err.println("GitVantage: the folder picker failed to open — $e")
                e.printStackTrace()
                toast("Couldn't open the folder picker: ${e.message ?: e::class.simpleName}")
                return@launch
            } ?: return@launch
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
        rememberNested(scanned)
        added.firstOrNull()?.let { selectedId = it.path }   // open detail on the first added
    }

    /**
     * Keep [nestedByRepo] level with what the last scan found.
     *
     * Called wherever a scan result reaches [repos], because that is the only thing that can change
     * a repo's nested worktrees. Entries for repos that stop being tracked are dropped by
     * [syncWatches], which every untracking path already goes through.
     */
    private fun rememberNested(scanned: List<Repo>) {
        scanned.forEach { nestedByRepo[it.id] = it.nestedWorktrees }
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

    /** [sticky] holds it until the user dismisses it — for things they must not miss by looking
     *  away for five seconds, like being told their registry wouldn't load. */
    private fun toast(msg: String, sticky: Boolean = false) {
        opStatus = msg
        toastJob?.cancel()
        toastJob = if (sticky) null else scope.launch { delay(4500.milliseconds); opStatus = null }
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
    fun pushBranch(id: String, b: Branch) {
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
    fun checkoutRemoteBranch(id: String, rb: RemoteBranch) {
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
    fun deleteRemoteBranch(id: String, rb: RemoteBranch) {
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
     * Always runs from the parent checkout — [id] — because a worktree is never the repo being
     * viewed any more: the lists it appears in are the parent's sub-rows and cards, and git refuses
     * to delete the directory it is standing in.
     *
     * [removeBranch] also deletes the branch it held — offered only for an agent worktree whose
     * branch has already landed, where the branch is leftover bookkeeping rather than work.
     */
    fun removeWorktree(id: String, wt: Worktree, removeBranch: Boolean = false) {
        if (worktreesBusy) return
        worktreesBusy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                WorktreeOps.remove(
                    id, wt.path, force = wt.dirtyCount > 0, locked = wt.locked,
                    alsoBranch = wt.branch?.takeIf { removeBranch },
                )
            }
            toast(r.message)
            if (r.ok) {
                if (wtExpanded == wt.path) { wtExpanded = null; wtChanges = emptyList(); wtCommits = emptyList() }
                // The overrides were keyed on a folder that no longer exists, so they can never match
                // again — kept, they would silently apply to a future worktree that happened to be
                // created at the same path. Deliberately not done when a worktree merely moves in the
                // list, which is the case the path key exists to survive.
                entries[id]?.let { e ->
                    if (wt.path in e.worktreeAlerts) entries[id] = e.copy(worktreeAlerts = e.worktreeAlerts - wt.path)
                }
            }
            // The parent's worktree count and unlanded roll-up both just changed.
            reloadWorktrees(id)
            rescanRepos(listOf(id), fetch = false)
            worktreesBusy = false
        }
    }

    // ---- one worktree's own contents (all reads run `git -C <worktree-path>`) ----

    /** Which worktree card is expanded in the detail pane, by path; null when all are collapsed. */
    var wtExpanded by mutableStateOf<String?>(null)
        private set

    /** Which tab that card is showing — "changes" or "log". Remembered across cards on purpose:
     *  someone comparing two worktrees is asking the same question of both. */
    var wtTab by mutableStateOf("changes")
        private set

    var wtChanges by mutableStateOf<List<WorktreeChange>>(emptyList())
        private set
    var wtCommits by mutableStateOf<List<Commit>>(emptyList())
        private set
    var wtLoading by mutableStateOf(false)
        private set

    /**
     * Expand one worktree card (or collapse it, if it was the open one) and load the active tab.
     *
     * One at a time. Each body is a pair of git reads inside another checkout, and a section that
     * opened every card at once would run them for every worktree the repo has before the user had
     * said which one they were asking about.
     */
    fun toggleWorktreeCard(path: String) {
        if (wtExpanded == path) { wtExpanded = null; wtChanges = emptyList(); wtCommits = emptyList(); return }
        wtExpanded = path
        loadWorktreeBody(path)
    }

    /** Switch the open card between Changes and Log, loading the side that hasn't been read yet. */
    fun setWorktreeTab(tab: String) {
        if (wtTab == tab) return
        wtTab = tab
        wtExpanded?.let { loadWorktreeBody(it) }
    }

    /**
     * Read the open tab's contents for [path].
     *
     * Guarded on [wtExpanded] still being [path] when the results land: the reads are subprocesses
     * in another directory, and collapsing a card or opening a different one mid-read would
     * otherwise drop the first worktree's files into the second one's body.
     */
    private fun loadWorktreeBody(path: String) {
        wtLoading = true
        scope.launch {
            if (wtTab == "log") {
                val commits = LogOps.loadRange(path, "HEAD")
                if (wtExpanded == path) wtCommits = commits
            } else {
                val changes = WorktreeOps.changes(path)
                if (wtExpanded == path) wtChanges = changes
            }
            if (wtExpanded == path) wtLoading = false
        }
    }

    /**
     * Reach a worktree from a list surface: select its parent and open its card.
     *
     * The one navigation a worktree row performs, and deliberately not a *selection* of its own —
     * there is nothing to select, since the worktree has no repo row behind it. Selecting the parent
     * and expanding the card is what "scrolled to that worktree" resolves to; the card scrolls itself
     * into view once it's open (see the pane's worktree cards).
     */
    fun openWorktreeInPane(repoId: String, path: String) {
        selectedId = repoId
        if (wtExpanded != path) {
            wtExpanded = path
            wtChanges = emptyList()
            wtCommits = emptyList()
            loadWorktreeBody(path)
        }
    }

    /** The working-tree diff of one worktree, in the same overlay a repo's own Diff opens. */
    fun openWorktreeDiff(path: String) = openDiff(path)

    /** One worktree's commit history. */
    fun openWorktreeLog(path: String) =
        openRangeLog(path, "HEAD", "${File(path).name} — commit history")

    /**
     * Commit inside a worktree, then rescan the *parent*.
     *
     * The commit runs against a transient [RegistryEntry] for the worktree's path, because it hasn't
     * got a registry row and shouldn't gain one — [RepoOps.commit] only ever reads the path off it.
     * The rescan is the parent's because the parent is the row on screen, and what just changed for
     * it is how much unlanded work its sub-row is holding.
     */
    fun commitInWorktree(repoId: String, path: String, title: String, body: String, stageAll: Boolean) {
        if (title.isBlank()) return
        scope.launch {
            toast("Committing ${File(path).name}…")
            val result = withContext(Dispatchers.Default) {
                scanLimiter.withPermit { RepoOps.commit(RegistryEntry(path), title, body, stageAll) }
            }
            rescanRepos(listOf(repoId), fetch = false)
            wtExpanded?.let { if (it == path) loadWorktreeBody(path) }
            toast(result.message)
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
    fun trackSubmodule(parentId: String, sub: Submodule) =
        trackRepoAt(File(parentId, sub.path).absolutePath)

    /**
     * Fold away registry rows for checkouts that are linked worktrees of another tracked repo.
     *
     * These are the leftovers of the old model, where "Add Repo" on a worktree row gave it a
     * repository of its own. It never had one: a worktree shares the object store, the refs, the
     * stashes, the remote and the issue tracker with its parent, so every repo-wide setting on the
     * duplicate row was a second copy of the parent's answer that could silently disagree with it.
     * They are now sub-rows of the parent instead, so the duplicate row goes.
     *
     * Registry-only, and the least destructive form of it: nothing on disk is touched, the worktree
     * itself is untouched, and a snooze the row was carrying is carried across as that worktree's
     * own snooze rather than dropped — the user asked for quiet on that folder, and the new model
     * has a place to keep it. Tags, notes and reminders on the duplicate are not migrated: they were
     * always the parent's project's, and merging them would silently rewrite the parent's own.
     *
     * Runs once, after the first scan — [Repo.isWorktree] and [Repo.worktreeMain] are scan products,
     * and asking git again here would be a second `worktree list` per repo for an answer already on
     * the table.
     */
    private var worktreeRowsFolded = false

    private fun foldTrackedWorktrees() {
        if (worktreeRowsFolded) return
        worktreeRowsFolded = true
        val folded = repos.filter { it.isWorktree }.mapNotNull { child ->
            val parentId = child.worktreeMain?.let { trackedRepoAt(it) } ?: return@mapNotNull null
            val childEntry = entries[child.id] ?: return@mapNotNull null
            entries[parentId]?.let { parent ->
                val snooze = childEntry.snoozeUntilEpoch
                if (snooze != null) {
                    val kept = worktreeAlerts(parentId, child.id).copy(snoozeUntilEpoch = snooze)
                    entries[parentId] = parent.copy(worktreeAlerts = parent.worktreeAlerts + (child.id to kept))
                }
            }
            child.id
        }
        if (folded.isEmpty()) return
        folded.forEach { id ->
            entries.remove(id); order.remove(id); repos.removeAll { it.id == id }
            if (selectedId == id) selectedId = null
            bulkSelected = bulkSelected - id
        }
        // The inherited marker went with them. Left on the parents it would claim they are worktrees,
        // which is the confusion the tag existed to resolve and now creates.
        order.toList().forEach { id ->
            entries[id]?.let { e -> if (LEGACY_WORKTREE_TAG in e.tags) entries[id] = e.copy(tags = e.tags - LEGACY_WORKTREE_TAG) }
        }
        persist(); syncWatches()
        toast(
            "${folded.size} tracked worktree${if (folded.size == 1) "" else "s"} " +
                "now listed under ${if (folded.size == 1) "its" else "their"} main checkout",
        )
    }

    /** Register a checkout the app already knows about — a submodule — as a tracked repo of its
     *  own, then select it. Worktrees no longer come through here; see [foldTrackedWorktrees]. */
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
    fun openSubmoduleDiff(parentId: String, sub: Submodule) {
        val target = sub.remoteRef ?: return
        val subPath = File(parentId, sub.path).absolutePath
        diffRepoName = "${File(parentId).name}/${sub.path}"
        diffTitle = "${sub.path}: pending pointer move"
        diff = Diff(emptyList(), emptyList())
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
        diff = Diff(emptyList(), emptyList())
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
        diff = Diff(emptyList(), emptyList())
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
        diff = Diff(emptyList(), emptyList())
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
        diff = Diff(emptyList(), emptyList())
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
        diff = Diff(emptyList(), emptyList())
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
        diff = Diff(emptyList(), emptyList())
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
    fun openSubmoduleLog(parentId: String, sub: Submodule) {
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
    private suspend fun rescanRepos(ids: Collection<String>, fetch: Boolean, userInitiated: Boolean = true) {
        val es = ids.mapNotNull { entries[it] }
        val scanned = withContext(Dispatchers.Default) {
            es.map { e ->
                async { scanLimiter.withPermit { runCatching { RepoScanner.scan(e, fetch, userInitiated) }.getOrNull() } }
            }.awaitAll()
        }.filterNotNull()
        // Before the prevBehind baseline moves below — the advance stamp needs the previous value.
        if (fetch) stampFetched(scanned, System.currentTimeMillis())
        scanned.forEach { s ->
            val idx = repos.indexOfFirst { it.id == s.id }
            if (idx >= 0) repos[idx] = s else repos.add(s)
            prevBehind[s.id] = s.behind   // keep baseline current so auto-refresh won't false-alert
        }
        rememberNested(scanned)
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

    /**
     * Poll GitHub on its own clock.
     *
     * This used to ride on the five-minute all-repo git refresh. That coupling was invisible and
     * one-directional: change the git fetch cadence and the PR/issue pills quietly stop updating,
     * with no error and nothing to show the data has gone stale. The two also draw on unrelated
     * budgets — `gh` spends a per-token API rate limit, `git fetch` spends network round trips to
     * the remote — so there was never a reason for one policy to govern both.
     */
    private fun startGitHubRefresh(intervalSeconds: Long = 300) {
        scope.launch {
            while (isActive) {
                delay((intervalSeconds * 1000).milliseconds)
                refreshGitHub()   // no-ops harmlessly when no repo has a tracked GitHub remote
            }
        }
    }

    /**
     * Fetch a repo when the user opens its detail pane — rule 1.
     *
     * Observed rather than hooked into the click handlers: `selectedId` is assigned from the repo
     * list, the chooser, keyboard navigation and "Add repo", and a fetch wired into each of them
     * would be missing from whichever one is added next. `snapshotFlow` sees them all, and
     * `distinctUntilChanged` keeps a recomposition that reassigns the same id from re-fetching.
     *
     * User-initiated, so this one *is* recorded — unlike the five-minute repeat that follows it in
     * [startAutoRefresh], which is the clock's doing and would flood the console at 12 entries an
     * hour for whichever repo happens to be open.
     */
    private var selectionFetchJob: Job? = null

    private fun startSelectionFetch() {
        scope.launch {
            snapshotFlow { selectedId }.distinctUntilChanged().collect { id ->
                // Debounced like the fs-watcher rescan, and for the same reason: arrow-keying down
                // the list would otherwise open a network connection to every repo it passes over.
                selectionFetchJob?.cancel()
                if (id == null || entries[id] == null) return@collect
                selectionFetchJob = scope.launch {
                    delay(500.milliseconds)
                    rescanRepos(listOf(id), fetch = true)
                }
            }
        }
    }

    /**
     * The background fetch loop.
     *
     * The tick is a *scheduling grain*, not a fetch rate: every five minutes this asks which repos
     * are due under [FetchPolicy] and fetches only those, so an idle dashboard of mostly-dormant
     * repos goes almost entirely quiet. It replaced a loop that fetched every repo on every tick.
     *
     * The open repo is always due — rule 2, "fetch every five minutes while its details are open",
     * which falls out of the tick rate rather than needing a timer of its own.
     *
     * None of it is user-initiated, so none of it reaches the console; see [RepoScanner.scan].
     */
    private fun startAutoRefresh(intervalSeconds: Long = 300) {
        scope.launch {
            var running = false
            while (isActive) {
                delay((intervalSeconds * 1000).milliseconds)
                // A round that outlasts the tick must not have the next one stacked on top of it.
                // Each round fetches every due repo, and a fetch is allowed ninety seconds, so on a
                // slow link or a large dashboard a round can take longer than the interval — and
                // rounds that overlap queue their whole repo list behind the same eight permits,
                // which makes every one after it slower still. Skipping costs nothing: [isFetchDue]
                // is answered from the clock, so whatever was due stays due at the next tick.
                if (running) continue
                running = true
                try {
                    val now = System.currentTimeMillis()
                    val due = order.filter { id -> id == selectedId || isFetchDue(id, now) }
                    if (due.isNotEmpty()) rescanRepos(due, fetch = true, userInitiated = false)
                } finally {
                    running = false
                }
            }
        }
    }

    /** Whether [id]'s background fetch interval has elapsed. See [FetchPolicy]. */
    private fun isFetchDue(id: String, now: Long): Boolean {
        val e = entries[id] ?: return false
        return FetchPolicy.isDue(activityMsOf(id, e), e.lastFetchedEpoch, now)
    }

    /** The newest sign of life for [id], across git history, the working tree and the upstream. */
    private fun activityMsOf(id: String, e: RegistryEntry): Long? = FetchPolicy.activityMs(
        lastCommitEpochSeconds = repos.firstOrNull { it.id == id }?.lastCommitEpoch,
        dirtySinceMs = e.dirtySinceEpoch,
        lastUpstreamAdvanceMs = e.lastUpstreamAdvanceEpoch,
    )

    /**
     * Stamp the fetch bookkeeping [FetchPolicy] schedules from, for repos that were just fetched.
     *
     * The upstream-advance stamp uses the `behind` count rising against [prevBehind], which is the
     * same signal the upstream notifications already run on — a repo that turns out to be live
     * promotes itself back out of the dormant tier the next time a fetch finds something.
     *
     * Called *before* [rescanRepos] refreshes `prevBehind`, since it needs the previous value.
     */
    private fun stampFetched(scanned: List<Repo>, now: Long) {
        var changed = false
        scanned.forEach { r ->
            val e = entries[r.id] ?: return@forEach
            val advanced = r.behind > (prevBehind[r.id] ?: 0)
            entries[r.id] = e.copy(
                lastFetchedEpoch = now,
                lastUpstreamAdvanceEpoch = if (advanced) now else e.lastUpstreamAdvanceEpoch,
            )
            changed = true
        }
        if (changed) persist()
    }

    /** Toolbar timestamp text. */
    /**
     * How stale the dashboard is, as the *least* recently fetched repo — not the most.
     *
     * When every repo was fetched on the same five-minute timer, one timestamp spoke accurately
     * for all of them. Under [FetchPolicy] they run on different clocks, and "fetched just now"
     * taken from whichever repo happened to be due would assert a freshness the hourly and daily
     * tiers do not have. The oldest is the honest summary, and it is the number that answers the
     * question the label sits next to: is this worth pressing Refresh for?
     *
     * Repos the policy has switched off are excluded. They are stale by design, and including
     * them would peg this at a month forever and make it say nothing at all.
     */
    fun fetchedLabel(): String {
        if (scanning) return "fetching…"
        val now = System.currentTimeMillis()
        val oldest = order.mapNotNull { id ->
            val e = entries[id] ?: return@mapNotNull null
            if (FetchPolicy.intervalMs(activityMsOf(id, e), now) == null) null else e.lastFetchedEpoch ?: 0L
        }.minOrNull() ?: lastFetchedEpoch
        if (oldest == 0L) return "not fetched yet"
        val secs = (now - oldest) / 1000
        val rel = when {
            secs < 5 -> "just now"
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            else -> "${secs / 3600}h ago"
        }
        return "fetched $rel"
    }

    // ---- derived views / filtering / grouping ----

    private fun views(): List<RepoView> =
        repos.map { deriveView(it, accent, tagsOf(it.id), ghSummary(it), worktreeViews(it.id, it.snoozed, it.worktrees)) }

    /**
     * Resolve a repo's worktrees against its registry overrides.
     *
     * Takes the tree list rather than reading [Repo.worktrees] itself so the detail panel can pass
     * the richer list it loaded — the scan's copy doesn't know whether a branch has landed, and
     * "merged" is exactly the badge the pane's cards show. Both callers get the same inheritance
     * resolution, which is what keeps a sub-row and its card from disagreeing about a ⚙.
     */
    fun worktreeViews(repoId: String, repoSnoozed: Boolean, trees: List<Worktree>): List<WorktreeView> {
        if (trees.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return trees.filter { !it.isCurrent && !it.bare }.map { wt ->
            val alerts = worktreeAlerts(repoId, wt.path)
            val (snoozed, snoozedFor) = Meta.snoozeState(alerts.snoozeUntilEpoch, now)
            WorktreeView(
                wt = wt, repoId = repoId, alerts = alerts,
                snoozed = snoozed, snoozedFor = snoozedFor,
                parentAlertsOn = !repoSnoozed,
            )
        }
    }

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
            // Parents, not worktrees: the filter selects the rows that have sub-rows, and counting
            // the sub-rows would put a number in the chip that doesn't match the list it produces.
            "worktrees" to vms.count { it.worktrees.isNotEmpty() },
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
        "worktrees" -> v.worktrees.isNotEmpty()
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
     *  important-stale, unlanded work in a linked worktree, or an issue awaiting you) → blue
     *  (behind, stale, or open issues) → green (clean). Keep the two in step.
     *
     *  Exception, deliberate: a snooze pulls a repo down the ranking further than it dims the dot.
     *  Staleness reads through [RepoView.isStale], which is already 0 while snoozed, so a snoozed
     *  stale repo ranks green here while its dot stays blue — the alternative was ranking it as
     *  stale while [statusPredicate] and the "stale" chip count both drop it from the Stale filter,
     *  which is a contradiction rather than a trade-off. Dirty/unpushed/problem are read raw and
     *  keep their rank while snoozed: those are facts about the working tree, not calls to action,
     *  and a snooze can't make them untrue. [sorted] sinks them within their rank instead. */
    private fun attentionRank(v: RepoView): Int = when {
        v.repo.warning != null || v.issueLevel == IssueLevel.CRITICAL -> 0
        v.isDirty || v.repo.ahead > 0 || (v.isStale && v.repo.staleImportant) ||
            v.worktreesUnlanded > 0 ||
            v.issueLevel == IssueLevel.IMPORTANT -> 1
        v.repo.behind > 0 || v.isStale || v.issueLevel == IssueLevel.INFO -> 2
        else -> 3
    }

    private fun sorted(vs: List<RepoView>): List<RepoView> = when (sortBy) {
        "commit" -> vs.sortedWith(
            compareByDescending<RepoView> { it.repo.lastCommitEpoch ?: Long.MIN_VALUE }
                .thenBy { it.repo.name.lowercase() })
        // Snoozed repos sink within their rank. A snooze can't clear a dirty working tree, so the
        // repo keeps its amber rank (and its amber dot) — but "not now" has to buy something, and
        // what it buys is that the rows still asking for you outrank the ones you've muted.
        // [groups] partitions this order without re-sorting, so sections inherit the same tail.
        "attention" -> vs.sortedWith(
            compareBy<RepoView> { attentionRank(it) }
                .thenBy { if (it.snoozed) 1 else 0 }
                .thenBy { it.repo.name.lowercase() })
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

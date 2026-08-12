// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage.app

import com.gitvantage.git.GitLog
import com.gitvantage.model.Meta
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Open issues / pull requests for the tracked GitHub repos, read through the `gh` CLI.
 *
 * Why `gh` rather than an HTTP client: it means the app never sees, stores or asks for a
 * credential — it inherits whatever `gh auth login` already set up, including SSO and GitHub
 * Enterprise hosts. It also keeps the release image free of a TLS/HTTP stack, which matters
 * here because the packaged build is a GraalVM native image with a minimal jlink runtime (see
 * build.gradle.kts): adding `java.net.http` would mean proving out root certificates and
 * `--enable-url-protocols=https` in the native image, for no user-visible gain.
 *
 * The cost is a hard dependency on `gh` being installed and authenticated. That's a soft
 * failure by design — [status] reports what's missing and the rest of the dashboard is
 * unaffected.
 *
 * Everything is fetched in one GraphQL round trip per [BATCH] repos. REST would need a request
 * per repo plus one per issue to see the latest comment, which is the whole point of the
 * "awaiting you" signal — GraphQL gets it in the same response.
 */
object GitHub {

    /** Repos per GraphQL request. Keeps each query's node budget (and its rate-limit cost)
     *  modest while still collapsing a 40-repo dashboard into ~7 round trips. */
    private const val BATCH = 6

    /**
     * Repos per request for the ones you maintain, which fetch [PER_REPO] issues *and* PRs with a
     * comment, its reactions and the labels on each. Lower than [BATCH] because that shape is what
     * GitHub's per-query resource limit bites on — but it is a starting point, not a guarantee:
     * see [splitOnLimit] for why no constant here can be one.
     */
    private const val MINE_BATCH = 3

    /**
     * Floor for [PER_REPO] when backing off. Below this the request stops being worth retrying:
     * a repo that can't return ten items is failing for some other reason.
     */
    private const val MIN_PER_REPO = 10

    /** Open items fetched per repo, per type. A repo with more than this many open issues is
     *  reported by its [RepoState.issueTotal] (which is the true count) but only the most
     *  recently updated [PER_REPO] are inspected for the "awaiting you" signal. */
    private const val PER_REPO = 50

    /** Repos per [classify] request. Far wider than [BATCH] because it asks for one scalar each. */
    private const val CLASSIFY_BATCH = 50

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether the integration can run at all, and who it is authenticated as. */
    sealed interface Status {
        /** Not probed yet. */
        data object Unknown : Status
        /** No `gh` binary on PATH or in the usual install locations. */
        data object Missing : Status
        /** `gh` is installed but not logged in. */
        data object NoAuth : Status
        /**
         * [hosts] is every host `gh` holds a working login for, lowercased — the authoritative
         * answer to "is this remote a GitHub instance?". A GitHub Enterprise install can live at
         * any hostname (`git.company.com` is as common as `github.company.com`), so hostname
         * pattern-matching can only ever guess; `gh` already knows. Empty on the fallback path.
         */
        data class Ok(val login: String, val hosts: Set<String> = emptySet()) : Status
        data class Failed(val message: String) : Status
    }

    /** One open issue or pull request. [awaitingYou] and [involvesYou] are resolved at parse
     *  time, while the viewer's login is in hand. */
    data class Item(
        val number: Int,
        val title: String,
        val url: String,
        val isPr: Boolean,
        val isDraft: Boolean,
        val updatedAt: Long,          // epoch millis
        val author: String,
        /** As GitHub spells them, for [RegistryEntry.ignoreLabels] to match case-insensitively. */
        val labels: List<String>,
        val awaitingYou: Boolean,
        val involvesYou: Boolean,
        /** Why it's awaiting you — shown as a hint in the detail panel ("review requested"). */
        val reason: String?,
    ) {
        /**
         * Whether any of [names] is on this item. Case-insensitive because GitHub's own label
         * autocomplete is, and a filter that missed "Utility Request" after you typed
         * "utility request" would look simply broken.
         */
        fun hasAnyLabel(names: List<String>): Boolean =
            names.isNotEmpty() && labels.any { l -> names.any { it.equals(l, ignoreCase = true) } }
    }

    /**
     * A repo's open items. [issueTotal]/[prTotal] are GitHub's own counts and can exceed the
     * fetched [issues]/[prs] lists — the dashboard shows the true total but can only classify
     * what it fetched.
     */
    data class RepoState(
        val issues: List<Item> = emptyList(),
        val prs: List<Item> = emptyList(),
        val issueTotal: Int = 0,
        val prTotal: Int = 0,
        val fetchedAt: Long = 0L,
        /** Set when this specific repo failed (renamed, private, deleted) while others succeeded. */
        val error: String? = null,
    )

    /**
     * `owner/name` parsed out of a repo's browsable remote base, plus the host it lives on.
     * [host] matters because [Meta.webBase] also treats `github.<company>.com` as GitHub: those
     * are GitHub Enterprise, and querying them against github.com would report every repo as
     * "could not resolve" rather than working.
     */
    data class Coord(
        val owner: String,
        val name: String,
        val host: String = PUBLIC_HOST,
        /**
         * Whether this is a repo you maintain, which decides how much of it is even fetched:
         * true asks for every open item and runs the full attention rules, false asks only for
         * the items you authored. Null means "not stated" and is answered by [classify] from the
         * token's push access — see [roleCache].
         */
        val mine: Boolean? = null,
    )

    const val PUBLIC_HOST = "github.com"

    // GitHub logins and repo names are restricted to these; anything else can't be a real
    // coordinate and must not reach the query string.
    private val SAFE = Regex("[A-Za-z0-9._-]+")

    /**
     * Parse `https://github.com/owner/repo` (as produced by [Meta.webBase]) into a [Coord].
     * Returns null for anything that isn't exactly a two-segment path of safe characters —
     * gists, enterprise paths with extra segments, and anything that would need escaping.
     */
    fun coordOf(webBase: String?): Coord? {
        val hostAndPath = webBase?.substringAfter("://")?.trim('/') ?: return null
        val host = hostAndPath.substringBefore('/')
        val path = hostAndPath.substringAfter('/', "")
        val parts = path.split('/')
        if (host.isEmpty() || parts.size != 2) return null
        val (owner, name) = parts
        if (!SAFE.matches(owner) || !SAFE.matches(name)) return null
        return Coord(owner, name, host)
    }

    // ---- the gh binary -------------------------------------------------------------------

    /**
     * Locations to look for `gh` beyond PATH. A desktop app launched from a dock or menu
     * doesn't inherit the shell's PATH (most visibly on macOS, where a Finder-launched .app
     * gets a bare `/usr/bin:/bin:/usr/sbin:/sbin`), so resolving by name alone would report
     * "not installed" for a perfectly working `gh`.
     */
    private val EXTRA_PATHS = listOf(
        "/opt/homebrew/bin/gh",     // Homebrew, Apple silicon
        "/usr/local/bin/gh",        // Homebrew, Intel + manual installs
        "/usr/bin/gh",
        "/home/linuxbrew/.linuxbrew/bin/gh",
        System.getProperty("user.home") + "/.local/bin/gh",
    )

    private val binary: String? by lazy {
        // `gh --version` rather than a filesystem probe: it's the only check that proves the
        // thing found is runnable (not a dangling symlink or a wrapper that dies on startup).
        if (exec(listOf("gh", "--version"), timeoutSeconds = 10).code == 0) return@lazy "gh"
        EXTRA_PATHS.firstOrNull { File(it).canExecute() }
    }

    /**
     * Probe for `gh`, the logged-in user, and every host that login covers. Cheap enough to
     * re-run on each refresh cycle, so a `gh auth login` performed while the app is open takes
     * effect on the next tick.
     *
     * `gh auth status --json hosts` answers all three at once, and is the only way to learn about
     * Enterprise hosts — there is nothing in a remote URL that identifies `git.company.com` as
     * GitHub. It also reports per-account `state`, so a host whose token has expired is treated
     * as not-logged-in rather than silently failing every query against it.
     */
    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val gh = binary ?: return@withContext Status.Missing
        val res = exec(listOf(gh, "auth", "status", "--json", "hosts"), timeoutSeconds = 25)
        val parsed = res.out.takeIf { it.trimStart().startsWith("{") }
            ?.let { runCatching { json.decodeFromString(AuthStatus.serializer(), it) }.getOrNull() }
        if (parsed != null) {
            val ok = parsed.hosts.values.flatten().filter { it.state.equals("success", true) }
            if (ok.isEmpty()) return@withContext Status.NoAuth
            // Which login to report is only a display/gating concern — each fetch resolves the
            // viewer per host anyway (logins differ between github.com and an Enterprise SSO
            // account). Prefer the active github.com one, since that's whose name users expect.
            val who = ok.firstOrNull { it.host.equals(PUBLIC_HOST, true) && it.active }
                ?: ok.firstOrNull { it.host.equals(PUBLIC_HOST, true) }
                ?: ok.first()
            return@withContext Status.Ok(who.login, ok.mapTo(HashSet()) { it.host.lowercase() })
        }
        // `gh auth status --json` is relatively recent. On an older gh it exits non-zero with a
        // flag error, so fall back to the original probe: no host list (callers then rely on the
        // hostname heuristic alone, i.e. github.com keeps working and Enterprise doesn't).
        val u = exec(listOf(gh, "api", "user", "--jq", ".login"), timeoutSeconds = 20)
        val login = u.out.trim()
        when {
            u.code == 0 && login.isNotEmpty() -> Status.Ok(login)
            // gh says this in various shapes; all of them mean "run gh auth login".
            u.err.contains("auth login", true) ||
                u.err.contains("authentication", true) ||
                u.err.contains("HTTP 401", true) -> Status.NoAuth
            else -> Status.Failed(firstLine(u.err) ?: firstLine(res.err) ?: "gh auth status failed")
        }
    }

    // ---- fetching ------------------------------------------------------------------------

    /**
     * Fetch open issues and PRs for [coords], keyed by the caller's own repo id. Grouped by host
     * (so Enterprise repos query their own server) and batched into [BATCH]-repo GraphQL queries;
     * a batch that fails wholesale marks only its own repos with an error, so one bad repo can't
     * blank the dashboard.
     */
    suspend fun fetch(coords: Map<String, Coord>): Map<String, RepoState> =
        withContext(Dispatchers.IO) {
            val gh = binary ?: return@withContext emptyMap()
            val out = HashMap<String, RepoState>()
            coords.entries.groupBy { it.value.host }.forEach { (host, forHost) ->
                // Before the real fetch, not during it: [Coord.mine] decides the *shape* of each
                // repo's query, so a repo whose role is unstated has to be answered first. Only
                // ever runs for repos never seen this session, and asks for one scalar apiece.
                classify(gh, host, forHost.map { it.value }.filter { it.mine == null && !isClassified(it) })
                // Split by role before chunking, because the two shapes cost wildly different
                // amounts: a maintained repo asks for 100 items with a comment apiece, a
                // contributed one for the handful you filed. Batching them together would size
                // every batch for the worst case it might contain.
                val (maintained, contributed) = forHost.partition { isMine(it.value) }
                maintained.chunked(MINE_BATCH).forEach { out += fetchBatch(gh, host, it) }
                contributed.chunked(BATCH).forEach { out += fetchBatch(gh, host, it) }
            }
            out
        }

    /**
     * Push access per repo, from a token that can't be asked "which repos do I maintain?" directly.
     * Session-scoped rather than persisted so a permission change (you're made a maintainer, you
     * leave an org) is picked up on the next launch instead of sticking forever; an explicit
     * [Coord.mine] always wins over it, so this only decides the repos you never classified.
     */
    private val roleCache = HashMap<String, Boolean>()

    private fun cacheKey(c: Coord) = "${c.host}/${c.owner}/${c.name}".lowercase()

    private fun isClassified(c: Coord) = synchronized(roleCache) { cacheKey(c) in roleCache }

    /**
     * What the push access says this repo is, or null if it hasn't been answered yet (never
     * fetched, or the classify call failed). For showing the user which way an unstated repo
     * was read — the answer is only as current as the last fetch.
     */
    fun inferredMine(c: Coord): Boolean? = synchronized(roleCache) { roleCache[cacheKey(c)] }

    /**
     * Whether to treat this repo as one you maintain. Your own answer wins; failing that the
     * derived one; failing that "no", which is the shape that fetches *less* — an unclassifiable
     * repo should under-report for one cycle, not fire the full maintainer rules blind.
     */
    private fun isMine(c: Coord): Boolean =
        c.mine ?: synchronized(roleCache) { roleCache[cacheKey(c)] } ?: false

    /** Fill [roleCache] for [coords] from `viewerPermission`. One scalar per repo, so it batches wide. */
    private fun classify(gh: String, host: String, coords: List<Coord>) {
        coords.distinctBy { cacheKey(it) }.chunked(CLASSIFY_BATCH).forEach { chunk ->
            val query = buildString {
                append("query {")
                chunk.forEachIndexed { i, c ->
                    append(" R$i: repository(owner: \"${c.owner}\", name: \"${c.name}\") { viewerPermission }")
                }
                append(" }")
            }
            val args = buildList {
                add(gh); add("api"); add("graphql")
                if (!host.equals(PUBLIC_HOST, true)) { add("--hostname"); add(host) }
                add("-f"); add("query=$query")
            }
            val res = exec(args, timeoutSeconds = 30)
            val data = runCatching { json.parseToJsonElement(res.out).jsonObject["data"] as? JsonObject }.getOrNull()
                ?: return@forEach   // leave them unclassified; [isMine] falls back and the next poll retries
            chunk.forEachIndexed { i, c ->
                val repo = (data["R$i"] as? JsonObject)
                    ?.let { runCatching { json.decodeFromJsonElement(GqlRepo.serializer(), it) }.getOrNull() }
                // A repo that errored has no permission to record. Caching a false for it would
                // pin it to "contributing" for the whole session over what may be a transient failure.
                if (repo?.viewerPermission != null) synchronized(roleCache) { roleCache[cacheKey(c)] = repo.canWrite }
            }
        }
    }

    /**
     * Retry the same repos in smaller pieces after GitHub refused the query as too expensive,
     * or null when there's nothing smaller left to try.
     *
     * Necessary because no constant [MINE_BATCH] can be correct: the limit is computed from how
     * much work the query does, not from its node count, so whether a batch fits depends entirely
     * on how big the trackers in it happen to be. Measured on the three largest repos here, a
     * 8,100-node request is refused while a 10,200-node one over moderate repos succeeds — there
     * is no threshold to tune, only a failure to react to. And the refusal is not partial: every
     * item node in the batch comes back null, so one oversized tracker would otherwise blank out
     * the small repos that happened to share its request.
     *
     * Splitting the batch first and only then narrowing [perRepo] keeps whole repos intact for as
     * long as possible — a batch that shrinks to one repo has stopped costing its neighbours
     * anything, and it's only past that point that fetching fewer of its items is the sole move
     * left.
     */
    private fun splitOnLimit(
        gh: String,
        host: String,
        batch: List<Map.Entry<String, Coord>>,
        perRepo: Int,
    ): Map<String, RepoState>? = when {
        batch.size > 1 -> batch.chunked((batch.size + 1) / 2)
            .fold(emptyMap()) { acc, half -> acc + fetchBatch(gh, host, half, perRepo) }
        perRepo > MIN_PER_REPO -> fetchBatch(gh, host, batch, maxOf(MIN_PER_REPO, perRepo / 2))
        else -> null
    }

    /** True when GitHub refused the query outright rather than failing individual aliases. */
    private fun isResourceLimit(errors: JsonArray?): Boolean = errors.orEmpty().any {
        ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "RESOURCE_LIMITS_EXCEEDED"
    }

    private fun fetchBatch(
        gh: String,
        host: String,
        batch: List<Map.Entry<String, Coord>>,
        perRepo: Int = PER_REPO,
    ): Map<String, RepoState> {
        // Aliases are positional (R0, R1, …) so the response maps back to repo ids by index —
        // repo *names* can't be used as GraphQL aliases (they allow characters aliases don't).
        // `viewer` comes from the same response rather than being passed in: on an Enterprise
        // host the account is a different one, so "am I mentioned?" has to be asked per host.
        val mine = batch.map { isMine(it.value) }
        val query = buildString {
            append("query { viewer { login }")
            batch.forEachIndexed { i, (_, c) ->
                if (mine[i]) {
                    append(" R$i: repository(owner: \"${c.owner}\", name: \"${c.name}\") { ...F }")
                } else {
                    // The repository alias is still asked for, with nothing but the permission on
                    // it: it's what keeps the "renamed / private / deleted" error path working for
                    // these repos (a search over a repo you can't see is simply empty, not an
                    // error), and it re-answers the role each poll so an auto-classified repo
                    // follows a permission change instead of holding last session's answer.
                    append(" R$i: repository(owner: \"${c.owner}\", name: \"${c.name}\") { viewerPermission }")
                    append(" S$i: search(query: \"repo:${c.owner}/${c.name} is:open author:@me\"")
                    append(", type: ISSUE, first: $perRepo) { $SEARCH_ITEMS }")
                }
            }
            append(" }\n")
            // Only when something actually spreads it: GraphQL rejects a query that declares a
            // fragment it never uses, so a batch of purely contributed repos must not carry it.
            if (mine.any { it }) append(fragment(perRepo))
        }
        val args = buildList {
            add(gh); add("api"); add("graphql")
            if (!host.equals(PUBLIC_HOST, true)) { add("--hostname"); add(host) }
            add("-f"); add("query=$query")
        }
        val res = exec(args, timeoutSeconds = 45)
        // Parse the body even on a non-zero exit: a partial failure (one repo renamed or now
        // private) returns HTTP 200 with `data` populated for every other alias plus an
        // `errors` array, and gh exits non-zero for it. Discarding that would throw away
        // good data for every repo in the batch because of one bad one.
        val body = res.out.takeIf { it.trimStart().startsWith("{") }
            ?: return batch.associate { (id, _) ->
                id to RepoState(error = firstLine(res.err) ?: "gh api graphql failed (exit ${res.code})")
            }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return batch.associate { (id, _) -> id to RepoState(error = "unreadable response from gh") }
        val errors = root["errors"] as? JsonArray
        val firstError = errors?.firstNotNullOfOrNull { msgOf(it) }

        // Refused as too expensive. Every item node in `data` is null in this case, so there is
        // nothing here worth keeping — go again with less asked for, and only fall through to
        // reporting the failure once there's no smaller request left to make.
        if (isResourceLimit(errors)) {
            splitOnLimit(gh, host, batch, perRepo)?.let { return it }
        }

        // No `data` at all means the whole request failed rather than some aliases within it.
        // gh writes the response body to stdout even for HTTP errors, and those bodies are
        // `{"message": "...", "documentation_url": "..."}` — no `data`, no `errors`. Without
        // this the rate-limit, SAML-enforcement and expired-token cases all fell through to the
        // per-alias "not accessible" below, which reads as "these repos were deleted" while the
        // real explanation sat unused in the body.
        val data = root["data"] as? JsonObject
        if (data == null) {
            val why = (root["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: firstError ?: firstLine(res.err) ?: "gh api graphql failed (exit ${res.code})"
            return batch.associate { (id, _) -> id to RepoState(error = why) }
        }
        val viewer = ((data["viewer"] as? JsonObject)?.get("login") as? JsonPrimitive)
            ?.contentOrNull.orEmpty()

        return batch.mapIndexed { i, (id, c) ->
            val alias = "R$i"
            val node = data[alias] as? JsonObject
            if (node == null) {
                // This alias specifically came back null — the repo is gone, renamed, or not
                // visible to this token. GraphQL tags each error with the path it belongs to, so
                // prefer the one for *this* alias: two repos can fail in the same batch for
                // different reasons, and reporting the first message for both is simply wrong.
                id to RepoState(error = errorForPath(errors, alias) ?: firstError ?: "not accessible")
            } else {
                val repo = runCatching { json.decodeFromJsonElement(GqlRepo.serializer(), node) }.getOrNull()
                if (repo == null) id to RepoState(error = "unreadable response for this repo")
                else {
                    if (repo.viewerPermission != null) {
                        synchronized(roleCache) { roleCache[cacheKey(c)] = repo.canWrite }
                    }
                    // The *role* is what the rules answer to, not the permission behind it: saying
                    // a repo is yours is a claim that its unanswered comments are your problem, and
                    // that holds on an org repo whose token happens to report only read access.
                    id to if (mine[i]) repo.toState(viewer) else searchState(data["S$i"], viewer)
                }
            }
        }.toMap()
    }

    /**
     * Everything the attention rules need from one item.
     *
     * `comments(last: 1)` is the expensive-to-emulate part: it's what makes "the last comment
     * @-mentions me" answerable without a request per issue. Note it covers *conversation*
     * comments only — a PR whose newest activity is an inline review comment reports the last
     * conversation comment instead. `bodyText` (not `body`) so markdown and code fences can't
     * produce a false @-mention.
     *
     * The item's *own* `reactionGroups` sit alongside the comment's because an item nobody has
     * answered yet has no last comment to react to — there, the thing you'd 👍 is the body. See
     * [GqlNode.acknowledgedBy].
     */
    private const val ITEM = """
              number title url updatedAt viewerDidAuthor
              author { login }
              assignees(first: 10) { nodes { login } }
              labels(first: 20) { nodes { name } }
              reactionGroups { viewerHasReacted }
              comments(last: 1) { nodes { author { login } bodyText reactionGroups { viewerHasReacted } } }
    """

    /** The extra fields only a pull request has. */
    private const val PR_ITEM = """
              isDraft
              reviewRequests(first: 10) { nodes { requestedReviewer { ... on User { login } } } }
    """

    /**
     * A maintained repo: every open item, because on your own tracker anyone's unanswered comment
     * is your problem regardless of who filed it.
     */
    private fun fragment(perRepo: Int) = """
        fragment F on Repository {
          viewerPermission
          issues(states: OPEN, first: $perRepo, orderBy: {field: UPDATED_AT, direction: DESC}) {
            totalCount
            nodes {$ITEM}
          }
          pullRequests(states: OPEN, first: $perRepo, orderBy: {field: UPDATED_AT, direction: DESC}) {
            totalCount
            nodes {$ITEM$PR_ITEM}
          }
        }
    """.trimIndent()

    /**
     * A repo you only contribute to: the items you filed, and nothing else.
     *
     * Search rather than `repository { issues, pullRequests }` because `pullRequests` has no
     * author argument — only `issues` takes `filterBy: {createdBy:}` — so the connection form
     * would still have to pull every open PR in the repo and discard them here. That is the whole
     * cost this avoids: on a 782-issue tracker the connection form fetches 100 items to keep 3,
     * and a batch holding two such repos exceeds GitHub's per-query resource limit outright.
     *
     * `author:@me` rather than the viewer's login: the login isn't known until this same response
     * comes back, and on an Enterprise host it's a different account than on github.com.
     */
    private val SEARCH_ITEMS = """
        nodes {
          __typename
          ... on Issue {$ITEM}
          ... on PullRequest {$ITEM$PR_ITEM}
        }
    """.trimIndent()

    /** Every open item, with the maintainer rules live. */
    private fun GqlRepo.toState(viewer: String) = RepoState(
        issues = issues.nodes.map { it.toItem(isPr = false, viewer = viewer, canWrite = true) },
        prs = pullRequests.nodes.map { it.toItem(isPr = true, viewer = viewer, canWrite = true) },
        issueTotal = issues.totalCount,
        prTotal = pullRequests.totalCount,
        fetchedAt = System.currentTimeMillis(),
    )

    /**
     * The items you filed on a repo you contribute to. The counts are the fetched sizes rather
     * than the repo's true totals — on these repos your own items *are* the universe, and
     * reporting "782 open" next to the three you can actually see would make the dashboard's
     * truncation indicator fire on every contributed repo forever.
     */
    private fun searchState(node: JsonElement?, viewer: String): RepoState {
        val found = (node as? JsonObject)
            ?.let { runCatching { json.decodeFromJsonElement(GqlSearch.serializer(), it) }.getOrNull() }
            ?: return RepoState(error = "unreadable response for this repo")
        // canWrite is false whatever the token says: the point of the role is that these repos
        // only ever surface what you authored, and the maintainer rule is about everyone else's.
        val (prs, issues) = found.nodes
            .map { it.toItem(isPr = it.isPr, viewer = viewer, canWrite = false) }
            .partition { it.isPr }
        return RepoState(
            issues = issues,
            prs = prs,
            issueTotal = issues.size,
            prTotal = prs.size,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    // ---- attention rules -----------------------------------------------------------------

    /**
     * `@login` as an actual mention: not part of a longer handle, and not an e-mail address.
     * Built per viewer and cached, since it's applied to every comment fetched.
     */
    private val mentionRegexes = HashMap<String, Regex>()

    private fun mentionsMe(text: String, viewer: String): Boolean {
        if (viewer.isEmpty() || text.isEmpty()) return false
        val re = synchronized(mentionRegexes) {
            mentionRegexes.getOrPut(viewer) {
                Regex("(?<![A-Za-z0-9_.\\-])@" + Regex.escape(viewer) + "(?![A-Za-z0-9_\\-])", RegexOption.IGNORE_CASE)
            }
        }
        return re.containsMatchIn(text)
    }

    /**
     * Login equality that can't be satisfied by a blank on either side.
     *
     * [viewer] is empty whenever `data.viewer.login` was absent or null — reachable on a partial
     * response, e.g. a GitHub Enterprise token without `read:user` errors the `viewer` field
     * while the repository aliases resolve fine. A bare `equals` would then match every item
     * whose own login is also blank (any issue with no comments), flipping the whole "only mine"
     * filter to "everything".
     */
    private fun String?.isLogin(viewer: String): Boolean =
        viewer.isNotEmpty() && !this.isNullOrEmpty() && this.equals(viewer, true)

    /**
     * Whether you've reacted to whatever spoke last here — the newest comment if there is one,
     * otherwise the item's own body. Reacting to the body of an issue that already has replies
     * says nothing about the replies, which is why this reads one or the other rather than both.
     */
    private val GqlNode.acknowledgedBy: Boolean
        get() = (comments.nodes.lastOrNull()?.reactionGroups ?: reactionGroups)
            .any { it.viewerHasReacted }

    private fun GqlNode.toItem(isPr: Boolean, viewer: String, canWrite: Boolean): Item {
        val authorLogin = author?.login.orEmpty()
        val assigned = assignees.nodes.any { it.login.isLogin(viewer) }
        val reviewRequested = isPr && reviewRequests.nodes.any {
            it.requestedReviewer?.login.isLogin(viewer)
        }
        val lastComment = comments.nodes.lastOrNull()
        val lastCommentBy = lastComment?.author?.login.orEmpty()
        // Who spoke last. On an item nobody has answered that's whoever filed it: the body is a
        // message waiting on a reply in exactly the way a follow-up comment is.
        val lastVoiceBy = lastCommentBy.ifEmpty { authorLogin }

        /**
         * Reacting to a comment is how you say "seen". By the time you've hit 👍 you've either
         * answered already or written the follow-up down somewhere, so re-raising the same comment
         * every poll is exactly the nagging this avoids. Any reaction counts, not just THUMBS_UP —
         * 🎉 or ❤️ says "seen" just as clearly, which is why `content` isn't in the query.
         *
         * Only the two comment-derived signals below are gated on it. A review request or an
         * assignment is a standing obligation someone else has to clear; noticing the comment that
         * announced it doesn't discharge the work.
         */
        val acknowledged = acknowledgedBy

        val mentioned = lastComment != null && !acknowledged && mentionsMe(lastComment.bodyText, viewer)
        // "Someone replied on my own thread and I haven't answered." Only counts when the last
        // comment is by someone else — my own last word means the ball is in their court.
        val repliedToMine = viewerDidAuthor && lastComment != null && !acknowledged &&
            lastCommentBy.isNotEmpty() && !lastCommentBy.isLogin(viewer)

        /**
         * The maintainer's inbox: on a repo you maintain, someone else having the last word is
         * itself the obligation — you don't have to have opened the thread or been named in it.
         * [repliedToMine] misses this entirely, since `comments(last: 1)` can't see that you
         * answered earlier in a thread somebody else filed.
         *
         * "Last word" includes the body of an item nobody has answered yet: a freshly filed issue
         * is owed a reply exactly as much as a follow-up comment is, and having none of them count
         * would hide the newest reports — the ones most likely to still matter — behind the ones
         * that already got a conversation. [lastVoiceBy] is what makes the two the same case.
         */
        val awaitingMaintainer = canWrite && !acknowledged &&
            lastVoiceBy.isNotEmpty() && !lastVoiceBy.isLogin(viewer)

        // Ordered by how specifically each signal says "you, now" — the first match is what the
        // UI shows as the explanation.
        val reason = when {
            reviewRequested -> "review requested"
            mentioned -> "mentioned in the last comment"
            repliedToMine -> "replied to your thread"
            assigned -> "assigned to you"
            awaitingMaintainer -> "awaiting your reply"
            else -> null
        }
        return Item(
            number = number,
            title = title,
            url = url,
            isPr = isPr,
            isDraft = isDraft,
            updatedAt = runCatching { Instant.parse(updatedAt).toEpochMilli() }.getOrDefault(0L),
            author = authorLogin,
            labels = labels.nodes.map { it.name },
            awaitingYou = reason != null,
            // Anything with your fingerprints on it, for the "only mine" filter. Broader than
            // awaitingYou: a thread you opened still involves you after you've had the last word,
            // and so does one you only replied to.
            //
            // Having written the last comment is the one participation signal available here —
            // GitHub's own `involves:` also matches a comment anywhere in the thread, which
            // `comments(last: 1)` can't see. Without this clause "only mine" hid the entire issue
            // tracker of a repo you maintain, since maintainers answer issues far more often than
            // they open them.
            //
            // [acknowledged] has to be its own clause rather than riding on [reason]: it's what
            // *suppresses* the reason, so a thread you were only ever @-mentioned in would drop
            // out of "only mine" the moment you reacted to the mention. Reacting is participation.
            involvesYou = reason != null || acknowledged || viewerDidAuthor ||
                authorLogin.isLogin(viewer) || lastCommentBy.isLogin(viewer),
            reason = reason,
        )
    }

    // ---- wire types ----------------------------------------------------------------------

    /** `gh auth status --json hosts`: host -> the accounts gh knows for it. */
    @Serializable private data class AuthStatus(val hosts: Map<String, List<AuthAccount>> = emptyMap())
    @Serializable private data class AuthAccount(
        val state: String = "",      // "success", or a description of what's wrong with the token
        val active: Boolean = false, // the account used when targeting this host
        val host: String = "",
        val login: String = "",
    )

    @Serializable private data class GqlError(val message: String = "")
    @Serializable private data class GqlRepo(
        val viewerPermission: String? = null,
        val issues: GqlConn = GqlConn(),
        val pullRequests: GqlConn = GqlConn(),
    ) {
        /**
         * Null on a token without the scope to see it, and READ/TRIAGE for a repo you merely
         * follow — all of which must read as "not mine to answer" rather than defaulting to yes,
         * or every tracker you've ever filed an issue in becomes your inbox.
         */
        val canWrite: Boolean get() = viewerPermission in setOf("WRITE", "MAINTAIN", "ADMIN")
    }
    @Serializable private data class GqlConn(val totalCount: Int = 0, val nodes: List<GqlNode> = emptyList())
    /** Search returns issues and pull requests in one list, so the node has to say which it is. */
    @Serializable private data class GqlSearch(val nodes: List<GqlNode> = emptyList())

    @Serializable private data class GqlNode(
        @SerialName("__typename") val typename: String = "",
        val number: Int = 0,
        val title: String = "",
        val url: String = "",
        val updatedAt: String = "",
        val isDraft: Boolean = false,
        val viewerDidAuthor: Boolean = false,
        val author: GqlActor? = null,
        val assignees: GqlActorConn = GqlActorConn(),
        val labels: GqlLabelConn = GqlLabelConn(),
        val reviewRequests: GqlReviewConn = GqlReviewConn(),
        val comments: GqlCommentConn = GqlCommentConn(),
        val reactionGroups: List<GqlReactionGroup> = emptyList(),
    ) {
        /** Only ever populated on search results; the connection form knows the type up front. */
        val isPr: Boolean get() = typename == "PullRequest"
    }
    @Serializable private data class GqlActor(val login: String = "")
    @Serializable private data class GqlActorConn(val nodes: List<GqlActor> = emptyList())
    @Serializable private data class GqlLabel(val name: String = "")
    @Serializable private data class GqlLabelConn(val nodes: List<GqlLabel> = emptyList())
    @Serializable private data class GqlReviewConn(val nodes: List<GqlReviewReq> = emptyList())
    @Serializable private data class GqlReviewReq(val requestedReviewer: GqlActor? = null)
    @Serializable private data class GqlCommentConn(val nodes: List<GqlComment> = emptyList())
    @Serializable private data class GqlComment(
        val author: GqlActor? = null,
        val bodyText: String = "",
        val reactionGroups: List<GqlReactionGroup> = emptyList(),
    )
    /**
     * GitHub returns all eight groups on every comment, reacted or not, so only the flag matters —
     * `content` is deliberately not requested: any reaction counts as an acknowledgement.
     */
    @Serializable private data class GqlReactionGroup(val viewerHasReacted: Boolean = false)

    // ---- process plumbing ----------------------------------------------------------------

    private data class Run(val code: Int, val out: String, val err: String)

    /**
     * Deliberately not routed through [GitLog]: that console records the mutating `git`
     * commands the user asked for, and a background poll every few minutes would bury them.
     */
    private fun exec(args: List<String>, timeoutSeconds: Long): Run = try {
        val proc = ProcessBuilder(args)
            // GH_PAGER/PAGER would make gh block forever waiting on a pager that has no tty.
            .apply { environment()["GH_PAGER"] = "cat"; environment()["PAGER"] = "cat"; environment()["GH_PROMPT_DISABLED"] = "1" }
            .start()
        proc.outputStream.close()   // gh must never wait on stdin
        // BOTH pipes are drained on their own threads, and the deadline is enforced by waitFor.
        //
        // Reading either stream on this thread would make [timeoutSeconds] unenforceable:
        // readText() returns only at EOF, which for a subprocess means "it exited" — so waitFor
        // would only ever be reached after the process was already gone. A `gh` wedged on a
        // half-open connection (VPN drop, unreachable Enterprise host) would block here forever,
        // and since this call isn't cancellable, AppState's in-flight flag would never clear:
        // the whole feature would sit on "Checking…" until the app was restarted.
        //
        // Draining both is also what stops the converse deadlock — a large GraphQL response
        // fills the stdout pipe buffer while we block on stderr, and neither side moves again.
        // StringBuffer (not StringBuilder) because a drainer writes it and this thread reads it.
        val outBuf = StringBuffer()
        val errBuf = StringBuffer()
        val outThread = drain(proc.inputStream, outBuf)
        val errThread = drain(proc.errorStream, errBuf)
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            outThread.join(1000); errThread.join(1000)
            Run(-1, outBuf.toString(), "timed out after ${timeoutSeconds}s")
        } else {
            // The process is gone, so both pipes are at EOF and the drainers finish immediately.
            outThread.join(2000); errThread.join(2000)
            Run(proc.exitValue(), outBuf.toString(), errBuf.toString())
        }
    } catch (e: Exception) {
        Run(-1, "", e.message ?: "failed to run gh")
    }

    private fun msgOf(e: kotlinx.serialization.json.JsonElement): String? =
        ((e as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** The error GraphQL attributed to [alias] — `errors[].path` names the field that failed. */
    private fun errorForPath(errors: JsonArray?, alias: String): String? = errors?.firstNotNullOfOrNull { e ->
        val head = ((e as? JsonObject)?.get("path") as? JsonArray)?.firstOrNull()
        if ((head as? JsonPrimitive)?.contentOrNull == alias) msgOf(e) else null
    }

    private fun drain(stream: java.io.InputStream, into: StringBuffer): Thread =
        Thread { runCatching { stream.bufferedReader().use { into.append(it.readText()) } } }
            .apply { isDaemon = true; start() }

    private fun firstLine(s: String): String? =
        s.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
}

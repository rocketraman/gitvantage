// SPDX-FileCopyrightText: 2026 Raman Gupta
// SPDX-License-Identifier: GPL-3.0-or-later

package com.gitvantage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

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

    /** Open items fetched per repo, per type. A repo with more than this many open issues is
     *  reported by its [RepoState.issueTotal] (which is the true count) but only the most
     *  recently updated [PER_REPO] are inspected for the "awaiting you" signal. */
    private const val PER_REPO = 50

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
        val awaitingYou: Boolean,
        val involvesYou: Boolean,
        /** Why it's awaiting you — shown as a hint in the detail panel ("review requested"). */
        val reason: String?,
    )

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
    data class Coord(val owner: String, val name: String, val host: String = PUBLIC_HOST)

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
                forHost.chunked(BATCH).forEach { batch -> out += fetchBatch(gh, host, batch) }
            }
            out
        }

    private fun fetchBatch(gh: String, host: String, batch: List<Map.Entry<String, Coord>>): Map<String, RepoState> {
        // Aliases are positional (R0, R1, …) so the response maps back to repo ids by index —
        // repo *names* can't be used as GraphQL aliases (they allow characters aliases don't).
        // `viewer` comes from the same response rather than being passed in: on an Enterprise
        // host the account is a different one, so "am I mentioned?" has to be asked per host.
        val query = buildString {
            append("query { viewer { login }")
            batch.forEachIndexed { i, (_, c) ->
                append(" R$i: repository(owner: \"${c.owner}\", name: \"${c.name}\") { ...F }")
            }
            append(" }\n")
            append(FRAGMENT)
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

        return batch.mapIndexed { i, (id, _) ->
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
                else id to RepoState(
                    issues = repo.issues.nodes.map { it.toItem(isPr = false, viewer = viewer) },
                    prs = repo.pullRequests.nodes.map { it.toItem(isPr = true, viewer = viewer) },
                    issueTotal = repo.issues.totalCount,
                    prTotal = repo.pullRequests.totalCount,
                    fetchedAt = System.currentTimeMillis(),
                )
            }
        }.toMap()
    }

    /**
     * Everything the attention rules need, in one round trip.
     *
     * `comments(last: 1)` is the expensive-to-emulate part: it's what makes "the last comment
     * @-mentions me" answerable without a request per issue. Note it covers *conversation*
     * comments only — a PR whose newest activity is an inline review comment reports the last
     * conversation comment instead. `bodyText` (not `body`) so markdown and code fences can't
     * produce a false @-mention.
     */
    private val FRAGMENT = """
        fragment F on Repository {
          issues(states: OPEN, first: $PER_REPO, orderBy: {field: UPDATED_AT, direction: DESC}) {
            totalCount
            nodes {
              number title url updatedAt viewerDidAuthor
              author { login }
              assignees(first: 10) { nodes { login } }
              comments(last: 1) { nodes { author { login } bodyText } }
            }
          }
          pullRequests(states: OPEN, first: $PER_REPO, orderBy: {field: UPDATED_AT, direction: DESC}) {
            totalCount
            nodes {
              number title url updatedAt isDraft viewerDidAuthor
              author { login }
              assignees(first: 10) { nodes { login } }
              reviewRequests(first: 10) { nodes { requestedReviewer { ... on User { login } } } }
              comments(last: 1) { nodes { author { login } bodyText } }
            }
          }
        }
    """.trimIndent()

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

    private fun GqlNode.toItem(isPr: Boolean, viewer: String): Item {
        val authorLogin = author?.login.orEmpty()
        val assigned = assignees.nodes.any { it.login.isLogin(viewer) }
        val reviewRequested = isPr && reviewRequests.nodes.any {
            it.requestedReviewer?.login.isLogin(viewer)
        }
        val lastComment = comments.nodes.lastOrNull()
        val lastCommentBy = lastComment?.author?.login.orEmpty()
        val mentioned = lastComment != null && mentionsMe(lastComment.bodyText, viewer)
        // "Someone replied on my own thread and I haven't answered." Only counts when the last
        // comment is by someone else — my own last word means the ball is in their court.
        val repliedToMine = viewerDidAuthor && lastComment != null &&
            lastCommentBy.isNotEmpty() && !lastCommentBy.isLogin(viewer)

        // Ordered by how specifically each signal says "you, now" — the first match is what the
        // UI shows as the explanation.
        val reason = when {
            reviewRequested -> "review requested"
            mentioned -> "mentioned in the last comment"
            repliedToMine -> "replied to your thread"
            assigned -> "assigned to you"
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
            involvesYou = reason != null || viewerDidAuthor ||
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
        val issues: GqlConn = GqlConn(),
        val pullRequests: GqlConn = GqlConn(),
    )
    @Serializable private data class GqlConn(val totalCount: Int = 0, val nodes: List<GqlNode> = emptyList())
    @Serializable private data class GqlNode(
        val number: Int = 0,
        val title: String = "",
        val url: String = "",
        val updatedAt: String = "",
        val isDraft: Boolean = false,
        val viewerDidAuthor: Boolean = false,
        val author: GqlActor? = null,
        val assignees: GqlActorConn = GqlActorConn(),
        val reviewRequests: GqlReviewConn = GqlReviewConn(),
        val comments: GqlCommentConn = GqlCommentConn(),
    )
    @Serializable private data class GqlActor(val login: String = "")
    @Serializable private data class GqlActorConn(val nodes: List<GqlActor> = emptyList())
    @Serializable private data class GqlReviewConn(val nodes: List<GqlReviewReq> = emptyList())
    @Serializable private data class GqlReviewReq(val requestedReviewer: GqlActor? = null)
    @Serializable private data class GqlCommentConn(val nodes: List<GqlComment> = emptyList())
    @Serializable private data class GqlComment(val author: GqlActor? = null, val bodyText: String = "")

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

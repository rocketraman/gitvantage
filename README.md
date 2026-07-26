# GitVantage

A desktop dashboard that keeps every git repository you care about on one screen — so nothing you're working on quietly drifts out of sync, goes stale, or sits with uncommitted work you forgot about.

## The problem

Most developers no longer have *one* repo.
They have dozens, and keeping track of the state of all of them — what's dirty, what's unpushed, what's behind its remote, what's been sitting untouched — is a real, growing burden.

- **AI tooling lets us work on many things at once.**
  With coding agents you can now have work in flight across many repositories *simultaneously*.
  Branches, uncommitted changes, and pending pushes pile up faster than you can hold in your head.
- **Multi-repo microservice systems become hard to coordinate.**
  A single organization often spreads one product across many related-but-separate repositories (services, libraries, infra, submodules).
  Their states are coupled in your mind but scattered on disk.
- **Working across orgs and open source proliferate more repositories.**
  People contribute across multiple employers, clients, and open-source projects at once — each with its own repos, remotes, and rhythms.

GitVantage exists to make that whole surface visible and actionable at a glance.

## A look at it

Every tracked repo on one screen, sorted by how much it wants your attention — red is broken (detached HEAD, no upstream), yellow is work sitting on your machine (uncommitted or unpushed), blue is informational (behind the remote, or gone quiet), green is clean and in sync.

![The GitVantage dashboard: eight repositories in a table, sorted by attention, each with its branch, status badges and tags](docs/screenshots/overview.png)

Select a repo and everything about it is one pane away — actions, stashes, branches with their tracking status, and how it decides this repo is stale.

![The detail panel for a repository, showing tags, actions, a stash, and the branch list with tracking status](docs/screenshots/detail-panel.png)

Uncommitted work opens into a side-by-side diff with character-level highlighting, so you can see what you were in the middle of.

![A side-by-side diff of a modified TypeScript file, with changed regions highlighted within the lines](docs/screenshots/diff.png)

Prefer cards? The same information, laid out for scanning — with your working notes on the face of each one.

![The same repositories in card view, each card showing its change bar, status, tags and working note](docs/screenshots/cards.png)

## What GitVantage does

GitVantage watches all your local repositories and gives you, in one place:

- **A live status dashboard** — every repo with its branch, ahead/behind, and an at-a-glance state (clean · dirty · unpushed · behind · stale · **aging** · issues).
  Table or card view.
- **Real-time updates** — a filesystem watcher re-scans a repo the moment its files change (even from the terminal or another tool), with a periodic background fetch and a manual refresh button for anything a watcher can't see (e.g. remote advances).
- **Powerful triage** — filter by status (dirty, aging, unpushed, stale, issues, stashes, reminders, snoozed), group by tag namespace, sort by name / last commit / attention, and live-search across names, branches, and tags.
- **Namespaced tags + notes** — organize repos with `owner:me`, `lang:kotlin`, … (with inline autocomplete), and keep per-repo working notes.
  Bulk-tag, untag, and act on many repos at once.
- **A rich per-repo detail pane**:
  - Changed files (staged / modified / untracked) and a **GitHub-style side-by-side diff viewer** with character-level highlighting and a flattened file tree.
  - **Branches** with their tracking status vs upstream (ahead / behind / diverged / in sync) *and* vs mainline (merged / stale), one-click **switch**, **diff**, and **delete** (mirrors `git branch -d/-D`).
  - **Remote branches** with last author, one-click checkout into a local tracking branch.
  - **Stashes** — apply, drop, and diff.
  - **Submodules** — see the target repo, how far the pointer is **behind**, uncommitted changes inside, and fetch / update-the-pointer / diff-the-pending-move / add-as-a-tracked-repo / deinit — plus a link up to the parent superproject when it's also tracked.
- **Safe git actions** — commit, push, fetch, fast-forward, branch switch, stash ops; destructive ones are confirmed, and everything the app runs is recorded in a **git console** (with git's colors).
- **Reminders, snooze, and a notification outlook** — set reminders, snooze a repo's alerts for a while, and see a plain-language summary of exactly what will notify you and when (reminders, aging, stale, upstream advances), accounting for any active snooze.
  Desktop notifications fire for due reminders, for aging and stale threshold crossings, and — when you opt a repo in — when its upstream advances.
- **Aging detection** — flags repos whose uncommitted work has been sitting past a threshold, so long-forgotten changes surface instead of rotting.

## Git only — by design

GitVantage is a **git** tool, and will very likely stay that way.
Its whole vocabulary — upstreams, ahead/behind, stashes, submodule pointers, fast-forward, merged branches — is git-specific, and the UI leans into those concepts rather than abstracting them away.
Supporting other VCSs would mean a different, blander tool; GitVantage chooses to be great at git.

## Building & running

It's a Kotlin + Compose Desktop app on the [Nucleus](https://nucleusframework.dev) framework.

```bash
./gradlew run          # run it
./gradlew build        # compile + test
```

**Packaging.**
Release installers are built from a **GraalVM native image** and produced per-OS by CI on a version tag: `.deb` + `.rpm` (Linux), `.msi` (Windows), `.dmg` (macOS).
See `.github/workflows/` for the CI (build + test on every push/PR; native installers on `v*` tags).

## Support

GitVantage is free and open source.
If it saves you time and you'd like to help keep it maintained, donations are very welcome.

- [Sponsor on GitHub](https://github.com/sponsors/rocketraman)
- [Buy Me a Coffee](https://www.buymeacoffee.com/rocketraman)

## Contributing

Contributions are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).
Commits must be signed off under the [Developer Certificate of Origin](https://developercertificate.org/) (`git commit -s`).

## License

GitVantage is licensed under the **GNU General Public License v3.0-or-later** — see [LICENSE](LICENSE).
Third-party components and their licenses are listed in [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md); all are GPL-3.0-compatible.

The GitVantage name and branding are not part of the GPL grant.

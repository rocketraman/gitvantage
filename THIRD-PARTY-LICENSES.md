# Third-party licenses

GitVantage is licensed under the GNU General Public License v3.0-or-later (see
[LICENSE](LICENSE)). It links and/or bundles the components below. Every one of
these licenses is compatible with GPL-3.0, so the combined work may be
distributed under the GPL.

This file is a summary for convenience. The authoritative license text for each
dependency ships inside its published artifact (and at the linked project).

## Runtime frameworks & libraries

| Component | Project | License |
|---|---|---|
| Nucleus (application, decorated-window-tao, notification, fs-watcher) | dev.nucleusframework | MIT |
| Kotlin standard library | JetBrains / Kotlin | Apache-2.0 |
| kotlinx-coroutines, kotlinx-serialization, kotlinx-io, atomicfu | JetBrains / Kotlin | Apache-2.0 |
| Compose Multiplatform (runtime, foundation, material, ui, animation) | JetBrains | Apache-2.0 |
| Skiko (Skia bindings) | JetBrains | Apache-2.0 |
| AndroidX (collection, annotation, lifecycle, savedstate, navigationevent) | Google / AOSP | Apache-2.0 |
| FileKit (dialogs, core) | io.github.vinceglb | MIT |
| JNA / JNA-platform | java-native-access | Apache-2.0 (dual-licensed LGPL-2.1-or-later / Apache-2.0) |
| dbus-java (core, transport) | hypfvieh | MIT |
| JetBrains Runtime API (jbr-api) | JetBrains | Apache-2.0 |
| SLF4J API | QOS.ch | MIT |
| JSpecify | jspecify.org | Apache-2.0 |
| JetBrains Java annotations | JetBrains | Apache-2.0 |

## Bundled fonts

| Font | License |
|---|---|
| JetBrains Mono | SIL Open Font License 1.1 |
| Cantarell | SIL Open Font License 1.1 |

## Build-time only (not redistributed in the app)

- **GraalVM native-image toolchain** — provisioned by the Nucleus Gradle plugin to
  produce native release binaries. It is a build tool, not linked into or shipped
  with the JVM application; its own license governs its use.

---

If you add or update a dependency, keep this file in sync and confirm the new
license is GPL-3.0-compatible.

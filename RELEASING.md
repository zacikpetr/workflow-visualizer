# Releasing a new version

This document is for **maintainers**. Users who just want to install the
plugin should read [README.md](README.md) instead.

The repository is unified — source, build, screenshots, and metadata all
live here. Binary releases are uploaded as **GitHub Release assets** so
the `main` branch stays clean (no ZIPs in git history). The plugin
auto-update mechanism in IntelliJ reads `updatePlugins.xml` from `main`
and fetches the ZIP from the corresponding GitHub Release.

## One-time setup

- Push access to this GitHub repo.
- [GitHub CLI (`gh`)](https://cli.github.com/) installed and authenticated
  (`gh auth login`). Used to publish the Release in one command. If you'd
  rather click around the GitHub web UI, that works too.

## Per-release checklist

### 1. Bump source-side metadata

In **`CHANGELOG.md`** — the single source of truth for release notes; the
plugin's `change-notes` (the IDE plugin-update dialog) are generated from it
at build time. Rename the `## [Unreleased]` section to the new version and
add a fresh empty one above it:
```
## [Unreleased]

## [0.2.0] - YYYY-MM-DD

### Added
- …

### Fixed
- …
```
`./gradlew patchChangelog` performs that promotion for you. Keep the
`## [x.y.z] - DATE` header format — the Gradle Changelog Plugin parses it.

In **`build.gradle.kts`**:
- bump `version` (line near the top) to the new semver value, e.g. `0.2.0`.
  (No change-notes to touch — they are rendered from `CHANGELOG.md`.)

In **`updatePlugins.xml`** (a standalone manifest, not produced by the build):
- update the `version` attribute, and `since-build` if the IDE floor moved.
- update the GitHub Release URL in `<plugin url=…>` to the new tag (only
  the version number changes).
- refresh `<change-notes>` to match the new `CHANGELOG.md` section.

If user-facing things changed, refresh **`README.md`** too (screenshots,
settings, etc.).

### 2. Build the binary

```bash
./gradlew buildPlugin
# Produces build/distributions/workflow-visualizer-<version>.zip
```

### 3. Commit + tag + push

```bash
git add -A
git diff --staged          # sanity check
git commit -m "v0.2.0"
git tag v0.2.0
git push --follow-tags
```

### 4. Create the GitHub Release with the ZIP

With the GitHub CLI:

```bash
VERSION=0.2.0
gh release create v$VERSION \
  build/distributions/workflow-visualizer-$VERSION.zip \
  --title "v$VERSION" \
  --notes "See CHANGELOG.md for the full breakdown."
```

Or via the web UI:
1. Go to https://github.com/zacikpetr/workflow-visualizer/releases/new
2. Choose the existing tag (`v0.2.0`).
3. Title: `v0.2.0`. Body: copy the matching `CHANGELOG.md` section.
4. Drag-and-drop `build/distributions/workflow-visualizer-0.2.0.zip` into
   the "Attach binaries" zone.
5. Publish.

### 5. Verify in an IDE

1. Open IntelliJ → Settings → Plugins.
2. If you haven't already, add the manifest URL once:
   `https://raw.githubusercontent.com/zacikpetr/workflow-visualizer/main/updatePlugins.xml`
3. The plugin should appear with the new version available — install / update.
4. Smoke-test the headline features against a real `.sw.json`.

## Versioning rules

The project follows [Semantic Versioning](https://semver.org/):
- **MAJOR** (X.0.0): incompatible plugin XML / API surface changes that
  affect users' settings or break installed instances.
- **MINOR** (0.X.0): new features, new inspections, new settings —
  backwards compatible.
- **PATCH** (0.0.X): bug fixes and internal refactors.

The plugin's `since-build` is pinned to `252` (IntelliJ 2025.2) with no
`until-build` cap. Bumping `since-build` drops users on older IDEs and is
treated as a major change.

## Hotfix workflow

For a fast follow-up patch: bump patch version → CHANGELOG entry →
buildPlugin → tag + push → `gh release create`. Same as the standard
flow but tighter.

## Rolling back

If a published version is broken:
1. Delete the GitHub Release (`gh release delete v0.2.0`) and the tag
   (`git push --delete origin v0.2.0 && git tag -d v0.2.0`).
2. Revert the commit that introduced it (`git revert <sha>`) and push.
3. In `updatePlugins.xml`, leave the older version advertised — users
   who already installed the broken one will see the older version as
   "available" on the next IDE check and can downgrade by reinstalling.
4. Ship the real fix as the next patch version.

Users who already installed the broken build stay on it until they
upgrade or downgrade; there is no remote-uninstall mechanism.

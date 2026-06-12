# Releasing a new version

This document is for **maintainers**. Users who just want to install the
plugin should read [README.md](README.md) instead.

The repository is unified — source, build, screenshots, and metadata all
live here. Binary releases are uploaded as **GitHub Release assets**; the
plugin auto-update mechanism in IntelliJ reads `updatePlugins.xml` from
`main` and fetches the ZIP from the corresponding GitHub Release.

Since the CI pipeline (`.github/workflows/release.yml`) was added, a release
is **driven by pushing a version tag** — building, verifying, publishing the
Release and updating the manifest all happen in CI, in an order that never
advertises a download URL before the asset exists.

## Per-release checklist

### 1. Bump source-side metadata

```bash
./gradlew patchChangelog   # promotes ## [Unreleased] to the new version
```

In **`build.gradle.kts`**: bump `version` to the same semver value.
That's it — the plugin's `change-notes` and `updatePlugins.xml` are both
generated from `CHANGELOG.md` + `version`; there is nothing else to sync.

If user-facing things changed, refresh **`README.md`** too (screenshots,
settings, etc.).

### 2. Commit, tag, push

```bash
git add -u && git commit -m "Release 0.4.0: <one-line summary>"
git tag v0.4.0
git push --follow-tags
```

The tag must match the Gradle `version` (`v` prefix) — the release workflow
fails fast otherwise.

### 3. CI takes over

The `Release` workflow then:
1. builds, runs the tests and the Plugin Verifier,
2. creates the GitHub Release with the ZIP + SHA-256, notes taken from the
   matching `CHANGELOG.md` section,
3. regenerates `updatePlugins.xml` (`./gradlew generateUpdatePluginsXml`)
   and pushes it to `main` — **after** the Release exists, so polling IDEs
   never hit a 404.

Watch it at https://github.com/zacikpetr/workflow-visualizer/actions.

### 4. Verify in an IDE

1. Open IntelliJ → Settings → Plugins (manifest URL added once:
   `https://raw.githubusercontent.com/zacikpetr/workflow-visualizer/main/updatePlugins.xml`).
2. The new version should appear as an update (raw.githubusercontent.com
   caches ~5 min — allow for the delay).
3. Smoke-test the headline features against a real `.sw.json`.

## Manual fallback

If CI is unavailable, the steps it automates are:

```bash
VERSION=0.4.0
./gradlew build verifyPlugin -PciBuild   # -PciBuild = include searchable options
gh release create v$VERSION \
  build/distributions/workflow-visualizer-$VERSION.zip \
  --title "v$VERSION" --notes "See CHANGELOG.md."
./gradlew generateUpdatePluginsXml
git add updatePlugins.xml && git commit -m "Advertise v$VERSION" && git push
```

Keep that order — Release first, manifest second.

## Versioning rules

The project follows [Semantic Versioning](https://semver.org/):
- **MAJOR** (X.0.0): incompatible plugin XML / API surface changes that
  affect users' settings or break installed instances.
- **MINOR** (0.X.0): new features, new inspections, new settings —
  backwards compatible.
- **PATCH** (0.0.X): bug fixes and internal refactors.

The plugin's `since-build` is pinned in `build.gradle.kts`
(`pluginSinceBuild`, currently `252` = IntelliJ 2025.2) with no
`until-build` cap. Bumping `since-build` drops users on older IDEs and is
treated as a major change.

## Rolling back

If a published version is broken:
1. Delete the GitHub Release (`gh release delete v0.4.0`) and the tag
   (`git push --delete origin v0.4.0 && git tag -d v0.4.0`).
2. Revert the version-bump commit and the manifest commit on `main`, push.
   raw.githubusercontent.com caches ~5 min, so the rollback propagates with
   a delay.
3. Ship the real fix as the next patch version.

Users who already installed the broken build stay on it until they upgrade
or downgrade; there is no remote-uninstall mechanism.

## Invariants

- **Never rename the repository, the `main` branch, or move
  `updatePlugins.xml`** — installed IDEs poll its raw URL; a rename silently
  strands every user with no update channel. If it ever must happen, ship a
  transitional release first.

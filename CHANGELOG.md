# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.3.0] - 2026-06-03

### Added
- Trackpad pinch-to-zoom on the diagram (macOS).
- Zoom In / Zoom Out toolbar buttons (`Ctrl+=` / `Ctrl+-`).
- A one-time hint pointing out the diagram's navigation gestures.

### Changed
- Scrolling now pans the diagram (two-finger trackpad swipe or mouse wheel);
  zoom moved to ⌘/Ctrl+scroll or a trackpad pinch. Previously the mouse wheel
  zoomed.

## [0.2.0] - 2026-06-01

### Fixed
- The diagram no longer stretches when the tool window's proportions differ
  from the diagram's — it keeps its aspect ratio at any window size.

### Changed
- Minimum supported IDE raised to 2025.2 (no upper version bound).
- Definition names are now colored by default, matching the diagram's
  per-type palette (names in bold, references in italic). Every color stays
  rebindable under Settings → Editor → Color Scheme.
- Updated bundled PlantUML, Batik and Gson.

## [0.1.0] - 2026-05-29

Initial release.

### Added

#### Diagram tool window
- Live PlantUML state-diagram rendering of `.sw.json` in a side tool window.
- Smetana layout — no Graphviz / `dot` dependency.
- Zoom (mouse wheel) and pan (left-drag); zoom is preserved across edits when
  the user is at or above the auto-fit baseline.
- Bidirectional navigation: click a state in the diagram to jump the editor
  caret to its definition; move the caret in the JSON to highlight the
  enclosing state in the diagram.
- Located-state fill highlighting via in-place SVG DOM patching (no
  re-render on caret moves).
- Toolbar: **Fit to Window**, **Export as SVG…**, **Export as PUML…**.
- Auto-recovery from PlantUML / Smetana render failures — keeps the last
  good diagram on screen instead of an error stack trace.

#### Inspections (under "Serverless Workflow")
- **Missing definition** — unresolved state / function / event / error
  references, with a quick-fix that inserts a stub.
- **Duplicate names** — repeated `name` within `states[]`, `functions[]`,
  `events[]` or `errors[]`.
- **Missing `start` state**.
- **No terminal state** — workflow lacks any `end: true` or
  transition-less state.
- **Unreachable state** — state plus every transition literal inside it
  are highlighted as dead code.
- **Unused state** — defined but no `start` / `transition` ever
  references it.
- **Switch without `defaultCondition`** — switch with `dataConditions` and
  no fallback.
- **Event state without `timeouts`** — may wait indefinitely.

#### Navigation and PSI
- PSI references on `transition` / `functionRef.refName` / `eventRef` /
  `errorRef` / `start` / `nextState` / `eventRefs[]` literals — enables
  Go to Declaration, Find Usages, and Rename refactoring.
- Gutter icons with usage count on each definition's `name`.
- Custom usage-popup renderer: each row is the JSON path inside the
  enclosing state plus a discriminator snippet (condition / eventRef /
  errorRef). Usages inside unreachable states render struck-through and
  dimmed.
- Hover and Quick Documentation (F1 / Ctrl+Q) preview of the referenced
  definition's body.

#### Editor coloring
- Semantic highlights for references (state / function / event / error)
  and definition names (states by type — operation / switch / event /
  foreach / start / end / other, plus function / event / error names).
- Color Settings page (Settings → Editor → Color Scheme → Workflow
  Visualizer) lets users rebind every key.

#### Mutation badges
- Opt-in per-state annotations showing the runtime fields each state's
  actions mutate, parsed from `expression`-typed functions.
- Configurable field list (defaults: `state`, `phase`, `continuationPoint`,
  `stateDetail`).
- Per-field tint matching the GitHub-style legend.

#### Dead-code visualisation in the diagram
- Unreachable states render in grey with a struck-through label; their
  outgoing edges render in grey as well — `onErrors` edges from dead
  states stay grey rather than red.

#### Settings
- Application-level Settings panel under Settings → Tools → Workflow
  Visualizer: semantic coloring, locate color, edge-label truncation,
  error-edge color, dim unreachable, mutation badges.
- Live update — toggles apply on **Apply** without an IDE restart.

#### Other
- JSON Schema suppression for `*.sw.json` — prevents schemastore.org's
  v1.0 schema from generating noise on v0.8 documents.
- Cached workflow index (definitions, names, usages) and reachability set,
  keyed on PSI modifications.

### Notes

- Built for **Serverless Workflow v0.8** (`specVersion: "0.8"`).
- Requires IntelliJ Platform 233 (2023.3) or newer — no upper version
  bound.
- License: GPLv3 (inherited from bundled PlantUML).

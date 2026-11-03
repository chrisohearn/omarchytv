---
project_id: omarchytv
project_name: OmarchyTV
owner: Chris
status: active
canonical_repository: chrisohearn/omarchytv
last_reviewed: 2026-11-03
source_head: ba084c10dab2a6695594f781d3978864206fed41
runtime_last_verified: unverified
---

# Project operating context

## Delivery expectation

Chris's instructions are "always fully work until shipped and live" and
"always check your work live". Carry an authorized product change through
shipping and fresh verification in the running product. Source, a passing build,
or a reviewed PR is an intermediate checkpoint. Use a source-only endpoint when
Chris explicitly requests one.

Report the installed source and artifact identity, the behavior actually checked,
and any remaining limits. Inspect the actual screen after a UI update. Preserve
review gates, task authority, signing, pairing, app data, and active media and
device state. Coordinate shared repository and device ownership before effects.

Every launcher update displays a dated version after the workspace buttons in
the top bar. Use `vYYYY.MM.DD` for the first version on a local calendar date.
If that date is already in use, increment its highest shipped revision:
`v2026.11.02` → `v2026.11.02.1` → `v2026.11.02.2`. Treat the unsuffixed date
as revision zero. A new date starts without a suffix.

Before a version change, read the current installed label and the latest shipped
version for that date. Pass `-PbuildRevision=N` to Gradle for revision `N`.
Omit the option, or use `-PbuildRevision=0`, for an unsuffixed date. Gradle generates
`build_stamp` from the local date and this revision. Build a fresh APK on the
installation day, using the build machine's local calendar date. If the date
changes before installation, select the new date and rebuild.
Verify the complete date and revision on the installed screen. The label
supplements the source commit and APK hash used for precise update and rollback
records.

## UI direction

Keep OmarchyTV's terminal identity and configurable themes. Home is a quiet strip
of chosen app artwork without captions or a normal name/count header. The Apps
desktop provides the complete TV-app catalog, including apps hidden from Home.
Workspace order and visibility remain configurable.

## Evidence and maintenance

Repository source and fresh authenticated runtime readback outrank this file.
Runtime identities, screenshots, credentials, and personal device configuration
stay in private task evidence. This document records owner preferences and source
context, not current deployment state or additional execution authority.

Reverify mutable state before each rollout. Keep this file concise and update it
only for an authorized durable decision. Root owns shared project-memory edits.
See [UI validation](docs/ui-validation.md) for native rendering and rollout checks.

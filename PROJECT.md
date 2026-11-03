---
project_id: omarchytv
project_name: OmarchyTV
owner: Chris
status: active
canonical_repository: chrisohearn/omarchytv
last_reviewed: 2026-11-03
source_head: f9ad70a12d4598f23dc1e9f190b36d327e05f8f3
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

Every launcher update displays `vYYYY.MM.DD` after the workspace buttons in the
top bar. Build a fresh APK on the installation day, using the build machine's
local calendar date. If the date changes before installation, rebuild. Verify
that the installed screen shows today's date. The date label supplements the
source commit and APK hash used for precise update and rollback records.

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

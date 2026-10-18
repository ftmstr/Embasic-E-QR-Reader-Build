# Public CI and release gates — 2026-10-18

Provenance: file-level selection from the existing private Android development source; **not** a Git history migration.

| Gate | Requirement | Initial evidence |
| --- | --- | --- |
| A | Source scan, synthetic Python checks | Initial source scan performed before staging; full CI pending |
| B | Gradle resolves plugins/dependencies | Pending actual GitHub Actions |
| C | JVM tests and Android lint pass | Pending actual GitHub Actions |
| D | Debug APK assembles; checksum verified | Pending actual GitHub Actions |
| E | On-device camera/permissions/gallery/Private Scan tests | Not executed |
| F | Complete license/CVE inventory; merged manifest | Not executed |
| G | Release signing, Play Store policy and data-safety review | Out of scope |

Important: The app contains advertisements in the existing source. Debug sample IDs are not production credentials; real consent UX and network behavior require device review. An APK generated in CI is an unsigned debug artifact, not a release.

Privacy review: The selected source snapshot contains no identified private keys or real user sample data in the inspected files. This does not establish that the entire private history, dependency tree, CI metadata or compiled application is free from secrets. The public GitHub API may attach the authenticated account's email address to new commits; GitHub account privacy controls and history need separate review.

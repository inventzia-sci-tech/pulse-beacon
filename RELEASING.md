<!--
SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
-->

# Releasing pulse-data + pulse-beacon

The two repos release **together**, at the **same version and tag**. The JPype runtime
jar embeds Java `pulse-data`, and the Python `pulse-beacon` wheel pins Python
`pulse-data==<exact>`, so their versions must match exactly — `check-versions.sh`
enforces this and the release is gated on it.

## Version scheme

One version per release, applied to the Python and Java halves of both components,
using the **PEP 440 canonical spelling** — which Maven also accepts and orders as a
pre-release:

| kind  | example    |
|-------|------------|
| alpha | `0.1.0a1`  |
| beta  | `0.1.0b1`  |
| rc    | `0.1.0rc1` |
| final | `0.1.0`    |

Use `0.1.0b1`, **not** Maven's `0.1.0-beta1`: the canonical form keeps the version
string identical on both sides, so the gate compares by exact equality (Maven still
parses the `b` as its beta qualifier, so `0.1.0b1 < 0.1.0`). Verified end-to-end —
Maven installs and resolves `0.1.0b1`, and the runtime jar builds through it.

During development the poms stay on `-SNAPSHOT`; the flip below happens only in the
release commit.

## Prerequisites (one-time)

**Protect `v*` tags in both repos** against deletion and force-update (GitHub → Settings →
Rules / Tag protection). The release job resolves and records the pulse-data commit SHA for
provenance, but nothing *prevents* a movable pulse-data tag from yielding different artifacts
on a rerun — tag protection makes the `tag → commit` binding immutable. (Alternative: pin the
expected pulse-data SHA in pulse-beacon and assert it in the release job.)

## 1. Flip the version (release commit)

Set the release version in exactly these six places (example: `0.1.0b1`):

- `pulse-data/pyproject.toml`      → `version = "0.1.0b1"`
- `pulse-data/pom.xml`             → `<version>0.1.0b1</version>`
- `pulse-beacon/pyproject.toml`    → `version = "0.1.0b1"` **and** the dep `"pulse-data==0.1.0b1"`
- `pulse-beacon/core/java/pom.xml` → `<version>0.1.0b1</version>` **and** `<pulse-data.version>0.1.0b1</pulse-data.version>`

Verify before committing (must report "release versions consistent"):

```bash
( cd pulse-beacon && bash check-versions.sh --release --tag v0.1.0b1 )
```

The gate rejects: any `-SNAPSHOT`, any Python≠Maven mismatch, a `pulse-data` pin looser
than `==<version>`, or a tag that doesn't match the version. Commit the flip in **both**
repos.

## 2. Tag both repos and push — pulse-data first

The pulse-beacon CI `release` job checks out the **same tag in both repos**, so pulse-data's
tag must already exist remotely *before* pulse-beacon's tag is pushed — otherwise the
pulse-beacon release run fails immediately at the pulse-data checkout. Order matters:

```bash
# 1) pulse-data first
git -C pulse-data tag v0.1.0b1
git -C pulse-data push origin v0.1.0b1
# 2) confirm it exists remotely (must print the tag)
git -C pulse-data ls-remote --tags origin v0.1.0b1
# 3) then pulse-beacon — this push is what triggers the release job
git -C pulse-beacon tag v0.1.0b1
git -C pulse-beacon push origin v0.1.0b1
```

Pushing the pulse-beacon tag triggers the `release` job: it re-checks the tag against both
package versions, runs `release-build.sh` through the version gate, and uploads the two
wheels plus `release-inputs.txt` (tag + both commit SHAs) as provenance.

> **First live run.** The `release` job only executes on a `v*` tag, so the first release tag
> is also its first real exercise — matching-tag checkout, tag validation, and artifact upload
> have not run before. The prerequisite jobs (`maven` / `dist` / `integration`) are proven on
> every push, but watch this run and be ready to fix forward. Because publishing is a separate
> step, a failed first `release` run costs only a tag, not a published artifact — so the beta
> tag is effectively its rehearsal.

## 3. (Optional) Local build / TestPyPI dry-run

To produce the wheels without tagging — e.g. to rehearse on TestPyPI:

```bash
( cd pulse-beacon && bash release-build.sh <output-dir> )
```

It runs the release gate first (so it refuses until the versions are flipped), builds the
shaded runtime jar, and builds both wheels with the jar bundled. PyYAML is only needed to
regenerate schemas, not to build.

## 4. After the release — return to a development state

Update **all six** version locations back to a development state the gate's *dev* mode
accepts (per component, `base(Maven) == Python`) — not just the poms:

- **Maven** (both pom `<version>` **and** the `pulse-data.version` property) → `<next>-SNAPSHOT`
- **Python** (both `pyproject.toml` versions **and** the `pulse-data==` pin) → the matching `<next>` base

For example, after `0.1.0b1` while still heading to `0.1.0`: poms → `0.1.0-SNAPSHOT`, Python →
`0.1.0` and `pulse-data==0.1.0`. Bumping only the poms would leave Python on `0.1.0b1` and
**fail** `check-versions.sh` — dev mode compares `base(Maven)` (which strips `-SNAPSHOT`) to the
Python version, so the two must agree. Keep the components in lockstep.

## Publishing (separate, credentialed step — not yet wired)

The CI `release` job builds and uploads artifacts but does **not** publish. Actual
publication additionally needs: GPG signing + the central-publishing plugin (Maven
Central), a PyPI token, and the real `<scm>` coordinates in both poms. See
`PackagingAndPublishingRelease.md` §5.

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

## 2. Tag both repos and push

The pulse-beacon CI `release` job checks out the **same tag in both repos**, so tag both
at the release commit:

```bash
# in each repo, on its release commit:
git tag v0.1.0b1
git push origin v0.1.0b1
```

Pushing the tag triggers the `release` job, which re-checks the tag against both package
versions, runs `release-build.sh` through the version gate, and uploads the two wheels
plus `release-inputs.txt` (tag + both commit SHAs) as artifacts.

## 3. (Optional) Local build / TestPyPI dry-run

To produce the wheels without tagging — e.g. to rehearse on TestPyPI:

```bash
( cd pulse-beacon && bash release-build.sh <output-dir> )
```

It runs the release gate first (so it refuses until the versions are flipped), builds the
shaded runtime jar, and builds both wheels with the jar bundled. PyYAML is only needed to
regenerate schemas, not to build.

## 4. After the release

Bump the poms back to the next `-SNAPSHOT` so development continues on a snapshot; keep
the two components in lockstep.

## Publishing (separate, credentialed step — not yet wired)

The CI `release` job builds and uploads artifacts but does **not** publish. Actual
publication additionally needs: GPG signing + the central-publishing plugin (Maven
Central), a PyPI token, and the real `<scm>` coordinates in both poms. See
`PackagingAndPublishingRelease.md` §5.

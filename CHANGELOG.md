# Changelog

All notable changes to pulse-beacon are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Core Java interfaces and types under `core/java/src/main/java/com/inventzia/beacon/core/`:
  - `Event` — base interface for all events; carries `typeId()`, `schemaVersion()`, `key()`,
    `eventTime()`, `publishedTime()`, `receivedTime()` (epoch milliseconds).
  - `Topic<E extends Event>` — typed routing identity record; validates the `TYPE_ID` static
    field contract on event classes at construction time.
  - `Pub` — publisher interface: `publish(Topic<E>, E)`.
  - `Sub` — subscriber interface: `onEvent(Topic<E>, E)`.
  - `Actor` — combined publisher and subscriber: `extends Pub, Sub`.
  - `GatewayStatus` — lifecycle state enum: `BLANK → INITIALIZED → PRESTART → STARTED →
    WRAP_UP → PAUSED → FINALIZE → STOPPED → COMPLETE`.
  - `Gateway` — gateway contract: pub/sub registration, simulation window, clock-driving
    flag, connection lifecycle, identity and status.
- Initial repository scaffolding.
- Dual-licensing files: `LICENSE-AGPL-3.0`, `LICENSE-COMMERCIAL.txt`.
- Contribution policy with DCO sign-off (`CLA.md`).
- Security disclosure policy (`SECURITY.md`).
- Commercial licensing description (`COMMERCIAL.md`).
- Third-party attribution file (`NOTICE`).
- GitHub Actions workflow enforcing DCO sign-off on pull requests.

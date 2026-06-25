# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-beacon.
#
# pulse-beacon is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
"""
Python mirror of pulse-beacon's Java logging facade
(``Reporter`` / ``ReportLevel`` / ``Slf4jReporter`` / ``ComponentReporter``).

Components never pick or configure a logging library themselves. Every actor,
gateway, and bridge piece owns a :class:`ComponentReporter` bound to its name,
provided by the base class it extends, and logs through it
(``self.log.info(...)``). The reporter forwards to a swappable :class:`Reporter`
sink — by default :class:`LoggingReporter`, which routes to Python's stdlib
``logging`` (the analog of the Java ``Slf4jReporter``). This guarantees one
methodology across the infrastructure, identical in spirit to the Java side.

Levels mirror Java's :class:`ReportLevel` exactly, and map to stdlib logging as:
LARGEINFO→DEBUG, INFO→INFO, WARNING→WARN, SEVERE/FATAL→ERROR. The rendered line
matches the Java/Logback pattern:

    2026-06-17 09:10:01.123 [INFO ] {engine}           : status STARTED
"""

import logging
import time
from abc import ABC, abstractmethod
from enum import IntEnum
from typing import Callable, Union


class ReportLevel(IntEnum):
    """Severity levels, mirroring the Java ``ReportLevel`` (ordinal = severity)."""

    LARGEINFO = 0   # verbose diagnostics (≈ debug)
    INFO = 1        # normal operational information
    WARNING = 2     # unexpected but recoverable
    SEVERE = 3      # serious error affecting correctness/integrity
    FATAL = 4       # unrecoverable

    def meets(self, threshold: "ReportLevel") -> bool:
        """True if this level is at least as severe as ``threshold``."""
        return self.value >= threshold.value


# ReportLevel → stdlib logging level (Java: LARGEINFO→debug, …, SEVERE/FATAL→error)
_TO_LOGGING = {
    ReportLevel.LARGEINFO: logging.DEBUG,
    ReportLevel.INFO: logging.INFO,
    ReportLevel.WARNING: logging.WARNING,
    ReportLevel.SEVERE: logging.ERROR,
    ReportLevel.FATAL: logging.ERROR,
}


class Reporter(ABC):
    """The delivery sink behind logging/reporting. Mirror of the Java ``Reporter``."""

    @abstractmethod
    def report(self, timestamp: int, source: str, message: str, level: ReportLevel) -> None:
        """Deliver a message from ``source`` at ``level`` (``timestamp`` = platform millis)."""

    def is_enabled(self, source: str, level: ReportLevel) -> bool:
        """Whether such a message would be delivered; lets callers skip costly builds."""
        return True


class LoggingReporter(Reporter):
    """Default sink: routes to Python's stdlib ``logging`` (analog of ``Slf4jReporter``).

    Each ``source`` maps to its own logger, so a deployment can level or silence
    components independently. The backend supplies the wall-clock timestamp, so
    the platform ``timestamp`` argument is left for the message to embed where it
    matters (e.g. compressed-time replay) and is not prepended here.
    """

    _shared: "LoggingReporter | None" = None

    @classmethod
    def shared(cls) -> "LoggingReporter":
        """The process-wide shared sink."""
        if cls._shared is None:
            cls._shared = cls()
        return cls._shared

    def report(self, timestamp: int, source: str, message: str, level: ReportLevel) -> None:
        logging.getLogger(source).log(_TO_LOGGING[level], message)

    def is_enabled(self, source: str, level: ReportLevel) -> bool:
        return logging.getLogger(source).isEnabledFor(_TO_LOGGING[level])


class ComponentReporter:
    """Per-component logging handle. Mirror of the Java ``ComponentReporter``.

    Components log through this (``log.info("…")``) rather than touching a logging
    framework. The sink can be swapped at runtime via :meth:`sink` to redirect a
    component's output without changing the component.
    """

    def __init__(self, source: str, sink: "Reporter | None" = None):
        self._source = source
        self._sink: Reporter = sink or LoggingReporter.shared()

    def sink(self, sink: Reporter) -> None:
        """Redirect this component's output to a different sink."""
        self._sink = sink

    def large_info(self, message: Union[str, Callable[[], str]]) -> None:
        """Verbose diagnostics (≈ debug). Pass a callable to defer building hot-path messages."""
        self._emit(ReportLevel.LARGEINFO, message)

    def info(self, message: Union[str, Callable[[], str]]) -> None:
        """Normal operational information."""
        self._emit(ReportLevel.INFO, message)

    def warn(self, message: Union[str, Callable[[], str]]) -> None:
        """Something unexpected but recoverable."""
        self._emit(ReportLevel.WARNING, message)

    def severe(self, message: Union[str, Callable[[], str]]) -> None:
        """A serious error that may affect correctness or data integrity."""
        self._emit(ReportLevel.SEVERE, message)

    def fatal(self, message: Union[str, Callable[[], str]]) -> None:
        """An unrecoverable condition."""
        self._emit(ReportLevel.FATAL, message)

    def _emit(self, level: ReportLevel, message: Union[str, Callable[[], str]]) -> None:
        # Any level may take a callable to defer building the message; if the level
        # is disabled the callable is never invoked (and never logged as its repr).
        if callable(message):
            if not self._sink.is_enabled(self._source, level):
                return
            message = message()
        self._sink.report(_now(), self._source, message, level)


def _now() -> int:
    return int(time.time() * 1000)


# ----------------------------------------------------------------------------
# Output configuration — the Python equivalent of logback.xml. An application
# (a launcher/example) calls this once; components never configure logging.
# ----------------------------------------------------------------------------

class _BeaconFormatter(logging.Formatter):
    """Renders the Java/Logback line shape: ``ts [LEVEL] {component}        : msg``."""

    def format(self, record: logging.LogRecord) -> str:
        record.component = ("{" + record.name + "}").ljust(18)
        return super().format(record)


def configure_logging(level: ReportLevel = ReportLevel.INFO) -> None:
    """Install the harmonized console format on the root logger (idempotent).

    Matches the Java/Logback pattern so Python and Java lines read identically in
    a shared console. Call once from a launcher; not from component code.
    """
    logging.addLevelName(logging.WARNING, "WARN")  # match the Java/Logback token
    handler = logging.StreamHandler()
    handler.setFormatter(_BeaconFormatter(
        fmt="%(asctime)s.%(msecs)03d [%(levelname)-5s] %(component)s : %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"))
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(_TO_LOGGING[level])

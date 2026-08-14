# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# This file is part of pulse-data.
#
# pulse-data is dual-licensed:
#   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
#   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
#     Contact operations@inventzia.com.
#
# THIS FILE IS GENERATED. DO NOT EDIT MANUALLY.
# Source: schemas/extended_bar.yaml
# Regenerate: python schemas/schemas-generators/generate_python.py

from __future__ import annotations
from decimal import Decimal
from pydantic import BaseModel, ConfigDict, Field
from typing import ClassVar, Optional


class ExtendedBar(BaseModel):
    """
    An example extension datum: a CdfBar-style market bar with extra order-flow fields, defined by an independent package (pulse-ext-example) and contributed through the DatumTypeProvider SPI without modifying pulse-data.
    """

    model_config = ConfigDict(extra="ignore", frozen=True)

    TYPE_ID:      ClassVar[str] = "com.inventzia.pulse.ext.schemas.ExtendedBar"
    TYPE_VERSION: ClassVar[int] = 1

    symb: str
    """Instrument symbol"""
    timestamp: int
    """Epoch milliseconds of the bar open"""
    op: Decimal
    """Open price"""
    hi: Decimal
    """High price"""
    lo: Decimal
    """Low price"""
    cl: Decimal
    """Close price"""
    vlm: Decimal
    """Volume traded"""
    bid_volume: Decimal = Field(alias="bidVolume")
    """Volume traded at the bid"""
    ask_volume: Decimal = Field(alias="askVolume")
    """Volume traded at the ask"""
    trade_count: int = Field(alias="tradeCount")
    """Number of trades in the bar"""

    venue: Optional[str] = None
    """Optional source venue"""

    # -- Datum protocol ---------------------------------------------------

    @property
    def datum_key(self) -> str:
        return self.symb

    @property
    def datum_time(self) -> int:
        return self.timestamp

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
# Source: all schemas under schemas_yaml/
# Regenerate: python schemas/schemas-generators/generate_python.py

"""The datum-type provider (generated) for com.inventzia.pulse.ext."""

from inventzia.pulse.data.datum.provider import DatumTypeBinding
from inventzia.pulse.ext.schemas.extended_bar import ExtendedBar


class ExtDatumTypeProvider:
    """Datum types contributed by com.inventzia.pulse.ext, discovered via the SPI."""

    def provider_id(self) -> str:
        return "com.inventzia.pulse.ext"

    def spi_version(self) -> int:
        return 1

    def package_version(self) -> str:
        return "0.1.0"

    def bindings(self) -> "list[DatumTypeBinding]":
        return [
            DatumTypeBinding(ExtendedBar.TYPE_ID, ExtendedBar.TYPE_VERSION, ExtendedBar),
        ]

    def manifest(self):
        return "pdm1|com.inventzia.pulse.ext|com.inventzia.pulse.ext.schemas.ExtendedBar:1:cb40134d48fec99259517730b99eba77683308ed2f5328bace0cb97e661fd7dc"

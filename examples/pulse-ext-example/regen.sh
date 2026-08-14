#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
# Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
#
# Regenerate this extension's Python + Java datum bindings and its DatumTypeProvider,
# using pulse-data's generators with this package's own namespace and provider id.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# pulse-data's generators. Defaults to a sibling checkout of pulse-data next to
# pulse-beacon (../../../pulse-data from here); override with PULSE_DATA_GENERATORS.
GEN="${PULSE_DATA_GENERATORS:-$HERE/../../../pulse-data/schemas/schemas-generators}"
PROVIDER_ID="com.inventzia.pulse.ext"
PROVIDER_CLASS="ExtDatumTypeProvider"
VERSION="0.1.0"

python "$GEN/generate_python.py" \
    --schemas-dir "$HERE/schemas" --output-dir "$HERE/src" \
    --base-package inventzia.pulse.ext.schemas \
    --provider-id "$PROVIDER_ID" --provider-class "$PROVIDER_CLASS" --package-version "$VERSION"

python "$GEN/generate_java.py" \
    --schemas-dir "$HERE/schemas" --output-dir "$HERE/java/src/main/java" \
    --base-package com.inventzia.pulse.ext.schemas \
    --provider-id "$PROVIDER_ID" --provider-class "$PROVIDER_CLASS" --package-version "$VERSION"

echo "regenerated ExtendedBar bindings + $PROVIDER_CLASS"

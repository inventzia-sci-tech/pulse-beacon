/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 *
 * This file is part of pulse-data.
 *
 * pulse-data is dual-licensed:
 *   - Under the GNU Affero General Public License v3.0 or later (see LICENSE-AGPL-3.0).
 *   - Under a commercial license (see LICENSE-COMMERCIAL.txt).
 *     Contact operations@inventzia.com.
 *
 * THIS FILE IS GENERATED. DO NOT EDIT MANUALLY.
 * Source: all schemas under schemas_yaml/
 * Regenerate: python schemas/schemas-generators/generate_java.py
 */

package com.inventzia.pulse.ext.schemas;

import com.inventzia.pulse.data.datum.DatumTypeBinding;
import com.inventzia.pulse.data.datum.DatumTypeProvider;
import com.inventzia.pulse.ext.schemas.ExtendedBar;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Datum types contributed by com.inventzia.pulse.ext (generated), discovered via the SPI.
 */
public final class ExtDatumTypeProvider implements DatumTypeProvider {

    @Override public String providerId()     { return "com.inventzia.pulse.ext"; }
    @Override public int    spiVersion()      { return 1; }
    @Override public String packageVersion()  { return "0.1.0"; }

    @Override
    public Collection<DatumTypeBinding> bindings() {
        return List.of(
                new DatumTypeBinding(ExtendedBar.TYPE_ID, ExtendedBar.TYPE_VERSION, ExtendedBar.class)
        );
    }

    @Override
    public Optional<String> manifest() {
        return Optional.of("pdm1|com.inventzia.pulse.ext|com.inventzia.pulse.ext.schemas.ExtendedBar:1:cb40134d48fec99259517730b99eba77683308ed2f5328bace0cb97e661fd7dc");
    }
}

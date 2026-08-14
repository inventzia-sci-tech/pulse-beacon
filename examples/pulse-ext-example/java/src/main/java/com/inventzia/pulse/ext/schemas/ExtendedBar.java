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
 * Source: schemas/extended_bar.yaml
 * Regenerate: python schemas/schemas-generators/generate_java.py
 */

package com.inventzia.pulse.ext.schemas;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.inventzia.pulse.data.datum.Datum;
import java.math.BigDecimal;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An example extension datum: a CdfBar-style market bar with extra order-flow fields, defined by an independent package (pulse-ext-example) and contributed through the DatumTypeProvider SPI without modifying pulse-data.
 *
 * <p>Type ID: {@value #TYPE_ID}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtendedBar(
    @JsonProperty(value = "symb", required = true) String symb,
    @JsonProperty(value = "timestamp", required = true) long timestamp,
    @JsonProperty(value = "op", required = true) BigDecimal op,
    @JsonProperty(value = "hi", required = true) BigDecimal hi,
    @JsonProperty(value = "lo", required = true) BigDecimal lo,
    @JsonProperty(value = "cl", required = true) BigDecimal cl,
    @JsonProperty(value = "vlm", required = true) BigDecimal vlm,
    @JsonProperty(value = "bidVolume", required = true) BigDecimal bidVolume,
    @JsonProperty(value = "askVolume", required = true) BigDecimal askVolume,
    @JsonProperty(value = "tradeCount", required = true) long tradeCount,
    @JsonProperty("venue") @Nullable String venue
) implements Datum {

    public ExtendedBar {
        symb = Objects.requireNonNull(symb, "symb");
        op = Objects.requireNonNull(op, "op");
        hi = Objects.requireNonNull(hi, "hi");
        lo = Objects.requireNonNull(lo, "lo");
        cl = Objects.requireNonNull(cl, "cl");
        vlm = Objects.requireNonNull(vlm, "vlm");
        bidVolume = Objects.requireNonNull(bidVolume, "bidVolume");
        askVolume = Objects.requireNonNull(askVolume, "askVolume");
    }

    public static final String TYPE_ID      = "com.inventzia.pulse.ext.schemas.ExtendedBar";
    public static final int    TYPE_VERSION = 1;

    @Override public String getDatumKey()  { return symb; }
    @Override public long   getDatumTime() { return timestamp; }
}

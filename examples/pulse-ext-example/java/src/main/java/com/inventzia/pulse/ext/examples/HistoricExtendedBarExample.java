/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 */
package com.inventzia.pulse.ext.examples;

import com.inventzia.pulse.beacon.core.OperatingMode;

/**
 * Runnable historical example: the extension's {@code ExtendedBar} replayed through the engine in
 * {@link OperatingMode#COMPRESSED_TIME}. The window is entirely in the past, so the run is
 * deterministic and finishes as fast as the bars can be merged.
 *
 * <p>A plain {@code main}: in Eclipse, import {@code java/} as a Maven project and run or debug
 * this class with <em>Run/Debug As &gt; Java Application</em> (set breakpoints and step through).
 * See {@link RealTimeExtendedBarExample} for the wall-clock variant and {@link ExtendedBarRun}
 * for the shared logic.
 */
public final class HistoricExtendedBarExample {

    public static void main(String[] args) throws Exception {
        System.exit(ExtendedBarRun.execute(false) ? 0 : 1);
    }

    private HistoricExtendedBarExample() {}
}

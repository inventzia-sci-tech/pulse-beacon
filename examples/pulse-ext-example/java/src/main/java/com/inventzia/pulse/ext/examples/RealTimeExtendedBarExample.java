/*
 * SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Inventzia-Commercial
 * Copyright (c) 2013-2026 Magrino Bini, Paola Apruzzese, Inventzia Science and Technology Ltd.
 */
package com.inventzia.pulse.ext.examples;

import com.inventzia.pulse.beacon.core.OperatingMode;

/**
 * Runnable real-time example: the extension's {@code ExtendedBar} flowing through the engine in
 * {@link OperatingMode#REAL_TIME}. The window spans "now", so the source gateway paces itself to
 * the wall clock and the engine dispatches from its live queue; the run takes a few seconds.
 *
 * <p>A plain {@code main}: in Eclipse, import {@code java/} as a Maven project and run or debug
 * this class with <em>Run/Debug As &gt; Java Application</em> (set breakpoints and step through).
 * See {@link HistoricExtendedBarExample} for the compressed-time variant and {@link ExtendedBarRun}
 * for the shared logic.
 */
public final class RealTimeExtendedBarExample {

    public static void main(String[] args) throws Exception {
        System.exit(ExtendedBarRun.execute(true) ? 0 : 1);
    }

    private RealTimeExtendedBarExample() {}
}
